package com.ccdev.drainage.web;

import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.auth.AuthInterceptor;
import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import com.ccdev.drainage.service.AlignmentService;
import com.ccdev.drainage.service.ClusteringService;
import com.ccdev.drainage.service.IngestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final InspectionRepository inspectionRepo;
    private final AnalysisRepository analysisRepo;
    private final IngestionService ingestionService;
    private final AlignmentService alignmentService;
    private final ClusteringService clusteringService;

    public InspectionController(InspectionRepository inspectionRepo, AnalysisRepository analysisRepo,
                                IngestionService ingestionService, AlignmentService alignmentService,
                                ClusteringService clusteringService) {
        this.inspectionRepo = inspectionRepo;
        this.analysisRepo = analysisRepo;
        this.ingestionService = ingestionService;
        this.alignmentService = alignmentService;
        this.clusteringService = clusteringService;
    }

    @GetMapping("/batches")
    public List<?> batches() {
        return inspectionRepo.findAllBatches();
    }

    @GetMapping("/videos")
    public List<?> videos() {
        return inspectionRepo.findAllVideos();
    }

    @GetMapping("/observations")
    public List<?> observations() {
        return inspectionRepo.findAllObservations();
    }

    @GetMapping("/observations/{id}/candidates")
    public Object candidates(@PathVariable Long id) {
        return alignmentService.candidatesOfObservation(id);
    }

    @GetMapping("/observations/{id}/attribution")
    public Object attribution(@PathVariable Long id) {
        return analysisRepo.findAttribution(id).orElse(null);
    }

    @PostMapping("/batches")
    public Object uploadBatch(@Valid @RequestBody Requests.BatchUploadRequest req) {
        return ingestionService.uploadBatch(req.batchCode(), req.contractor(), req.inspectedAt(),
                req.gradingStandard(), req.directionConvention(), req.contentHash());
    }

    @PostMapping("/videos")
    public Object uploadVideo(@Valid @RequestBody Requests.VideoUploadRequest req) {
        return ingestionService.uploadVideo(req.batchId(), req.videoCode(), req.fileUri(), req.segmentHint(),
                req.startM(), req.endM(), req.stationDirection(), req.contentHash());
    }

    @PostMapping("/observations")
    public Map<String, Object> uploadObservation(@Valid @RequestBody Requests.ObservationUploadRequest req) {
        long id = ingestionService.uploadObservation(req.videoId(), req.defectType(), req.rawGrade(),
                req.stationStartM(), req.stationEndM(), req.evidenceUri(), req.evidenceStrength());
        return Map.of("observationId", id);
    }

    /** 重新执行 对齐 → 聚类 流水线（上传新归档后手动触发）。 */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        Map<String, Object> align = alignmentService.realignAll();
        Map<String, Object> cluster = clusteringService.rebuildClusters();
        Map<String, Object> result = new HashMap<>();
        result.put("alignment", align);
        result.put("clustering", cluster);
        return result;
    }
}
