package com.ccdev.drainage.service;

import com.ccdev.drainage.domain.Models.AssetVersion;
import com.ccdev.drainage.domain.Models.ClusterMember;
import com.ccdev.drainage.domain.Models.DefectCluster;
import com.ccdev.drainage.domain.Models.JudgementRule;
import com.ccdev.drainage.domain.Models.PipeSegment;
import com.ccdev.drainage.domain.Models.SegmentMapping;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.DatasetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估数据集：
 *   1. 只采纳通过判读规则门槛（等级/区间长度/证据强度）的确认聚类
 *   2. MAX_SEVERITY：经确认观察中的最高严重等级，突出局部失效
 *      BURDEN：Σ 区间长度 × 等级 × 证据权重，体现广泛劣化负担
 *   3. 冲突聚类所在管段保留冲突并暂停确定排序（rank 为空，排在最后）
 *   4. 按指定资产版本复现：旧版聚类经里程映射继承到新版管段；
 *      跨拆分点的聚类隔离不复制；历史归属本身不被改写
 */
@Service
public class DatasetService {

    static final Map<String, BigDecimal> EVIDENCE_WEIGHT = Map.of(
            "FULL", BigDecimal.ONE,
            "PARTIAL", new BigDecimal("0.800"),
            "SPARSE", new BigDecimal("0.600"));

    private final DatasetRepository datasetRepo;
    private final AnalysisRepository analysisRepo;
    private final AssetRepository assetRepo;

    public DatasetService(DatasetRepository datasetRepo, AnalysisRepository analysisRepo,
                          AssetRepository assetRepo) {
        this.datasetRepo = datasetRepo;
        this.analysisRepo = analysisRepo;
        this.assetRepo = assetRepo;
    }

    /** 映射到目标版本后的有效缺陷副本。 */
    private static final class EffectiveCluster {
        DefectCluster cluster;
        long targetSegmentId;
        BigDecimal start;
        BigDecimal end;
        boolean conflict;
        Integer grade;
    }

