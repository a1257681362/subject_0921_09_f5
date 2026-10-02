package com.evops.credit.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class CustomerCreateRequest {
    /** 客户名称，唯一 */
    @NotBlank(message = "客户名称不能为空")
    private String customerName;
    /** 初始信用额度（建档即给，记 v1 INITIAL 版本） */
    @NotNull(message = "初始信用额度不能为空")
    @DecimalMin(value = "0", message = "信用额度不能为负")
    private BigDecimal creditLimit;
    /** 归属销售：销售建档只能是自己；财务/系统可指定名下销售 */
    private String ownerSales;
    private String contact;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
