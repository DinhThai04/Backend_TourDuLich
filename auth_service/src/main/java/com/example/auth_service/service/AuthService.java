package com.example.auth_service.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.auth_service.config.CredentialVersion;
import com.example.auth_service.dto.request.AuthenticationRequest;
import com.example.auth_service.dto.request.ChangePasswordRequest;
import com.example.auth_service.dto.request.IntrospectRequest;
import com.example.auth_service.dto.request.LogoutRequest;
import com.example.auth_service.dto.request.RefreshRequest;
import com.example.auth_service.dto.response.AuthenticationResponse;
import com.example.auth_service.dto.response.IntrospectResponse;
import com.example.auth_service.entity.InvalidatedToken;
import com.example.auth_service.entity.Permission;
import com.example.auth_service.entity.Role;
import com.example.auth_service.entity.User;
import com.example.auth_service.entity.UserStatus;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.repository.InvalidatedTokenRepository;
import com.example.auth_service.repository.RoleRepository;
import com.example.auth_service.repository.UserAccountEventRepository;
import com.example.auth_service.repository.UserRepository;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthService {

    private static final String TOKEN_ISSUER = "tour.com";
    /** Cost-10 bcrypt hash of an unrelated string; only used to keep the unknown-email path as slow as a real check. */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);

    UserRepository userRepository;
    InvalidatedTokenRepository invalidatedTokenRepository;
    UserMapper userMapper;
    PasswordEncoder passwordEncoder;
    FileServiceClient fileServiceClient;
    RoleRepository roleRepository;
    UserAccountEventRepository accountEvents;
    SessionEpochService sessionEpochService;

    @NonFinal
    @Value("${app.registration.customer-role-name:Khách Hàng}")
    String customerRoleName;

    @NonFinal
    @Value("${jwt.signerKey}")
    protected String SIGNER_KEY;

    @NonFinal
    @Value("${jwt.valid-duration}")
    protected long VALID_DURATION;

    @NonFinal
    @Value("${jwt.refreshable-duration}")
    protected long REFRESHABLE_DURATION;

    @NonFinal
    @Value("${app.google.client-id}")
    String googleClientId;

    public GoogleIdToken.Payload verifyGG(String idTokenString) throws Exception {
        if (idTokenString == null || idTokenString.isBlank() || idTokenString.length() > 8192) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier
                .Builder(new NetHttpTransport(), new GsonFactory())
                .setAudience(Collections.singletonList(googleClientId))
                .build();
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(idTokenString);
        } catch (IllegalArgumentException malformed) {
            // A syntactically invalid token is a client error, not a server failure.
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        if (idToken == null) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        return idToken.getPayload();
    }

    @Transactional(readOnly = true)
    public AuthenticationResponse authenticated(AuthenticationRequest request) {
        var user = userRepository.findByEmail(request.getEmail()).orElse(null);
        if (user == null) {
            // Spend the same bcrypt time as a real check, so login timing does not reveal which emails exist.
            passwordEncoder.matches(request.getPassword(), DUMMY_PASSWORD_HASH);
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }
        var userResponse = userMapper.toUserResponse(user);

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + VALID_DURATION * 1000);
        boolean authenticated = passwordEncoder.matches(request.getPassword(), user.getPassword());
        if (!authenticated) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }
        // Locked or role-less accounts get the same answer as a wrong password, so an attacker
        // cannot use the login form to discover which accounts exist and are locked.
        if (user.getStatus() != UserStatus.ACTIVE || user.getRole() == null || !Boolean.TRUE.equals(user.getRole().getActive())) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }
        var token = generateToken(user, TokenType.ACCESS);
        return AuthenticationResponse.builder()
                .token(token)
                .refreshToken(generateToken(user, TokenType.REFRESH))
                .user(userResponse)
                .expiryTime(expiryDate)
                .build();
    }

    // Verification calls Google over the network, so it stays outside a database transaction.
    public AuthenticationResponse authenticatedWithGG(String header) throws Exception {
        String idToken = header.replace("Bearer ", "");
        var payload = verifyGG(idToken);
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }

        String googleId = payload.getSubject();
        String email = payload.getEmail();
        String name = (String) payload.get("name");
        String avatar = (String) payload.get("picture");

        User user = findOrCreateGoogleUser(googleId, email, name, avatar);
        if (user.getRole() == null) {
            user.setRole(customerRole());
            userRepository.save(user);
        }
        // No transaction here, so load the role/permission graph the login payload and token need.
        user = userRepository.findWithAccessById(user.getId())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        var userResponse = userMapper.toUserResponse(user);
        String token = generateToken(user, TokenType.ACCESS);
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + VALID_DURATION * 1000);

        return AuthenticationResponse.builder()
                .token(token)
                .refreshToken(generateToken(user, TokenType.REFRESH))
                .user(userResponse)
                .expiryTime(expiryDate)
                .build();
    }

    /** Access token dùng gọi API; refresh token chỉ dùng để cấp lại cặp token. */
    enum TokenType {
        ACCESS, REFRESH;

        String claimValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private String generateToken(User user, TokenType type) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        }
        if (user.getRole() == null || !Boolean.TRUE.equals(user.getRole().getActive())) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        long lifetimeSeconds = type == TokenType.ACCESS ? VALID_DURATION : REFRESHABLE_DURATION;
        var claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(user.getId()))
                .issuer(TOKEN_ISSUER)
                .issueTime(new Date())
                .expirationTime(new Date(
                        Instant.now().plus(lifetimeSeconds, ChronoUnit.SECONDS).toEpochMilli()))
                .jwtID(UUID.randomUUID().toString())
                .claim("tokenType", type.claimValue())
                .claim("sessionEpoch", accountEvents.sessionEpoch(user.getId()))
                .claim("credentialVersion", CredentialVersion.of(user, SIGNER_KEY));
        if (type == TokenType.ACCESS) {
            claims.claim("scope", buildScope(user));
        }

        JWSObject jwsObject = new JWSObject(
                new JWSHeader(JWSAlgorithm.HS512), new Payload(claims.build().toJSONObject()));
        try {
            jwsObject.sign(new MACSigner(SIGNER_KEY.getBytes(StandardCharsets.UTF_8)));
            return jwsObject.serialize();
        } catch (JOSEException exception) {
            throw new RuntimeException(exception);
        }
    }

    @Transactional(readOnly = true)
    public IntrospectResponse introspect(IntrospectRequest request) {
        boolean isValid = true;
        try {
            var jwt = verifyToken(request.getToken(), TokenType.ACCESS);
            long id = Long.parseLong(jwt.getJWTClaimsSet().getSubject());
            var user = userRepository.findById(id).orElse(null);
            isValid = user != null
                    && user.getStatus() == UserStatus.ACTIVE
                    && user.getRole() != null
                    && Boolean.TRUE.equals(user.getRole().getActive())
                    && new HashSet<>(Arrays.asList(buildScope(user).split(" ")))
                    .equals(new HashSet<>(Arrays.asList(
                            String.valueOf(jwt.getJWTClaimsSet().getClaim("scope")).split(" "))));
        } catch (AppException | ParseException | NumberFormatException exception) {
            isValid = false;
        }
        return IntrospectResponse.builder().valid(isValid).build();
    }

    private String buildScope(User user) {
        StringJoiner scopes = new StringJoiner(" ");
        if (user.getRole() != null && Boolean.TRUE.equals(user.getRole().getActive())) {
            if ("admin".equalsIgnoreCase(user.getRole().getName())) {
                scopes.add("SGU_SUPER_ADMIN");
            }
            scopes.add("ROLE_" + user.getRole().getName().replaceAll("\\s+", "_"));
            if (user.getRole().getPermissions() != null) {
                for (Permission permission : user.getRole().getPermissions()) {
                    if (permission == null) {
                        continue;
                    }
                    // Codes are the stable contract; apiPath/method stay as admin-facing metadata only.
                    String code = permission.getCode() == null || permission.getCode().isBlank()
                            ? permission.getName() : permission.getCode();
                    if (code == null || !code.trim().matches("[A-Za-z][A-Za-z0-9_]{1,99}")) {
                        continue;
                    }
                    scopes.add("PERM:" + code.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return scopes.toString();
    }

    private SignedJWT verifyToken(String token, TokenType expectedType) {
        if (token == null || token.isBlank() || token.length() > 16384) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        SignedJWT signedJWT;
        JWTClaimsSet claims;
        try {
            signedJWT = SignedJWT.parse(token);
            claims = signedJWT.getJWTClaimsSet();
        } catch (ParseException | RuntimeException malformed) {
            // Nimbus reports structurally broken Base64/JSON as NPE, not ParseException.
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        try {
            JWSVerifier signatureVerifier = new MACVerifier(SIGNER_KEY.getBytes(StandardCharsets.UTF_8));
            if (!JWSAlgorithm.HS512.equals(signedJWT.getHeader().getAlgorithm())
                    || !TOKEN_ISSUER.equals(claims.getIssuer())
                    || claims.getIssueTime() == null
                    || claims.getExpirationTime() == null
                    || claims.getJWTID() == null
                    || claims.getIssueTime().after(new Date(System.currentTimeMillis() + 60_000))
                    || !signedJWT.verify(signatureVerifier)) {
                throw new AppException(ErrorCode.INVALID_TOKEN);
            }

            // Token phát hành trước khi tách loại được coi là access token để người dùng
            // đang đăng nhập không bị đăng xuất ngay khi triển khai.
            TokenType actualType = TokenType.REFRESH.claimValue().equals(claims.getStringClaim("tokenType"))
                    ? TokenType.REFRESH
                    : TokenType.ACCESS;
            if (actualType != expectedType) {
                throw new AppException(ErrorCode.INVALID_TOKEN);
            }

            if (!claims.getExpirationTime().after(new Date())) {
                throw new AppException(ErrorCode.EXPIRED_TOKEN);
            }

            String jti = claims.getJWTID();
            if (invalidatedTokenRepository.existsById(jti)) {
                throw new AppException(ErrorCode.INVALID_TOKEN);
            }

            long accountId;
            try {
                accountId = Long.parseLong(claims.getSubject());
            } catch (NumberFormatException | NullPointerException invalid) {
                throw new AppException(ErrorCode.INVALID_TOKEN);
            }
            var account = userRepository.findById(accountId)
                    .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
            if (account.getStatus() != UserStatus.ACTIVE) {
                throw new AppException(ErrorCode.UNAUTHENTICATED);
            }
            Long epoch = claims.getLongClaim("sessionEpoch");
            if ((epoch == null ? 0L : epoch) != accountEvents.sessionEpoch(accountId)) {
                throw new AppException(ErrorCode.INVALID_TOKEN);
            }
            String binding = claims.getStringClaim("credentialVersion");
            String expected = CredentialVersion.of(account, SIGNER_KEY);
            if (binding == null || !MessageDigest.isEqual(binding.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                throw new AppException(ErrorCode.INVALID_TOKEN);
            }
            return signedJWT;
        } catch (ParseException | JOSEException malformed) {
            // A client-supplied token is input, not a server failure: answer with INVALID_TOKEN, never 500.
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
    }

    @Transactional
    public AuthenticationResponse refreshToken(RefreshRequest request) {
        SignedJWT signedJWT;
        try {
            signedJWT = verifyToken(request.getToken(), TokenType.REFRESH);
        } catch (AppException invalid) {
            // Reusing a consumed refresh token means the whole token family is compromised:
            // invalidate every access/refresh token of that user, not only this copy.
            ReplayToken replay = invalid.getErrorCode() == ErrorCode.INVALID_TOKEN
                    ? replayToken(request.getToken())
                    : null;
            if (replay == null) {
                throw invalid;
            }
            revokeFamily(replay);
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        var claims = claims(signedJWT);
        var jti = claims.getJWTID();

        InvalidatedToken invalidatedToken = InvalidatedToken.builder()
                .id(jti)
                .expiryTime(claims.getExpirationTime())
                .build();
        var subject = claims.getSubject();
        long accountId;
        try {
            accountId = Long.parseLong(subject);
        } catch (NumberFormatException invalid) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        var user = userRepository.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        if (user.getStatus() != UserStatus.ACTIVE || user.getRole() == null || !Boolean.TRUE.equals(user.getRole().getActive())) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        try {
            // Flush now: a parallel request replaying the same jti must lose here, not at commit.
            invalidatedTokenRepository.saveAndFlush(invalidatedToken);
        } catch (org.springframework.dao.DataIntegrityViolationException alreadyConsumed) {
            // A concurrent replay lost the insert race; the outer transaction is rollback-only,
            // so revoke the family in its own transaction before rejecting the request.
            revokeFamily(new ReplayToken(accountId, epochOf(signedJWT)));
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        Date now = new Date();
        Date newExpiryTime = new Date(now.getTime() + VALID_DURATION * 1000);
        var userResponse = userMapper.toUserResponse(user);
        return AuthenticationResponse.builder()
                .token(generateToken(user, TokenType.ACCESS))
                .refreshToken(generateToken(user, TokenType.REFRESH))
                .user(userResponse)
                .expiryTime(newExpiryTime)
                .build();
    }

    private record ReplayToken(long userId, long epoch) {}

    /**
     * Recognises a genuine replay: only a signed refresh token issued by us whose jti is already
     * on the blacklist. Unsigned or foreign tokens can therefore never force a family logout.
     */
    private ReplayToken replayToken(String token) {
        if (token == null || token.isBlank() || token.length() > 16384) return null;
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            var claims = signedJWT.getJWTClaimsSet();
            if (!JWSAlgorithm.HS512.equals(signedJWT.getHeader().getAlgorithm())
                    || !TOKEN_ISSUER.equals(claims.getIssuer())
                    || !TokenType.REFRESH.claimValue().equals(claims.getStringClaim("tokenType"))
                    || claims.getJWTID() == null
                    || !signedJWT.verify(new MACVerifier(SIGNER_KEY.getBytes(StandardCharsets.UTF_8)))
                    || !invalidatedTokenRepository.existsById(claims.getJWTID())) {
                return null;
            }
            return new ReplayToken(Long.parseLong(claims.getSubject()), epochOf(signedJWT));
        } catch (Exception notAReplay) {
            return null;
        }
    }

    private void revokeFamily(ReplayToken replay) {
        sessionEpochService.revokeFamily(replay.userId(), replay.epoch());
        // Never log token contents or its jti.
        log.warn("Refresh token replay detected for userId={}; revoking all sessions", replay.userId());
    }

    private static long epochOf(SignedJWT signedJWT) {
        try {
            Long epoch = signedJWT.getJWTClaimsSet().getLongClaim("sessionEpoch");
            return epoch == null ? 0L : epoch;
        } catch (ParseException malformed) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
    }

    /** verifyToken already parsed these claims; re-wrap so callers never propagate checked exceptions. */
    private static JWTClaimsSet claims(SignedJWT signedJWT) {
        try {
            return signedJWT.getJWTClaimsSet();
        } catch (ParseException malformed) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException malformed) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
    }

    public void logout(LogoutRequest request) {
        if (request == null) return;
        invalidateQuietly(request.getToken(), TokenType.ACCESS);
        invalidateQuietly(request.getRefreshToken(), TokenType.REFRESH);
    }

    /** Logout is idempotent: a malformed, expired or already-revoked token is already unusable. */
    private void invalidateQuietly(String token, TokenType type) {
        if (token == null || token.isBlank()) return;
        try {
            invalidate(token, type);
        } catch (AppException alreadyInvalid) {
            // Nothing to revoke.
        } catch (org.springframework.dao.DataIntegrityViolationException concurrentlyRevoked) {
            // Another request blacklisted this jti first, so the logout already holds.
            // Each revocation stays in its own transaction: wrapping both in one would let the
            // caught violation mark that transaction rollback-only and turn 200 into 500.
        }
    }

    private void invalidate(String token, TokenType type) {
        var signedJWT = verifyToken(token, type);
        var claims = claims(signedJWT);
        invalidatedTokenRepository.save(InvalidatedToken.builder()
                .id(claims.getJWTID())
                // Store the real expiry so the blacklist row can be cleaned up once the token dies.
                .expiryTime(claims.getExpirationTime())
                .build());
    }

    @Transactional
    public void changePassword(String subject, String credentialVersion, ChangePasswordRequest request) {
        validatePasswordChange(request);
        long accountId;
        try {
            accountId = Long.parseLong(subject);
        } catch (NumberFormatException | NullPointerException invalid) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        var user = userRepository.lockForPasswordChange(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));

        // The caller is the authenticated principal; a stale binding means the session was
        // already rotated by another password change, so this request must not win.
        String expectedBinding = CredentialVersion.of(user, SIGNER_KEY);
        if (credentialVersion == null || !MessageDigest.isEqual(
                credentialVersion.getBytes(StandardCharsets.UTF_8),
                expectedBinding.getBytes(StandardCharsets.UTF_8))) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH);
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new AppException(ErrorCode.OLD_PASSWORD_NOT_MATCH);
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    private void validatePasswordChange(ChangePasswordRequest request) {
        if (request == null
                || request.getOldPassword() == null
                || request.getOldPassword().isBlank()
                || request.getOldPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || request.getNewPassword() == null
                || request.getNewPassword().isBlank()
                || request.getNewPassword().length() < 8
                || request.getNewPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || request.getConfirmPassword() == null
                || request.getConfirmPassword().length() > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mật khẩu mới cần ít nhất 8 ký tự và tối đa 72 byte UTF-8");
        }
    }

    public User findOrCreateGoogleUser(String googleId, String email, String name, String avatarUrl) {
        User user = userRepository.findByGoogleId(googleId).orElse(null);
        if (user != null) {
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
            }
            return user;
        }

        user = userRepository.findByEmail(email).orElse(null);
        if (user != null) {
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
            }
            // Never attach a Google identity to an existing password account: registration does not
            // verify email ownership, so an attacker could pre-register the victim's address and keep
            // password access after the silent link.
            throw new AppException(ErrorCode.GOOGLE_LINK_REQUIRED);
        }

        User newUser = new User();
        newUser.setGoogleId(googleId);
        newUser.setProvider("GOOGLE");
        newUser.setEmail(email);
        newUser.setUsername(googleUsername(email, name));
        newUser.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        newUser.setRole(customerRole());
        try {
            newUser = userRepository.save(newUser);
        } catch (org.springframework.dao.DataIntegrityViolationException collision) {
            // A concurrent sign-in may have created this Google account; reuse only that identity.
            User existing = userRepository.findByGoogleId(googleId).orElse(null);
            if (existing != null) {
                if (existing.getStatus() != UserStatus.ACTIVE) {
                    throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
                }
                return existing;
            }
            // A password account may have claimed the same email in the meantime. Linking it here
            // would bypass GOOGLE_LINK_REQUIRED, so reject exactly like the pre-save check does.
            User byEmail = userRepository.findByEmail(email).orElse(null);
            if (byEmail != null) {
                if (byEmail.getStatus() != UserStatus.ACTIVE) {
                    throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
                }
                throw new AppException(ErrorCode.GOOGLE_LINK_REQUIRED);
            }
            // Otherwise the collision was the display name; retry with a suffix.
            newUser.setUsername(googleUsername(email, name, true));
            newUser = userRepository.save(newUser);
        }

        applyGoogleAvatar(newUser, avatarUrl);
        userRepository.save(newUser);
        return newUser;
    }

    private static final int USERNAME_MAX = 50;
    private static final int USERNAME_MIN = 3;

    private String googleUsername(String email, String name) {
        return googleUsername(email, name, false);
    }

    private String googleUsername(String email, String name, boolean forceSuffix) {
        String base = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        base = base.codePoints().filter(code -> !Character.isISOControl(code))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (base.isEmpty() && email != null) {
            int at = email.indexOf('@');
            base = (at > 0 ? email.substring(0, at) : email).strip();
        }
        if (base.isEmpty()) base = "SGU Tour";
        String candidate = truncateCodePoints(base, USERNAME_MAX);
        if (candidate.codePointCount(0, candidate.length()) < USERNAME_MIN) candidate = "user-" + candidate;
        if (!forceSuffix && !userRepository.existsByUsername(candidate)) return candidate;
        String suffix = "-" + UUID.randomUUID().toString().substring(0, 7);
        return truncateCodePoints(base, USERNAME_MAX - suffix.length()) + suffix;
    }

    private static String truncateCodePoints(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, max)).strip();
    }

    private Role customerRole() {
        var role = roleRepository.findByName(customerRoleName);
        if (role == null || !Boolean.TRUE.equals(role.getActive())) {
            throw new AppException(ErrorCode.REGISTRATION_ROLE_UNAVAILABLE);
        }
        return role;
    }

    private void applyGoogleAvatar(User user, String url) {
        if (url == null) {
            return;
        }
        try {
            var uri = URI.create(url);
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com"))) {
                user.setAvatar(url);
            }
        } catch (IllegalArgumentException ignored) {
            /* Invalid optional avatar does not block login. */
        }
    }
}
