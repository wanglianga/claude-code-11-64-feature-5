package com.ccdev.drainage;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.Attribution;
import com.ccdev.drainage.domain.Models.DefectCluster;
import com.ccdev.drainage.domain.Models.SegmentScore;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.DatasetRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import com.ccdev.drainage.service.AlignmentService;
import com.ccdev.drainage.service.ClusteringService;
import com.ccdev.drainage.service.DatasetService;
import com.ccdev.drainage.service.IngestionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试：在独立库 ccdev_test 上重建 schema/种子，重跑
 * 对齐 → 聚类 → 双口径数据集，覆盖题目全部验收场景。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DrainageCensusIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired AlignmentService alignmentService;
    @Autowired ClusteringService clusteringService;
    @Autowired DatasetService datasetService;
    @Autowired IngestionService ingestionService;
    @Autowired AnalysisRepository analysisRepo;
    @Autowired AssetRepository assetRepo;
    @Autowired DatasetRepository datasetRepo;
    @Autowired InspectionRepository inspectionRepo;

    /**
     * 静态块在 Spring 测试上下文启动之前执行：确保 BootstrapRunner 看到的是
     * 与当前 schema.sql 一致的全新库，避免 IF NOT EXISTS 保留旧列定义。
     */
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
    void rebuildPipeline() {
        alignmentService.realignAll();
        clusteringService.rebuildClusters();
        datasetService.generateForVersion(2L, "MAX_SEVERITY", 1L);
        datasetService.generateForVersion(2L, "BURDEN", 1L);
        datasetService.generateForVersion(1L, "MAX_SEVERITY", 1L);
    }

    private Attribution attr(long observationId) {
        return analysisRepo.findAttribution(observationId).orElseThrow();
    }

    /** 按“版本号-口径”解析数据集 id，避免依赖自增序列。 */
    private long datasetId(long versionId, String mode) {
        return datasetRepo.findAllDatasets().stream()
                .filter(d -> d.asOfVersionId() == versionId && mode.equals(d.scoringMode()))
                .findFirst().orElseThrow().id();
    }

    // ------------------------------------------------------------ 1. 位置归属
    @Test
    @Order(1)
    void reverseMileageIsNormalizedToUpstreamOrigin() {
        // OBS-3 反向里程 [47.5,50]（管长 60）⇒ 归一 [10,12.5]，与 OBS-1 同位置
        Attribution a1 = attr(1L);
        Attribution a3 = attr(3L);
        assertEquals(0, a1.normStartM().compareTo(new BigDecimal("10.000")));
        assertEquals(0, a3.normStartM().compareTo(new BigDecimal("10.000")));
        assertEquals(0, a3.normEndM().compareTo(new BigDecimal("12.500")));
        assertEquals(a1.segmentId(), a3.segmentId());
        // 固定在检查时点版本 V2024-01（id=1），而非后续修订版
        assertEquals(1L, a3.assetVersionId());

        // OBS-13 反向里程 [30,32]（管长 40）⇒ 归一 [8,10]
        Attribution a13 = attr(13L);
        assertEquals(0, a13.normStartM().compareTo(new BigDecimal("8.000")));
        assertEquals(0, a13.normEndM().compareTo(new BigDecimal("10.000")));

        // 参照候选保留且未被选中，证明后续版本不会吸走历史归属
        var candidates = alignmentService.candidatesOfObservation(3L);
        assertTrue(candidates.size() >= 2);
        assertTrue(candidates.stream().anyMatch(c -> Boolean.FALSE.equals(
                ((com.ccdev.drainage.domain.Models.AlignmentCandidate) c.get("candidate")).selected())));
    }

    // ------------------------------------------------------------ 2. 去重与冲突
    @Test
    @Order(2)
    void duplicateAndOverlappingObservationsDoNotInflateRisk() {
        // OBS-7/8 来自内容哈希完全相同的重复视频，保持 DUPLICATE，不进聚类
        assertEquals("DUPLICATE", inspectionRepo.findObservationById(7L).status());
        assertEquals("DUPLICATE", inspectionRepo.findObservationById(8L).status());

        // OBS-1/3/9 同位置同等级并为一簇：仅 1 个 PRIMARY 主张，其余 DUPLICATE
        DefectCluster crack = analysisRepo.findAllClusters().stream()
                .filter(c -> "CRACK".equals(c.defectType()) && c.segmentId() == 1L
                        && c.normStartM().compareTo(new BigDecimal("10.000")) == 0)
                .findFirst().orElseThrow();
        var members = analysisRepo.findMembersByCluster(crack.id());
        assertEquals(3, members.size());
        assertEquals(1, members.stream().filter(m -> "PRIMARY".equals(m.memberRole())).count());
        assertEquals(2, members.stream().filter(m -> "DUPLICATE".equals(m.memberRole())).count());
    }

    @Test
    @Order(3)
    void incompatibleGradesWithoutDecisiveEvidenceStayConflictAndPauseRanking() {
        DefectCluster conflict = analysisRepo.findAllClusters().stream()
                .filter(DefectCluster::conflictFlag).findFirst().orElseThrow();
        assertEquals("CONFLICT", conflict.status());
        assertNull(conflict.resolvedGrade());

        // 冲突所在管段 WS-002（新版行 id=5）暂停排序
        SegmentScore score = datasetRepo.findScoresByDataset(datasetId(2L, "MAX_SEVERITY")).stream()
                .filter(s -> s.segmentId() == 5L).findFirst().orElseThrow();
        assertTrue(score.rankingPaused());
        assertNull(score.rankNo());
        assertTrue(score.pauseReasons().contains("暂停"));
    }

    // ------------------------------------------------------------ 3. 拆分继承
    @Test
    @Order(4)
    void splitInheritanceRequiresMileageContainmentAndNeverDuplicatesAcrossBoundary() {
        // 数据集 1 = V2026-06 / MAX_SEVERITY
        Map<Long, SegmentScore> bySegment = new java.util.HashMap<>();
        for (SegmentScore s : datasetRepo.findScoresByDataset(datasetId(2L, "MAX_SEVERITY"))) {
            bySegment.put(s.segmentId(), s);
        }
        // WS-003A（id=6）继承裂缝（等级3），WS-003B（id=7）只继承渗漏（等级2）
        assertEquals(3, bySegment.get(6L).maxGrade());
        assertEquals(2, bySegment.get(7L).maxGrade());

        // 跨拆分点的 OBS-11 变形 [21,23] 不出现在任何新段的入围缺陷中
        List<DefectCluster> on3a = analysisRepo.findClustersBySegment(3L);
        DefectCluster crossing = on3a.stream()
                .filter(c -> c.normStartM().compareTo(new BigDecimal("21.000")) == 0)
                .findFirst().orElseThrow();
        var impacts = datasetRepo.findImpactsByDataset(datasetId(2L, "MAX_SEVERITY"));
        long quarantined = impacts.stream().filter(i -> i.quarantinedCount() > 0)
                .peek(i -> assertTrue(i.note().contains(crossing.clusterCode())))
                .count();
        assertTrue(quarantined >= 1, "跨拆分点缺陷必须在影响清单中登记隔离");
    }

    // ------------------------------------------------------------ 4. 双口径评分
    @Test
    @Order(5)
    void maxSeverityAndBurdenModesProduceExplainedRankings() {
        List<SegmentScore> maxScores = datasetRepo.findScoresByDataset(datasetId(2L, "MAX_SEVERITY"));
        SegmentScore first = maxScores.stream().filter(s -> s.rankNo() != null)
                .min(java.util.Comparator.comparingInt(SegmentScore::rankNo)).orElseThrow();
        assertEquals(4, first.maxGrade()); // WS-001 含等级 4 变形，最高等级口径排第 1
        assertTrue(first.explanation().contains("最高严重等级"));

        List<SegmentScore> burdenScores = datasetRepo.findScoresByDataset(datasetId(2L, "BURDEN"));
        SegmentScore burdenFirst = burdenScores.stream().filter(s -> s.rankNo() != null)
                .min(java.util.Comparator.comparingInt(SegmentScore::rankNo)).orElseThrow();
        // WS-001 负担分 = 2.5×2×1.0 + 1×3×0.8 + 1×4×0.6 = 9.8
        assertEquals(0, burdenFirst.burdenScore().compareTo(new BigDecimal("9.800")));
        assertTrue(burdenFirst.explanation().contains("累计负担分"));
        // 两种口径下 WS-002 均暂停
        assertTrue(burdenScores.stream().filter(s -> s.segmentId() == 5L)
                .findFirst().orElseThrow().rankingPaused());
    }

    // ------------------------------------------------------------ 5. 历史复现
    @Test
    @Order(6)
    void historicalDatasetReproducesPreSplitState() {
        // 数据集 3 基于 V2024-01：WS-003（id=3）仍保留跨点变形，缺陷数 3 且不暂停
        SegmentScore oldWs003 = datasetRepo.findScoresByDataset(datasetId(1L, "MAX_SEVERITY")).stream()
                .filter(s -> s.segmentId() == 3L).findFirst().orElseThrow();
        assertFalse(oldWs003.rankingPaused());
        assertEquals(3, oldWs003.eligibleClusterCount());
        assertEquals(4, oldWs003.maxGrade());
        // 历史归属行未被修订版改写
        assertEquals(1L, attr(11L).assetVersionId());
    }

    // ------------------------------------------------------------ 6. 重复上传拦截
    @Test
    @Order(7)
    void duplicateBatchUploadIsRejectedAndDuplicateVideoFlagged() {
        ApiException ex = assertThrows(ApiException.class, () -> ingestionService.uploadBatch(
                "B-DUP", "重复承包商", java.time.LocalDateTime.parse("2025-03-10T09:00:00"),
                "STD-CCT-2021", "上游起算", "hash:batch:b202501"));
        assertEquals(409, ex.getStatus());

        var result = ingestionService.uploadVideo(1L, "VID-DUP-HASH", "archive://x.mp4", "WS-001",
                new BigDecimal("0"), new BigDecimal("60"), "FORWARD", "sha256:vid-ws001-full");
        assertEquals(true, result.get("duplicate"));
        long obsId = ingestionService.uploadObservation((Long) result.get("videoId"),
                "CRACK", "II", new BigDecimal("10"), new BigDecimal("12"),
                "archive://x", "FULL");
        assertEquals("DUPLICATE", inspectionRepo.findObservationById(obsId).status());
    }

    // ------------------------------------------------------------ 7. 人工裁决恢复排序
    @Test
    @Order(8)
    void adjudicationResolvesConflictAndConfirmedGradePersists() {
        DefectCluster conflict = analysisRepo.findAllClusters().stream()
                .filter(DefectCluster::conflictFlag).findFirst().orElseThrow();
        clusteringService.adjudicate(conflict.id(), 4, "复核视频帧后采信等级4", 2L);
        DefectCluster reloaded = analysisRepo.findClusterById(conflict.id());
        assertEquals("ADJUDICATED", reloaded.status());
        assertEquals(4, reloaded.resolvedGrade());
        assertNotNull(reloaded.resolvedAt());

        long newDs = (long) datasetService.generateForVersion(2L, "MAX_SEVERITY", 2L).get("datasetId");
        SegmentScore ws002 = datasetRepo.findScoresByDataset(newDs).stream()
                .filter(s -> s.segmentId() == 5L).findFirst().orElseThrow();
        assertFalse(ws002.rankingPaused());
        assertNotNull(ws002.rankNo());
    }
}
