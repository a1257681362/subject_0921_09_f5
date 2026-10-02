package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 上刊占用明细：一张上刊单一条，占用金额即待结金额。
 * 落架不释放——占用一直挂到回款核销（SETTLED）或对冲改错（RELEASED）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_credit_occupancy")
public class CreditOccupancy extends BaseEntity {
    private Long customerId;
    private String customerNo;
    /** 占用的上刊单（一对一） */
    private Long orderId;
    private String orderNo;
    /** 占用金额 */
    private BigDecimal amount;
    /** 已核销金额（分次核累加） */
    private BigDecimal settledAmount;
    /** 状态：OCCUPYING 占用中 / SETTLED 已核销 / RELEASED 已释放 */
    private String status;
    /** 归属月，随上刊单固化，月底拉明细按它过滤 */
    private String bizPeriod;
    private LocalDate occurDate;
    private LocalDateTime occupyTime;
    private LocalDateTime releaseTime;
    private String releaseNote;
}
