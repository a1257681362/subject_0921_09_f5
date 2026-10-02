package com.evops.credit.dto;

import com.evops.credit.entity.Payment;
import com.evops.credit.entity.PaymentWriteoff;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 回款单详情：单头 + 已核销流水 + 未核销余额。 */
@Data
public class PaymentDetailView {
    private Payment payment;
    private List<PaymentWriteoff> writeoffs;
    /** 未核销余额 = 回款金额 - 已核销 */
    private BigDecimal unallocatedAmount;
}
