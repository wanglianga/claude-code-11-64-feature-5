package com.ccdev.drainage.repo;

import com.ccdev.drainage.domain.Models.AssetHistory;
import com.ccdev.drainage.domain.Models.CohortCell;
import com.ccdev.drainage.domain.Models.CohortEvidence;
import com.ccdev.drainage.domain.Models.CohortRun;
import com.ccdev.drainage.domain.Models.DefectEvent;
import com.ccdev.drainage.domain.Models.InspectionExposure;
import com.ccdev.drainage.domain.Models.SourceVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 队列统计域仓储：资产沿革、检查暴露、缺陷事件三类源数据与队列运行结果分别版本化。
 * 所有删除均为逻辑留痕或级联清理本运行产物，历史运行行本身不被补录/撤销改写。
 */
@Repository
public class CohortRepository {

    private final JdbcTemplate jdbc;

    public CohortRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ RowMapper
    private static final RowMapper<SourceVersion> SV_MAPPER = (rs, n) -> new SourceVersion(
            rs.getLong("id"), rs.getString("source_domain"), rs.getString("version_no"),
            rs.getString("source_note"), rs.getObject("created_by", Long.class),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<AssetHistory> AH_MAPPER = (rs, n) -> new AssetHistory(
            rs.getLong("id"), rs.getString("physical_pipe_code"), rs.getString("segment_code"),
            rs.getString("event_type"), rs.getString("material"), rs.getInt("diameter_mm"),
            rs.getInt("commission_year"), rs.getBigDecimal("length_m"),
            rs.getTimestamp("effective_from").toLocalDateTime(),
            rs.getTimestamp("effective_to") == null ? null : rs.getTimestamp("effective_to").toLocalDateTime(),
            rs.getBoolean("age_reset"), rs.getLong("source_version_id"), rs.getString("evidence_ref"),
            rs.getObject("superseded_by_id", Long.class));

    private static final RowMapper<InspectionExposure> IE_MAPPER = (rs, n) -> new InspectionExposure(
            rs.getLong("id"), rs.getString("physical_pipe_code"), rs.getString("segment_code"),
            rs.getString("batch_code"), rs.getTimestamp("inspected_at").toLocalDateTime(),
            rs.getBigDecimal("station_start_m"), rs.getBigDecimal("station_end_m"),
            rs.getString("coverage_status"), rs.getString("obscured_reason"), rs.getString("risk_zone"),
            rs.getLong("source_version_id"), rs.getString("status"),
            rs.getObject("revoked_by_version_id", Long.class), rs.getString("evidence_ref"));

    private static final RowMapper<DefectEvent> DE_MAPPER = (rs, n) -> new DefectEvent(
            rs.getLong("id"), rs.getString("physical_pipe_code"), rs.getString("segment_code"),
            rs.getString("event_kind"), rs.getObject("original_event_id", Long.class),
            rs.getString("defect_type"), rs.getInt("normalized_grade"),
            rs.getBigDecimal("station_start_m"), rs.getBigDecimal("station_end_m"),
            rs.getTimestamp("found_at").toLocalDateTime(), rs.getString("exposure_key"),
            rs.getString("repair_id"),
            rs.getTimestamp("repaired_at") == null ? null : rs.getTimestamp("repaired_at").toLocalDateTime(),
            rs.getString("repair_method"), rs.getString("post_repair_status"),
            rs.getLong("source_version_id"), rs.getString("evidence_ref"));

    private static final RowMapper<CohortRun> RUN_MAPPER = (rs, n) -> new CohortRun(
            rs.getLong("id"), rs.getString("run_code"), rs.getDate("as_of_date").toLocalDate(),
            rs.getString("estimation_method"), rs.getInt("age_band_years"),
            rs.getLong("asset_version_id"), rs.getLong("exposure_version_id"), rs.getLong("defect_version_id"),
            rs.getString("selection_model"), rs.getString("method_note"), rs.getString("non_inference_note"),
            rs.getString("status"), rs.getObject("superseded_run_id", Long.class),
            rs.getObject("generated_by", Long.class), rs.getTimestamp("generated_at").toLocalDateTime());

    private static final RowMapper<CohortCell> CELL_MAPPER = (rs, n) -> new CohortCell(
            rs.getLong("id"), rs.getLong("run_id"), rs.getString("material"),
            rs.getString("commission_decade"), rs.getString("diameter_band"), rs.getString("age_band"),
            rs.getBigDecimal("total_length_m"), rs.getBigDecimal("inspected_length_m"),
            rs.getBigDecimal("obscured_length_m"), rs.getBigDecimal("effective_coverage"),
            rs.getInt("defect_event_count"), rs.getInt("first_finding_count"), rs.getInt("recurrence_count"),
            rs.getBigDecimal("defective_length_m"), rs.getBigDecimal("defect_ratio"),
            rs.getBigDecimal("recurrence_rate"), rs.getBigDecimal("weight_sum"),
            rs.getBigDecimal("weighted_defect_length"), rs.getBigDecimal("weighted_inspected_length"),
            rs.getBigDecimal("weighted_defect_ratio"), rs.getBigDecimal("min_weight"),
            rs.getBigDecimal("max_weight"), rs.getString("evidence_sources"),
            rs.getBoolean("low_coverage_flag"), rs.getBoolean("extreme_weight_flag"),
            rs.getBoolean("non_inferable_flag"));

    private static final RowMapper<CohortEvidence> CE_MAPPER = (rs, n) -> new CohortEvidence(
            rs.getLong("id"), rs.getLong("run_id"), rs.getString("cell_key"),
            rs.getString("evidence_kind"), rs.getString("source_ref"), rs.getString("detail"));

    // ------------------------------------------------------------ 源数据版本
    public long insertSourceVersion(String domain, String versionNo, String note, Long userId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_source_version(source_domain, version_no, source_note, created_by) "
                            + "VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, domain);
            ps.setString(2, versionNo);
            ps.setString(3, note);
            if (userId == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, userId);
            }
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public List<SourceVersion> findVersionsByDomain(String domain) {
        return jdbc.query("SELECT * FROM app_source_version WHERE source_domain = ? ORDER BY id",
                SV_MAPPER, domain);
    }

    public Optional<SourceVersion> findLatestVersionByDomain(String domain) {
        List<SourceVersion> list = jdbc.query(
                "SELECT * FROM app_source_version WHERE source_domain = ? ORDER BY id DESC LIMIT 1",
                SV_MAPPER, domain);
        return list.stream().findFirst();
    }

    public SourceVersion findVersionById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_source_version WHERE id = ?", SV_MAPPER, id);
    }

