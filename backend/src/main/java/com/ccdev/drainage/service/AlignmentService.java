package com.ccdev.drainage.service;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.AlignmentCandidate;
import com.ccdev.drainage.domain.Models.AssetVersion;
import com.ccdev.drainage.domain.Models.Attribution;
import com.ccdev.drainage.domain.Models.Observation;
import com.ccdev.drainage.domain.Models.PipeSegment;
import com.ccdev.drainage.domain.Models.Video;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 位置对齐：
 *   1. 取批次检查时间，确定“检查发生时有效”的资产版本（effective_at <= inspected_at 的最新版）
 *   2. 按视频里程方向把原始里程换算为归一里程（反向里程 norm = length - station）
 *   3. 为每条观察保留候选依据（含其他版本的参照候选及其落选原因）
 *   4. 固定归属写入 app_observation_attribution，之后测绘修订不再改写
 */
@Service
public class AlignmentService {

    private final InspectionRepository inspectionRepo;
    private final AssetRepository assetRepo;
    private final AnalysisRepository analysisRepo;

    public AlignmentService(InspectionRepository inspectionRepo, AssetRepository assetRepo,
                            AnalysisRepository analysisRepo) {
        this.inspectionRepo = inspectionRepo;
        this.assetRepo = assetRepo;
        this.analysisRepo = analysisRepo;
    }

    /** 全量重算对齐（仅处理未标记 DUPLICATE 的观察）。 */
    @Transactional
    public Map<String, Object> realignAll() {
        analysisRepo.deleteAllCandidatesAndAttributions();

        Map<Long, Video> videoCache = new HashMap<>();
        Map<Long, AssetVersion> versionCache = new HashMap<>();
        int aligned = 0;
        int quarantined = 0;

        for (Observation obs : inspectionRepo.findAllObservations()) {
            Video video = videoCache.computeIfAbsent(obs.videoId(),
                    id -> inspectionRepo.findAllVideos().stream()
                            .filter(v -> v.id().equals(id)).findFirst()
                            .orElseThrow(() -> new ApiException(500, "视频不存在: " + id)));
            // 仅跳过“重复上传视频”里的观察；聚类派生的 DUPLICATE 状态不能作为跳过依据，
            // 否则重跑流水线会丢失这些观察的历史归属
            if (video.duplicateOfId() != null) {
                continue;
            }
            var batch = inspectionRepo.findBatchById(obs.batchId());
            AssetVersion effective = versionCache.computeIfAbsent(batch.id(),
                    k -> assetRepo.findEffectiveVersionAt(batch.inspectedAt()));
            if (effective == null) {
                throw new ApiException(500, "批次 " + batch.batchCode() + " 检查时点之前没有有效资产版本");
            }

            PipeSegment hinted = assetRepo.findSegmentsByVersion(effective.id()).stream()
                    .filter(s -> s.segmentCode().equals(video.segmentHint()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(500,
                            "版本 " + effective.versionNo() + " 中找不到管段 " + video.segmentHint()));

            BigDecimal[] norm = StationNormalizer.normalizeInterval(
                    obs.stationStartM(), obs.stationEndM(), hinted.lengthM(), video.stationDirection());
            BigDecimal normStart = norm[0];
            BigDecimal normEnd = norm[1];

            List<AlignmentCandidate> candidates = new ArrayList<>();
            boolean contained = StationNormalizer.contained(normStart, normEnd, hinted.lengthM());
            BigDecimal primaryScore = contained ? new BigDecimal("1.000") : new BigDecimal("0.500");
            String formula = "REVERSED".equals(video.stationDirection())
                    ? "反向里程换算 norm = 管长(" + hinted.lengthM() + ") - 原始里程"
                    : "同向里程 norm = 原始里程";
            String primaryReason = "检查时点有效版本=" + effective.versionNo()
                    + "；视频指向管段=" + hinted.segmentCode()
                    + "；方向约定=" + video.stationDirection() + "；" + formula
                    + "；归一区间=[" + normStart + "," + normEnd + "]"
                    + (contained ? "；完整落入管段[0," + hinted.lengthM() + "]" : "；区间越界，证据不足");
            candidates.add(new AlignmentCandidate(null, obs.id(), hinted.id(), effective.id(),
                    normStart, normEnd, primaryScore, contained, primaryReason));

            // 参照候选：同一管段编码在其他版本中的行——明确记录落选原因，证明历史归属不被后续版本吸走
            for (PipeSegment other : assetRepo.findSegmentsByCodeAcrossVersions(hinted.segmentCode())) {
                if (other.versionId().equals(effective.id())) {
                    continue;
                }
                AssetVersion otherVersion = assetRepo.findVersionById(other.versionId());
                BigDecimal[] otherNorm = StationNormalizer.normalizeInterval(
                        obs.stationStartM(), obs.stationEndM(), other.lengthM(), video.stationDirection());
                String reason = "版本 " + otherVersion.versionNo()
                        + ("ACTIVE".equals(otherVersion.status()) ? " 为后续测绘修订版" : " 非检查时点版本")
                        + "，按“检查时点固定归属”原则不得采用，仅作参照；" + formula;
                candidates.add(new AlignmentCandidate(null, obs.id(), other.id(), other.versionId(),
                        otherNorm[0], otherNorm[1], new BigDecimal("0.200"), false, reason));
            }

            AlignmentCandidate selected = null;
            for (AlignmentCandidate c : candidates) {
                analysisRepo.insertCandidate(c);
                if (Boolean.TRUE.equals(c.selected()) && selected == null) {
                    selected = c;
                }
            }

            if (selected == null) {
                inspectionRepo.updateObservationStatusAndDuplicate(obs.id(), "QUARANTINED", null);
                quarantined++;
                continue;
            }
            analysisRepo.insertAttribution(new Attribution(obs.id(), selected.segmentId(),
                    selected.assetVersionId(), selected.normStartM(), selected.normEndM(), null,
                    "固定于检查时点版本 " + effective.versionNo() + "，后续测绘修订不得改写"));
            aligned++;
        }
        return Map.of("aligned", aligned, "quarantined", quarantined,
                "candidates", analysisRepo.countCandidates());
    }

    public List<Map<String, Object>> candidatesOfObservation(Long observationId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var c : analysisRepo.findCandidatesByObservation(observationId)) {
            PipeSegment seg = assetRepo.findSegmentById(c.segmentId());
            AssetVersion ver = assetRepo.findVersionById(c.assetVersionId());
            Map<String, Object> row = new HashMap<>();
            row.put("candidate", c);
            row.put("segmentCode", seg.segmentCode());
            row.put("versionNo", ver.versionNo());
            result.add(row);
        }
        return result;
    }
}
