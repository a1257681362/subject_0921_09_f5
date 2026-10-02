package com.evops.credit.dto;

import lombok.Data;

/** 额度调整审批动作（通过 / 驳回）。 */
@Data
public class ApproveRequest {
    /** 审批人，留空取当前登录账号；不能是申请人本人 */
    private String operatorName;
    /** 审批意见（驳回时填驳回原因） */
    private String remark;
}
