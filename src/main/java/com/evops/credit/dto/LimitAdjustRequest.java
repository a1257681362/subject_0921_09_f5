package com.evops.credit.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 额度调整申请：额度动一动要走审批。 */
@Data
public class LimitAdjustRequest {
    /** 申请调整到的目标额度 */
    @NotNull(message = "目标额度不能为空")
    @DecimalMin(value = "0", message = "目标额度不能为负")
    private BigDecimal limitAfter;
    /** 调整原因 */
    @NotBlank(message = "调整原因不能为空")
    private String reason;
    /** 申请人，留空取当前登录账号 */
    private String operatorName;
}
