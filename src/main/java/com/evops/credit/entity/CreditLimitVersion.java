package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 信用额度版本：每次额度变动记一版，前后值都留，历史永久可翻。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_credit_limit_version")
public class CreditLimitVersion extends BaseEntity {
    private Long customerId;
    /** 版本号，客户内单调递增，v1 为建档初始 */
    private Integer versionNo;
    /** 来源：INITIAL 建档初始 / ADJUST 调整审批 */
    private String source;
    /** 调整前额度（前后值都留） */
    private BigDecimal limitBefore;
    /** 调整后额度 */
    private BigDecimal limitAfter;
    /** 来源为 ADJUST 时的审批单 */
    private Long applyId;
    private String applyNo;
    /** 版本状态：EFFECTIVE 生效中 / SUPERSEDED 已被新版取代 */
    private String status;
    /** 经办人（建档人或调整申请人） */
    private String operatorName;
    /** 审批人（INITIAL 版为空） */
    private String approverName;
    private LocalDateTime approveTime;
    private String remark;
}
