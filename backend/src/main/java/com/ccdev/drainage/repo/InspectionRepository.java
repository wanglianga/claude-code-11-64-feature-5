package com.ccdev.drainage.repo;

import com.ccdev.drainage.domain.Models.ContractorBatch;
import com.ccdev.drainage.domain.Models.Observation;
import com.ccdev.drainage.domain.Models.Video;
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
public class InspectionRepository {

    private final JdbcTemplate jdbc;

    public InspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ContractorBatch> BATCH_MAPPER = (rs, n) -> new ContractorBatch(
            rs.getLong("id"), rs.getString("batch_code"), rs.getString("contractor"),
            rs.getTimestamp("inspected_at").toLocalDateTime(), rs.getString("grading_standard"),
            rs.getString("direction_convention"), rs.getString("content_hash"));

    private static final RowMapper<Video> VIDEO_MAPPER = (rs, n) -> new Video(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("video_code"),
            rs.getString("file_uri"), rs.getString("segment_hint"),
            rs.getBigDecimal("start_m"), rs.getBigDecimal("end_m"),
            rs.getString("station_direction"), rs.getString("content_hash"),
            rs.getObject("duplicate_of_id", Long.class));

    private static final RowMapper<Observation> OBS_MAPPER = (rs, n) -> new Observation(
            rs.getLong("id"), rs.getLong("video_id"), rs.getLong("batch_id"),
            rs.getString("defect_type"), rs.getString("raw_grade"),
            rs.getBigDecimal("station_start_m"), rs.getBigDecimal("station_end_m"),
            rs.getString("evidence_uri"), rs.getString("evidence_strength"),
            rs.getString("status"), rs.getObject("duplicate_of_id", Long.class),
            rs.getObject("cluster_id", Long.class));

    // ------------------------------------------------------------ 批次
    public List<ContractorBatch> findAllBatches() {
        return jdbc.query("SELECT * FROM app_contractor_batch ORDER BY inspected_at", BATCH_MAPPER);
    }

    public ContractorBatch findBatchById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_contractor_batch WHERE id = ?", BATCH_MAPPER, id);
    }

    public Optional<ContractorBatch> findBatchByCode(String batchCode) {
        return jdbc.query("SELECT * FROM app_contractor_batch WHERE batch_code = ?", BATCH_MAPPER, batchCode)
                .stream().findFirst();
    }

    public Optional<ContractorBatch> findBatchByContentHash(String hash) {
        return jdbc.query("SELECT * FROM app_contractor_batch WHERE content_hash = ?", BATCH_MAPPER, hash)
                .stream().findFirst();
    }

    public long insertBatch(ContractorBatch batch) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_contractor_batch(batch_code, contractor, inspected_at, grading_standard, "
                            + "direction_convention, content_hash) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batch.batchCode());
            ps.setString(2, batch.contractor());
            ps.setTimestamp(3, Timestamp.valueOf(batch.inspectedAt()));
            ps.setString(4, batch.gradingStandard());
            ps.setString(5, batch.directionConvention());
            ps.setString(6, batch.contentHash());
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    // ------------------------------------------------------------ 视频
    public List<Video> findAllVideos() {
        return jdbc.query("SELECT * FROM app_video ORDER BY id", VIDEO_MAPPER);
    }

    public List<Video> findVideosByBatch(Long batchId) {
        return jdbc.query("SELECT * FROM app_video WHERE batch_id = ? ORDER BY id", VIDEO_MAPPER, batchId);
    }

    public Optional<Video> findVideoByContentHash(String hash) {
        return jdbc.query("SELECT * FROM app_video WHERE content_hash = ?", VIDEO_MAPPER, hash)
                .stream().findFirst();
    }

    public long insertVideo(Video v) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_video(batch_id, video_code, file_uri, segment_hint, start_m, end_m, "
                            + "station_direction, content_hash, duplicate_of_id) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, v.batchId());
            ps.setString(2, v.videoCode());
            ps.setString(3, v.fileUri());
            ps.setString(4, v.segmentHint());
            ps.setBigDecimal(5, v.startM());
            ps.setBigDecimal(6, v.endM());
            ps.setString(7, v.stationDirection());
            ps.setString(8, v.contentHash());
            if (v.duplicateOfId() == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, v.duplicateOfId());
            }
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    // ------------------------------------------------------------ 观察
    public List<Observation> findAllObservations() {
        return jdbc.query("SELECT * FROM app_observation ORDER BY id", OBS_MAPPER);
    }

    public List<Observation> findObservationsByBatch(Long batchId) {
        return jdbc.query("SELECT * FROM app_observation WHERE batch_id = ? ORDER BY id", OBS_MAPPER, batchId);
    }

    public Observation findObservationById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_observation WHERE id = ?", OBS_MAPPER, id);
    }

    public long insertObservation(Observation o) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_observation(video_id, batch_id, defect_type, raw_grade, "
                            + "station_start_m, station_end_m, evidence_uri, evidence_strength, status, duplicate_of_id) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, o.videoId());
            ps.setLong(2, o.batchId());
            ps.setString(3, o.defectType());
            ps.setString(4, o.rawGrade());
            ps.setBigDecimal(5, o.stationStartM());
            ps.setBigDecimal(6, o.stationEndM());
            ps.setString(7, o.evidenceUri());
            ps.setString(8, o.evidenceStrength());
            ps.setString(9, o.status());
            if (o.duplicateOfId() == null) {
                ps.setNull(10, java.sql.Types.BIGINT);
            } else {
                ps.setLong(10, o.duplicateOfId());
            }
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public void updateObservationStatus(Long id, String status, Long clusterId) {
        jdbc.update("UPDATE app_observation SET status = ?, cluster_id = ? WHERE id = ?",
                status, clusterId, id);
    }

    public void updateObservationStatusAndDuplicate(Long id, String status, Long duplicateOfId) {
        jdbc.update("UPDATE app_observation SET status = ?, duplicate_of_id = ? WHERE id = ?",
                status, duplicateOfId, id);
    }

    // ------------------------------------------------------------ 等级标准
    public Integer normalizeGrade(String standardCode, String rawGrade) {
        List<Integer> list = jdbc.query(
                "SELECT normalized_grade FROM app_grade_standard WHERE standard_code = ? AND raw_grade = ?",
                (rs, n) -> rs.getInt(1), standardCode, rawGrade);
        return list.isEmpty() ? null : list.get(0);
    }
}
