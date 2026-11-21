package com.ccdev.drainage.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 领域模型集合：全部以不可变 record 承载，数据库写入通过 JdbcTemplate 显式 SQL 完成。
 */
public final class Models {

    private Models() {
    }

    public record AssetVersion(Long id, String versionNo, String name, LocalDateTime effectiveAt,
                               String status, String note) {
    }

    public record Manhole(Long id, Long versionId, String manholeCode, BigDecimal geomX, BigDecimal geomY) {
    }

    public record PipeSegment(Long id, Long versionId, String segmentCode, Long upstreamMhId,
                              Long downstreamMhId, BigDecimal lengthM, String flowDirection,
                              String material, Integer diameterMm, Long parentSegmentId,
                              String lifecycleStatus) {
    }

    public record SegmentMapping(Long id, Long oldSegmentId, Long newSegmentId,
                                 BigDecimal oldStartM, BigDecimal oldEndM,
                                 BigDecimal newStartM, BigDecimal newEndM,
                                 String basis, String note) {
    }

    public record ContractorBatch(Long id, String batchCode, String contractor, LocalDateTime inspectedAt,
                                  String gradingStandard, String directionConvention, String contentHash) {
    }

    public record Video(Long id, Long batchId, String videoCode, String fileUri, String segmentHint,
                        BigDecimal startM, BigDecimal endM, String stationDirection,
                        String contentHash, Long duplicateOfId) {
    }

    public record Observation(Long id, Long videoId, Long batchId, String defectType, String rawGrade,
                              BigDecimal stationStartM, BigDecimal stationEndM, String evidenceUri,
                              String evidenceStrength, String status, Long duplicateOfId, Long clusterId) {
    }

    public record JudgementRule(String defectType, Integer minGrade, BigDecimal minLengthM,
                                String requiredEvidence, Boolean enabled) {
    }

    public record AlignmentCandidate(Long id, Long observationId, Long segmentId, Long assetVersionId,
                                     BigDecimal normStartM, BigDecimal normEndM, BigDecimal score,
                                     Boolean selected, String reason) {
    }

    public record Attribution(Long observationId, Long segmentId, Long assetVersionId,
                              BigDecimal normStartM, BigDecimal normEndM, LocalDateTime fixedAt, String note) {
    }

    public record DefectCluster(Long id, String clusterCode, Long segmentId, Long assetVersionId,
                                String defectType, BigDecimal normStartM, BigDecimal normEndM,
                                String status, Boolean conflictFlag, Integer resolvedGrade,
                                String resolutionNote, Long resolvedBy, LocalDateTime resolvedAt) {
    }

    public record ClusterMember(Long id, Long clusterId, Long observationId, String memberRole,
                                Integer gradeAtInspection, String evidenceStrength, Long batchId) {
    }

    public record Dataset(Long id, String datasetCode, Long asOfVersionId, String scoringMode,
                          String status, LocalDateTime generatedAt, Long generatedBy, String formulaNote) {
    }

    public record SegmentScore(Long id, Long datasetId, Long segmentId, Integer maxGrade,
                               BigDecimal burdenScore, Integer rankNo, Boolean rankingPaused,
                               String pauseReasons, Integer eligibleClusterCount, Integer conflictCount,
                               BigDecimal evidenceCompleteness, String explanation) {
    }

    public record DatasetImpact(Long id, Long datasetId, Long oldSegmentId, Long newSegmentId,
                                String impactType, Integer inheritedCount, Integer quarantinedCount,
                                String note) {
    }

    public record AppUser(Long id, String username, String passwordHash, String displayName, String role) {
    }

    public record AuditLog(Long id, Long userId, String action, String targetRef, String detail,
                           LocalDateTime createdAt) {
    }

    // ------------------------------------------------------------ 材料/年代队列域
    public record SourceVersion(Long id, String sourceDomain, String versionNo, String sourceNote,
                                Long createdBy, LocalDateTime createdAt) {
    }

    /** 资产沿革区间：投用/换管/内衬/材料变更/退役；ageReset=true 时服役年龄从 from 重新起算。 */
    public record AssetHistory(Long id, String physicalPipeCode, String segmentCode, String eventType,
                               String material, Integer diameterMm, Integer commissionYear, BigDecimal lengthM,
                               LocalDateTime effectiveFrom, LocalDateTime effectiveTo, Boolean ageReset,
                               Long sourceVersionId, String evidenceRef, Long supersededById) {
    }

    /** 检查暴露：有观察范围的检测时段；OBSCURED 为遮挡，不计无缺陷；REVOKED 为撤销留痕。 */
    public record InspectionExposure(Long id, String physicalPipeCode, String segmentCode, String batchCode,
                                     LocalDateTime inspectedAt, BigDecimal stationStartM, BigDecimal stationEndM,
                                     String coverageStatus, String obscuredReason, String riskZone,
                                     Long sourceVersionId, String status, Long revokedByVersionId,
                                     String evidenceRef) {
    }

    /** 缺陷事件：复发关联 originalEventId 与同一 exposureKey，不拆成独立管段。 */
    public record DefectEvent(Long id, String physicalPipeCode, String segmentCode, String eventKind,
                              Long originalEventId, String defectType, Integer normalizedGrade,
                              BigDecimal stationStartM, BigDecimal stationEndM, LocalDateTime foundAt,
                              String exposureKey, String repairId, LocalDateTime repairedAt, String repairMethod,
                              String postRepairStatus, Long sourceVersionId, String evidenceRef) {
    }

    public record CohortRun(Long id, String runCode, LocalDate asOfDate, String estimationMethod,
                            Integer ageBandYears, Long assetVersionId, Long exposureVersionId,
                            Long defectVersionId, String selectionModel, String methodNote,
                            String nonInferenceNote, String status, Long supersededRunId,
                            Long generatedBy, LocalDateTime generatedAt) {
    }

    /** 队列结果单元格：材料 × 投用年代 × 管径 × 年龄层，分母/权重/证据全部可追溯。 */
    public record CohortCell(Long id, Long runId, String material, String commissionDecade,
                             String diameterBand, String ageBand, BigDecimal totalLengthM,
                             BigDecimal inspectedLengthM, BigDecimal obscuredLengthM, BigDecimal effectiveCoverage,
                             Integer defectEventCount, Integer firstFindingCount, Integer recurrenceCount,
                             BigDecimal defectiveLengthM, BigDecimal defectRatio, BigDecimal recurrenceRate,
                             BigDecimal weightSum, BigDecimal weightedDefectLength, BigDecimal weightedInspectedLength,
                             BigDecimal weightedDefectRatio, BigDecimal minWeight, BigDecimal maxWeight,
                             String evidenceSources, Boolean lowCoverageFlag, Boolean extremeWeightFlag,
                             Boolean nonInferableFlag) {
    }

    public record CohortEvidence(Long id, Long runId, String cellKey, String evidenceKind,
                                 String sourceRef, String detail) {
    }
}
