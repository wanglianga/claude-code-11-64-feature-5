package com.ccdev.drainage.repo;

import com.ccdev.drainage.domain.Models.Dataset;
import com.ccdev.drainage.domain.Models.DatasetImpact;
import com.ccdev.drainage.domain.Models.SegmentScore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class DatasetRepository {

    private final JdbcTemplate jdbc;

    public DatasetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Dataset> DS_MAPPER = (rs, n) -> new Dataset(
            rs.getLong("id"), rs.getString("dataset_code"), rs.getLong("as_of_version_id"),
            rs.getString("scoring_mode"), rs.getString("status"),
            rs.getTimestamp("generated_at").toLocalDateTime(),
            rs.getObject("generated_by", Long.class), rs.getString("formula_note"));

    private static final RowMapper<SegmentScore> SCORE_MAPPER = (rs, n) -> new SegmentScore(
            rs.getLong("id"), rs.getLong("dataset_id"), rs.getLong("segment_id"),
            rs.getObject("max_grade", Integer.class), rs.getBigDecimal("burden_score"),
            rs.getObject("rank_no", Integer.class), rs.getBoolean("ranking_paused"),
            rs.getString("pause_reasons"), rs.getInt("eligible_cluster_count"),
            rs.getInt("conflict_count"), rs.getBigDecimal("evidence_completeness"),
            rs.getString("explanation"));

    private static final RowMapper<DatasetImpact> IMPACT_MAPPER = (rs, n) -> new DatasetImpact(
            rs.getLong("id"), rs.getLong("dataset_id"), rs.getLong("old_segment_id"),
            rs.getObject("new_segment_id", Long.class), rs.getString("impact_type"),
            rs.getInt("inherited_count"), rs.getInt("quarantined_count"), rs.getString("note"));

    public long countDatasets() {
        return jdbc.queryForObject("SELECT count(*) FROM app_dataset", Long.class);
    }

    /** 同编码数据集重新生成前先级联清理（评分、影响清单、数据集本身）。 */
    public void deleteByCode(String code) {
        List<Long> ids = jdbc.query("SELECT id FROM app_dataset WHERE dataset_code = ?",
                (rs, n) -> rs.getLong(1), code);
        for (Long id : ids) {
            jdbc.update("DELETE FROM app_dataset_segment_score WHERE dataset_id = ?", id);
            jdbc.update("DELETE FROM app_dataset_impact WHERE dataset_id = ?", id);
            jdbc.update("DELETE FROM app_dataset WHERE id = ?", id);
        }
    }

    public long insertDataset(String code, Long asOfVersionId, String scoringMode,
                              Long generatedBy, String formulaNote) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_dataset(dataset_code, as_of_version_id, scoring_mode, status, "
                            + "generated_at, generated_by, formula_note) VALUES (?,?,?,'GENERATED',now(),?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setLong(2, asOfVersionId);
            ps.setString(3, scoringMode);
            if (generatedBy == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, generatedBy);
            }
            ps.setString(5, formulaNote);
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public List<Dataset> findAllDatasets() {
        return jdbc.query("SELECT * FROM app_dataset ORDER BY id", DS_MAPPER);
    }

    public Dataset findDatasetById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_dataset WHERE id = ?", DS_MAPPER, id);
    }

    public void insertScore(Long datasetId, Long segmentId, Integer maxGrade, BigDecimal burdenScore,
                            Integer rankNo, boolean rankingPaused, String pauseReasons,
                            int eligibleClusterCount, int conflictCount,
                            BigDecimal evidenceCompleteness, String explanation) {
        jdbc.update("INSERT INTO app_dataset_segment_score(dataset_id, segment_id, max_grade, burden_score, "
                        + "rank_no, ranking_paused, pause_reasons, eligible_cluster_count, conflict_count, "
                        + "evidence_completeness, explanation) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                datasetId, segmentId, maxGrade, burdenScore, rankNo, rankingPaused, pauseReasons,
                eligibleClusterCount, conflictCount, evidenceCompleteness, explanation);
    }

    public List<SegmentScore> findScoresByDataset(Long datasetId) {
        return jdbc.query("SELECT * FROM app_dataset_segment_score WHERE dataset_id = ? ORDER BY "
                + "ranking_paused DESC, COALESCE(rank_no, 999999), id", SCORE_MAPPER, datasetId);
    }

    public void insertImpact(Long datasetId, Long oldSegmentId, Long newSegmentId, String impactType,
                             int inheritedCount, int quarantinedCount, String note) {
        jdbc.update("INSERT INTO app_dataset_impact(dataset_id, old_segment_id, new_segment_id, impact_type, "
                        + "inherited_count, quarantined_count, note) VALUES (?,?,?,?,?,?,?)",
                datasetId, oldSegmentId, newSegmentId, impactType,
                inheritedCount, quarantinedCount, note);
    }

    public List<DatasetImpact> findImpactsByDataset(Long datasetId) {
        return jdbc.query("SELECT * FROM app_dataset_impact WHERE dataset_id = ? ORDER BY id",
                IMPACT_MAPPER, datasetId);
    }
}
