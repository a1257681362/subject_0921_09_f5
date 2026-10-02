package com.evops.credit.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 回款登记。 */
@Data
public class PaymentRegisterRequest {
    /** 客户编号 */
    @NotBlank(message = "客户编号不能为空")
    private String customerNo;
    /** 回款金额 */
    @NotNull(message = "回款金额不能为空")
    @DecimalMin(value = "0.01", message = "回款金额必须大于 0")
    private BigDecimal amount;
    /** 回款日期，留空取今天；归属月随它固化 */
    private LocalDate receivedDate;
    /** 回款凭证号（银行回单号等） */
    private String voucherNo;
    /** 回款方式 */
    private String method;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
