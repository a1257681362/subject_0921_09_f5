package com.evops.credit.dto;

import com.evops.credit.entity.CreditOccupancy;
import com.evops.credit.entity.Customer;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 月底占用明细：每户的额度/占用/可用快照 + 期内占用明细行。 */
@Data
public class OccupancyReportView {

    private String period;
    private List<CustomerOccupancy> customers;

    @Data
    public static class CustomerOccupancy {
        private Customer customer;
        /** 可用额度 = 信用额度 - 当前占用 */
        private BigDecimal availableAmount;
        /** 期内发生的占用明细行 */
        private List<CreditOccupancy> lines;
        /** 期内占用合计 */
        private BigDecimal periodOccupied;
        /** 期内行已核销合计 */
        private BigDecimal periodSettled;
        /** 期内行未核销合计 */
        private BigDecimal periodOutstanding;
    }
}
