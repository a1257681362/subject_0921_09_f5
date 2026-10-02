package com.evops.credit.controller;

import com.evops.common.ApiResponse;
import com.evops.credit.dto.PaymentDetailView;
import com.evops.credit.dto.PaymentRegisterRequest;
import com.evops.credit.dto.WriteoffRequest;
import com.evops.credit.entity.Payment;
import com.evops.credit.service.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 回款：登记（财务/系统）、按单核销（部分回款分次核）、翻查。 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 回款登记：归属月随回款日固化，凭证号留底。 */
    @PostMapping
    public ApiResponse<Payment> register(@Valid @RequestBody PaymentRegisterRequest request) {
        return ApiResponse.ok(paymentService.register(request));
    }

    /** 按单核销：一笔回款核到若干上刊单；核多少释放多少额度。 */
    @PostMapping("/{paymentNo}/writeoff")
    public ApiResponse<PaymentDetailView> writeoff(@PathVariable String paymentNo,
                                                   @Valid @RequestBody WriteoffRequest request) {
        return ApiResponse.ok(paymentService.writeoff(paymentNo, request));
    }

    /** 回款单详情：单头 + 核销流水 + 未核销余额。 */
    @GetMapping("/{paymentNo}")
    public ApiResponse<PaymentDetailView> detail(@PathVariable String paymentNo,
                                                 @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(paymentService.detail(paymentNo, operatorName));
    }

    /** 回款翻查：?customerNo=&period=yyyy-MM；销售仅名下客户。 */
    @GetMapping
    public ApiResponse<List<Payment>> list(@RequestParam(required = false) String customerNo,
                                           @RequestParam(required = false) String period,
                                           @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(paymentService.list(customerNo, period, operatorName));
    }
}
