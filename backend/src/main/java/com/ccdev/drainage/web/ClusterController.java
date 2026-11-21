package com.ccdev.drainage.web;

import com.ccdev.drainage.auth.AuthInterceptor;
import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import com.ccdev.drainage.repo.UserRepository;
import com.ccdev.drainage.service.ClusteringService;
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
@RequestMapping("/api/clusters")
public class ClusterController {

    private final AnalysisRepository analysisRepo;
    private final AssetRepository assetRepo;
    private final InspectionRepository inspectionRepo;
    private final ClusteringService clusteringService;
    private final UserRepository userRepo;

    public ClusterController(AnalysisRepository analysisRepo, AssetRepository assetRepo,
                             InspectionRepository inspectionRepo, ClusteringService clusteringService,
                             UserRepository userRepo) {
        this.analysisRepo = analysisRepo;
        this.assetRepo = assetRepo;
        this.inspectionRepo = inspectionRepo;
        this.clusteringService = clusteringService;
        this.userRepo = userRepo;
    }

    /** 聚类总览（带管段编码与成员明细）。 */
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var cluster : analysisRepo.findAllClusters()) {
            Map<String, Object> row = new HashMap<>();
            row.put("cluster", cluster);
            row.put("segmentCode", assetRepo.findSegmentById(cluster.segmentId()).segmentCode());
            List<Map<String, Object>> members = new ArrayList<>();
            for (var member : analysisRepo.findMembersByCluster(cluster.id())) {
                Map<String, Object> m = new HashMap<>();
                m.put("member", member);
                m.put("observation", inspectionRepo.findObservationById(member.observationId()));
                m.put("batchCode", inspectionRepo.findBatchById(member.batchId()).batchCode());
                members.add(m);
            }
            row.put("members", members);
            result.add(row);
        }
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = new HashMap<>();
        var cluster = analysisRepo.findClusterById(id);
        row.put("cluster", cluster);
        row.put("segment", assetRepo.findSegmentById(cluster.segmentId()));
        List<Map<String, Object>> members = new ArrayList<>();
        for (var member : analysisRepo.findMembersByCluster(id)) {
            Map<String, Object> m = new HashMap<>();
            m.put("member", member);
            m.put("observation", inspectionRepo.findObservationById(member.observationId()));
            m.put("batch", inspectionRepo.findBatchById(member.batchId()));
            members.add(m);
        }
        row.put("members", members);
        return row;
    }

    /** 人工裁决冲突等级。 */
    @PostMapping("/{id}/adjudicate")
    public Map<String, Object> adjudicate(@PathVariable Long id,
                                          @Valid @RequestBody Requests.AdjudicateRequest req,
                                          HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        clusteringService.adjudicate(id, req.resolvedGrade(), req.note(), user.id());
        userRepo.audit(user.id(), "ADJUDICATE_CONFLICT", "cluster:" + id,
                "采信等级 " + req.resolvedGrade() + "，说明：" + req.note());
        return Map.of("adjudicated", true, "clusterId", id);
    }
}
