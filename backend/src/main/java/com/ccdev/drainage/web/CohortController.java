package com.ccdev.drainage.web;

import com.ccdev.drainage.auth.AuthInterceptor;
import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.domain.Models.AssetHistory;
import com.ccdev.drainage.domain.Models.DefectEvent;
import com.ccdev.drainage.domain.Models.InspectionExposure;
import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.repo.CohortRepository;
import com.ccdev.drainage.repo.UserRepository;
import com.ccdev.drainage.service.CohortService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 材料/年代队列统计数据产品接口。
 * 读接口暴露沿革、暴露、事件、版本与运行结果；写接口（补录/撤销/登记）全部产生
 * 源数据新版本并只重算受影响队列，历史运行结果以 SUPERSEDED 状态保留。
 */
@RestController
@RequestMapping("/api/cohorts")
public class CohortController {

    private final CohortService cohortService;
    private final CohortRepository cohortRepo;
    private final UserRepository userRepo;

    public CohortController(CohortService cohortService, CohortRepository cohortRepo,
                            UserRepository userRepo) {
        this.cohortService = cohortService;
        this.cohortRepo = cohortRepo;
        this.userRepo = userRepo;
    }

    // ------------------------------------------------------------ 源数据与版本
    @GetMapping("/versions")
    public List<?> versions(@RequestParam String domain) {
        return cohortService.versions(domain);
    }

    @GetMapping("/asset-history")
    public List<?> assetHistory(@RequestParam(required = false) String pipeCode) {
        return cohortService.assetHistory(pipeCode);
    }

    @GetMapping("/exposures")
    public List<?> exposures() {
        return cohortService.exposures();
    }

    @GetMapping("/defect-events")
    public List<?> defectEvents() {
        return cohortService.defectEvents();
    }

    // ------------------------------------------------------------ 队列运行
    @GetMapping("/runs")
    public List<?> runs() {
        return cohortRepo.findAllRuns();
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable Long id) {
        return cohortService.runDetail(id);
    }

    @GetMapping("/runs/{id}/evidence")
    public List<?> evidence(@PathVariable Long id,
                            @RequestParam(required = false) String cellKey) {
        return cellKey == null || cellKey.isBlank()
                ? cohortRepo.findEvidenceByRun(id)
                : cohortRepo.findEvidenceByRunAndCell(id, cellKey);
    }

    /** 生成队列运行：estimationMethod=NAIVE（默认）或 IPW，基准日与年龄层宽度可调。 */
    @PostMapping("/runs")
    public Map<String, Object> createRun(@Valid @RequestBody Requests.CohortRunRequest req,
                                         HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        String method = req.estimationMethod() == null ? "NAIVE" : req.estimationMethod();
        LocalDate asOf = req.asOfDate();
        Map<String, Object> result = cohortService.runCohort(method, asOf, req.ageBandYears(), user.id());
        userRepo.audit(user.id(), "COHORT_RUN", "run:" + result.get("code"),
                "口径=" + result.get("method") + " 基准日=" + asOf + " 单元格=" + result.get("cells"));
        return result;
    }

    /** 队列对比：同键单元格并排（典型用法：同一基准日的 NAIVE vs IPW）。 */
    @PostMapping("/compare")
    public Map<String, Object> compare(@Valid @RequestBody Requests.CohortCompareRequest req) {
        return cohortService.compareRuns(req.runIds());
    }

    // ------------------------------------------------------------ 补录 / 撤销 / 登记
    @PostMapping("/asset-history")
    public Map<String, Object> addAssetHistory(@Valid @RequestBody Requests.AssetHistoryRequest req,
                                               HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        Map<String, Object> result = cohortService.registerAssetHistory(new AssetHistory(null,
                req.physicalPipeCode(), req.segmentCode(), req.eventType(), req.material(),
                req.diameterMm(), req.commissionYear(), req.lengthM(), req.effectiveFrom(),
                req.effectiveTo(), req.ageReset(), null, req.evidenceRef(), null), user.id());
        userRepo.audit(user.id(), "COHORT_ASSET_ADD", "history:" + result.get("assetHistoryId"),
                req.physicalPipeCode() + " " + req.eventType() + " 生效 " + req.effectiveFrom());
        return result;
    }

    /** 补录换管/内衬/材料变更日期：封口旧区间、另起年龄重置区间，并重算受影响队列。 */
    @PostMapping("/backfill-replacement")
    public Map<String, Object> backfill(@Valid @RequestBody Requests.BackfillRequest req,
                                        HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        Map<String, Object> result = cohortService.backfillReplacement(req.physicalPipeCode(),
                req.eventType(), req.effectiveDate(), req.material(), req.diameterMm(), req.lengthM(),
                req.evidenceRef(), req.note(), user.id());
        userRepo.audit(user.id(), "COHORT_BACKFILL", "pipe:" + req.physicalPipeCode(),
                req.eventType() + " 日期 " + req.effectiveDate() + " 重算运行 " + result.get("rerunRuns"));
        return result;
    }

    @PostMapping("/exposures")
    public Map<String, Object> addExposure(@Valid @RequestBody Requests.ExposureRequest req,
                                           HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        Map<String, Object> result = cohortService.registerExposure(new InspectionExposure(null,
                req.physicalPipeCode(), req.segmentCode(), req.batchCode(), req.inspectedAt(),
                req.stationStartM(), req.stationEndM(), req.coverageStatus(), req.obscuredReason(),
                req.riskZone(), null, "ACTIVE", null, req.evidenceRef()), user.id());
        userRepo.audit(user.id(), "COHORT_EXPOSURE_ADD", "exposure:" + result.get("exposureId"),
                req.physicalPipeCode() + " " + req.batchCode() + " " + req.coverageStatus());
        return result;
    }

    /** 撤销一次观察：逻辑留痕，受影响队列重算，旧运行保留为 SUPERSEDED。 */
    @PostMapping("/exposures/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable Long id,
                                      @Valid @RequestBody Requests.RevokeExposureRequest req,
                                      HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        Map<String, Object> result = cohortService.revokeObservation(id, req.reason(), user.id());
        userRepo.audit(user.id(), "COHORT_EXPOSURE_REVOKE", "exposure:" + id,
                "原因=" + req.reason() + " 重算运行 " + result.get("rerunRuns"));
        return result;
    }

    @PostMapping("/defect-events")
    public Map<String, Object> addDefectEvent(@Valid @RequestBody Requests.DefectEventRequest req,
                                              HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        Map<String, Object> result = cohortService.registerDefectEvent(new DefectEvent(null,
                req.physicalPipeCode(), req.segmentCode(), req.eventKind(), req.originalEventId(),
                req.defectType(), req.normalizedGrade(), req.stationStartM(), req.stationEndM(),
                req.foundAt(), req.exposureKey(), req.repairId(), req.repairedAt(), req.repairMethod(),
                req.postRepairStatus(), null, req.evidenceRef()), user.id());
        userRepo.audit(user.id(), "COHORT_DEFECT_ADD", "event:" + result.get("defectEventId"),
                req.physicalPipeCode() + " " + req.eventKind() + " " + req.defectType());
        return result;
    }
}