    @Transactional
    public Map<String, Object> generateForVersion(Long versionId, String scoringMode, Long userId) {
        AssetVersion targetVersion = assetRepo.findVersionById(versionId);
        List<PipeSegment> targetSegments = assetRepo.findSegmentsByVersion(versionId);
        List<JudgementRule> rules = analysisRepo.findAllRules();
        Map<String, JudgementRule> ruleByType = new HashMap<>();
        for (JudgementRule r : rules) {
            ruleByType.put(r.defectType(), r);
        }

        // ---------- 1. 把全部历史聚类投影到目标版本管段 ----------
        Map<Long, List<EffectiveCluster>> byTarget = new LinkedHashMap<>();
        for (PipeSegment s : targetSegments) {
            byTarget.put(s.id(), new ArrayList<>());
        }
        List<String> quarantineNotes = new ArrayList<>();

        for (DefectCluster c : analysisRepo.findAllClusters()) {
            // 低于判读规则的簇不进入数据集
            JudgementRule rule = ruleByType.get(c.defectType());
            BigDecimal length = c.normEndM().subtract(c.normStartM());
            Integer grade = "CONFLICT".equals(c.status()) ? null
                    : (c.resolvedGrade() != null ? c.resolvedGrade() : null);
            boolean passesRule = rule != null && grade != null
                    && grade >= rule.minGrade()
                    && length.compareTo(rule.minLengthM()) >= 0
                    && hasRequiredEvidence(c, rule.requiredEvidence());
            if (!"CONFLICT".equals(c.status()) && !passesRule) {
                continue;
            }

            List<SegmentMapping> mappings = assetRepo.findMappingsFromOldSegment(c.segmentId());
            List<SegmentMapping> applicable = mappings.stream()
                    .filter(m -> targetVersionSegment(m.newSegmentId(), versionId) != null)
                    .toList();

            if (!applicable.isEmpty()) {
                boolean placed = false;
                for (SegmentMapping m : applicable) {
                    if (contains(m.oldStartM(), m.oldEndM(), c.normStartM(), c.normEndM())) {
                        EffectiveCluster ec = toEffective(c, m, c.normStartM(), c.normEndM());
                        byTarget.computeIfAbsent(ec.targetSegmentId, k -> new ArrayList<>()).add(ec);
                        placed = true;
                    }
                }
                if (!placed) {
                    // 跨拆分点（或落在映射边界上）：隔离，不得复制到任何新段；
                    // 具体计数由 writeImpacts 写入影响清单
                    quarantineNotes.add("聚类 " + c.clusterCode() + " 归一区间[" + c.normStartM() + ","
                            + c.normEndM() + "]跨越拆分边界，隔离不继承");
                }
            } else {
                // 无映射：尝试同版本自身或父段直系延续
                PipeSegment same = targetSegments.stream()
                        .filter(s -> s.id() == c.segmentId()).findFirst().orElse(null);
                if (same != null) {
                    byTarget.get(same.id()).add(toEffective(c, null, c.normStartM(), c.normEndM()));
                } else {
                    PipeSegment child = targetSegments.stream()
                            .filter(s -> c.segmentId() == (s.parentSegmentId() == null ? -1 : s.parentSegmentId()))
                            .findFirst().orElse(null);
                    if (child != null) {
                        EffectiveCluster ec = toEffective(c, null, c.normStartM(), c.normEndM());
                        ec.targetSegmentId = child.id();
                        byTarget.computeIfAbsent(child.id(), k -> new ArrayList<>()).add(ec);
                    }
                }
            }
        }

        // ---------- 2. 逐管段评分 ----------
        String formulaNote = "MAX_SEVERITY".equals(scoringMode)
                ? "最高严重等级口径：取管段内经确认缺陷的最高归一等级（1-4），同分时按负担分次序；突出局部失效风险"
                : "累计负担口径：负担分 = Σ 缺陷区间长度(m) × 等级 × 证据权重(FULL=1.0/PARTIAL=0.8/SPARSE=0.6)；体现广泛劣化";
        String code = "DS-" + targetVersion.versionNo() + "-" + scoringMode;
        datasetRepo.deleteByCode(code);
        long datasetId = datasetRepo.insertDataset(code, versionId, scoringMode, userId, formulaNote);

        List<long[]> rankEntries = new ArrayList<>();
        Map<Long, Integer> maxGradeMap = new HashMap<>();
        Map<Long, BigDecimal> burdenMap = new HashMap<>();
        Map<Long, Boolean> pausedMap = new HashMap<>();
        Map<Long, Integer> conflictMap = new HashMap<>();
        Map<Long, Integer> eligibleMap = new HashMap<>();
        Map<Long, BigDecimal> evidenceMap = new HashMap<>();
        Map<Long, String> explanationMap = new HashMap<>();
        Map<Long, String> pauseReasonMap = new HashMap<>();

        for (PipeSegment seg : targetSegments) {
            List<EffectiveCluster> ecs = byTarget.getOrDefault(seg.id(), List.of());
            List<EffectiveCluster> conflicts = ecs.stream().filter(e -> e.conflict).toList();
            List<EffectiveCluster> definite = ecs.stream().filter(e -> !e.conflict).toList();

            int eligible = definite.size();
            int conflictCount = conflicts.size();
            Integer maxGrade = definite.stream().map(e -> e.grade).max(Integer::compare).orElse(null);

            BigDecimal burden = BigDecimal.ZERO;
            int fullEvidence = 0;
            List<String> factors = new ArrayList<>();
            for (EffectiveCluster e : definite) {
                BigDecimal len = e.end.subtract(e.start);
                BigDecimal w = EVIDENCE_WEIGHT.getOrDefault(strongestEvidence(e.cluster),
                        new BigDecimal("0.600"));
                BigDecimal part = len.multiply(BigDecimal.valueOf(e.grade)).multiply(w)
                        .setScale(3, RoundingMode.HALF_UP);
                burden = burden.add(part);
                if ("FULL".equals(strongestEvidence(e.cluster))) {
                    fullEvidence++;
                }
                factors.add(e.cluster.clusterCode() + ": 长" + len.setScale(3, RoundingMode.HALF_UP)
                        + "m×等级" + e.grade + "×证据" + w + "=" + part);
            }
            burden = burden.setScale(3, RoundingMode.HALF_UP);
            BigDecimal completeness = eligible == 0 ? null
                    : BigDecimal.valueOf(fullEvidence).divide(BigDecimal.valueOf(eligible), 4, RoundingMode.HALF_UP);

            boolean paused = conflictCount > 0;
            StringBuilder pause = new StringBuilder();
            for (EffectiveCluster e : conflicts) {
                if (!pause.isEmpty()) {
                    pause.append("；");
                }
                pause.append("冲突缺陷 ").append(e.cluster.clusterCode())
                        .append(" 等级不相容且证据不足，暂停该处确定排序");
            }

            String explanation;
            if ("MAX_SEVERITY".equals(scoringMode)) {
                explanation = "口径=最高严重等级；最高等级=" + (maxGrade == null ? "无入围缺陷" : maxGrade)
                        + "；入围缺陷数=" + eligible + "；负担分(参照)=" + burden
                        + (factors.isEmpty() ? "" : "；明细：" + String.join("；", factors));
            } else {
                explanation = "口径=累计负担分；负担分=" + burden + "；入围缺陷数=" + eligible
                        + "；最高等级(参照)=" + (maxGrade == null ? "无" : maxGrade)
                        + (factors.isEmpty() ? "" : "；明细：" + String.join("；", factors));
            }
            if (paused) {
                explanation = pause + "；" + explanation;
            }

            maxGradeMap.put(seg.id(), maxGrade);
            burdenMap.put(seg.id(), burden);
            pausedMap.put(seg.id(), paused);
            conflictMap.put(seg.id(), conflictCount);
            eligibleMap.put(seg.id(), eligible);
            evidenceMap.put(seg.id(), completeness);
            explanationMap.put(seg.id(), explanation);
            pauseReasonMap.put(seg.id(), pause.toString());
        }

        // ---------- 3. 排名（暂停段不给名次，排最后） ----------
        List<PipeSegment> rankable = targetSegments.stream()
                .filter(s -> !pausedMap.get(s.id()) && eligibleMap.get(s.id()) > 0)
                .sorted((a, b) -> {
                    int byMax = maxGradeMap.get(b.id()).compareTo(maxGradeMap.get(a.id()));
                    if (byMax != 0) {
                        return byMax;
                    }
                    return burdenMap.get(b.id()).compareTo(burdenMap.get(a.id()));
                }).toList();
        Map<Long, Integer> ranks = new HashMap<>();
        int rank = 1;
        for (PipeSegment s : rankable) {
            ranks.put(s.id(), rank++);
        }

        for (PipeSegment seg : targetSegments) {
            datasetRepo.insertScore(datasetId, seg.id(), maxGradeMap.get(seg.id()),
                    burdenMap.get(seg.id()), ranks.get(seg.id()), pausedMap.get(seg.id()),
                    pauseReasonMap.get(seg.id()).isBlank() ? null : pauseReasonMap.get(seg.id()),
                    eligibleMap.get(seg.id()), conflictMap.get(seg.id()),
                    evidenceMap.get(seg.id()), explanationMap.get(seg.id()));
        }

        // ---------- 4. 影响清单 ----------
        writeImpacts(datasetId, versionId, quarantineNotes);

        return Map.of("datasetId", datasetId, "code", code,
                "segments", targetSegments.size(),
                "ranked", ranks.size(),
                "paused", targetSegments.stream().filter(s -> pausedMap.get(s.id())).count(),
                "quarantined", quarantineNotes.size());
    }