    // ------------------------------------------------------------ 资产沿革
    public long insertAssetHistory(AssetHistory h) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_asset_history(physical_pipe_code, segment_code, event_type, material, "
                            + "diameter_mm, commission_year, length_m, effective_from, effective_to, age_reset, "
                            + "source_version_id, evidence_ref, superseded_by_id) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, h.physicalPipeCode());
            ps.setString(2, h.segmentCode());
            ps.setString(3, h.eventType());
            ps.setString(4, h.material());
            ps.setInt(5, h.diameterMm());
            ps.setInt(6, h.commissionYear());
            ps.setBigDecimal(7, h.lengthM());
            ps.setTimestamp(8, Timestamp.valueOf(h.effectiveFrom()));
            setTimestamp(ps, 9, h.effectiveTo());
            ps.setBoolean(10, h.ageReset());
            ps.setLong(11, h.sourceVersionId());
            ps.setString(12, h.evidenceRef());
            setLong(ps, 13, h.supersededById());
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public List<AssetHistory> findAllAssetHistory() {
        return jdbc.query("SELECT * FROM app_asset_history ORDER BY physical_pipe_code, effective_from, id",
                AH_MAPPER);
    }

    public List<AssetHistory> findAssetHistoryByPipe(String physicalPipeCode) {
        return jdbc.query("SELECT * FROM app_asset_history WHERE physical_pipe_code = ? "
                + "ORDER BY effective_from, id", AH_MAPPER, physicalPipeCode);
    }

    public AssetHistory findAssetHistoryById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_asset_history WHERE id = ?", AH_MAPPER, id);
    }

    /** 补录换管日期：封口旧区间并登记取代关系，旧行保留留痕。 */
    public void closeAssetHistoryInterval(Long id, LocalDateTime effectiveTo, Long supersededById) {
        jdbc.update("UPDATE app_asset_history SET effective_to = ?, superseded_by_id = ? WHERE id = ?",
                Timestamp.valueOf(effectiveTo), supersededById, id);
    }

