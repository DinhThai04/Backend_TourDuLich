package com.example.auth_service.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.auth_service.entity.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id=:id")
    Optional<User> lockForReview(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id=:id")
    Optional<User> lockForPasswordChange(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id=:id")
    Optional<User> lockForLifecycle(@org.springframework.data.repository.query.Param("id") Long id);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    

    Optional<User> findByGoogleId(String googleId);

    // Login and token generation read the role/permission graph after the session closes.
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"role", "role.permissions"})
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id=:id")
    Optional<User> findWithAccessById(@org.springframework.data.repository.query.Param("id") Long id);

    // Changing a role's permissions must invalidate the tokens of every account holding it.
    @org.springframework.data.jpa.repository.Query("select u.id from User u where u.role.id=:roleId")
    java.util.List<Long> findIdsByRoleId(@org.springframework.data.repository.query.Param("roleId") Long roleId);

    // Reassigning one permission across roles touches several roles at once.
    @org.springframework.data.jpa.repository.Query("select u.id from User u where u.role.id in :roleIds")
    java.util.List<Long> findIdsByRoleIds(@org.springframework.data.repository.query.Param("roleIds") java.util.Collection<Long> roleIds);
}
