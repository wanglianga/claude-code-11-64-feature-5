package com.ccdev.drainage.service;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.ContractorBatch;
import com.ccdev.drainage.domain.Models.Observation;
import com.ccdev.drainage.domain.Models.Video;
import com.ccdev.drainage.repo.InspectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检测归档上传：
 *   - 批次 content_hash 重复：整批拒绝，防止重复上传造成风险重复累加
 *   - 视频 content_hash 重复：登记为重复视频（duplicate_of_id），观察不参与对齐
 *   - 视频片段与既有片段里程重叠：允许登记但给出重叠警示，聚类阶段按同位置并簇而非累加
 */
@Service
public class IngestionService {

    private final InspectionRepository inspectionRepo;

    public IngestionService(InspectionRepository inspectionRepo) {
        this.inspectionRepo = inspectionRepo;
    }

    @Transactional
    public Map<String, Object> uploadBatch(String batchCode, String contractor, LocalDateTime inspectedAt,
                                           String gradingStandard, String directionConvention,
                                           String contentHash) {
        inspectionRepo.findBatchByCode(batchCode).ifPresent(b -> {
            throw new ApiException(409, "批次编号 " + batchCode + " 已存在，拒绝重复上传");
        });
        inspectionRepo.findBatchByContentHash(contentHash).ifPresent(b -> {
            throw new ApiException(409, "批次内容哈希与既有批次 " + b.batchCode()
                    + " 相同，判定为重复上传，已拒绝以防止同一观察被重复累加");
        });
        long id = inspectionRepo.insertBatch(new ContractorBatch(null, batchCode, contractor,
                inspectedAt, gradingStandard, directionConvention, contentHash));
        return Map.of("batchId", id, "batchCode", batchCode);
    }

    @Transactional
    public Map<String, Object> uploadVideo(Long batchId, String videoCode, String fileUri, String segmentHint,
                                           BigDecimal startM, BigDecimal endM, String stationDirection,
                                           String contentHash) {
        List<String> warnings = new ArrayList<>();
        Long duplicateOfId = null;
        var existingByHash = inspectionRepo.findVideoByContentHash(contentHash);
        if (existingByHash.isPresent()) {
            duplicateOfId = existingByHash.get().id();
            warnings.add("内容哈希与视频 " + existingByHash.get().videoCode()
                    + " 完全一致，登记为重复视频；其观察标记 DUPLICATE，不参与风险累加");
        }

        long videoId = inspectionRepo.insertVideo(new Video(null, batchId, videoCode, fileUri, segmentHint,
                startM, endM, stationDirection, contentHash, duplicateOfId));

        // 同指向管段的既有视频里程区间重叠警示
        for (Video v : inspectionRepo.findAllVideos()) {
            if (v.id().equals(videoId) || !v.segmentHint().equals(segmentHint)) {
                continue;
            }
            if (StationNormalizer.overlaps(startM, endM, v.startM(), v.endM())) {
                warnings.add("与既有视频 " + v.videoCode() + " 的里程区间[" + v.startM() + ","
                        + v.endM() + "]重叠；重叠位置的观察将在聚类时并为同一缺陷，不重复计数");
            }
        }
        return Map.of("videoId", videoId, "duplicate", duplicateOfId != null,
                "duplicateOfId", duplicateOfId == null ? -1 : duplicateOfId,
                "warnings", warnings);
    }

    @Transactional
    public long uploadObservation(Long videoId, String defectType, String rawGrade,
                                  BigDecimal stationStart, BigDecimal stationEnd,
                                  String evidenceUri, String evidenceStrength) {
        Video video = inspectionRepo.findAllVideos().stream()
                .filter(v -> v.id().equals(videoId)).findFirst()
                .orElseThrow(() -> new ApiException(404, "视频不存在: " + videoId));
        String status = video.duplicateOfId() != null ? "DUPLICATE" : "PENDING";
        return inspectionRepo.insertObservation(new Observation(null, videoId, video.batchId(),
                defectType, rawGrade, stationStart, stationEnd, evidenceUri,
                evidenceStrength, status, video.duplicateOfId(), null));
    }
}