    // ------------------------------------------------------------ 检查暴露
    public long insertExposure(InspectionExposure e) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_inspection_exposure(physical_pipe_code, segment_code, batch_code, "
                            + "inspected_at, station_start_m, station_end_m, coverage_status, obscured_reason, "
                            + "risk_zone, source_version_id, status, evidence_ref) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?, 'ACTIVE', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, e.physicalPipeCode());
            ps.setString(2, e.segmentCode());
            ps.setString(3, e.batchCode());
            ps.setTimestamp(4, Timestamp.valueOf(e.inspectedAt()));
            ps.setBigDecimal(5, e.stationStartM());
            ps.setBigDecimal(6, e.stationEndM());
            ps.setString(7, e.coverageStatus());
            ps.setString(8, e.obscuredReason());
            ps.setString(9, e.riskZone());
            ps.setLong(10, e.sourceVersionId());
            ps.setString(11, e.evidenceRef());
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public List<InspectionExposure> findAllExposures() {
        return jdbc.query("SELECT * FROM app_inspection_exposure ORDER BY physical_pipe_code, inspected_at, id",
                IE_MAPPER);
    }

    public InspectionExposure findExposureById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_inspection_exposure WHERE id = ?", IE_MAPPER, id);
    }

    /** 撤销一次观察：逻辑置 REVOKED 并记录撤销版本，重算时剔除但行保留。 */
    public void revokeExposure(Long id, Long revokedByVersionId) {
        jdbc.update("UPDATE app_inspection_exposure SET status = 'REVOKED', revoked_by_version_id = ? WHERE id = ?",
                revokedByVersionId, id);
    }

    // ------------------------------------------------------------ 缺陷事件
    public long insertDefectEvent(DefectEvent d) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_defect_event(physical_pipe_code, segment_code, event_kind, original_event_id, "
                            + "defect_type, normalized_grade, station_start_m, station_end_m, found_at, "
                            + "exposure_key, repair_id, repaired_at, repair_method, post_repair_status, "
                            + "source_version_id, evidence_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, d.physicalPipeCode());
            ps.setString(2, d.segmentCode());
            ps.setString(3, d.eventKind());
            setLong(ps, 4, d.originalEventId());
            ps.setString(5, d.defectType());
            ps.setInt(6, d.normalizedGrade());
            ps.setBigDecimal(7, d.stationStartM());
            ps.setBigDecimal(8, d.stationEndM());
            ps.setTimestamp(9, Timestamp.valueOf(d.foundAt()));
            ps.setString(10, d.exposureKey());
            ps.setString(11, d.repairId());
            setTimestamp(ps, 12, d.repairedAt());
            ps.setString(13, d.repairMethod());
            ps.setString(14, d.postRepairStatus());
            ps.setLong(15, d.sourceVersionId());
            ps.setString(16, d.evidenceRef());
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public List<DefectEvent> findAllDefectEvents() {
        return jdbc.query("SELECT * FROM app_defect_event ORDER BY physical_pipe_code, found_at, id", DE_MAPPER);
    }

    public DefectEvent findDefectEventById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_defect_event WHERE id = ?", DE_MAPPER, id);
    }

    // ------------------------------------------------------------ 队列运行
    public long insertRun(CohortRun r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_cohort_run(run_code, as_of_date, estimation_method, age_band_years, "
                            + "asset_version_id, exposure_version_id, defect_version_id, selection_model, "
                            + "method_note, non_inference_note, status, generated_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?, 'CURRENT', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.runCode());
            ps.setDate(2, Date.valueOf(r.asOfDate()));
            ps.setString(3, r.estimationMethod());
            ps.setInt(4, r.ageBandYears());
            ps.setLong(5, r.assetVersionId());
            ps.setLong(6, r.exposureVersionId());
            ps.setLong(7, r.defectVersionId());
            ps.setString(8, r.selectionModel());
            ps.setString(9, r.methodNote());
            ps.setString(10, r.nonInferenceNote());
            setLong(ps, 11, r.generatedBy());
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public List<CohortRun> findAllRuns() {
        return jdbc.query("SELECT * FROM app_cohort_run ORDER BY id DESC", RUN_MAPPER);
    }

    public List<CohortRun> findRunsByMethod(String method) {
        return jdbc.query("SELECT * FROM app_cohort_run WHERE estimation_method = ? ORDER BY id DESC",
                RUN_MAPPER, method);
    }

    public CohortRun findRunById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_cohort_run WHERE id = ?", RUN_MAPPER, id);
    }

    public Optional<CohortRun> findRunByCode(String code) {
        List<CohortRun> list = jdbc.query("SELECT * FROM app_cohort_run WHERE run_code = ?", RUN_MAPPER, code);
        return list.stream().findFirst();
    }

    public long countRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM app_cohort_run", Long.class);
    }

