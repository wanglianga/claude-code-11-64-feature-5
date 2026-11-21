package com.ccdev.drainage.service;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepo;

    public AuthService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> login(String username, String password) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ApiException(401, "用户名或密码错误"));
        if (!user.passwordHash().equals(sha256(password))) {
            throw new ApiException(401, "用户名或密码错误");
        }
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String token = HexFormat.of().formatHex(buf);
        userRepo.saveToken(token, user.id(), LocalDateTime.now().plusHours(12));
        userRepo.audit(user.id(), "LOGIN", username, "用户登录");
        return Map.of("token", token, "userId", user.id(),
                "username", user.username(), "displayName", user.displayName(), "role", user.role());
    }

    public void logout(String token) {
        if (token != null) {
            userRepo.deleteToken(token);
        }
    }

    public AppUser requireUser(String token) {
        Long userId = userRepo.findUserIdByValidToken(token)
                .orElseThrow(() -> new ApiException(401, "登录已失效，请重新登录"));
        return userRepo.findById(userId)
                .orElseThrow(() -> new ApiException(401, "用户不存在"));
    }
}
