package com.evops.credit.dto;

import lombok.Data;

/** 信用额度状态变更（冻结/解冻）。 */
@Data
public class CreditStatusRequest {
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    /** 状态变更原因 */
    private String reason;
}
