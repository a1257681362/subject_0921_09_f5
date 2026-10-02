package com.evops.credit.controller;

import com.evops.common.ApiResponse;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.dto.CustomerView;
import com.evops.credit.dto.CreditStatusRequest;
import com.evops.credit.dto.LimitAdjustRequest;
import com.evops.credit.entity.CreditAdjustApply;
import com.evops.credit.entity.CreditLimitVersion;
import com.evops.credit.entity.CreditOccupancy;
import com.evops.credit.entity.Customer;
import com.evops.credit.service.CreditLimitService;
import com.evops.credit.service.CreditOccupancyService;
import com.evops.credit.service.CustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/** 客户档案与信用额度：建档给额度、翻查（销售仅名下）、冻结/解冻、额度版本与调整申请。 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final CreditLimitService creditLimitService;
    private final CreditOccupancyService occupancyService;

    public CustomerController(CustomerService customerService,
                              CreditLimitService creditLimitService,
                              CreditOccupancyService occupancyService) {
        this.customerService = customerService;
        this.creditLimitService = creditLimitService;
        this.occupancyService = occupancyService;
    }

    /** 建档：初始额度直接生效并记 v1 版本；同名客户不许重复建档。 */
    @PostMapping
    public ApiResponse<Customer> create(@Valid @RequestBody CustomerCreateRequest request) {
        return ApiResponse.ok(customerService.create(request));
    }

    /** 翻查：?keyword=&ownerSales=&operatorName=；销售只看自己名下。 */
    @GetMapping
    public ApiResponse<List<CustomerView>> list(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String ownerSales,
                                                @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(customerService.search(keyword, ownerSales, operatorName));
    }

    /** 详情：档案 + 可用额度 + 当前额度版本号。 */
    @GetMapping("/{customerNo}")
    public ApiResponse<CustomerView> detail(@PathVariable String customerNo,
                                            @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(customerService.detail(customerNo, operatorName));
    }

    /** 冻结信用额度：拦截新上刊占用，回款核销照走。仅财务/系统。 */
    @PostMapping("/{customerNo}/freeze")
    public ApiResponse<Customer> freeze(@PathVariable String customerNo,
                                        @RequestBody(required = false) CreditStatusRequest request) {
        CreditStatusRequest body = request != null ? request : new CreditStatusRequest();
        return ApiResponse.ok(customerService.freeze(customerNo, body.getOperatorName(), body.getReason()));
    }

    /** 解冻信用额度。仅财务/系统。 */
    @PostMapping("/{customerNo}/unfreeze")
    public ApiResponse<Customer> unfreeze(@PathVariable String customerNo,
                                          @RequestBody(required = false) CreditStatusRequest request) {
        CreditStatusRequest body = request != null ? request : new CreditStatusRequest();
        return ApiResponse.ok(customerService.unfreeze(customerNo, body.getOperatorName(), body.getReason()));
    }

    /** 信用额度版本翻查：每次调整留前后值，新版本号在前。 */
    @GetMapping("/{customerNo}/limit-versions")
    public ApiResponse<List<CreditLimitVersion>> limitVersions(@PathVariable String customerNo,
                                                               @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(creditLimitService.listVersions(customerNo, operatorName));
    }

    /** 提交额度调整申请：走审批，通过才换版。 */
    @PostMapping("/{customerNo}/limit-applies")
    public ApiResponse<CreditAdjustApply> applyLimit(@PathVariable String customerNo,
                                                     @Valid @RequestBody LimitAdjustRequest request) {
        return ApiResponse.ok(creditLimitService.apply(customerNo, request));
    }

    /** 客户占用明细翻查：?status=OCCUPYING/SETTLED/RELEASED。 */
    @GetMapping("/{customerNo}/occupancies")
    public ApiResponse<List<CreditOccupancy>> occupancies(@PathVariable String customerNo,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String operatorName) {
        // 先过一遍详情接口的权限口径（销售限名下），再拉明细
        customerService.detail(customerNo, operatorName);
        Customer customer = customerService.requireByNo(customerNo);
        return ApiResponse.ok(occupancyService.listByCustomer(customer.getId(), status));
    }
}
