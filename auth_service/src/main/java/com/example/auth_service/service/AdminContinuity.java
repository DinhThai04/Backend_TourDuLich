package com.example.auth_service.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Shared write fence for account lifecycle, role assignment and role editing. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AdminContinuity {
    private final JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

    /**
     * Locks only the rows that the "last active admin" rule depends on: every role named
     * admin plus the role(s) involved in this change. Locking the whole roles table made
     * every account edit wait behind unrelated role edits.
     */
    public void lockRoles(Long... roleIds) {
        StringBuilder sql = new StringBuilder("SELECT id FROM roles WHERE LOWER(name)='admin'");
        java.util.List<Object> params = new java.util.ArrayList<>();
        for (Long roleId : roleIds) {
            if (roleId != null) {
                sql.append(" OR id=?");
                params.add(roleId);
            }
        }
        sql.append(" ORDER BY id FOR UPDATE");
        jdbc.queryForList(sql.toString(), params.toArray());
    }

    public void refresh(Object entity) {
        // Authorization may already have loaded this entity in the current transaction.
        if (entityManager.contains(entity)) entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
    }

    private List<Long> adminRoles() {
        return jdbc.query("SELECT id,name,active FROM roles", (rs, row) ->
                rs.getBoolean("active") && "admin".equalsIgnoreCase(rs.getString("name"))
                        ? rs.getLong("id") : null).stream().filter(java.util.Objects::nonNull).toList();
    }

    private long activeMembers(long roleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role_id=? AND status='ACTIVE'", Long.class, roleId);
    }

    public void requireUserRoleChange(long userId, Long nextRoleId) {
        var admins = adminRoles();
        if (nextRoleId != null && admins.contains(nextRoleId)) return;
        var affected = jdbc.queryForList("SELECT role_id,status FROM users WHERE id=?", userId);
        if (affected.isEmpty() || !"ACTIVE".equals(affected.getFirst().get("status"))) return;
        var role = (Number) affected.getFirst().get("role_id");
        if (role != null && admins.contains(role.longValue()) && admins.stream().mapToLong(this::activeMembers).sum() <= 1)
            reject();
    }

    public void requireRoleChange(long roleId, String nextName, boolean nextActive) {
        var admins = adminRoles();
        if (!admins.contains(roleId) || nextActive && "admin".equalsIgnoreCase(nextName)) return;
        long affected = activeMembers(roleId);
        if (affected > 0 && admins.stream().mapToLong(this::activeMembers).sum() == affected) reject();
    }

    public void requireUnusedRole(long roleId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role_id=?", Long.class, roleId) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vai trò đang được tài khoản sử dụng; hãy đổi vai trò của các tài khoản trước");
    }

    private void reject() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Không được vô hiệu hóa admin hoạt động cuối cùng");
    }
}
