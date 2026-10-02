package com.evops.credit.controller;

import com.evops.common.ApiResponse;
import com.evops.credit.dto.OccupancyReportView;
import com.evops.credit.service.CreditReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** 月底占用明细：财务拉全量、销售只看名下；支持整页导出 CSV。 */
@RestController
@RequestMapping("/api/credit/reports")
public class CreditReportController {

    private final CreditReportService reportService;

    public CreditReportController(CreditReportService reportService) {
        this.reportService = reportService;
    }

    /** 每户占用明细：?period=yyyy-MM&customerNo=（可选）。 */
    @GetMapping("/occupancy")
    public ApiResponse<OccupancyReportView> occupancy(@RequestParam String period,
                                                      @RequestParam(required = false) String customerNo,
                                                      @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(reportService.occupancyReport(period, customerNo, operatorName));
    }

    /** 占用明细导出 CSV（带 BOM，Excel 打开不乱码）。 */
    @GetMapping("/occupancy/export")
    public ResponseEntity<byte[]> occupancyExport(@RequestParam String period,
                                                  @RequestParam(required = false) String customerNo,
                                                  @RequestParam(required = false) String operatorName) {
        String csv = reportService.occupancyCsv(period, customerNo, operatorName);
        String filename = "occupancy-" + period + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
