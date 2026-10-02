package com.evops.credit.dto;

import com.evops.credit.entity.Customer;
import lombok.Data;

import java.math.BigDecimal;

/** 客户视图：档案 + 实时可用额度 + 当前额度版本号。 */
@Data
public class CustomerView {
    private Customer customer;
    /** 可用额度 = 信用额度 - 当前占用 */
    private BigDecimal availableAmount;
    /** 当前生效的信用额度版本号 */
    private Integer currentVersionNo;
}
