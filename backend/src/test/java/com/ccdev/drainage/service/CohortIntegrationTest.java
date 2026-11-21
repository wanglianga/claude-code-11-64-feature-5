package com.ccdev.drainage.service;

import com.ccdev.drainage.domain.Models.AssetHistory;
import com.ccdev.drainage.domain.Models.CohortCell;
import com.ccdev.drainage.domain.Models.CohortRun;
import com.ccdev.drainage.domain.Models.InspectionExposure;
import com.ccdev.drainage.repo.CohortRepository;
import com.ccdev.drainage.service.CohortService.Range;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列统计域集成测试：独立重建 schema/种子后运行 NAIVE 与 IPW 两种口径，
 * 覆盖年龄分布不均、选择性检查、内衬改造、换管重置、修复复发、低覆盖/遮挡、
 * IPW 极端权重、补录/撤销的版本化增量重算与旧结果保留。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CohortIntegrationTest {

    private static final LocalDate AS_OF = LocalDate.parse("2026-11-21");
    private static final String RCP = "钢筋混凝土";

    @Autowired CohortService cohortService;
    @Autowired CohortRepository repo;

    private long naiveRunId;
    private long ipwRunId;

    static {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/ccdev_test", "ccdev", "ccdev")) {
            try (var st = conn.createStatement()) {
                st.execute("DROP SCHEMA public CASCADE");
                st.execute("CREATE SCHEMA public");
            }
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed-data.sql"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    void generateRuns() {
        naiveRunId = ((Number) cohortService.runCohort("NAIVE", AS_OF, 10, 1L).get("runId")).longValue();
        ipwRunId = ((Number) cohortService.runCohort("IPW", AS_OF, 10, 1L).get("runId")).longValue();
    }

    private Map<String, CohortCell> cells(long runId) {
        Map<String, CohortCell> map = new java.util.LinkedHashMap<>();
        for (CohortCell c : repo.findCellsByRun(runId)) {
            map.put(c.material() + "|" + c.commissionDecade() + "|" + c.diameterBand() + "|" + c.ageBand(), c);
        }
        return map;
    }

    private static void assertScale(BigDecimal actual, double expected) {
        assertEquals(0, actual.compareTo(BigDecimal.valueOf(expected)),
                "期望 " + expected + " 实际 " + actual);
    }

    private static void assertClose(BigDecimal actual, double expected, double tol) {
        assertNotNull(actual);
        assertEquals(expected, actual.doubleValue(), tol);
    }

    // ------------------------------------------------------------ 1. 年龄分层与透明口径分母
    @Test
    @Order(1)
    void naiveCellsCarryTransparentDenominatorsAndAgeBands() {
        Map<String, CohortCell> cells = cells(naiveRunId);

        // RCP-01(1995)、RCP-02(1998) 的 20-29 年龄层全覆盖；RCP-04 内衬前旧区间同格但无检查
        CohortCell rcp90s = cells.get(RCP + "|1990s|DN≥800|20-29");
        assertNotNull(rcp90s);
        assertScale(rcp90s.totalLengthM(), 290);       // 100 + 100 + RCP-04 旧区间 90
        assertScale(rcp90s.inspectedLengthM(), 200);   // 仅 RCP-01/02 受检
        assertClose(rcp90s.effectiveCoverage(), 0.6897, 0.0001);
        // 缺陷长度：RCP-01 2m + RCP-02 首次发现 1.5+0.5=2m（复发段不重复计长）⇒ 4/200
        assertScale(rcp90s.defectiveLengthM(), 4);
        assertScale(rcp90s.defectRatio(), 0.020000);
        assertEquals(4, rcp90s.defectEventCount());
        assertEquals(3, rcp90s.firstFindingCount());
        assertFalse(rcp90s.lowCoverageFlag());

        // 未检查年龄层：分母为 0，不可推断，绝不计成“无缺陷”
        CohortCell uninspected = cells.get(RCP + "|1990s|DN≥800|0-9");
        assertNotNull(uninspected);
        assertScale(uninspected.inspectedLengthM(), 0);
        assertNull(uninspected.defectRatio());
        assertTrue(uninspected.nonInferableFlag());
        assertTrue(uninspected.lowCoverageFlag());

        // 年龄分布不均：1995 投用管道已进入 30-39 层，2010s 管道只在 0-9/10-19 层
        assertNotNull(cells.get(RCP + "|1990s|DN≥800|30-39"));
        assertNotNull(cells.get("PVC|2010s|DN<500|0-9"));
    }

    // ------------------------------------------------------------ 2. 修复后复发关联原区间
    @Test
    @Order(2)
    void recurrenceIsLinkedToOriginalIntervalAndCountedOnce() {
        CohortCell rcp90s = cells(naiveRunId).get(RCP + "|1990s|DN≥800|20-29");
        assertEquals(1, rcp90s.recurrenceCount());
        // 已修复首次发现 2 起（DE-1、DE-2），其中 1 起原位复发 ⇒ 复发率 0.5
        assertScale(rcp90s.recurrenceRate(), 0.500000);
        // 证据明细中复发行关联原事件 #2，且没有把复发位置重复加进缺陷长度
        var evidence = repo.findEvidenceByRunAndCell(naiveRunId, RCP + "|1990s|DN≥800|20-29");
        assertTrue(evidence.stream().anyMatch(e -> "RECURRENCE".equals(e.evidenceKind())
                && e.detail().contains("关联原事件#2")));
        assertTrue(evidence.stream().anyMatch(e -> "REPAIR".equals(e.evidenceKind())));
    }

    // ------------------------------------------------------------ 3. 内衬分段重置年龄
    @Test
    @Order(3)
    void relinerResetsServiceAgeAndMaterialExposure() {
        Map<String, CohortCell> cells = cells(naiveRunId);

        // 2024 年内衬层上的裂缝归到 CIPP 2010s 队列的 0-9 年龄层（管龄 6 年，而非 32 年）
        CohortCell cipp = cells.get("CIPP内衬|2010s|DN≥800|0-9");
        assertNotNull(cipp);
        assertScale(cipp.totalLengthM(), 90);
        assertScale(cipp.inspectedLengthM(), 90);
        assertScale(cipp.defectiveLengthM(), 1.2);
        assertScale(cipp.defectRatio(), 0.013333);
        assertTrue(repo.findEvidenceByRun(naiveRunId).stream()
                .anyMatch(e -> "INSPECTION_WINDOW".equals(e.evidenceKind())
                        && e.detail().contains("P-RCP-04") && e.detail().contains("年龄层 0-9")));

        // 旧钢筋混凝土区间的低年龄层没有任何有效观察（2024 检查不得落回旧管龄）
        for (String band : List.of("0-9", "10-19")) {
            CohortCell old = cells.get(RCP + "|1990s|DN≥800|" + band);
            assertNotNull(old);
            assertScale(old.inspectedLengthM(), 0);
        }
    }

    // ------------------------------------------------------------ 4. 换管重置年龄
    @Test
    @Order(4)
    void pipeReplacementStartsNewAgeCohort() {
        Map<String, CohortCell> cells = cells(naiveRunId);
        // 2021 检查落在 2019 换管后的新管：2010s 队列 0-9 年龄层，缺陷 1m / 70m
        CohortCell replaced = cells.get(RCP + "|2010s|DN≥800|0-9");
        assertNotNull(replaced);
        assertScale(replaced.inspectedLengthM(), 70);
        assertScale(replaced.defectRatio(), 0.014286);
        // 2000s 旧管区间未受检
        CohortCell old = cells.get(RCP + "|2000s|DN≥800|10-19");
        assertNotNull(old);
        assertScale(old.inspectedLengthM(), 0);
        assertTrue(old.nonInferableFlag());
    }

    // ------------------------------------------------------------ 5. 低覆盖与遮挡不计无缺陷
    @Test
    @Order(5)
    void obscuredAndUninspectedRangesAreNotCountedAsDefectFree() {
        CohortCell cell = cells(naiveRunId).get(RCP + "|2000s|DN500-799|20-29");
        assertNotNull(cell);
        assertScale(cell.totalLengthM(), 80);
        assertScale(cell.inspectedLengthM(), 20);   // 仅 0-20m 有效观察
        assertScale(cell.obscuredLengthM(), 20);    // 30-50m 遮挡单列
        assertScale(cell.effectiveCoverage(), 0.25);
        assertTrue(cell.lowCoverageFlag());
        assertScale(cell.defectRatio(), 0.05);      // 1m / 20m，遮挡段不入分母
        assertFalse(cell.nonInferableFlag());
        assertTrue(repo.findEvidenceByRun(naiveRunId).stream()
                .anyMatch(e -> "OBSCURED_WINDOW".equals(e.evidenceKind())
                        && e.detail().contains("P-RCP-03")));
    }

    // ------------------------------------------------------------ 6. 选择性检查与 IPW 加权
    @Test
    @Order(6)
    void ipwReweightsSelectiveInspectionAndReportsWeights() {
        CohortRun run = repo.findRunById(ipwRunId);
        assertEquals("IPW", run.estimationMethod());
        assertNotNull(run.selectionModel());
        Map<String, CohortCell> cells = cells(ipwRunId);

        // PVC 2010s 0-9：普通区 P-PVC-01（p=0.5,w=2.0，无缺陷）与高风险 P-PVC-02（p=0.75,w=1.3333，1m 缺陷）
        CohortCell pvc = cells.get("PVC|2010s|DN<500|0-9");
        assertNotNull(pvc);
        assertScale(pvc.weightSum(), 3.3333);
        assertClose(pvc.weightedInspectedLength(), 199.998, 0.001);
        assertScale(pvc.weightedDefectLength(), 1.3333);
        assertScale(pvc.weightedDefectRatio(), 0.006667);
        // NAIVE 同格把高风险管与普通管等权看待：1/120=0.008333，系统性高于 IPW
        CohortCell naive = cells(naiveRunId).get("PVC|2010s|DN<500|0-9");
        assertScale(naive.defectRatio(), 0.008333);
        assertTrue(pvc.weightedDefectRatio().compareTo(naive.defectRatio()) < 0,
                "缺陷位于高风险管时 IPW 下调其权重，估计比例应低于 NAIVE");
        assertScale(pvc.minWeight(), 1.3333);
        assertScale(pvc.maxWeight(), 2.0);

        // 重复三轮检查的高风险 RCP-02 只贡献一次并集分母，权重按插曲只计一次。
        // NORMAL 分层含 RCP-01 与 RCP-04 旧插曲（7 插曲 1 受检，p=2/9≈0.2222，w=4.5）；
        // HIGH_RISK 分层 RCP-02（3 插曲 1 受检，p=0.4，w=2.5）
        CohortCell rcp90s = cells.get(RCP + "|1990s|DN≥800|20-29");
        assertScale(rcp90s.inspectedLengthM(), 200);
        assertScale(rcp90s.maxWeight(), 4.5);
        assertScale(rcp90s.minWeight(), 2.5);
    }

    // ------------------------------------------------------------ 7. 极端权重诊断
    @Test
    @Order(7)
    void extremeWeightsAreFlagged() {
        long ahVersion = repo.findLatestVersionByDomain("ASSET_HISTORY").orElseThrow().id();
        // 同分层（NORMAL/钢筋混凝土/1990s）补入 6 条无检查管道，每条 4 个年龄插曲 ⇒
        // 该分层插曲 7→31、受检仍为 1，p=(1+1)/(31+2)≈0.0606，受检插曲权重 ≈16.5 ≥ 10
        for (int i = 0; i < 6; i++) {
            repo.insertAssetHistory(new AssetHistory(null, "P-X-" + i, "P-X-" + i, "COMMISSION",
                    RCP, 800, 1995, BigDecimal.valueOf(100), LocalDateTime.parse("1995-01-01T00:00:00"),
                    null, false, ahVersion, "archive://test/P-X-" + i, null));
        }
        Map<String, Object> summary = cohortService.runCohort("IPW", AS_OF, 10, 1L);
        assertTrue((Integer) summary.get("extremeWeightCells") >= 1);
        CohortCell flagged = repo.findCellsByRun(((Number) summary.get("runId")).longValue()).stream()
                .filter(CohortCell::extremeWeightFlag).findFirst().orElseThrow();
        assertTrue(flagged.maxWeight().compareTo(CohortService.EXTREME_WEIGHT) >= 0);
    }

    // ------------------------------------------------------------ 8. 撤销观察：留痕 + 增量重算 + 旧结果保留
    @Test
    @Order(8)
    void revokeObservationSupersedesOnlyAffectedRunsAndKeepsOldResults() {
        List<CohortRun> before = repo.findCurrentRuns();
        assertTrue(before.size() >= 2);

        Map<String, Object> result = cohortService.revokeObservation(5L, "录像带损坏，观察撤销", 2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rerun = (List<Map<String, Object>>) result.get("rerunRuns");
        assertEquals(before.size(), rerun.size());

        // 旧运行置 SUPERSEDED 且结果行仍可查
        for (CohortRun old : before) {
            assertEquals("SUPERSEDED", repo.findRunById(old.id()).status());
            assertFalse(repo.findCellsByRun(old.id()).isEmpty(), "旧运行结果必须保留");
        }
        // 新运行中 RCP-03 有效观察归零：不可推断，缺陷事件仍在但无法形成比例
        long newNaive = repo.findCurrentRuns().stream()
                .filter(r -> "NAIVE".equals(r.estimationMethod())).findFirst().orElseThrow().id();
        CohortCell cell = cells(newNaive).get(RCP + "|2000s|DN500-799|20-29");
        assertScale(cell.inspectedLengthM(), 0);
        assertScale(cell.obscuredLengthM(), 20);
        assertTrue(cell.nonInferableFlag());
        assertNull(cell.defectRatio());
        assertEquals(1, cell.defectEventCount(), "撤销观察不删除缺陷事实，只是失去有效分母");
        InspectionExposure revoked = repo.findExposureById(5L);
        assertEquals("REVOKED", revoked.status());
        assertNotNull(revoked.revokedByVersionId());
    }

    // ------------------------------------------------------------ 9. 补录换管日期：只重算受影响队列
    @Test
    @Order(9)
    void backfillReplacementClosesOldIntervalAndRepinsAges() {
        List<CohortRun> before = repo.findCurrentRuns();
        long supersededBefore = repo.findAllRuns().stream()
                .filter(r -> "SUPERSEDED".equals(r.status())).count();

        Map<String, Object> result = cohortService.backfillReplacement("P-PVC-01", "PIPE_REPLACEMENT",
                LocalDate.parse("2024-05-01"), "PVC", 400, new BigDecimal("60.000"),
                "archive://test/ppvc01-replace", "补录 2024 年换管", 2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rerun = (List<Map<String, Object>>) result.get("rerunRuns");
        assertEquals(before.size(), rerun.size(), "所有 CURRENT 运行钉的资产版本均已落后，都应重算");

        // 旧区间封口，新区间年龄从 2024 起算
        var history = repo.findAssetHistoryByPipe("P-PVC-01");
        assertTrue(history.stream().anyMatch(h -> h.effectiveTo() != null), "旧区间必须被封口");
        AssetHistory latest = history.get(history.size() - 1);
        assertEquals("PIPE_REPLACEMENT", latest.eventType());
        assertTrue(latest.ageReset());
        assertEquals(2024, latest.commissionYear());

        // 2021 检查仍挂在旧区间（2010s 0-9）；同格还含 P-PVC-02 的 60m，共 120m 有效观察；
        // 换管后新队列 2020s 0-9 尚无检查，不可推断
        long newNaive = repo.findCurrentRuns().stream()
                .filter(r -> "NAIVE".equals(r.estimationMethod())).findFirst().orElseThrow().id();
        Map<String, CohortCell> newCells = cells(newNaive);
        assertScale(newCells.get("PVC|2010s|DN<500|0-9").inspectedLengthM(), 120);
        assertTrue(repo.findEvidenceByRun(newNaive).stream()
                .anyMatch(e -> "INSPECTION_WINDOW".equals(e.evidenceKind())
                        && e.detail().contains("P-PVC-01") && e.detail().contains("年龄层 0-9")),
                "2021 检查必须保留在旧管区间，不得搬移到换管后区间");
        CohortCell newCohort = newCells.get("PVC|2020s|DN<500|0-9");
        assertNotNull(newCohort);
        assertScale(newCohort.totalLengthM(), 60);
        assertScale(newCohort.inspectedLengthM(), 0);
        assertTrue(newCohort.nonInferableFlag());

        // 历史 SUPERSEDED 运行不被二次搬动
        assertEquals(supersededBefore + rerun.size(),
                repo.findAllRuns().stream().filter(r -> "SUPERSEDED".equals(r.status())).count());
    }

    // ------------------------------------------------------------ 10. 队列对比（用被取代前的运行 id，结果行仍可查）
    @Test
    @Order(10)
    void compareRunsAlignsCellsByKey() {
        Map<String, Object> comparison = cohortService.compareRuns(List.of(naiveRunId, ipwRunId));
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> byKey =
                (Map<String, List<Map<String, Object>>>) comparison.get("cells");
        List<Map<String, Object>> pair = byKey.get(RCP + "|1990s|DN≥800|20-29");
        assertNotNull(pair);
        assertEquals(2, pair.size());
    }

    // ------------------------------------------------------------ 11. 纯函数工具
    @Test
    @Order(11)
    void unionAndBandHelpers() {
        assertScale(CohortService.unionLength(List.of(
                new Range(BigDecimal.valueOf(0), BigDecimal.valueOf(10)),
                new Range(BigDecimal.valueOf(5), BigDecimal.valueOf(20)),
                new Range(BigDecimal.valueOf(30), BigDecimal.valueOf(40)))), 30);
        assertScale(CohortService.overlapLength(
                List.of(new Range(BigDecimal.valueOf(0), BigDecimal.valueOf(100))),
                List.of(new Range(BigDecimal.valueOf(30), BigDecimal.valueOf(50)))), 20);
        assertEquals("20-29", CohortService.ageBand(25, 10));
        assertEquals("1990s", CohortService.decade(1995));
        assertEquals("DN500-799", CohortService.diameterBand(600));
        assertEquals("DN≥800", CohortService.diameterBand(800));
    }
}