    /** 同编码重跑：清理旧运行的单元格/证据/运行行（旧结果若需保留应先归档，见 supersede）。 */
    public void deleteRunCascade(Long runId) {
        jdbc.update("DELETE FROM app_cohort_evidence WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM app_cohort_cell WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM app_cohort_run WHERE id = ?", runId);
    }

    /** 补录/撤销触发重算：旧运行置 SUPERSEDED 并指向新运行，结果行原样保留。 */
    public void supersedeRun(Long oldRunId, Long newRunId) {
        jdbc.update("UPDATE app_cohort_run SET status = 'SUPERSEDED', superseded_run_id = ? WHERE id = ?",
                newRunId, oldRunId);
    }

    public List<CohortRun> findCurrentRuns() {
        return jdbc.query("SELECT * FROM app_cohort_run WHERE status = 'CURRENT' ORDER BY id", RUN_MAPPER);
    }

    // ------------------------------------------------------------ 队列单元格
    public void insertCell(CohortCell c) {
        jdbc.update("INSERT INTO app_cohort_cell(run_id, material, commission_decade, diameter_band, age_band, "
                        + "total_length_m, inspected_length_m, obscured_length_m, effective_coverage, "
                        + "defect_event_count, first_finding_count, recurrence_count, defective_length_m, "
                        + "defect_ratio, recurrence_rate, weight_sum, weighted_defect_length, "
                        + "weighted_inspected_length, weighted_defect_ratio, min_weight, max_weight, "
                        + "evidence_sources, low_coverage_flag, extreme_weight_flag, non_inferable_flag) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.runId(), c.material(), c.commissionDecade(), c.diameterBand(), c.ageBand(),
                c.totalLengthM(), c.inspectedLengthM(), c.obscuredLengthM(), c.effectiveCoverage(),
                c.defectEventCount(), c.firstFindingCount(), c.recurrenceCount(), c.defectiveLengthM(),
                c.defectRatio(), c.recurrenceRate(), c.weightSum(), c.weightedDefectLength(),
                c.weightedInspectedLength(), c.weightedDefectRatio(), c.minWeight(), c.maxWeight(),
                c.evidenceSources(), c.lowCoverageFlag(), c.extremeWeightFlag(), c.nonInferableFlag());
    }

    public List<CohortCell> findCellsByRun(Long runId) {
        return jdbc.query("SELECT * FROM app_cohort_cell WHERE run_id = ? "
                + "ORDER BY material, commission_decade, diameter_band, age_band", CELL_MAPPER, runId);
    }

    // ------------------------------------------------------------ 证据明细
    public void insertEvidence(CohortEvidence e) {
        jdbc.update("INSERT INTO app_cohort_evidence(run_id, cell_key, evidence_kind, source_ref, detail) "
                + "VALUES (?,?,?,?,?)", e.runId(), e.cellKey(), e.evidenceKind(), e.sourceRef(), e.detail());
    }

    public List<CohortEvidence> findEvidenceByRun(Long runId) {
        return jdbc.query("SELECT * FROM app_cohort_evidence WHERE run_id = ? ORDER BY cell_key, id",
                CE_MAPPER, runId);
    }

    public List<CohortEvidence> findEvidenceByRunAndCell(Long runId, String cellKey) {
        return jdbc.query("SELECT * FROM app_cohort_evidence WHERE run_id = ? AND cell_key = ? ORDER BY id",
                CE_MAPPER, runId, cellKey);
    }

    private static void setLong(PreparedStatement ps, int idx, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    private static void setTimestamp(PreparedStatement ps, int idx, LocalDateTime value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(idx, Timestamp.valueOf(value));
        }
    }

    // ------------------------------------------------------------ 供服务层复用的空值兜底
    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static LocalDate today() {
        return LocalDate.now();
    }
}
