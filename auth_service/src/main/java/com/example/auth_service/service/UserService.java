package com.example.auth_service.service;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.mapstruct.control.MappingControl;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.auth_service.dto.request.UserCreationRequest;
import com.example.auth_service.dto.request.RegisterRequest;
import org.springframework.beans.factory.annotation.Value;
import com.example.auth_service.dto.request.UserUpdate;
import com.example.auth_service.dto.response.FileResponse;
import com.example.auth_service.dto.response.UserResponse;
import com.example.auth_service.entity.Role;
import com.example.auth_service.entity.User;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.paging.PageSlice;
import com.example.auth_service.repository.RoleRepository;
import com.example.auth_service.repository.UserRepository;
import com.example.auth_service.repository.httpclient.FileServiceClient;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.Map<Long, String> displayNames(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id < 1))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Cần từ 1 đến 100 mã tài khoản hợp lệ");
        return userRepository.findAllById(ids.stream().distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, User::getUsername));
    }
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserService.class);
    private final AccountLifecycleService lifecycle;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final FileServiceClient fileServiceClient;
    private final RoleRepository roleRepository;
    private final com.example.auth_service.config.AccountAccess accountAccess;
    private final AdminContinuity adminContinuity;
    private final AvatarProfileWriter avatarProfileWriter;
    private final SessionEpochService sessionEpochService;
    @Value("${app.registration.customer-role-name:Khách Hàng}")
    private String customerRoleName;

    @Transactional
    public UserResponse registerCustomer(RegisterRequest request) {
        Role role = roleRepository.findByName(customerRoleName);
        if (role == null || !Boolean.TRUE.equals(role.getActive())) {
            throw new AppException(ErrorCode.REGISTRATION_ROLE_UNAVAILABLE);
        }
        UserCreationRequest creation = UserCreationRequest.builder()
                .username(request.getUsername()).email(request.getEmail())
                .phone(request.getPhone()).address(request.getAddress())
                .password(request.getPassword()).roleId(role.getId()).build();
        return createUser(creation);
    }

    @Transactional
    public boolean canCreateManagedUser(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
            && accountAccess.can("USERS_REGISTER")
            && accountAccess.can("USERS_GET_LIST");
    }

    @org.springframework.transaction.annotation.Transactional
    public UserResponse createUser(UserCreationRequest request) {
        UserProfilePolicy.validateCreation(request);

        // Validate các trường bắt buộc
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Tên đăng nhập không được để trống");
        }
        // BCrypt only reads the first 72 bytes, so longer UTF-8 passwords would be silently truncated.
        if (request.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Mật khẩu tối đa 72 byte UTF-8");
        }
        // Role managers must choose a role explicitly. Other operators may only create customers,
        // so their account defaults to the customer role instead of failing the form.
        if (request.getRoleId() == null && accountAccess.can("ROLES_UPDATE")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Vui lòng chọn vai trò cho tài khoản");
        }

        // Check trùng email/tên tài khoản; dùng chung một lỗi để không tiết lộ tài khoản nào đã tồn tại.
        if (userRepository.existsByEmail(request.getEmail()) || userRepository.existsByUsername(request.getUsername()))
            throw new AppException(ErrorCode.ACCOUNT_ALREADY_USED);

        // Map DTO -> Entity
        User user = userMapper.toUser(request);

        // Encode password
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        // Map Role nếu có roleId
        if (request.getRoleId() != null) {
            Role role = roleRepository.findById(request.getRoleId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
            if (!Boolean.TRUE.equals(role.getActive())) throw new org.springframework.security.access.AccessDeniedException("Vai trò không hoạt động");
            if (!accountAccess.can("ROLES_UPDATE")) {
                var customer=roleRepository.findByName(customerRoleName);
                if (customer==null || !role.getId().equals(customer.getId()))
                    throw new org.springframework.security.access.AccessDeniedException("Cần quyền quản lý vai trò để tạo tài khoản có quyền quản trị");
            }
            user.setRole(role);
        } else {
            Role customer = roleRepository.findByName(customerRoleName);
            if (customer == null || !Boolean.TRUE.equals(customer.getActive()))
                throw new AppException(ErrorCode.REGISTRATION_ROLE_UNAVAILABLE);
            user.setRole(customer);
        }

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // Recheck unique keys to distinguish a concurrent duplicate from another DB failure.
            if (userRepository.existsByEmail(request.getEmail()) || userRepository.existsByUsername(request.getUsername()))
                throw new AppException(ErrorCode.ACCOUNT_ALREADY_USED);
            throw ex;
        }

        return userMapper.toUserResponse(user);
    }
    // Mapping touches each user's role and its permissions. Priming the small role table in one
    // query keeps the user page paginated in SQL; an EntityGraph over the ManyToMany role would
    // make Hibernate paginate the whole user table in memory instead.
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<UserResponse> getAllUser(Integer page, Integer size) {
        Pageable pageable = PageSlice.of(page, size).pageable(Sort.by(Sort.Direction.DESC, "id"));
        roleRepository.findAllWithPermissions();
        List<User> users = userRepository.findAll(pageable).getContent();
        return users.stream()
                .map(user -> { var dto=userMapper.toUserResponse(user);dto.setManagementAllowed(lifecycle.manageable(user));return dto; })
                .collect(Collectors.toList());
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        return userRepository.findById(id)
                .map(userMapper::toUserResponse)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

    }

   
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public UserResponse updateUser(Long id, UserUpdate req) {
        UserProfilePolicy.validate(req);
        adminContinuity.lockRoles(req.getRoleId());
        User user = userRepository.lockForLifecycle(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        adminContinuity.refresh(user);

        // Same scope as the lifecycle endpoints: staff may only touch customer accounts.
        if (!isSelf(id) && !actorIsAdmin()
                && (user.getRole() == null || customerRoleName == null
                    || !customerRoleName.equalsIgnoreCase(user.getRole().getName())))
            throw new org.springframework.security.access.AccessDeniedException("Nhân viên chỉ được cập nhật tài khoản khách hàng");

        if (user.getStatus()==com.example.auth_service.entity.UserStatus.ARCHIVED)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Không sửa tài khoản đã lưu trữ");
        // Update username nếu khác null/blank
        if (req.getUsername() != null && !req.getUsername().isBlank()) {
            userRepository.findByUsername(req.getUsername())
                    .filter(u -> !u.getId().equals(id))
                    .ifPresent(u -> {
                        throw new AppException(ErrorCode.USER_EXISTED);
                    });

            user.setUsername(req.getUsername());
        }

        // Update phone và address nếu khác null
        Optional.ofNullable(req.getPhone()).ifPresent(user::setPhone); 
        Optional.ofNullable(req.getAddress()).ifPresent(user::setAddress);

        // Update role nếu roleId khác null
        boolean roleChanged = false;
        if (req.getRoleId() != null) {
            boolean sameRole = user.getRole() != null && req.getRoleId().equals(user.getRole().getId());
            if (!sameRole && !accountAccess.can("ROLES_UPDATE")) {
                throw new org.springframework.security.access.AccessDeniedException("Không được tự thay đổi vai trò");
            }
            Role role = roleRepository.findById(req.getRoleId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
            if (!Boolean.TRUE.equals(role.getActive())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Vai trò không hoạt động");
            adminContinuity.requireUserRoleChange(id, role.getId());
            roleChanged = !sameRole;
            user.setRole(role);
        }

        try {
            // Flush here: a username claimed between the check above and this write must surface as
            // the same conflict the check raises, not as a rollback failure after the method returns.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (req.getUsername() != null && userRepository.existsByUsername(req.getUsername()))
                throw new AppException(ErrorCode.USER_EXISTED);
            throw ex;
        }

        // The token still carries the old role and permissions; revoke it with the role change.
        if (roleChanged) {
            sessionEpochService.revokeAll(List.of(id),
                    "Vai trò của tài khoản đã thay đổi, đã thu hồi phiên đăng nhập cũ");
        }

        return userMapper.toUserResponse(user);
    }

    public FileResponse uploadAvatar(String userId, MultipartFile file) throws IOException {
        long accountId;
        try {
            accountId = Long.parseLong(userId);
            if (accountId < 1) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Mã tài khoản không hợp lệ");
        }
        var account = userRepository.findWithAccessById(accountId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        // Same scope as the lifecycle endpoints: staff may only touch customer accounts.
        if (!isSelf(accountId) && !actorIsAdmin()
                && (account.getRole() == null || customerRoleName == null
                    || !customerRoleName.equalsIgnoreCase(account.getRole().getName())))
            throw new org.springframework.security.access.AccessDeniedException("Nhân viên chỉ được cập nhật tài khoản khách hàng");
        if(account.getStatus()==com.example.auth_service.entity.UserStatus.ARCHIVED)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Không sửa tài khoản đã lưu trữ");
        FileResponse fileResponse;
        try {
            fileResponse = fileServiceClient.uploadAvt(Long.toString(accountId), file);
        } catch (feign.FeignException rejected) {
            // Surface the file service verdict (size/type/auth) instead of a generic 500.
            if (rejected.status() >= 400 && rejected.status() < 500)
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.valueOf(rejected.status()), "Không tải được ảnh đại diện");
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ tệp tạm thời không khả dụng");
        }

        fileResponse.setUrl(avatarProfileWriter.apply(accountId));
        return fileResponse;
    }

    public void deleteUser(Long id,String reason) { lifecycle.delete(id,reason); }

    private boolean isSelf(Long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && String.valueOf(id).equals(auth.getName());
    }

    private boolean actorIsAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        try {
            // uploadAvatar runs without a transaction, so the role graph must be fetched eagerly.
            return userRepository.findWithAccessById(Long.parseLong(auth.getName()))
                    .map(actor -> actor.getRole() != null && "admin".equalsIgnoreCase(actor.getRole().getName()))
                    .orElse(false);
        } catch (NumberFormatException invalid) { return false; }
    }
    // The lifecycle transaction already committed when it returns; the response mapper still needs a session.
    // Same isolation as the lifecycle methods it joins, so an existing transaction is reused unchanged.
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public UserResponse changeStatus(Long id,com.example.auth_service.entity.UserStatus status,String reason) {
        return userMapper.toUserResponse(lifecycle.status(id,status,reason));
    }
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public UserResponse archive(Long id,String reason) { return userMapper.toUserResponse(lifecycle.archive(id,reason)); }
    public java.util.List<com.example.auth_service.entity.UserAccountEvent> accountEvents(Long id) { return lifecycle.history(id); }


}
