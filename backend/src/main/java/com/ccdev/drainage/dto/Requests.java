package com.ccdev.drainage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class Requests {

    private Requests() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record BatchUploadRequest(@NotBlank String batchCode, @NotBlank String contractor,
                                     @NotNull LocalDateTime inspectedAt, @NotBlank String gradingStandard,
                                     @NotBlank String directionConvention, @NotBlank String contentHash) {
    }

    public record VideoUploadRequest(@NotNull Long batchId, @NotBlank String videoCode,
                                     @NotBlank String fileUri, @NotBlank String segmentHint,
                                     @NotNull BigDecimal startM, @NotNull BigDecimal endM,
                                     @NotBlank String stationDirection, @NotBlank String contentHash) {
    }

    public record ObservationUploadRequest(@NotNull Long videoId, @NotBlank String defectType,
                                           @NotBlank String rawGrade, @NotNull BigDecimal stationStartM,
                                           @NotNull BigDecimal stationEndM, String evidenceUri,
                                           @NotBlank String evidenceStrength) {
    }

    public record DatasetGenerateRequest(@NotNull Long versionId, @NotBlank String scoringMode) {
    }

    public record AdjudicateRequest(@NotNull Integer resolvedGrade, @NotBlank String note) {
    }

    // ------------------------------------------------------------ 队列统计域
    public record CohortRunRequest(String estimationMethod,
                                   @NotNull java.time.LocalDate asOfDate,
                                   Integer ageBandYears) {
    }

    public record CohortCompareRequest(@NotNull List<Long> runIds) {
    }

    public record AssetHistoryRequest(@NotBlank String physicalPipeCode, @NotBlank String segmentCode,
                                      @NotBlank String eventType, @NotBlank String material,
                                      @NotNull Integer diameterMm, @NotNull Integer commissionYear,
                                      @NotNull BigDecimal lengthM, @NotNull LocalDateTime effectiveFrom,
                                      LocalDateTime effectiveTo, @NotNull Boolean ageReset,
                                      String evidenceRef) {
    }

    public record BackfillRequest(@NotBlank String physicalPipeCode, @NotBlank String eventType,
                                  @NotNull java.time.LocalDate effectiveDate, @NotBlank String material,
                                  @NotNull Integer diameterMm, @NotNull BigDecimal lengthM,
                                  String evidenceRef, @NotBlank String note) {
    }

    public record ExposureRequest(@NotBlank String physicalPipeCode, @NotBlank String segmentCode,
                                  @NotBlank String batchCode, @NotNull LocalDateTime inspectedAt,
                                  @NotNull BigDecimal stationStartM, @NotNull BigDecimal stationEndM,
                                  @NotBlank String coverageStatus, String obscuredReason,
                                  @NotBlank String riskZone, String evidenceRef) {
    }

    public record RevokeExposureRequest(@NotBlank String reason) {
    }

    public record DefectEventRequest(@NotBlank String physicalPipeCode, @NotBlank String segmentCode,
                                     @NotBlank String eventKind, Long originalEventId,
                                     @NotBlank String defectType, @NotNull Integer normalizedGrade,
                                     @NotNull BigDecimal stationStartM, @NotNull BigDecimal stationEndM,
                                     @NotNull LocalDateTime foundAt, @NotBlank String exposureKey,
                                     String repairId, LocalDateTime repairedAt, String repairMethod,
                                     String postRepairStatus, String evidenceRef) {
    }
}
