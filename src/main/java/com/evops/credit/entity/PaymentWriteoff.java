package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 核销明细：回款 × 占用单的多对多流水，部分回款分次核全靠它。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_payment_writeoff")
public class PaymentWriteoff extends BaseEntity {
    private Long paymentId;
    private String paymentNo;
    private Long occupancyId;
    private String orderNo;
    private Long customerId;
    /** 本次核销金额 */
    private BigDecimal amount;
    private String operatorName;
}
