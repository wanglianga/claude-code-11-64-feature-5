package com.ccdev.drainage.repo;

import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.domain.Models.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AppUser> USER_MAPPER = (rs, n) -> new AppUser(
            rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
            rs.getString("display_name"), rs.getString("role"));

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT * FROM app_user WHERE username = ?", USER_MAPPER, username)
                .stream().findFirst();
    }

    public Optional<AppUser> findById(Long id) {
        return jdbc.query("SELECT * FROM app_user WHERE id = ?", USER_MAPPER, id)
                .stream().findFirst();
    }

    public void saveToken(String token, Long userId, LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO app_login_token(token, user_id, expires_at) VALUES (?,?,?)",
                token, userId, Timestamp.valueOf(expiresAt));
    }

    public Optional<Long> findUserIdByValidToken(String token) {
        List<Long> ids = jdbc.query(
                "SELECT user_id FROM app_login_token WHERE token = ? AND expires_at > now()",
                (rs, n) -> rs.getLong(1), token);
        return ids.stream().findFirst();
    }

    public void deleteToken(String token) {
        jdbc.update("DELETE FROM app_login_token WHERE token = ?", token);
    }

    public void audit(Long userId, String action, String targetRef, String detail) {
        jdbc.update("INSERT INTO app_audit_log(user_id, action, target_ref, detail) VALUES (?,?,?,?)",
                userId, action, targetRef, detail);
    }

    public List<AuditLog> recentAudit(int limit) {
        return jdbc.query("SELECT * FROM app_audit_log ORDER BY id DESC LIMIT ?",
                (rs, n) -> new AuditLog(rs.getLong("id"), rs.getObject("user_id", Long.class),
                        rs.getString("action"), rs.getString("target_ref"), rs.getString("detail"),
                        rs.getTimestamp("created_at").toLocalDateTime()), limit);
    }
}