    private void writeImpacts(long datasetId, long versionId, List<String> quarantineNotes) {
        Map<String, JudgementRule> ruleByType = new HashMap<>();
        for (JudgementRule r : analysisRepo.findAllRules()) {
            ruleByType.put(r.defectType(), r);
        }
        for (SegmentMapping m : assetRepo.findAllMappings()) {
            PipeSegment newSeg = assetRepo.findSegmentById(m.newSegmentId());
            if (newSeg.versionId() != versionId) {
                continue;
            }
            PipeSegment oldSeg = assetRepo.findSegmentById(m.oldSegmentId());
            int inherited = 0;
            int quarantined = 0;
            List<String> qn = new ArrayList<>();
            for (DefectCluster c : analysisRepo.findClustersBySegment(oldSeg.id())) {
                // 与数据集保持同一判读门槛：未入围的低等级簇不计入继承
                JudgementRule rule = ruleByType.get(c.defectType());
                BigDecimal len = c.normEndM().subtract(c.normStartM());
                boolean eligible = "CONFLICT".equals(c.status())
                        || (rule != null && c.resolvedGrade() != null
                        && c.resolvedGrade() >= rule.minGrade()
                        && len.compareTo(rule.minLengthM()) >= 0
                        && hasRequiredEvidence(c, rule.requiredEvidence()));
                if (!eligible) {
                    continue;
                }
                if (contains(m.oldStartM(), m.oldEndM(), c.normStartM(), c.normEndM())) {
                    inherited++;
                } else if (c.normStartM().compareTo(m.oldEndM()) < 0
                        && c.normEndM().compareTo(m.oldStartM()) > 0) {
                    quarantined++;
                    qn.add(c.clusterCode() + "[" + c.normStartM() + "," + c.normEndM() + "]跨边界不复制");
                }
            }
            String type = oldSeg.lifecycleStatus(); // SPLIT / MERGED / RETIRED / ACTIVE
            String impactType = switch (type) {
                case "SPLIT" -> "SPLIT";
                case "MERGED" -> "MERGE";
                case "RETIRED" -> "RETIRE";
                default -> "RETAIN";
            };
            String note = "依据=" + m.basis() + "；旧里程[" + m.oldStartM() + "," + m.oldEndM()
                    + "]→新里程[" + m.newStartM() + "," + m.newEndM() + "]"
                    + (qn.isEmpty() ? "" : "；隔离：" + String.join("，", qn));
            datasetRepo.insertImpact(datasetId, oldSeg.id(), newSeg.id(), impactType,
                    inherited, quarantined, note);
        }
    }

