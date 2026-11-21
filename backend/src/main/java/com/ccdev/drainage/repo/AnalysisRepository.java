package com.ccdev.drainage.repo;

import com.ccdev.drainage.domain.Models.AlignmentCandidate;
import com.ccdev.drainage.domain.Models.Attribution;
import com.ccdev.drainage.domain.Models.ClusterMember;
import com.ccdev.drainage.domain.Models.DefectCluster;
import com.ccdev.drainage.domain.Models.JudgementRule;
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
import java.util.Optional;

@Repository
public class AnalysisRepository {

    private final JdbcTemplate jdbc;

    public AnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AlignmentCandidate> CANDIDATE_MAPPER = (rs, n) -> new AlignmentCandidate(
            rs.getLong("id"), rs.getLong("observation_id"), rs.getLong("segment_id"),
            rs.getLong("asset_version_id"), rs.getBigDecimal("norm_start_m"), rs.getBigDecimal("norm_end_m"),
            rs.getBigDecimal("score"), rs.getBoolean("selected"), rs.getString("reason"));

    private static final RowMapper<Attribution> ATTR_MAPPER = (rs, n) -> new Attribution(
            rs.getLong("observation_id"), rs.getLong("segment_id"), rs.getLong("asset_version_id"),
            rs.getBigDecimal("norm_start_m"), rs.getBigDecimal("norm_end_m"),
            rs.getTimestamp("fixed_at").toLocalDateTime(), rs.getString("note"));

    private static final RowMapper<DefectCluster> CLUSTER_MAPPER = (rs, n) -> new DefectCluster(
            rs.getLong("id"), rs.getString("cluster_code"), rs.getLong("segment_id"),
            rs.getLong("asset_version_id"), rs.getString("defect_type"),
            rs.getBigDecimal("norm_start_m"), rs.getBigDecimal("norm_end_m"),
            rs.getString("status"), rs.getBoolean("conflict_flag"),
            rs.getObject("resolved_grade", Integer.class), rs.getString("resolution_note"),
            rs.getObject("resolved_by", Long.class),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toLocalDateTime());

    private static final RowMapper<ClusterMember> MEMBER_MAPPER = (rs, n) -> new ClusterMember(
            rs.getLong("id"), rs.getLong("cluster_id"), rs.getLong("observation_id"),
            rs.getString("member_role"), rs.getInt("grade_at_inspection"),
            rs.getString("evidence_strength"), rs.getLong("batch_id"));

    private static final RowMapper<JudgementRule> RULE_MAPPER = (rs, n) -> new JudgementRule(
            rs.getString("defect_type"), rs.getInt("min_grade"), rs.getBigDecimal("min_length_m"),
            rs.getString("required_evidence"), rs.getBoolean("enabled"));

    // ------------------------------------------------------------ 对齐候选与归属
    public long countCandidates() {
        return jdbc.queryForObject("SELECT count(*) FROM app_alignment_candidate", Long.class);
    }

    public long countAttributions() {
        return jdbc.queryForObject("SELECT count(*) FROM app_observation_attribution", Long.class);
    }

    public void deleteAllCandidatesAndAttributions() {
        jdbc.update("DELETE FROM app_alignment_candidate");
        jdbc.update("DELETE FROM app_observation_attribution");
    }

    public void insertCandidate(AlignmentCandidate c) {
        jdbc.update("INSERT INTO app_alignment_candidate(observation_id, segment_id, asset_version_id, "
                        + "norm_start_m, norm_end_m, score, selected, reason) VALUES (?,?,?,?,?,?,?,?)",
                c.observationId(), c.segmentId(), c.assetVersionId(),
                c.normStartM(), c.normEndM(), c.score(), c.selected(), c.reason());
    }

    public List<AlignmentCandidate> findCandidatesByObservation(Long observationId) {
        return jdbc.query("SELECT * FROM app_alignment_candidate WHERE observation_id = ? ORDER BY score DESC, id",
                CANDIDATE_MAPPER, observationId);
    }

    public void insertAttribution(Attribution a) {
        jdbc.update("INSERT INTO app_observation_attribution(observation_id, segment_id, asset_version_id, "
                        + "norm_start_m, norm_end_m, note) VALUES (?,?,?,?,?,?)",
                a.observationId(), a.segmentId(), a.assetVersionId(),
                a.normStartM(), a.normEndM(), a.note());
    }

