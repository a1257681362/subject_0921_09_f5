package com.evops.credit.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** 按单核销：一笔回款核到若干上刊单上，每单核多少逐行给。 */
@Data
public class WriteoffRequest {

    /** 核销行：单号 + 本次核销金额 */
    @Data
    public static class Line {
        @NotBlank(message = "核销行的上刊单号不能为空")
        private String orderNo;
        @NotNull(message = "核销金额不能为空")
        @DecimalMin(value = "0.01", message = "核销金额必须大于 0")
        private BigDecimal amount;
    }

    @NotEmpty(message = "至少要核销一行")
    @Valid
    private List<Line> lines;

    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}
