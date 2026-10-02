package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 额度调整审批：额度动一动要走审批，通过才换版。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_credit_adjust_apply")
public class CreditAdjustApply extends BaseEntity {
    /** 申请单号，CA+序列，全局唯一 */
    private String applyNo;
    private Long customerId;
    /** 申请时当前额度快照 */
    private BigDecimal limitBefore;
    /** 申请调整到的目标额度 */
    private BigDecimal limitAfter;
    /** 调整原因（必填） */
    private String reason;
    /** 状态：PENDING 待审批 / APPROVED 已通过 / REJECTED 已驳回 */
    private String status;
    private String applicantName;
    private String approverName;
    private LocalDateTime approveTime;
    private String approveRemark;
}
