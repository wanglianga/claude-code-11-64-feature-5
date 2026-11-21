package com.ccdev.drainage.repo;

import com.ccdev.drainage.domain.Models.AssetVersion;
import com.ccdev.drainage.domain.Models.Manhole;
import com.ccdev.drainage.domain.Models.PipeSegment;
import com.ccdev.drainage.domain.Models.SegmentMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AssetRepository {

    private final JdbcTemplate jdbc;

    public AssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AssetVersion> VERSION_MAPPER = (rs, n) -> new AssetVersion(
            rs.getLong("id"), rs.getString("version_no"), rs.getString("name"),
            rs.getTimestamp("effective_at").toLocalDateTime(), rs.getString("status"),
            rs.getString("note"));

    private static final RowMapper<PipeSegment> SEGMENT_MAPPER = (rs, n) -> new PipeSegment(
            rs.getLong("id"), rs.getLong("version_id"), rs.getString("segment_code"),
            rs.getObject("upstream_mh_id", Long.class), rs.getObject("downstream_mh_id", Long.class),
            rs.getBigDecimal("length_m"), rs.getString("flow_direction"),
            rs.getString("material"), rs.getObject("diameter_mm", Integer.class),
            rs.getObject("parent_segment_id", Long.class), rs.getString("lifecycle_status"));

    private static final RowMapper<Manhole> MANHOLE_MAPPER = (rs, n) -> new Manhole(
            rs.getLong("id"), rs.getLong("version_id"), rs.getString("manhole_code"),
            rs.getBigDecimal("geom_x"), rs.getBigDecimal("geom_y"));

    private static final RowMapper<SegmentMapping> MAPPING_MAPPER = (rs, n) -> new SegmentMapping(
            rs.getLong("id"), rs.getLong("old_segment_id"), rs.getLong("new_segment_id"),
            rs.getBigDecimal("old_start_m"), rs.getBigDecimal("old_end_m"),
            rs.getBigDecimal("new_start_m"), rs.getBigDecimal("new_end_m"),
            rs.getString("basis"), rs.getString("note"));

    public List<AssetVersion> findAllVersions() {
        return jdbc.query("SELECT * FROM app_asset_version ORDER BY effective_at", VERSION_MAPPER);
    }

    public AssetVersion findVersionById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_asset_version WHERE id = ?", VERSION_MAPPER, id);
    }

    /** 检查时点有效的资产版本：effective_at <= inspectedAt 的最新版本。 */
    public AssetVersion findEffectiveVersionAt(java.time.LocalDateTime inspectedAt) {
        List<AssetVersion> list = jdbc.query(
                "SELECT * FROM app_asset_version WHERE effective_at <= ? ORDER BY effective_at DESC LIMIT 1",
                VERSION_MAPPER, java.sql.Timestamp.valueOf(inspectedAt));
        return list.isEmpty() ? null : list.get(0);
    }

    public List<PipeSegment> findSegmentsByVersion(Long versionId) {
        return jdbc.query("SELECT * FROM app_pipe_segment WHERE version_id = ? ORDER BY segment_code",
                SEGMENT_MAPPER, versionId);
    }

    public PipeSegment findSegmentById(Long id) {
        return jdbc.queryForObject("SELECT * FROM app_pipe_segment WHERE id = ?", SEGMENT_MAPPER, id);
    }

    public List<PipeSegment> findSegmentsByCodeAcrossVersions(String segmentCode) {
        return jdbc.query("SELECT * FROM app_pipe_segment WHERE segment_code = ? ORDER BY version_id",
                SEGMENT_MAPPER, segmentCode);
    }

    public List<Manhole> findManholesByVersion(Long versionId) {
        return jdbc.query("SELECT * FROM app_manhole WHERE version_id = ? ORDER BY manhole_code",
                MANHOLE_MAPPER, versionId);
    }

    public List<SegmentMapping> findAllMappings() {
        return jdbc.query("SELECT * FROM app_segment_mapping ORDER BY id", MAPPING_MAPPER);
    }

    public List<SegmentMapping> findMappingsFromOldSegment(Long oldSegmentId) {
        return jdbc.query("SELECT * FROM app_segment_mapping WHERE old_segment_id = ? ORDER BY old_start_m",
                MAPPING_MAPPER, oldSegmentId);
    }
}
