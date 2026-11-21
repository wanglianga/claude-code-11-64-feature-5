package com.ccdev.drainage.web;

import com.ccdev.drainage.auth.AuthInterceptor;
import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.DatasetRepository;
import com.ccdev.drainage.service.DatasetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final DatasetRepository datasetRepo;
    private final DatasetService datasetService;
    private final AssetRepository assetRepo;

    public DatasetController(DatasetRepository datasetRepo, DatasetService datasetService,
                             AssetRepository assetRepo) {
        this.datasetRepo = datasetRepo;
        this.datasetService = datasetService;
        this.assetRepo = assetRepo;
    }

    @GetMapping
    public List<?> list() {
        return datasetRepo.findAllDatasets();
    }

    /** 按资产版本与评分口径生成数据集（历史复现：可选任意历史版本）。 */
    @PostMapping("/generate")
    public Map<String, Object> generate(@Valid @RequestBody Requests.DatasetGenerateRequest req,
                                        HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        String mode = "BURDEN".equalsIgnoreCase(req.scoringMode()) ? "BURDEN" : "MAX_SEVERITY";
        return datasetService.generateForVersion(req.versionId(), mode, user.id());
    }

    @GetMapping("/{id}/scores")
    public Map<String, Object> scores(@PathVariable Long id) {
        var dataset = datasetRepo.findDatasetById(id);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var score : datasetRepo.findScoresByDataset(id)) {
            Map<String, Object> row = new HashMap<>();
            row.put("score", score);
            row.put("segment", assetRepo.findSegmentById(score.segmentId()));
            rows.add(row);
        }
        return Map.of("dataset", dataset, "scores", rows);
    }

    @GetMapping("/{id}/impacts")
    public Map<String, Object> impacts(@PathVariable Long id) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var impact : datasetRepo.findImpactsByDataset(id)) {
            Map<String, Object> row = new HashMap<>();
            row.put("impact", impact);
            row.put("oldSegment", assetRepo.findSegmentById(impact.oldSegmentId()));
            if (impact.newSegmentId() != null) {
                row.put("newSegment", assetRepo.findSegmentById(impact.newSegmentId()));
            }
            rows.add(row);
        }
        return Map.of("impacts", rows);
    }
}
