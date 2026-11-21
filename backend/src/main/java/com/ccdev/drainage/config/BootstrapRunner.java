package com.ccdev.drainage.config;

import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.CohortRepository;
import com.ccdev.drainage.repo.DatasetRepository;
import com.ccdev.drainage.service.AlignmentService;
import com.ccdev.drainage.service.ClusteringService;
import com.ccdev.drainage.service.CohortService;
import com.ccdev.drainage.service.DatasetService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Map;

/**
 * 启动引导：
 *   1. 执行 schema.sql（IF NOT EXISTS，可重复执行）
 *   2. 执行 seed-data.sql（ON CONFLICT DO NOTHING，可重复执行）
 *   3. 若对齐/聚类结果缺失，自动跑一遍 对齐 → 聚类 → 双口径数据集
 */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final AlignmentService alignmentService;
    private final ClusteringService clusteringService;
    private final DatasetService datasetService;
    private final CohortService cohortService;
    private final AnalysisRepository analysisRepo;
    private final DatasetRepository datasetRepo;
    private final CohortRepository cohortRepo;
    private final AssetRepository assetRepo;

    public BootstrapRunner(JdbcTemplate jdbc, AlignmentService alignmentService,
                           ClusteringService clusteringService, DatasetService datasetService,
                           CohortService cohortService,
                           AnalysisRepository analysisRepo, DatasetRepository datasetRepo,
                           CohortRepository cohortRepo, AssetRepository assetRepo) {
        this.jdbc = jdbc;
        this.alignmentService = alignmentService;
        this.clusteringService = clusteringService;
        this.datasetService = datasetService;
        this.cohortService = cohortService;
        this.analysisRepo = analysisRepo;
        this.datasetRepo = datasetRepo;
        this.cohortRepo = cohortRepo;
        this.assetRepo = assetRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed-data.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("启动建表/种子失败: " + e.getMessage(), e);
        }

        if (analysisRepo.countAttributions() == 0) {
            Map<String, Object> align = alignmentService.realignAll();
            System.out.println("[bootstrap] 位置对齐完成: " + align);
            Map<String, Object> cluster = clusteringService.rebuildClusters();
            System.out.println("[bootstrap] 缺陷聚类完成: " + cluster);
        }
        if (datasetRepo.countDatasets() == 0) {
            long latestVersion = assetRepo.findAllVersions().stream()
                    .map(v -> v.id()).max(Long::compare).orElseThrow();
            System.out.println("[bootstrap] 生成 MAX_SEVERITY 数据集: "
                    + datasetService.generateForVersion(latestVersion, "MAX_SEVERITY", 1L));
            System.out.println("[bootstrap] 生成 BURDEN 数据集: "
                    + datasetService.generateForVersion(latestVersion, "BURDEN", 1L));
        }

        // 队列统计：两种口径各生成一个运行（基准日与样例数据对齐，固定 2026-11-21）
        if (cohortRepo.countRuns() == 0) {
            LocalDate asOf = LocalDate.parse("2026-11-21");
            System.out.println("[bootstrap] 生成 NAIVE 队列运行: "
                    + cohortService.runCohort("NAIVE", asOf, 10, 1L));
            System.out.println("[bootstrap] 生成 IPW 队列运行: "
                    + cohortService.runCohort("IPW", asOf, 10, 1L));
        }
    }
}
