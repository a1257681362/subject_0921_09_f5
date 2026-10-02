package com.evops.ledger.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class OrderCreateRequest {
    /** 点位编号 */
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 画面名称 */
    @NotBlank(message = "画面名称不能为空")
    private String creativeName;
    /** 客户编号：上刊即占用该客户信用额度（F5 起必填） */
    @NotBlank(message = "客户编号不能为空：上刊单必须挂客户，先上刊后结钱也要落在授信上")
    private String customerNo;
    /** 占用金额：上刊占用的信用额度，即该单待结金额（F5 起必填） */
    @NotNull(message = "占用金额不能为空：上刊即占用客户信用额度")
    @DecimalMin(value = "0.01", message = "占用金额必须大于 0")
    private BigDecimal occupyAmount;
    /** 上刊发生日，决定所在周与归属月；留空取今天 */
    private LocalDate occurDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
