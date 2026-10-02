package com.evops.credit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 客户档案：建档即给信用额度；可用额度 = creditLimit - occupiedAmount。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_customer")
public class Customer extends BaseEntity {
    /** 客户编号，CU+序列，全局唯一 */
    private String customerNo;
    /** 客户名称，唯一，同名不许重复建档 */
    private String customerName;
    /** 归属销售（登录账号名）；销售只能看自己名下的客户 */
    private String ownerSales;
    /** 联系方式 */
    private String contact;
    /** 当前生效信用额度 */
    private BigDecimal creditLimit;
    /** 当前占用合计（条件更新防并发超占） */
    private BigDecimal occupiedAmount;
    /** 信用额度状态：NORMAL 正常 / FROZEN 冻结 */
    private String creditStatus;
    /** 建档经手人 */
    private String operatorName;
    private String remark;
}
