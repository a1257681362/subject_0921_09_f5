package com.evops.credit.service;

import com.evops.common.BusinessException;
import com.evops.credit.CreditEnums;
import com.evops.credit.dto.CustomerView;
import com.evops.credit.dto.OccupancyReportView;
import com.evops.credit.entity.CreditOccupancy;
import com.evops.credit.entity.Customer;
import com.evops.credit.support.SecurityHelper;
import com.evops.ledger.support.Operators;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 月底占用明细：财务拉全量、销售只看名下。
 * 每户给出额度/占用/可用快照 + 该归属月发生的占用明细行（含已核/未核）。
 */
@Service
public class CreditReportService {

    private final CustomerService customerService;
    private final CreditOccupancyService occupancyService;

    public CreditReportService(CustomerService customerService,
                               CreditOccupancyService occupancyService) {
        this.customerService = customerService;
        this.occupancyService = occupancyService;
    }

    /** 每户占用明细：period 必填（yyyy-MM）；customerNo 可选，指定后只拉一户。 */
    public OccupancyReportView occupancyReport(String period, String customerNo, String operatorName) {
        String operator = Operators.resolve(operatorName);
        String identity = SecurityHelper.currentIdentity(operator);
        String normalizedPeriod = requirePeriod(period);

        List<Customer> customers = resolveCustomers(customerNo, identity);
        List<Long> customerIds = customers.stream().map(Customer::getId).collect(Collectors.toList());
        Map<Long, List<CreditOccupancy>> linesByCustomer = occupancyService
                .listByPeriod(normalizedPeriod, customerIds).stream()
                .collect(Collectors.groupingBy(CreditOccupancy::getCustomerId,
                        LinkedHashMap::new, Collectors.toList()));

        OccupancyReportView view = new OccupancyReportView();
        view.setPeriod(normalizedPeriod);
        List<OccupancyReportView.CustomerOccupancy> rows = new ArrayList<>();
        for (Customer customer : customers) {
            OccupancyReportView.CustomerOccupancy row = new OccupancyReportView.CustomerOccupancy();
            row.setCustomer(customer);
            row.setAvailableAmount(customer.getCreditLimit().subtract(customer.getOccupiedAmount()));
            List<CreditOccupancy> lines = linesByCustomer.getOrDefault(customer.getId(), new ArrayList<>());
            row.setLines(lines);
            BigDecimal occupied = BigDecimal.ZERO;
            BigDecimal settled = BigDecimal.ZERO;
            for (CreditOccupancy line : lines) {
                occupied = occupied.add(line.getAmount());
                settled = settled.add(line.getSettledAmount());
            }
            row.setPeriodOccupied(occupied);
            row.setPeriodSettled(settled);
            row.setPeriodOutstanding(occupied.subtract(settled));
            rows.add(row);
        }
        view.setCustomers(rows);
        return view;
    }

    /** 占用明细 CSV（带 BOM，Excel 打开不乱码）：每行一条期内占用明细。 */
    public String occupancyCsv(String period, String customerNo, String operatorName) {
        OccupancyReportView report = occupancyReport(period, customerNo, operatorName);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("归属月,客户编号,客户名称,归属销售,上刊单号,占用金额,已核销,未核销,占用状态,发生日,结清/释放时间,说明\n");
        for (OccupancyReportView.CustomerOccupancy row : report.getCustomers()) {
            Customer c = row.getCustomer();
            for (CreditOccupancy line : row.getLines()) {
                csv.append(report.getPeriod()).append(',')
                        .append(escape(c.getCustomerNo())).append(',')
                        .append(escape(c.getCustomerName())).append(',')
                        .append(escape(c.getOwnerSales())).append(',')
                        .append(escape(line.getOrderNo())).append(',')
                        .append(line.getAmount().toPlainString()).append(',')
                        .append(line.getSettledAmount().toPlainString()).append(',')
                        .append(line.getAmount().subtract(line.getSettledAmount()).toPlainString()).append(',')
                        .append(occupancyStatusLabel(line.getStatus())).append(',')
                        .append(line.getOccurDate()).append(',')
                        .append(line.getReleaseTime() == null ? "" : line.getReleaseTime().toString()).append(',')
                        .append(escape(line.getReleaseNote()))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    public static String occupancyStatusLabel(String status) {
        switch (status) {
            case CreditEnums.OCC_OCCUPYING: return "占用中";
            case CreditEnums.OCC_SETTLED: return "已核销";
            case CreditEnums.OCC_RELEASED: return "已释放(对冲)";
            default: return status;
        }
    }

    // ---- 内部方法 ----

    private List<Customer> resolveCustomers(String customerNo, String identity) {
        if (customerNo != null && !customerNo.trim().isEmpty()) {
            Customer customer = customerService.requireByNo(customerNo);
            SecurityHelper.requireCustomerAccess(identity, customer);
            List<Customer> one = new ArrayList<>();
            one.add(customer);
            return one;
        }
        return customerService.search(null, null, identity).stream()
                .map(CustomerView::getCustomer).collect(Collectors.toList());
    }

    private String requirePeriod(String period) {
        if (period == null || !period.trim().matches("\\d{4}-\\d{2}")) {
            throw new BusinessException("归属月必填且格式为 yyyy-MM，例如 2026-10");
        }
        return period.trim();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
