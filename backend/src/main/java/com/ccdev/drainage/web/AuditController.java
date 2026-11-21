package com.ccdev.drainage.web;

import com.ccdev.drainage.repo.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final UserRepository userRepo;

    public AuditController(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    @GetMapping
    public List<?> recent() {
        return userRepo.recentAudit(100);
    }
}
