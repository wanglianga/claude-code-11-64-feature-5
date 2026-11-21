package com.ccdev.drainage.service;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.AssetHistory;
import com.ccdev.drainage.domain.Models.CohortCell;
import com.ccdev.drainage.domain.Models.CohortEvidence;
import com.ccdev.drainage.domain.Models.CohortRun;
import com.ccdev.drainage.domain.Models.DefectEvent;
import com.ccdev.drainage.domain.Models.InspectionExposure;
import com.ccdev.drainage.domain.Models.SourceVersion;
import com.ccdev.drainage.repo.CohortRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 材料/投用年代队列分析器。
 *
 * <p>统计口径（两种，均按投用年龄分层，分母/权重/证据逐格留痕）：
 * <ul>
 *   <li>NAIVE：年龄分层后直接比较“已检查长度中的缺陷比例”，透明易解释；
 *       但高风险街区被过度检查时，这类管段更容易暴露缺陷，比例系统性偏高。</li>
 *   <li>IPW：以可观测选检特征（风险区/材料/年代）估计每个管段-年龄插曲的受检概率 p，
 *       用 w=1/p 对受检样本逆概率加权，减轻选择性检查偏差；权重极端时标记并限制推断。</li>
 * </ul>
 *
 * <p>硬性规则：
 * <ol>
 *   <li>内衬/换管区间年龄从改造时点重新起算，不沿用原管龄；</li>
 *   <li>未检查与遮挡区间不计“无缺陷”，遮挡长度单列、有效覆盖分母只认有效观察范围；</li>
 *   <li>修复后复发关联原事件与原暴露区间，按新发事件计复发率，不重复统计为两个管段；</li>
 *   <li>同管段多轮重复检查的观察范围取并集，选择性复查不放大分母。</li>
 * </ol>
 */
@Service
public class CohortService {

    /** 低覆盖阈值：有效覆盖低于该值的单元格只作弱证据展示。 */
    static final BigDecimal LOW_COVERAGE = new BigDecimal("0.5000");
    /** 极端权重阈值：单元格内最大 IPW 权重超过该值即标记。 */
    static final BigDecimal EXTREME_WEIGHT = new BigDecimal("10.0000");
    /** 倾向得分平滑系数（拉普拉斯），避免零受检分层产生无穷权重。 */
    static final BigDecimal SMOOTH_ALPHA = BigDecimal.ONE;

    private final CohortRepository repo;

    public CohortService(CohortRepository repo) {
        this.repo = repo;
    }

    // ============================================================ 内部承载结构
    static final class Range {
        final BigDecimal start;
        final BigDecimal end;

