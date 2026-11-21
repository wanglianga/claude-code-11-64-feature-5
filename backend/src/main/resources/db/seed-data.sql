-- ============================================================================
-- 虚构样例数据（可重复执行：ON CONFLICT DO NOTHING）
-- 场景覆盖：
--   * 反向里程批次（B-2025-02，从下游井起算）
--   * 两批独立检查对同一位置给出不相容等级（WS-002 接口错位 III vs D=4）
--   * 重复上传（VID-B3-WS001-DUP 与 V1 同 content_hash）与重叠片段（V6）
--   * 管段拆分（WS-003 → WS-003A/WS-003B，拆分点 22m；OBS-11 跨拆分点）
--   * 低等级/证据不足观察不入围（OBS-14 等级 I）
-- 对齐候选、聚类、数据集由后端启动任务计算，不在此手工铺设。
-- ============================================================================

-- ---------------------------------------------------------------- 用户
INSERT INTO app_user (id, username, password_hash, display_name, role) VALUES
 (1, 'admin',    '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '工程处管理员', 'ADMIN'),
 (2, 'engineer', 'f63248efa4a61efc9f4c9f6e5de25b34b6f2b827717cd4c6b3905481c3bd483b', '复核工程师',   'ENGINEER')
ON CONFLICT (username) DO NOTHING;

-- ---------------------------------------------------------------- 资产版本
INSERT INTO app_asset_version (id, version_no, name, effective_at, status, note) VALUES
 (1, 'V2024-01', '2024 版竣工资产图', TIMESTAMP '2024-01-01 00:00:00', 'SUPERSEDED', '初始普查基线版本'),
 (2, 'V2026-06', '2026 年 6 月测绘修订版', TIMESTAMP '2026-06-01 00:00:00', 'ACTIVE', 'WS-003 复测后拆分为 WS-003A/WS-003B，新增井 MH03X')
ON CONFLICT (version_no) DO NOTHING;

-- ---------------------------------------------------------------- 井
INSERT INTO app_manhole (id, version_id, manhole_code, geom_x, geom_y) VALUES
 (1, 1, 'MH01', 100.000, 200.000),
 (2, 1, 'MH02', 160.000, 200.000),
 (3, 1, 'MH03', 210.000, 200.000),
 (4, 1, 'MH04', 250.000, 200.000),
 (5, 2, 'MH01', 100.000, 200.000),
 (6, 2, 'MH02', 160.000, 200.000),
 (7, 2, 'MH03', 210.000, 200.000),
 (8, 2, 'MH04', 250.000, 200.000),
 (9, 2, 'MH03X', 232.000, 200.000)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 管段
-- 旧版本三段
INSERT INTO app_pipe_segment
 (id, version_id, segment_code, upstream_mh_id, downstream_mh_id, length_m, flow_direction, material, diameter_mm, parent_segment_id, lifecycle_status) VALUES
 (1, 1, 'WS-001', 1, 2, 60.000, 'UP_TO_DOWN', '钢筋混凝土', 800, NULL, 'ACTIVE'),
 (2, 1, 'WS-002', 2, 3, 50.000, 'UP_TO_DOWN', '钢筋混凝土', 800, NULL, 'ACTIVE'),
 (3, 1, 'WS-003', 3, 4, 40.000, 'UP_TO_DOWN', 'PVC', 600, NULL, 'SPLIT')
ON CONFLICT DO NOTHING;
-- 新版本：WS-001/WS-002 延续，WS-003 拆分
INSERT INTO app_pipe_segment
 (id, version_id, segment_code, upstream_mh_id, downstream_mh_id, length_m, flow_direction, material, diameter_mm, parent_segment_id, lifecycle_status) VALUES
 (4, 2, 'WS-001', 5, 6, 60.000, 'UP_TO_DOWN', '钢筋混凝土', 800, 1, 'ACTIVE'),
 (5, 2, 'WS-002', 6, 7, 50.000, 'UP_TO_DOWN', '钢筋混凝土', 800, 2, 'ACTIVE'),
 (6, 2, 'WS-003A', 7, 9, 22.000, 'UP_TO_DOWN', 'PVC', 600, 3, 'ACTIVE'),
 (7, 2, 'WS-003B', 9, 8, 18.000, 'UP_TO_DOWN', 'PVC', 600, 3, 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 新旧管段里程映射（归一里程：上游井为 0）
INSERT INTO app_segment_mapping
 (id, old_segment_id, new_segment_id, old_start_m, old_end_m, new_start_m, new_end_m, basis, note) VALUES
 (1, 1, 4,  0.000, 60.000,  0.000, 60.000, 'STATION_EQUIVALENCE', 'WS-001 几何未变，里程等值映射'),
 (2, 2, 5,  0.000, 50.000,  0.000, 50.000, 'STATION_EQUIVALENCE', 'WS-002 几何未变，里程等值映射'),
 (3, 3, 6,  0.000, 22.000,  0.000, 22.000, 'SURVEY_MEASURE', 'WS-003 前段 → WS-003A，拆分点 22m'),
 (4, 3, 7, 22.000, 40.000,  0.000, 18.000, 'SURVEY_MEASURE', 'WS-003 后段 → WS-003B，新里程自 MH03X 起算')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 等级标准
INSERT INTO app_grade_standard (standard_code, raw_grade, normalized_grade) VALUES
 ('STD-CCT-2021', 'I',   1),
 ('STD-CCT-2021', 'II',  2),
 ('STD-CCT-2021', 'III', 3),
 ('STD-CCT-2021', 'IV',  4),
 ('STD-DX-2023',  'A',   1),
 ('STD-DX-2023',  'B',   2),
 ('STD-DX-2023',  'C',   3),
 ('STD-DX-2023',  'D',   4)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 判读规则（进入评估数据集的门槛）
INSERT INTO app_judgement_rule (defect_type, min_grade, min_length_m, required_evidence, enabled) VALUES
 ('CRACK',             2, 1.000, 'PARTIAL', true),
 ('DEFORMATION',       3, 0.000, 'SPARSE',  true),
 ('LEAK',              2, 0.000, 'SPARSE',  true),
 ('JOINT_DISLOCATION', 3, 0.000, 'PARTIAL', true)
ON CONFLICT (defect_type) DO NOTHING;

-- ---------------------------------------------------------------- 承包商批次
INSERT INTO app_contractor_batch (id, batch_code, contractor, inspected_at, grading_standard, direction_convention, content_hash) VALUES
 (1, 'B-2025-01', '甲勘测院',   TIMESTAMP '2025-03-10 09:00:00', 'STD-CCT-2021', '上游井起算，里程沿前进方向递增', 'hash:batch:b202501'),
 (2, 'B-2025-02', '乙检测公司', TIMESTAMP '2025-09-18 10:30:00', 'STD-DX-2023',  '下游井起算，反向里程（0 在下游井）', 'hash:batch:b202502'),
 (3, 'B-2026-03', '甲勘测院复测', TIMESTAMP '2026-03-05 14:00:00', 'STD-CCT-2021', '上游井起算，里程沿前进方向递增', 'hash:batch:b202603')
ON CONFLICT (batch_code) DO NOTHING;

-- ---------------------------------------------------------------- 视频索引
-- V5 与 V1 内容哈希相同：重复上传；V6 为部分重叠片段
INSERT INTO app_video (id, batch_id, video_code, file_uri, segment_hint, start_m, end_m, station_direction, content_hash, duplicate_of_id) VALUES
 (1, 1, 'VID-B1-WS001',     'archive://2025/B1/ws001.mp4',     'WS-001',  0.000, 60.000, 'FORWARD',  'sha256:vid-ws001-full',    NULL),
 (2, 2, 'VID-B2-WS001',     'archive://2025/B2/ws001-rev.mp4', 'WS-001',  0.000, 60.000, 'REVERSED', 'sha256:vid-ws001-rev',     NULL),
 (3, 1, 'VID-B1-WS002',     'archive://2025/B1/ws002.mp4',     'WS-002',  0.000, 50.000, 'FORWARD',  'sha256:vid-ws002',         NULL),
 (4, 2, 'VID-B2-WS002',     'archive://2025/B2/ws002.mp4',     'WS-002',  0.000, 50.000, 'FORWARD',  'sha256:vid-ws002-b2',      NULL),
 (5, 3, 'VID-B3-WS001-DUP', 'archive://2026/B3/ws001-copy.mp4','WS-001',  0.000, 60.000, 'FORWARD',  'sha256:vid-ws001-full',    1),
 (6, 3, 'VID-B3-WS001-OVL', 'archive://2026/B3/ws001-part.mp4','WS-001',  8.000, 20.000, 'FORWARD',  'sha256:vid-ws001-partial', NULL),
 (7, 1, 'VID-B1-WS003',     'archive://2025/B1/ws003.mp4',     'WS-003',  0.000, 40.000, 'FORWARD',  'sha256:vid-ws003',         NULL),
 (8, 2, 'VID-B2-WS003',     'archive://2025/B2/ws003-rev.mp4', 'WS-003',  0.000, 40.000, 'REVERSED', 'sha256:vid-ws003-rev',     NULL)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 观察
-- 归一里程由后端按视频方向换算（REVERSED: norm = length - station），此处仅存原始里程
INSERT INTO app_observation
 (id, video_id, batch_id, defect_type, raw_grade, station_start_m, station_end_m, evidence_uri, evidence_strength, status, duplicate_of_id) VALUES
-- B1 对 WS-001 的检查
 (1,  1, 1, 'CRACK',             'II',  10.000, 12.500, 'archive://2025/B1/frames/obs-001.zip', 'FULL',    'PENDING', NULL),
 (2,  1, 1, 'LEAK',              'III', 30.000, 31.000, 'archive://2025/B1/frames/obs-002.zip', 'PARTIAL', 'PENDING', NULL),
 (14, 1, 1, 'CRACK',             'I',   50.000, 51.000, 'archive://2025/B1/frames/obs-014.zip', 'SPARSE',  'PENDING', NULL),
-- B2 反向里程对 WS-001：47.5–50 反向 ⇒ 归一 10–12.5（与 OBS-1 同位置同等级，重复）；20–21 反向 ⇒ 归一 39–40
 (3,  2, 2, 'CRACK',             'B',   47.500, 50.000, 'archive://2025/B2/frames/obs-003.zip', 'FULL',    'PENDING', NULL),
 (4,  2, 2, 'DEFORMATION',       'D',   20.000, 21.000, 'archive://2025/B2/frames/obs-004.zip', 'SPARSE',  'PENDING', NULL),
-- WS-002 同位置等级不相容：III(FULL) vs D=4(PARTIAL)，证据不足以裁决 ⇒ 冲突暂停
 (5,  3, 1, 'JOINT_DISLOCATION', 'III', 15.000, 15.500, 'archive://2025/B1/frames/obs-005.zip', 'FULL',    'PENDING', NULL),
 (6,  4, 2, 'JOINT_DISLOCATION', 'D',   15.000, 15.600, 'archive://2025/B2/frames/obs-006.zip', 'PARTIAL', 'PENDING', NULL),
-- B3 重复上传视频 V5 中的两条观察，直接标记为 OBS-1/2 的重复件
 (7,  5, 3, 'CRACK', 'II',  10.000, 12.500, 'archive://2026/B3/frames/obs-007.zip', 'FULL',    'DUPLICATE', 1),
 (8,  5, 3, 'LEAK',  'III', 30.000, 31.000, 'archive://2026/B3/frames/obs-008.zip', 'PARTIAL', 'DUPLICATE', 2),
-- B3 重叠片段 V6 中的裂缝，聚类时并入同一簇，不重复累加
 (9,  6, 3, 'CRACK', 'II',  10.200, 12.300, 'archive://2026/B3/frames/obs-009.zip', 'FULL',    'PENDING', NULL),
-- WS-003（后拆分）：8–10 落入 3A；21–23 跨拆分点 22 ⇒ 隔离不得复制；30–31 落入 3B
 (10, 7, 1, 'CRACK',       'III',  8.000, 10.000, 'archive://2025/B1/frames/obs-010.zip', 'FULL',    'PENDING', NULL),
 (11, 7, 1, 'DEFORMATION', 'IV',  21.000, 23.000, 'archive://2025/B1/frames/obs-011.zip', 'FULL',    'PENDING', NULL),
 (12, 7, 1, 'LEAK',        'II',  30.000, 31.000, 'archive://2025/B1/frames/obs-012.zip', 'PARTIAL', 'PENDING', NULL),
-- B2 反向里程复检 WS-003：反向 30–32 ⇒ 归一 8–10，与 OBS-10 同位置同等级
 (13, 8, 2, 'CRACK',       'C',   30.000, 32.000, 'archive://2025/B2/frames/obs-013.zip', 'FULL',    'PENDING', NULL)
ON CONFLICT DO NOTHING;

-- 校正显式插入后的 identity 序列，避免后续自增冲突
SELECT setval(pg_get_serial_sequence('app_user','id'),               (SELECT max(id) FROM app_user));
SELECT setval(pg_get_serial_sequence('app_asset_version','id'),      (SELECT max(id) FROM app_asset_version));
SELECT setval(pg_get_serial_sequence('app_manhole','id'),            (SELECT max(id) FROM app_manhole));
SELECT setval(pg_get_serial_sequence('app_pipe_segment','id'),       (SELECT max(id) FROM app_pipe_segment));
SELECT setval(pg_get_serial_sequence('app_segment_mapping','id'),    (SELECT max(id) FROM app_segment_mapping));
SELECT setval(pg_get_serial_sequence('app_contractor_batch','id'),   (SELECT max(id) FROM app_contractor_batch));
SELECT setval(pg_get_serial_sequence('app_video','id'),              (SELECT max(id) FROM app_video));
SELECT setval(pg_get_serial_sequence('app_observation','id'),        (SELECT max(id) FROM app_observation));

-- ============================================================================
-- 队列统计域样例（7 条物理管道，独立于上方 WS-* 普查样例）
-- 场景覆盖：
--   * 年龄分布不均：1990s/2000s/2010s 投用队列并存，年龄层 0-9 / 10-19 / 20-29 / 30+
--   * 选择性检查：HIGH_RISK 街区管道被多轮重复检查（P-RCP-02 三轮、P-PVC-02 两轮）
--   * 内衬改造：P-RCP-04 2018 年 CIPP 内衬，服役年龄与材料暴露时间从 2018 重新起算
--   * 换管：P-RCP-05 2019 年整段更换，新管年龄从 0 起算
--   * 修复复发：P-RCP-02 渗漏 2023 修复、2025 原位复发（关联原事件，不拆成两段）
--   * 低覆盖/遮挡：P-RCP-03 仅 20/80m 受检，30-50m 水位遮挡，其余未检查
-- 分析基准日固定为 2026-11-21（服务层可传参复跑）。
-- ============================================================================

-- ---------------------------------------------------------------- 源数据版本
INSERT INTO app_source_version (id, source_domain, version_no, source_note, created_by) VALUES
 (10, 'ASSET_HISTORY',       'AH-V2026-11', '队列域资产沿革首版：含投用/换管/内衬区间', 1),
 (11, 'INSPECTION_EXPOSURE', 'IE-V2026-11', '队列域检查暴露首版：含遮挡区间与高风险区多轮检查', 1),
 (12, 'DEFECT_EVENT',        'DE-V2026-11', '队列域缺陷事件首版：含修复后复发关联', 1)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 资产沿革（暴露区间）
INSERT INTO app_asset_history
 (id, physical_pipe_code, segment_code, event_type, material, diameter_mm, commission_year, length_m,
  effective_from, effective_to, age_reset, source_version_id, evidence_ref) VALUES
 (1, 'P-RCP-01', 'P-RCP-01', 'COMMISSION',      '钢筋混凝土', 800, 1995, 100.000, TIMESTAMP '1995-06-01 00:00:00', NULL,                 false, 10, 'archive://cohort/asset/P-RCP-01/as-built-1995.pdf'),
 (2, 'P-RCP-02', 'P-RCP-02', 'COMMISSION',      '钢筋混凝土', 800, 1998, 100.000, TIMESTAMP '1998-03-01 00:00:00', NULL,                 false, 10, 'archive://cohort/asset/P-RCP-02/as-built-1998.pdf'),
 (3, 'P-RCP-03', 'P-RCP-03', 'COMMISSION',      '钢筋混凝土', 600, 2005,  80.000, TIMESTAMP '2005-09-01 00:00:00', NULL,                 false, 10, 'archive://cohort/asset/P-RCP-03/as-built-2005.pdf'),
 (4, 'P-PVC-01', 'P-PVC-01', 'COMMISSION',      'PVC',        400, 2012,  60.000, TIMESTAMP '2012-07-01 00:00:00', NULL,                 false, 10, 'archive://cohort/asset/P-PVC-01/as-built-2012.pdf'),
 (5, 'P-PVC-02', 'P-PVC-02', 'COMMISSION',      'PVC',        400, 2015,  60.000, TIMESTAMP '2015-05-01 00:00:00', NULL,                 false, 10, 'archive://cohort/asset/P-PVC-02/as-built-2015.pdf'),
-- P-RCP-04：1992 投用钢筋混凝土，2018 内衬 CIPP；旧区间封口，新区间年龄重置
 (6, 'P-RCP-04', 'P-RCP-04', 'COMMISSION',      '钢筋混凝土', 800, 1992,  90.000, TIMESTAMP '1992-04-01 00:00:00', TIMESTAMP '2018-08-15 00:00:00', false, 10, 'archive://cohort/asset/P-RCP-04/as-built-1992.pdf'),
 (7, 'P-RCP-04', 'P-RCP-04', 'RELINER',         'CIPP内衬',   800, 2018,  90.000, TIMESTAMP '2018-08-15 00:00:00', NULL,                  true, 10, 'archive://cohort/asset/P-RCP-04/reliner-2018.pdf'),
-- P-RCP-05：2000 投用，2019 整段换管；旧区间封口，新管年龄从 0 起算
 (8, 'P-RCP-05', 'P-RCP-05', 'COMMISSION',      '钢筋混凝土', 800, 2000,  70.000, TIMESTAMP '2000-10-01 00:00:00', TIMESTAMP '2019-06-20 00:00:00', false, 10, 'archive://cohort/asset/P-RCP-05/as-built-2000.pdf'),
 (9, 'P-RCP-05', 'P-RCP-05', 'PIPE_REPLACEMENT','钢筋混凝土', 800, 2019,  70.000, TIMESTAMP '2019-06-20 00:00:00', NULL,                  true, 10, 'archive://cohort/asset/P-RCP-05/replacement-2019.pdf')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 检查暴露（有观察范围的检测时段）
INSERT INTO app_inspection_exposure
 (id, physical_pipe_code, segment_code, batch_code, inspected_at, station_start_m, station_end_m,
  coverage_status, obscured_reason, risk_zone, source_version_id, evidence_ref) VALUES
-- P-RCP-01 普通街区：一轮全覆盖
 (1, 'P-RCP-01', 'P-RCP-01', 'B-C2024-04', TIMESTAMP '2024-04-10 09:00:00',  0.000, 100.000, 'OBSERVED', NULL,           'NORMAL',    11, 'archive://cohort/inspection/P-RCP-01/2024/video'),
-- P-RCP-02 高风险街区：连续三年三轮检查（选择性过度检查）
 (2, 'P-RCP-02', 'P-RCP-02', 'B-C2023-05', TIMESTAMP '2023-05-12 09:00:00',  0.000, 100.000, 'OBSERVED', NULL,           'HIGH_RISK', 11, 'archive://cohort/inspection/P-RCP-02/2023/video'),
 (3, 'P-RCP-02', 'P-RCP-02', 'B-C2024-06', TIMESTAMP '2024-06-18 09:00:00',  0.000, 100.000, 'OBSERVED', NULL,           'HIGH_RISK', 11, 'archive://cohort/inspection/P-RCP-02/2024/video'),
 (4, 'P-RCP-02', 'P-RCP-02', 'B-C2025-07', TIMESTAMP '2025-07-08 09:00:00',  0.000, 100.000, 'OBSERVED', NULL,           'HIGH_RISK', 11, 'archive://cohort/inspection/P-RCP-02/2025/video'),
-- P-RCP-03 普通街区：仅 0-20m 受检；30-50m 遮挡；20-30 与 50-80 从未检查
 (5, 'P-RCP-03', 'P-RCP-03', 'B-C2025-08', TIMESTAMP '2025-08-20 09:00:00',  0.000,  20.000, 'OBSERVED', NULL,           'NORMAL',    11, 'archive://cohort/inspection/P-RCP-03/2025/video-part1'),
 (6, 'P-RCP-03', 'P-RCP-03', 'B-C2025-08', TIMESTAMP '2025-08-20 09:30:00', 30.000,  50.000, 'OBSCURED', '管内水位超过管径2/3，影像无法判读', 'NORMAL', 11, 'archive://cohort/inspection/P-RCP-03/2025/obscured-log'),
-- P-PVC-01 普通街区：2021 年一轮全覆盖、无缺陷（管龄 9 年，与高风险 P-PVC-02 同处 0-9 年龄层，
-- 形成“同层不同选检概率”的对照，使 IPW 与 NAIVE 口径产生可验证差异）
 (7, 'P-PVC-01', 'P-PVC-01', 'B-C2021-10', TIMESTAMP '2021-10-15 09:00:00',  0.000,  60.000, 'OBSERVED', NULL,           'NORMAL',    11, 'archive://cohort/inspection/P-PVC-01/2021/video'),
-- P-PVC-02 高风险街区：两轮检查
 (8, 'P-PVC-02', 'P-PVC-02', 'B-C2023-09', TIMESTAMP '2023-09-06 09:00:00',  0.000,  60.000, 'OBSERVED', NULL,           'HIGH_RISK', 11, 'archive://cohort/inspection/P-PVC-02/2023/video'),
 (9, 'P-PVC-02', 'P-PVC-02', 'B-C2025-09', TIMESTAMP '2025-09-11 09:00:00',  0.000,  60.000, 'OBSERVED', NULL,           'HIGH_RISK', 11, 'archive://cohort/inspection/P-PVC-02/2025/video'),
-- P-RCP-04 内衬后：2024 检查落在 CIPP 内衬区间
 (10, 'P-RCP-04', 'P-RCP-04', 'B-C2024-11', TIMESTAMP '2024-11-05 09:00:00',  0.000,  90.000, 'OBSERVED', NULL,           'NORMAL',    11, 'archive://cohort/inspection/P-RCP-04/2024/video'),
-- P-RCP-05 换管后：2021 检查落在新管区间
 (11, 'P-RCP-05', 'P-RCP-05', 'B-C2021-07', TIMESTAMP '2021-07-22 09:00:00',  0.000,  70.000, 'OBSERVED', NULL,           'NORMAL',    11, 'archive://cohort/inspection/P-RCP-05/2021/video')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 缺陷事件与维修
INSERT INTO app_defect_event
 (id, physical_pipe_code, segment_code, event_kind, original_event_id, defect_type, normalized_grade,
  station_start_m, station_end_m, found_at, exposure_key, repair_id, repaired_at, repair_method,
  post_repair_status, source_version_id, evidence_ref) VALUES
-- P-RCP-01：裂缝，修复后未复发
 (1, 'P-RCP-01', 'P-RCP-01', 'FIRST_FINDING', NULL, 'CRACK', 2, 20.000, 22.000, TIMESTAMP '2024-04-10 10:00:00',
  'P-RCP-01|1995|[20,22]', 'RP-2024-007', TIMESTAMP '2024-09-02 00:00:00', '环氧局部修补', 'REPAIRED_NO_DEFECT', 12, 'archive://cohort/defect/P-RCP-01/DE-001'),
-- P-RCP-02：渗漏首次发现并修复，2025 原位复发（复发行关联原事件与同一 exposure_key）
 (2, 'P-RCP-02', 'P-RCP-02', 'FIRST_FINDING', NULL, 'LEAK', 3, 40.000, 41.500, TIMESTAMP '2023-05-12 10:00:00',
  'P-RCP-02|1998|[40,41.5]', 'RP-2023-015', TIMESTAMP '2023-11-20 00:00:00', '注浆止水', 'REPAIRED_RECURRED', 12, 'archive://cohort/defect/P-RCP-02/DE-002'),
 (3, 'P-RCP-02', 'P-RCP-02', 'RECURRENCE', 2, 'LEAK', 3, 40.000, 41.500, TIMESTAMP '2025-07-08 10:00:00',
  'P-RCP-02|1998|[40,41.5]', 'RP-2025-011', TIMESTAMP '2025-10-09 00:00:00', '二次注浆+不锈钢套环', 'REPAIRED_NO_DEFECT', 12, 'archive://cohort/defect/P-RCP-02/DE-003'),
-- P-RCP-02：接口错位，至今未修复
 (4, 'P-RCP-02', 'P-RCP-02', 'FIRST_FINDING', NULL, 'JOINT_DISLOCATION', 3, 60.000, 60.500, TIMESTAMP '2024-06-18 11:00:00',
  'P-RCP-02|1998|[60,60.5]', NULL, NULL, NULL, 'STILL_OPEN', 12, 'archive://cohort/defect/P-RCP-02/DE-004'),
-- P-RCP-03：受检段内裂缝（遮挡段与未检查段不得计为无缺陷）
 (5, 'P-RCP-03', 'P-RCP-03', 'FIRST_FINDING', NULL, 'CRACK', 2, 10.000, 11.000, TIMESTAMP '2025-08-20 10:00:00',
  'P-RCP-03|2005|[10,11]', NULL, NULL, NULL, 'STILL_OPEN', 12, 'archive://cohort/defect/P-RCP-03/DE-005'),
-- P-PVC-02：变形，修复后未复发
 (6, 'P-PVC-02', 'P-PVC-02', 'FIRST_FINDING', NULL, 'DEFORMATION', 3, 12.000, 13.000, TIMESTAMP '2023-09-06 10:00:00',
  'P-PVC-02|2015|[12,13]', 'RP-2024-003', TIMESTAMP '2024-02-18 00:00:00', '不锈钢发泡筒', 'REPAIRED_NO_DEFECT', 12, 'archive://cohort/defect/P-PVC-02/DE-006'),
-- P-RCP-04：内衬层上的裂缝，归属 2018 起算的 CIPP 区间（管龄 6 年而非 32 年）
 (7, 'P-RCP-04', 'P-RCP-04', 'FIRST_FINDING', NULL, 'CRACK', 2, 30.000, 31.200, TIMESTAMP '2024-11-05 10:00:00',
  'P-RCP-04|2018|[30,31.2]', NULL, NULL, NULL, 'STILL_OPEN', 12, 'archive://cohort/defect/P-RCP-04/DE-007'),
-- P-RCP-05：换管后新管上的渗漏，归属 2019 起算的新管区间
 (8, 'P-RCP-05', 'P-RCP-05', 'FIRST_FINDING', NULL, 'LEAK', 2, 50.000, 51.000, TIMESTAMP '2021-07-22 10:00:00',
  'P-RCP-05|2019|[50,51]', NULL, NULL, NULL, 'STILL_OPEN', 12, 'archive://cohort/defect/P-RCP-05/DE-008')
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('app_source_version','id'),       (SELECT max(id) FROM app_source_version));
SELECT setval(pg_get_serial_sequence('app_asset_history','id'),        (SELECT max(id) FROM app_asset_history));
SELECT setval(pg_get_serial_sequence('app_inspection_exposure','id'),  (SELECT max(id) FROM app_inspection_exposure));
SELECT setval(pg_get_serial_sequence('app_defect_event','id'),        (SELECT max(id) FROM app_defect_event));
