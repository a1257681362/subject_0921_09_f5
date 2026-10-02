package com.evops.credit.controller;

import com.evops.common.ApiResponse;
import com.evops.credit.dto.ApproveRequest;
import com.evops.credit.entity.CreditAdjustApply;
import com.evops.credit.service.CreditLimitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 额度调整审批：审批单翻查、通过、驳回。审批仅财务/系统，且不能审自己的申请。 */
@RestController
@RequestMapping("/api/credit/applies")
public class CreditApplyController {

    private final CreditLimitService creditLimitService;

    public CreditApplyController(CreditLimitService creditLimitService) {
        this.creditLimitService = creditLimitService;
    }

    /** 审批单翻查：?status=PENDING/APPROVED/REJECTED；销售只看自己提交的。 */
    @GetMapping
    public ApiResponse<List<CreditAdjustApply>> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(creditLimitService.listApplies(status, operatorName));
    }

    /** 审批通过：换版留前后值；目标额度低于当前占用会被拦下。 */
    @PostMapping("/{applyNo}/approve")
    public ApiResponse<CreditAdjustApply> approve(@PathVariable String applyNo,
                                                  @RequestBody(required = false) ApproveRequest request) {
        return ApiResponse.ok(creditLimitService.approve(applyNo, request != null ? request : new ApproveRequest()));
    }

    /** 驳回：留审批意见，额度不动。 */
    @PostMapping("/{applyNo}/reject")
    public ApiResponse<CreditAdjustApply> reject(@PathVariable String applyNo,
                                                 @RequestBody(required = false) ApproveRequest request) {
        return ApiResponse.ok(creditLimitService.reject(applyNo, request != null ? request : new ApproveRequest()));
    }
}