    private EffectiveCluster toEffective(DefectCluster c, SegmentMapping m,
                                         BigDecimal oldStart, BigDecimal oldEnd) {
        EffectiveCluster ec = new EffectiveCluster();
        ec.cluster = c;
        ec.conflict = "CONFLICT".equals(c.status());
        ec.grade = c.resolvedGrade();
        if (m == null) {
            ec.targetSegmentId = c.segmentId();
            ec.start = oldStart;
            ec.end = oldEnd;
        } else {
            ec.targetSegmentId = m.newSegmentId();
            BigDecimal spanOld = m.oldEndM().subtract(m.oldStartM());
            BigDecimal spanNew = m.newEndM().subtract(m.newStartM());
            ec.start = m.newStartM().add(scale(oldStart.subtract(m.oldStartM()), spanNew, spanOld))
                    .setScale(3, RoundingMode.HALF_UP);
            ec.end = m.newStartM().add(scale(oldEnd.subtract(m.oldStartM()), spanNew, spanOld))
                    .setScale(3, RoundingMode.HALF_UP);
        }
        return ec;
    }

    private static BigDecimal scale(BigDecimal value, BigDecimal newSpan, BigDecimal oldSpan) {
        if (oldSpan.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.multiply(newSpan).divide(oldSpan, 3, RoundingMode.HALF_UP);
    }

    private PipeSegment targetVersionSegment(long segmentId, long versionId) {
        PipeSegment s = assetRepo.findSegmentById(segmentId);
        return s.versionId() == versionId ? s : null;
    }

    private static boolean contains(BigDecimal outerStart, BigDecimal outerEnd,
                                    BigDecimal innerStart, BigDecimal innerEnd) {
        return innerStart.compareTo(outerStart) >= 0 && innerEnd.compareTo(outerEnd) <= 0;
    }

    private boolean hasRequiredEvidence(DefectCluster c, String required) {
        int best = analysisRepo.findMembersByCluster(c.id()).stream()
                .map(ClusterMember::evidenceStrength)
                .mapToInt(ClusteringService::evidenceRank).max().orElse(0);
        return best >= ClusteringService.evidenceRank(required);
    }

    private String strongestEvidence(DefectCluster c) {
        return ClusteringService.rankToEvidence(analysisRepo.findMembersByCluster(c.id()).stream()
                .map(ClusterMember::evidenceStrength)
                .mapToInt(ClusteringService::evidenceRank).max().orElse(1));
    }
}
