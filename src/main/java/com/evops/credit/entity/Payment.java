package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 回款单：登记后按单核销；allocatedAmount 记已核销部分，余额可分次再核。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_payment")
public class Payment extends BaseEntity {
    /** 回款单号，PM+序列，全局唯一 */
    private String paymentNo;
    private Long customerId;
    private String customerNo;
    /** 回款金额 */
    private BigDecimal amount;
    /** 已核销出去的金额 */
    private BigDecimal allocatedAmount;
    /** 回款日期，归属月随它固化 */
    private LocalDate receivedDate;
    private String bizPeriod;
    /** 回款凭证号（银行回单号等） */
    private String voucherNo;
    /** 回款方式（对公转账/承兑等） */
    private String method;
    private String operatorName;
    private String remark;
}
