package com.ccdev.drainage.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 判读规则查看与调整：规则改变后可重新生成数据集，等级口径对使用者透明。 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final JdbcTemplate jdbc;

    public RuleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT * FROM app_judgement_rule ORDER BY defect_type");
    }

    @PutMapping("/{defectType}")
    public Map<String, Object> update(@org.springframework.web.bind.annotation.PathVariable String defectType,
                                      @RequestBody Map<String, Object> body) {
        int updated = jdbc.update(
                "UPDATE app_judgement_rule SET min_grade = ?, min_length_m = ?, required_evidence = ?, "
                        + "enabled = ?, updated_at = now() WHERE defect_type = ?",
                body.get("minGrade"), body.get("minLengthM"), body.get("requiredEvidence"),
                body.getOrDefault("enabled", true), defectType);
        return Map.of("updated", updated);
    }
}
