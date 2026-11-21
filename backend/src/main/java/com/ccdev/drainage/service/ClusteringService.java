package com.ccdev.drainage.service;

import com.ccdev.drainage.domain.Models.Attribution;
import com.ccdev.drainage.domain.Models.ContractorBatch;
import com.ccdev.drainage.domain.Models.Observation;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺陷聚类：
 *   1. 同一管段、同一缺陷类型、归一区间重叠或间距 ≤0.5m 的观察归入同一缺陷簇
 *   2. 同等级多批次观察 = 同一缺陷的相互印证，只计一次（重复件不参与风险累加）
 *   3. 不同批次给出不相容等级：仅当 FULL 对 SPARSE 的证据悬殊时自动裁决，
 *      否则保留为 CONFLICT，该处在数据集里暂停确定排序，等待人工复核裁决
 */
@Service
public class ClusteringService {

    /** 近邻归并容差（米）：小于该间距的同类型观察视为同一处缺陷。 */
    static final BigDecimal PROXIMITY = new BigDecimal("0.500");

    private final InspectionRepository inspectionRepo;
    private final AssetRepository assetRepo;
    private final AnalysisRepository analysisRepo;

    public ClusteringService(InspectionRepository inspectionRepo, AssetRepository assetRepo,
                             AnalysisRepository analysisRepo) {
        this.inspectionRepo = inspectionRepo;
        this.assetRepo = assetRepo;
        this.analysisRepo = analysisRepo;
    }

    static int evidenceRank(String evidence) {
        return switch (evidence) {
            case "FULL" -> 3;
            case "PARTIAL" -> 2;
            default -> 1;
        };
    }

    static String rankToEvidence(int rank) {
        return switch (rank) {
            case 3 -> "FULL";
            case 2 -> "PARTIAL";
            default -> "SPARSE";
        };
    }

    private static final class Member {
        Observation obs;
        Attribution attr;
        int grade;
        String evidence;
        long batchId;
    }