    public Optional<Attribution> findAttribution(Long observationId) {
        return jdbc.query("SELECT * FROM app_observation_attribution WHERE observation_id = ?",
                ATTR_MAPPER, observationId).stream().findFirst();
    }

    public List<Attribution> findAllAttributions() {
        return jdbc.query("SELECT * FROM app_observation_attribution ORDER BY observation_id", ATTR_MAPPER);
    }

    // ------------------------------------------------------------ 判读规则
    public List<JudgementRule> findAllRules() {
        return jdbc.query("SELECT * FROM app_judgement_rule WHERE enabled = true", RULE_MAPPER);
    }

    // ------------------------------------------------------------ 聚类
    public long countClusters() {
        return jdbc.queryForObject("SELECT count(*) FROM app_defect_cluster", Long.class);
    }

    public void deleteAllClustersAndMembers() {
        jdbc.update("DELETE FROM app_cluster_member");
        jdbc.update("DELETE FROM app_defect_cluster");
    }

    public long insertCluster(Long segmentId, Long assetVersionId, String defectType,
                              BigDecimal normStart, BigDecimal normEnd, String status,
                              boolean conflictFlag, Integer resolvedGrade, String resolutionNote,
                              Long resolvedBy, Timestamp resolvedAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_defect_cluster(segment_id, asset_version_id, defect_type, norm_start_m, "
                            + "norm_end_m, status, conflict_flag, resolved_grade, resolution_note, resolved_by, resolved_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, segmentId);
            ps.setLong(2, assetVersionId);
            ps.setString(3, defectType);
            ps.setBigDecimal(4, normStart);
            ps.setBigDecimal(5, normEnd);
            ps.setString(6, status);
            ps.setBoolean(7, conflictFlag);
            if (resolvedGrade == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, resolvedGrade);
            }
            ps.setString(9, resolutionNote);
            if (resolvedBy == null) {
                ps.setNull(10, java.sql.Types.BIGINT);
            } else {
                ps.setLong(10, resolvedBy);
            }
            ps.setTimestamp(11, resolvedAt);
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public void insertClusterMember(Long clusterId, Long observationId, String role,
                                    Integer gradeAtInspection, String evidenceStrength, Long batchId) {
        jdbc.update("INSERT INTO app_cluster_member(cluster_id, observation_id, member_role, "
                        + "grade_at_inspection, evidence_strength, batch_id) VALUES (?,?,?,?,?,?)",
                clusterId, observationId, role, gradeAtInspection, evidenceStrength, batchId);
    }

    public List<DefectCluster> findClustersBySegment(Long segmentId) {
        return jdbc.query("SELECT * FROM app_defect_cluster WHERE segment_id = ? ORDER BY norm_start_m",
                CLUSTER_MAPPER, segmentId);
    }

    public List<DefectCluster> findAllClusters() {
        return jdbc.query("SELECT * FROM app_defect_cluster ORDER BY id", CLUSTER_MAPPER);
    }

    public DefectCluster findClusterById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_defect_cluster WHERE id = ?", CLUSTER_MAPPER, id);
    }

    public List<ClusterMember> findMembersByCluster(Long clusterId) {
        return jdbc.query("SELECT * FROM app_cluster_member WHERE cluster_id = ? ORDER BY id",
                MEMBER_MAPPER, clusterId);
    }

    public List<ClusterMember> findAllMembers() {
        return jdbc.query("SELECT * FROM app_cluster_member ORDER BY id", MEMBER_MAPPER);
    }

    public void updateClusterCode(Long clusterId, String clusterCode) {
        jdbc.update("UPDATE app_defect_cluster SET cluster_code = ? WHERE id = ?", clusterCode, clusterId);
    }

    public void adjudicateCluster(Long clusterId, Integer resolvedGrade, String note,
                                  Long userId, Timestamp resolvedAt) {
        jdbc.update("UPDATE app_defect_cluster SET status = 'ADJUDICATED', conflict_flag = false, "
                        + "resolved_grade = ?, resolution_note = ?, resolved_by = ?, resolved_at = ? WHERE id = ?",
                resolvedGrade, note, userId, resolvedAt, clusterId);
    }
}