        Range(BigDecimal start, BigDecimal end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Interval {
        final AssetHistory history;
        final int ageBaseYear;

        Interval(AssetHistory history, int ageBaseYear) {
            this.history = history;
            this.ageBaseYear = ageBaseYear;
        }
    }

    /** 管段-年龄插曲：同一物理管道在某个年龄层内停留的期间，是倾向建模与分层的基本单位。 */
    private static final class Episode {
        String pipeCode;
        Interval interval;
        String riskZone;
        String ageBand;
        int bandOrdinal;
        final List<Range> observedRanges = new ArrayList<>();
        final List<Range> obscuredRanges = new ArrayList<>();
        final List<DefectEvent> events = new ArrayList<>();
        BigDecimal observedLen = BigDecimal.ZERO;
        BigDecimal obscuredLen = BigDecimal.ZERO;
        BigDecimal defectiveLen = BigDecimal.ZERO;
        BigDecimal weight = null; // IPW 权重，仅受检插曲非空
    }

    private static final class CellAcc {
        final String material;
        final String decade;
        final String diameterBand;
        final String ageBand;
        final String key;
        BigDecimal totalLength = BigDecimal.ZERO;
        BigDecimal inspectedLength = BigDecimal.ZERO;
        BigDecimal obscuredLength = BigDecimal.ZERO;
        int defectEvents = 0;
        int firstFindings = 0;
        int recurrences = 0;
        int repairedFirstFindings = 0;
        BigDecimal defectiveLength = BigDecimal.ZERO;
        BigDecimal weightSum = null;
        BigDecimal weightedDefect = null;
        BigDecimal weightedInspected = null;
        BigDecimal minWeight = null;
        BigDecimal maxWeight = null;
        final List<String> evidence = new ArrayList<>();
        final List<Episode> episodes = new ArrayList<>();

        CellAcc(String material, String decade, String diameterBand, String ageBand) {
            this.material = material;
            this.decade = decade;
            this.diameterBand = diameterBand;
            this.ageBand = ageBand;
            this.key = material + "|" + decade + "|" + diameterBand + "|" + ageBand;
        }
    }

    // ============================================================ 对外服务
    @Transactional
    public Map<String, Object> runCohort(String method, LocalDate asOf, Integer bandYears, Long userId) {
        String estimation = "IPW".equalsIgnoreCase(method) ? "IPW" : "NAIVE";
        int width = bandYears == null || bandYears <= 0 ? 10 : bandYears;

        long assetVer = latestVersionId("ASSET_HISTORY");
        long exposureVer = latestVersionId("INSPECTION_EXPOSURE");
        long defectVer = latestVersionId("DEFECT_EVENT");

        List<AssetHistory> histories = repo.findAllAssetHistory().stream()
                .filter(h -> h.sourceVersionId() <= assetVer).toList();
        List<InspectionExposure> exposures = repo.findAllExposures().stream()
                .filter(e -> "ACTIVE".equals(e.status()) && e.sourceVersionId() <= exposureVer).toList();
        List<DefectEvent> events = repo.findAllDefectEvents().stream()
                .filter(d -> d.sourceVersionId() <= defectVer).toList();

        Map<String, List<Interval>> intervalsByPipe = buildIntervals(histories);
        Map<String, String> riskByPipe = resolveRiskZones(exposures);
        Map<String, Episode> episodeIndex = buildEpisodes(intervalsByPipe, riskByPipe, asOf, width);
        attachExposures(episodeIndex, intervalsByPipe, exposures, width);
        attachEvents(episodeIndex, intervalsByPipe, events, width);

        Map<String, BigDecimal> propensity = estimation.equals("IPW")
                ? estimatePropensities(episodeIndex) : Map.of();

        Map<String, CellAcc> cells = aggregate(episodeIndex, estimation, propensity, width);

        String code = "CR-" + estimation + "-" + asOf.toString().replace("-", "") + "-"
                + (repo.countRuns() + 1);
        String selectionModel = estimation.equals("IPW")
                ? "按风险区×材料×投用年代分层的经验受检概率（拉普拉斯平滑 α=1），权重 w=1/p；"
                + "假设选检仅依赖这些可观测特征，未观测混杂不在校正范围内"
                : "无加权；高风险区多轮复查的观察范围取并集，但选择性检查导致的暴露差异未校正";
        String methodNote = estimation.equals("IPW")
                ? "IPW 缺陷比例 = Σ(w×缺陷长度) / Σ(w×已检查长度)，w=1/受检概率 p；"
                + "复发率 = 复发事件数 / 已修复首次发现事件数；有效检查覆盖 = 有效观察长度 / 管段总长"
                : "NAIVE 缺陷比例 = 缺陷长度 / 已检查长度（仅有效观察范围，遮挡与未检查段不入分母）；"
                + "复发率 = 复发事件数 / 已修复首次发现事件数；有效检查覆盖 = 有效观察长度 / 管段总长";
        String nonInference = "未检查或遮挡的区间不计为无缺陷：有效覆盖为 0 的年龄层不可推断缺陷比例；"
                + (estimation.equals("IPW")
                ? "IPW 仅校正可观测选检特征，极端权重（≥" + EXTREME_WEIGHT.stripTrailingZeros().toPlainString()
                + "）单元格结论不可外推；未观测到的选检因素构成残余选择偏差"
                : "NAIVE 口径未校正选择性检查，高风险街区过度检查会抬高缺陷比例，组间差异不能直接解读为材料劣化差异");

        long runId = repo.insertRun(new CohortRun(null, code, asOf, estimation, width,
                assetVer, exposureVer, defectVer, selectionModel, methodNote, nonInference,
                "CURRENT", null, userId, null));

        int lowCoverage = 0;
        int extreme = 0;
        int nonInferable = 0;
        for (CellAcc c : cells.values()) {
            BigDecimal coverage = c.totalLength.signum() == 0 ? BigDecimal.ZERO
                    : c.inspectedLength.divide(c.totalLength, 4, RoundingMode.HALF_UP);
            BigDecimal defectRatio = c.inspectedLength.signum() == 0 ? null
                    : c.defectiveLength.divide(c.inspectedLength, 6, RoundingMode.HALF_UP);
            BigDecimal recurrenceRate = c.repairedFirstFindings == 0 ? null
                    : BigDecimal.valueOf(c.recurrences)
                    .divide(BigDecimal.valueOf(c.repairedFirstFindings), 6, RoundingMode.HALF_UP);
            BigDecimal weightedRatio = null;
            if ("IPW".equals(estimation) && c.weightedInspected != null
                    && c.weightedInspected.signum() > 0) {
                weightedRatio = c.weightedDefect.divide(c.weightedInspected, 6, RoundingMode.HALF_UP);
            }
            boolean lowFlag = coverage.compareTo(LOW_COVERAGE) < 0;
            boolean extremeFlag = c.maxWeight != null && c.maxWeight.compareTo(EXTREME_WEIGHT) >= 0;
            boolean inferFlag = c.inspectedLength.signum() == 0;
            if (lowFlag) {
                lowCoverage++;
            }
            if (extremeFlag) {
                extreme++;
            }
            if (inferFlag) {
                nonInferable++;
            }
            repo.insertCell(new CohortCell(null, runId, c.material, c.decade, c.diameterBand, c.ageBand,
                    c.totalLength, c.inspectedLength, c.obscuredLength, coverage,
                    c.defectEvents, c.firstFindings, c.recurrences, c.defectiveLength, defectRatio,
                    recurrenceRate, c.weightSum, c.weightedDefect, c.weightedInspected, weightedRatio,
                    c.minWeight, c.maxWeight, String.join("；", c.evidence),
                    lowFlag, extremeFlag, inferFlag));
            for (String ev : c.evidence) {
                // evidence 文本在写入时已带类型前缀，拆回结构化行便于下钻
                int sep = ev.indexOf('|');
                repo.insertEvidence(new CohortEvidence(null, runId, c.key,
                        ev.substring(0, sep), ev.substring(sep + 1), ev));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("code", code);
        summary.put("method", estimation);
        summary.put("asOf", asOf.toString());
        summary.put("cells", cells.size());
        summary.put("lowCoverageCells", lowCoverage);
        summary.put("extremeWeightCells", extreme);
        summary.put("nonInferableCells", nonInferable);
        summary.put("assetVersionId", assetVer);
        summary.put("exposureVersionId", exposureVer);
        summary.put("defectVersionId", defectVer);
        return summary;
    }

    /**
     * 补录/撤销后增量重算：只重跑“所钉源版本已落后于该域最新版本”的 CURRENT 运行，
     * 旧运行置 SUPERSEDED 并保留全部结果行，其他运行不动。
     */
    @Transactional
    public List<Map<String, Object>> rerunAffected(String changedDomain, Long userId) {
        List<Map<String, Object>> replaced = new ArrayList<>();
        for (CohortRun run : repo.findCurrentRuns()) {
            long pinned = switch (changedDomain) {
                case "ASSET_HISTORY" -> run.assetVersionId();
                case "INSPECTION_EXPOSURE" -> run.exposureVersionId();
                case "DEFECT_EVENT" -> run.defectVersionId();
                default -> throw new ApiException(400, "未知源数据域: " + changedDomain);
            };
            long latest = latestVersionId(changedDomain);
            if (pinned >= latest) {
                continue;
            }
            Map<String, Object> fresh = runCohort(run.estimationMethod(), run.asOfDate(),
                    run.ageBandYears(), userId);
            long newRunId = ((Number) fresh.get("runId")).longValue();
            repo.supersedeRun(run.id(), newRunId);
            replaced.add(Map.of("supersededRunId", run.id(), "newRunId", newRunId,
                    "method", run.estimationMethod(), "changedDomain", changedDomain));
        }
        return replaced;
    }

    public Map<String, Object> runDetail(Long runId) {
        CohortRun run = repo.findRunById(runId);
        return Map.of("run", run, "cells", repo.findCellsByRun(runId),
                "evidence", repo.findEvidenceByRun(runId),
                "assetVersion", repo.findVersionById(run.assetVersionId()),
                "exposureVersion", repo.findVersionById(run.exposureVersionId()),
                "defectVersion", repo.findVersionById(run.defectVersionId()));
    }

    /** 队列对比：多个运行的同键单元格并排，便于 NAIVE/IPW 或新旧版本对照。 */
    public Map<String, Object> compareRuns(List<Long> runIds) {
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Long id : runIds) {
            CohortRun run = repo.findRunById(id);
            runs.add(Map.of("runId", run.id(), "code", run.runCode(), "method", run.estimationMethod(),
                    "status", run.status(), "asOf", run.asOfDate().toString()));
            for (CohortCell cell : repo.findCellsByRun(id)) {
                String key = cell.material() + "|" + cell.commissionDecade() + "|"
                        + cell.diameterBand() + "|" + cell.ageBand();
                byKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(Map.of("runId", id, "cell", cell));
            }
        }
        return Map.of("runs", runs, "cells", byKey);
    }

    // ============================================================ 建模步骤
    private Map<String, List<Interval>> buildIntervals(List<AssetHistory> histories) {
        Map<String, List<Interval>> result = new LinkedHashMap<>();
        Map<String, Integer> originalCommission = new HashMap<>();
        for (AssetHistory h : histories) {
            originalCommission.putIfAbsent(h.physicalPipeCode(), h.commissionYear());
        }
        Map<String, List<AssetHistory>> grouped = new LinkedHashMap<>();
        for (AssetHistory h : histories) {
            grouped.computeIfAbsent(h.physicalPipeCode(), k -> new ArrayList<>()).add(h);
        }
        for (Map.Entry<String, List<AssetHistory>> e : grouped.entrySet()) {
            List<AssetHistory> sorted = e.getValue().stream()
                    .sorted(Comparator.comparing(AssetHistory::effectiveFrom).thenComparing(AssetHistory::id))
                    .toList();
            List<Interval> intervals = new ArrayList<>();
            for (AssetHistory h : sorted) {
                if ("RETIRE".equals(h.eventType())) {
                    continue;
                }
                // 年龄重置事件（换管/内衬）从改造年起算；材料变更仅材料暴露重置，结构年龄沿用原投用年
                int ageBase = Boolean.TRUE.equals(h.ageReset())
                        ? h.commissionYear()
                        : originalCommission.getOrDefault(e.getKey(), h.commissionYear());
                intervals.add(new Interval(h, ageBase));
            }
            result.put(e.getKey(), intervals);
        }
        return result;
    }

    private Map<String, String> resolveRiskZones(List<InspectionExposure> exposures) {
        Map<String, String> risk = new HashMap<>();
        for (InspectionExposure e : exposures) {
            // 同一管道若曾进入高风险区名单，按高风险建模（选检特征取最保守口径）
            risk.merge(e.physicalPipeCode(), e.riskZone(),
                    (a, b) -> "HIGH_RISK".equals(a) || "HIGH_RISK".equals(b) ? "HIGH_RISK" : "NORMAL");
        }
        return risk;
    }

    private Map<String, Episode> buildEpisodes(Map<String, List<Interval>> intervalsByPipe,
                                               Map<String, String> riskByPipe,
                                               LocalDate asOf, int width) {
        Map<String, Episode> index = new LinkedHashMap<>();
        for (Map.Entry<String, List<Interval>> e : intervalsByPipe.entrySet()) {
            for (Interval iv : e.getValue()) {
                LocalDate end = iv.history.effectiveTo() != null
                        ? iv.history.effectiveTo().toLocalDate() : asOf;
                if (end.isAfter(asOf)) {
                    end = asOf;
                }
                int maxAge = end.getYear() - iv.ageBaseYear;
                // 固定宽度年龄层（0-9/10-19/…），保证不同管道的同层能聚合到同一单元格；
                // 管道会在其经历过的每个年龄层各出现一次（人时口径），总长在各层分别计一次
                int bandCount = maxAge / width + 1;
                for (int b = 0; b < bandCount; b++) {
                    int lo = b * width;
                    String band = lo + "-" + (lo + width - 1);
                    Episode ep = new Episode();
                    ep.pipeCode = e.getKey();
                    ep.interval = iv;
                    ep.riskZone = riskByPipe.getOrDefault(e.getKey(), "NORMAL");
                    ep.ageBand = band;
                    ep.bandOrdinal = b;
                    index.put(episodeKey(e.getKey(), iv.history.id(), band), ep);
                }
            }
        }
        return index;
    }

    private void attachExposures(Map<String, Episode> episodeIndex,
                                 Map<String, List<Interval>> intervalsByPipe,
                                 List<InspectionExposure> exposures, int width) {
        for (InspectionExposure ex : exposures) {
            Interval iv = activeInterval(intervalsByPipe.get(ex.physicalPipeCode()), ex.inspectedAt());
            if (iv == null) {
                throw new ApiException(500, "检查暴露 " + ex.id() + " 时点 " + ex.inspectedAt()
                        + " 在管道 " + ex.physicalPipeCode() + " 找不到有效资产沿革区间");
            }
            int age = ex.inspectedAt().getYear() - iv.ageBaseYear;
            String band = ageBand(age, width);
            Episode ep = episodeIndex.get(episodeKey(ex.physicalPipeCode(), iv.history.id(), band));
            if (ep == null) {
                throw new ApiException(500, "检查暴露 " + ex.id() + " 找不到对应年龄插曲（管龄 " + age + "）");
            }
            Range range = new Range(ex.stationStartM(), ex.stationEndM());
            if ("OBSCURED".equals(ex.coverageStatus())) {
                ep.obscuredRanges.add(range);
            } else {
                ep.observedRanges.add(range);
            }
        }
        for (Episode ep : episodeIndex.values()) {
            ep.obscuredLen = unionLength(ep.obscuredRanges);
            // 遮挡段不得算作有效观察：有效观察 = 观察并集 − 遮挡并集
            ep.observedLen = unionLength(ep.observedRanges).subtract(
                    overlapLength(ep.observedRanges, ep.obscuredRanges));
            if (ep.observedLen.signum() < 0) {
                ep.observedLen = BigDecimal.ZERO;
            }
        }
    }

    private void attachEvents(Map<String, Episode> episodeIndex,
                              Map<String, List<Interval>> intervalsByPipe,
                              List<DefectEvent> events, int width) {
        for (DefectEvent d : events) {
            Interval iv = activeInterval(intervalsByPipe.get(d.physicalPipeCode()), d.foundAt());
            if (iv == null) {
                throw new ApiException(500, "缺陷事件 " + d.id() + " 时点 " + d.foundAt()
                        + " 在管道 " + d.physicalPipeCode() + " 找不到有效资产沿革区间");
            }
            int age = d.foundAt().getYear() - iv.ageBaseYear;
            String band = ageBand(age, width);
            Episode ep = episodeIndex.get(episodeKey(d.physicalPipeCode(), iv.history.id(), band));
            if (ep == null) {
                throw new ApiException(500, "缺陷事件 " + d.id() + " 找不到对应年龄插曲（管龄 " + age + "）");
            }
            ep.events.add(d);
        }
        for (Episode ep : episodeIndex.values()) {
            // 缺陷长度只按首次发现位置取并集；复发在原位，不增加缺陷长度、不拆成独立管段
            List<Range> firstRanges = ep.events.stream()
                    .filter(d -> "FIRST_FINDING".equals(d.eventKind()))
                    .map(d -> new Range(d.stationStartM(), d.stationEndM())).toList();
            ep.defectiveLen = unionLength(firstRanges);
        }
    }

    /**
     * 倾向得分：单位为管段-年龄插曲，特征键 = 风险区|材料|年代。
     * p = (受检插曲数 + α) / (插曲总数 + 2α)，全分层返回，未受检分层也有平滑后概率。
     */
    private Map<String, BigDecimal> estimatePropensities(Map<String, Episode> episodeIndex) {
        Map<String, int[]> counts = new HashMap<>(); // [total, inspected]
        Map<String, BigDecimal> p = new HashMap<>();
        for (Episode ep : episodeIndex.values()) {
            String fk = featureKey(ep);
            int[] c = counts.computeIfAbsent(fk, k -> new int[2]);
            c[0]++;
            if (ep.observedLen.signum() > 0) {
                c[1]++;
            }
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            BigDecimal numerator = BigDecimal.valueOf(e.getValue()[1]).add(SMOOTH_ALPHA);
            BigDecimal denominator = BigDecimal.valueOf(e.getValue()[0]).add(SMOOTH_ALPHA.multiply(BigDecimal.TWO));
            p.put(e.getKey(), numerator.divide(denominator, 6, RoundingMode.HALF_UP));
        }
        for (Episode ep : episodeIndex.values()) {
            if (ep.observedLen.signum() > 0) {
                BigDecimal propensity = p.get(featureKey(ep));
                ep.weight = BigDecimal.ONE.divide(propensity, 4, RoundingMode.HALF_UP);
            }
        }
        return p;
    }

    private Map<String, CellAcc> aggregate(Map<String, Episode> episodeIndex, String method,
                                           Map<String, BigDecimal> propensity, int width) {
        Map<String, CellAcc> cells = new LinkedHashMap<>();
        for (Episode ep : episodeIndex.values()) {
            AssetHistory h = ep.interval.history;
            String decade = decade(h.commissionYear());
            String dBand = diameterBand(h.diameterMm());
            CellAcc cell = cells.computeIfAbsent(cellKey(h.material(), decade, dBand, ep.ageBand),
                    k -> new CellAcc(h.material(), decade, dBand, ep.ageBand));
            cell.episodes.add(ep);
            // 同一管道在同一单元格只计一次总长，避免多轮复查重复累计
            cell.totalLength = cell.totalLength.add(h.lengthM());
            cell.inspectedLength = cell.inspectedLength.add(ep.observedLen);
            cell.obscuredLength = cell.obscuredLength.add(ep.obscuredLen);
            cell.defectiveLength = cell.defectiveLength.add(ep.defectiveLen);
            for (DefectEvent d : ep.events) {
                cell.defectEvents++;
                if ("RECURRENCE".equals(d.eventKind())) {
                    cell.recurrences++;
                } else {
                    cell.firstFindings++;
                    if (d.repairedAt() != null) {
                        cell.repairedFirstFindings++;
                    }
                }
            }
            if ("IPW".equals(method) && ep.weight != null) {
                cell.weightSum = nz(cell.weightSum).add(ep.weight);
                cell.weightedInspected = nz(cell.weightedInspected)
                        .add(ep.weight.multiply(ep.observedLen));
                cell.weightedDefect = nz(cell.weightedDefect)
                        .add(ep.weight.multiply(ep.defectiveLen));
                cell.minWeight = cell.minWeight == null ? ep.weight : cell.minWeight.min(ep.weight);
                cell.maxWeight = cell.maxWeight == null ? ep.weight : cell.maxWeight.max(ep.weight);
            }
            collectEvidence(cell, ep);
        }
        return cells;
    }

    private void collectEvidence(CellAcc cell, Episode ep) {
        AssetHistory h = ep.interval.history;
        String assetEv = "ASSET_INTERVAL|" + h.physicalPipeCode() + " " + h.eventType()
                + " " + h.material() + " DN" + h.diameterMm() + " 年龄基准年 " + ep.interval.ageBaseYear
                + " 证据 " + h.evidenceRef();
        if (!cell.evidence.contains(assetEv)) {
            cell.evidence.add(assetEv);
        }
        for (Range r : ep.observedRanges) {
            cell.evidence.add("INSPECTION_WINDOW|" + ep.pipeCode + " 里程[" + r.start + "," + r.end
                    + "] 风险区 " + ep.riskZone + " 年龄层 " + ep.ageBand);
        }
        for (Range r : ep.obscuredRanges) {
            cell.evidence.add("OBSCURED_WINDOW|" + ep.pipeCode + " 里程[" + r.start + "," + r.end
                    + "] 遮挡不计无缺陷 年龄层 " + ep.ageBand);
        }
        for (DefectEvent d : ep.events) {
            String kind = "RECURRENCE".equals(d.eventKind()) ? "RECURRENCE" : "FIRST_FINDING";
            cell.evidence.add(kind + "|" + ep.pipeCode + " " + d.defectType() + " 等级" + d.normalizedGrade()
                    + " 里程[" + d.stationStartM() + "," + d.stationEndM() + "] 暴露键 " + d.exposureKey()
                    + (d.originalEventId() == null ? "" : " 关联原事件#" + d.originalEventId())
                    + " 证据 " + d.evidenceRef());
            if (d.repairedAt() != null) {
                cell.evidence.add("REPAIR|" + ep.pipeCode + " 维修单 " + d.repairId() + " 工法 "
                        + d.repairMethod() + " 修复后状态 " + d.postRepairStatus());
            }
        }
    }

    // ============================================================ 工具方法
    private long latestVersionId(String domain) {
        return repo.findLatestVersionByDomain(domain)
                .map(SourceVersion::id)
                .orElseThrow(() -> new ApiException(500, "源数据域 " + domain + " 尚无版本，无法运行队列分析"));
    }

    private Interval activeInterval(List<Interval> intervals, LocalDateTime at) {
        if (intervals == null) {
            return null;
        }
        Interval found = null;
        for (Interval iv : intervals) {
            AssetHistory h = iv.history;
            boolean afterStart = !at.isBefore(h.effectiveFrom());
            boolean beforeEnd = h.effectiveTo() == null || at.isBefore(h.effectiveTo());
            if (afterStart && beforeEnd) {
                found = iv;
            }
        }
        return found;
    }

    static BigDecimal unionLength(List<Range> ranges) {
        if (ranges.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<Range> sorted = ranges.stream()
                .sorted(Comparator.comparing(r -> r.start)).toList();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal curStart = sorted.get(0).start;
        BigDecimal curEnd = sorted.get(0).end;
        for (int i = 1; i < sorted.size(); i++) {
            Range r = sorted.get(i);
            if (r.start.compareTo(curEnd) <= 0) {
                if (r.end.compareTo(curEnd) > 0) {
                    curEnd = r.end;
                }
            } else {
                total = total.add(curEnd.subtract(curStart));
                curStart = r.start;
                curEnd = r.end;
            }
        }
        return total.add(curEnd.subtract(curStart));
    }

    static BigDecimal overlapLength(List<Range> observed, List<Range> obscured) {
        BigDecimal overlap = BigDecimal.ZERO;
        for (Range o : observed) {
            for (Range b : obscured) {
                BigDecimal lo = o.start.max(b.start);
                BigDecimal hi = o.end.min(b.end);
                if (hi.compareTo(lo) > 0) {
                    overlap = overlap.add(hi.subtract(lo));
                }
            }
        }
        return overlap;
    }

    static String ageBand(int age, int width) {
        int lo = Math.max(age, 0) / width * width;
        return lo + "-" + (lo + width - 1);
    }

    static String decade(int year) {
        int lo = year / 10 * 10;
        return lo + "s";
    }

    static String diameterBand(int diameterMm) {
        if (diameterMm < 500) {
            return "DN<500";
        }
        if (diameterMm < 800) {
            return "DN500-799";
        }
        return "DN≥800";
    }

    private static String episodeKey(String pipe, long intervalId, String band) {
        return pipe + "#" + intervalId + "#" + band;
    }

    private static String cellKey(String material, String decade, String diameterBand, String ageBand) {
        return material + "|" + decade + "|" + diameterBand + "|" + ageBand;
    }

    private static String featureKey(Episode ep) {
        return ep.riskZone + "|" + ep.interval.history.material()
                + "|" + decade(ep.interval.history.commissionYear());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ============================================================ 补录/撤销入口
    @Transactional
    public Map<String, Object> backfillReplacement(String pipeCode, String eventType, LocalDate effectiveDate,
                                                   String material, Integer diameterMm, BigDecimal lengthM,
                                                   String evidenceRef, String note, Long userId) {
        if (!List.of("PIPE_REPLACEMENT", "RELINER", "MATERIAL_CHANGE").contains(eventType)) {
            throw new ApiException(400, "补录事件类型仅支持 PIPE_REPLACEMENT / RELINER / MATERIAL_CHANGE");
        }
        long ver = repo.insertSourceVersion("ASSET_HISTORY",
                "AH-BACKFILL-" + LocalDate.now(), note, userId);
        List<AssetHistory> pipeHistory = repo.findAssetHistoryByPipe(pipeCode);
        AssetHistory open = pipeHistory.stream()
                .filter(h -> h.effectiveTo() == null && !"RETIRE".equals(h.eventType()))
                .max(Comparator.comparing(AssetHistory::effectiveFrom))
                .orElseThrow(() -> new ApiException(404, "管道 " + pipeCode + " 没有开放中的沿革区间"));
        LocalDateTime at = effectiveDate.atStartOfDay();
        // 先插入新区间拿到 id，再回写旧区间的取代关系（旧行保留留痕）
        long newId = repo.insertAssetHistory(new AssetHistory(null, pipeCode, open.segmentCode(), eventType,
                material, diameterMm, effectiveDate.getYear(), lengthM, at, null,
                !"MATERIAL_CHANGE".equals(eventType), ver, evidenceRef, null));
        repo.closeAssetHistoryInterval(open.id(), at, newId);
        List<Map<String, Object>> replaced = rerunAffected("ASSET_HISTORY", userId);
        return Map.of("sourceVersionId", ver, "newHistoryId", newId, "closedHistoryId", open.id(),
                "rerunRuns", replaced);
    }

    @Transactional
    public Map<String, Object> revokeObservation(Long exposureId, String reason, Long userId) {
        InspectionExposure ex = repo.findExposureById(exposureId);
        if ("REVOKED".equals(ex.status())) {
            throw new ApiException(409, "检查暴露 " + exposureId + " 已撤销，不能重复撤销");
        }
        long ver = repo.insertSourceVersion("INSPECTION_EXPOSURE",
                "IE-REVOKE-" + LocalDate.now(), "撤销观察#" + exposureId + "：" + reason, userId);
        repo.revokeExposure(exposureId, ver);
        List<Map<String, Object>> replaced = rerunAffected("INSPECTION_EXPOSURE", userId);
        return Map.of("revokedExposureId", exposureId, "sourceVersionId", ver, "rerunRuns", replaced);
    }

    @Transactional
    public Map<String, Object> registerExposure(InspectionExposure request, Long userId) {
        // 先登记该域新版本，再把新观察挂到新版本上，保证版本钉选与重算口径一致
        long ver = repo.insertSourceVersion("INSPECTION_EXPOSURE",
                "IE-ADD-" + LocalDate.now(), "补录检查暴露 " + request.batchCode(), userId);
        long id = repo.insertExposure(new InspectionExposure(null, request.physicalPipeCode(),
                request.segmentCode(), request.batchCode(), request.inspectedAt(),
                request.stationStartM(), request.stationEndM(), request.coverageStatus(),
                request.obscuredReason(), request.riskZone(), ver, "ACTIVE", null, request.evidenceRef()));
        List<Map<String, Object>> replaced = rerunAffected("INSPECTION_EXPOSURE", userId);
        return Map.of("exposureId", id, "sourceVersionId", ver, "rerunRuns", replaced);
    }

    @Transactional
    public Map<String, Object> registerDefectEvent(DefectEvent request, Long userId) {
        long ver = repo.insertSourceVersion("DEFECT_EVENT", "DE-ADD-" + LocalDate.now(),
                "补录缺陷事件 " + request.physicalPipeCode() + " " + request.defectType(), userId);
        long id = repo.insertDefectEvent(new DefectEvent(null, request.physicalPipeCode(),
                request.segmentCode(), request.eventKind(), request.originalEventId(), request.defectType(),
                request.normalizedGrade(), request.stationStartM(), request.stationEndM(), request.foundAt(),
                request.exposureKey(), request.repairId(), request.repairedAt(), request.repairMethod(),
                request.postRepairStatus(), ver, request.evidenceRef()));
        List<Map<String, Object>> replaced = rerunAffected("DEFECT_EVENT", userId);
        return Map.of("defectEventId", id, "sourceVersionId", ver, "rerunRuns", replaced);
    }

    /** 登记一条资产沿革区间（新管道建档或补充材料变更），产生新版本并重算受影响队列。 */
    @Transactional
    public Map<String, Object> registerAssetHistory(AssetHistory request, Long userId) {
        long ver = repo.insertSourceVersion("ASSET_HISTORY", "AH-ADD-" + LocalDate.now(),
                "补录资产沿革 " + request.physicalPipeCode() + " " + request.eventType(), userId);
        long id = repo.insertAssetHistory(new AssetHistory(null, request.physicalPipeCode(),
                request.segmentCode(), request.eventType(), request.material(), request.diameterMm(),
                request.commissionYear(), request.lengthM(), request.effectiveFrom(), request.effectiveTo(),
                request.ageReset(), ver, request.evidenceRef(), request.supersededById()));
        List<Map<String, Object>> replaced = rerunAffected("ASSET_HISTORY", userId);
        return Map.of("assetHistoryId", id, "sourceVersionId", ver, "rerunRuns", replaced);
    }

    public List<SourceVersion> versions(String domain) {
        return repo.findVersionsByDomain(domain);
    }

    public List<AssetHistory> assetHistory(String pipeCode) {
        return Objects.requireNonNullElseGet(pipeCode, () -> "").isBlank()
                ? repo.findAllAssetHistory() : repo.findAssetHistoryByPipe(pipeCode);
    }

    public List<InspectionExposure> exposures() {
        return repo.findAllExposures();
    }

    public List<DefectEvent> defectEvents() {
        return repo.findAllDefectEvents();
    }
}