    @Transactional
    public Map<String, Object> rebuildClusters() {
        analysisRepo.deleteAllClustersAndMembers();

        // 重置可参与聚类的观察状态：重复上传视频中的观察保持 DUPLICATE，
        // 其余（含上一轮聚类派生的 DUPLICATE 成员）全部恢复 PENDING 重新归并
        var duplicateVideoIds = inspectionRepo.findAllVideos().stream()
                .filter(v -> v.duplicateOfId() != null)
                .map(v -> v.id()).toList();
        for (Observation o : inspectionRepo.findAllObservations()) {
            if (!duplicateVideoIds.contains(o.videoId())) {
                inspectionRepo.updateObservationStatus(o.id(), "PENDING", null);
            }
        }

        Map<Long, Attribution> attrMap = new HashMap<>();
        for (Attribution a : analysisRepo.findAllAttributions()) {
            attrMap.put(a.observationId(), a);
        }
        Map<Long, ContractorBatch> batchCache = new HashMap<>();

        // key = segmentId|defectType
        Map<String, List<Member>> groups = new HashMap<>();
        for (Observation o : inspectionRepo.findAllObservations()) {
            if ("DUPLICATE".equals(o.status()) || "QUARANTINED".equals(o.status())) {
                continue;
            }
            Attribution attr = attrMap.get(o.id());
            if (attr == null) {
                continue;
            }
            ContractorBatch batch = batchCache.computeIfAbsent(o.batchId(),
                    inspectionRepo::findBatchById);
            Integer grade = inspectionRepo.normalizeGrade(batch.gradingStandard(), o.rawGrade());
            if (grade == null) {
                throw new IllegalStateException("观察 " + o.id() + " 的原始等级 " + o.rawGrade()
                        + " 在标准 " + batch.gradingStandard() + " 中无映射");
            }
            Member m = new Member();
            m.obs = o;
            m.attr = attr;
            m.grade = grade;
            m.evidence = o.evidenceStrength();
            m.batchId = o.batchId();
            groups.computeIfAbsent(attr.segmentId() + "|" + o.defectType(), k -> new ArrayList<>()).add(m);
        }

        int clusterCount = 0;
        int conflictCount = 0;
        int duplicateMembers = 0;
        for (Map.Entry<String, List<Member>> entry : groups.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            long segmentId = Long.parseLong(parts[0]);
            String defectType = parts[1];
            List<Member> members = entry.getValue();
            members.sort(Comparator.comparing(m -> m.attr.normStartM()));

            // 近邻区间归并
            List<List<Member>> rawClusters = new ArrayList<>();
            List<Member> current = new ArrayList<>();
            BigDecimal currentEnd = null;
            for (Member m : members) {
                if (current.isEmpty()
                        || m.attr.normStartM().compareTo(currentEnd.add(PROXIMITY)) <= 0) {
                    current.add(m);
                    currentEnd = currentEnd == null || m.attr.normEndM().compareTo(currentEnd) > 0
                            ? m.attr.normEndM() : currentEnd;
                } else {
                    rawClusters.add(current);
                    current = new ArrayList<>();
                    current.add(m);
                    currentEnd = m.attr.normEndM();
                }
            }
            if (!current.isEmpty()) {
                rawClusters.add(current);
            }

            for (List<Member> mc : rawClusters) {
                BigDecimal start = mc.stream().map(m -> m.attr.normStartM()).min(BigDecimal::compareTo).orElseThrow();
                BigDecimal end = mc.stream().map(m -> m.attr.normEndM()).max(BigDecimal::compareTo).orElseThrow();
                long versionId = mc.get(0).attr.assetVersionId();

                boolean sameGrade = mc.stream().map(m -> m.grade).distinct().count() == 1;
                boolean multiBatch = mc.stream().map(m -> m.batchId).distinct().count() > 1;

                String status;
                boolean conflictFlag = false;
                Integer resolvedGrade = null;
                String note;

                int maxEvidence = mc.stream().map(m -> evidenceRank(m.evidence)).max(Integer::compare).orElse(1);
                int minEvidence = mc.stream().map(m -> evidenceRank(m.evidence)).min(Integer::compare).orElse(1);

                if (sameGrade) {
                    status = "ACTIVE";
                    resolvedGrade = mc.get(0).grade;
                    note = multiBatch ? "多批次同等级相互印证，按同一缺陷计一次" : "单批次缺陷";
                } else if (maxEvidence == 3 && minEvidence == 1) {
                    // 证据悬殊（FULL vs SPARSE）：自动采信 FULL 证据的等级
                    List<Member> full = mc.stream().filter(m -> evidenceRank(m.evidence) == 3).toList();
                    if (full.stream().map(m -> m.grade).distinct().count() == 1) {
                        status = "ADJUDICATED";
                        resolvedGrade = full.get(0).grade;
                        note = "等级不相容但证据悬殊（FULL vs SPARSE），自动采信 FULL 证据等级 " + resolvedGrade;
                    } else {
                        status = "CONFLICT";
                        conflictFlag = true;
                        note = "等级不相容且 FULL 证据之间仍不一致，原始依据不足以裁决，暂停排序";
                    }
                } else {
                    status = "CONFLICT";
                    conflictFlag = true;
                    note = "两批独立检查等级不相容（" + gradeSummary(mc)
                            + "），证据强度差不足以自动裁决，保留冲突并暂停该处确定排序";
                }
                if (conflictFlag) {
                    conflictCount++;
                }

                long clusterId = analysisRepo.insertCluster(segmentId, versionId, defectType,
                        start, end, status, conflictFlag, resolvedGrade, note, null, null);
                String clusterCode = "CL-" + assetRepo.findSegmentById(segmentId).segmentCode()
                        + "-" + defectType + "-" + start.toPlainString();
                analysisRepo.updateClusterCode(clusterId, clusterCode);

                // 成员角色：相容簇以最早批次观察为主，其余为重复件；冲突簇全部为主张方
                Member primary = mc.stream()
                        .min(Comparator.comparing((Member m) -> batchCache.computeIfAbsent(m.batchId,
                                inspectionRepo::findBatchById).inspectedAt())
                                .thenComparing(m -> m.obs.id())).orElseThrow();
                for (Member m : mc) {
                    if (conflictFlag || m == primary) {
                        analysisRepo.insertClusterMember(clusterId, m.obs.id(), "PRIMARY",
                                m.grade, m.evidence, m.batchId);
                        inspectionRepo.updateObservationStatus(m.obs.id(), "CONFIRMED", clusterId);
                    } else {
                        analysisRepo.insertClusterMember(clusterId, m.obs.id(), "DUPLICATE",
                                m.grade, m.evidence, m.batchId);
                        inspectionRepo.updateObservationStatusAndDuplicate(
                                m.obs.id(), "DUPLICATE", primary.obs.id());
                        duplicateMembers++;
                    }
                }
                clusterCount++;
            }
        }
        return Map.of("clusters", clusterCount, "conflicts", conflictCount,
                "duplicateMembers", duplicateMembers);
    }

    private String gradeSummary(List<Member> mc) {
        StringBuilder sb = new StringBuilder();
        for (Member m : mc) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append("批次").append(m.batchId).append(" 等级").append(m.grade)
                    .append("(").append(m.evidence).append(")");
        }
        return sb.toString();
    }

    /** 人工裁决冲突：工程处复核原始证据后给出确定等级，排序在下次生成数据集时恢复。 */
    @Transactional
    public void adjudicate(long clusterId, int resolvedGrade, String note, Long userId) {
        var cluster = analysisRepo.findClusterById(clusterId);
        if (!cluster.conflictFlag() && !"CONFLICT".equals(cluster.status())) {
            throw new IllegalArgumentException("聚类 " + clusterId + " 不处于冲突状态，无需裁决");
        }
        analysisRepo.adjudicateCluster(clusterId, resolvedGrade,
                "人工裁决：" + note, userId, Timestamp.valueOf(java.time.LocalDateTime.now()));
    }
}
