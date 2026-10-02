package com.evops.credit;

import com.evops.common.BusinessException;
import com.evops.credit.dto.ApproveRequest;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.dto.CustomerView;
import com.evops.credit.dto.LimitAdjustRequest;
import com.evops.credit.dto.OccupancyReportView;
import com.evops.credit.dto.PaymentDetailView;
import com.evops.credit.dto.PaymentRegisterRequest;
import com.evops.credit.dto.WriteoffRequest;
import com.evops.credit.entity.CreditAdjustApply;
import com.evops.credit.entity.CreditLimitVersion;
import com.evops.credit.entity.CreditOccupancy;
import com.evops.credit.entity.Customer;
import com.evops.credit.entity.Payment;
import com.evops.credit.service.CreditLimitService;
import com.evops.credit.service.CreditOccupancyService;
import com.evops.credit.service.CreditReportService;
import com.evops.credit.service.CustomerService;
import com.evops.credit.service.PaymentService;
import com.evops.ledger.dto.ActionRequest;
import com.evops.ledger.dto.OrderCreateRequest;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.service.OrderService;
import com.evops.ledger.service.SchemaVersionService;
import com.evops.ledger.service.SiteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F5 客户授信与回款核销口径端到端测试（真实 H2 + 真实事务）。 */
@SpringBootTest
class CreditFlowTest {

    @Autowired private CustomerService customerService;
    @Autowired private CreditLimitService creditLimitService;
    @Autowired private CreditOccupancyService occupancyService;
    @Autowired private PaymentService paymentService;
    @Autowired private CreditReportService reportService;
    @Autowired private OrderService orderService;
    @Autowired private SiteService siteService;
    @Autowired private SchemaVersionService schemaVersionService;

    private static int seq = 0;
    private synchronized String uniqueNo(String prefix) {
        seq++;
        return prefix + "-" + System.nanoTime() + "-" + seq;
    }

    // ---- 构造帮手 ----

    private Customer newCustomer(String limit, String operator) {
        CustomerCreateRequest req = new CustomerCreateRequest();
        req.setCustomerName(uniqueNo("C"));
        req.setCreditLimit(new BigDecimal(limit));
        req.setOperatorName(operator);
        return customerService.create(req);
    }

    private String newSite() {
        SiteCreateRequest req = new SiteCreateRequest();
        req.setSiteNo(uniqueNo("S"));
        req.setSizeSpec("18m×6m");
        req.setOrientation("朝北");
        req.setBusinessDistrict("王府井");
        req.setOperatorName("tester");
        return siteService.register(req).getSiteNo();
    }

    private PostingOrder post(String siteNo, String customerNo, String amount, LocalDate date, String operator) {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setSiteNo(siteNo);
        req.setCreativeName("画面-" + uniqueNo("K"));
        req.setCustomerNo(customerNo);
        req.setOccupyAmount(new BigDecimal(amount));
        req.setOccurDate(date);
        req.setOperatorName(operator);
        return orderService.createPosting(req);
    }

    private Payment pay(String customerNo, String amount, String operator) {
        PaymentRegisterRequest req = new PaymentRegisterRequest();
        req.setCustomerNo(customerNo);
        req.setAmount(new BigDecimal(amount));
        req.setVoucherNo(uniqueNo("V"));
        req.setOperatorName(operator);
        return paymentService.register(req);
    }

    private WriteoffRequest writeoff(String operator, String... orderAmountPairs) {
        WriteoffRequest req = new WriteoffRequest();
        req.setOperatorName(operator);
        req.setLines(Arrays.stream(orderAmountPairs).map(pair -> {
            String[] parts = pair.split(":");
            WriteoffRequest.Line line = new WriteoffRequest.Line();
            line.setOrderNo(parts[0]);
            line.setAmount(new BigDecimal(parts[1]));
            return line;
        }).collect(Collectors.toList()));
        return req;
    }

    private Customer reload(Customer customer) {
        return customerService.requireById(customer.getId());
    }

    private CreditOccupancy occupancyOf(String orderNo) {
        return occupancyService.requireByOrderNo(orderNo);
    }

    // ---- 口径测试 ----

    @Test
    void 建档给额度并记v1初始版本_同名客户拦第二次() {
        Customer customer = newCustomer("50000", "sales01");
        assertTrue(customer.getCustomerNo().startsWith("CU"));
        assertEquals(0, new BigDecimal("50000.00").compareTo(customer.getCreditLimit()));
        assertEquals(CreditEnums.CREDIT_NORMAL, customer.getCreditStatus());
        assertEquals("sales01", customer.getOwnerSales());

        List<CreditLimitVersion> versions = creditLimitService.listVersions(customer.getCustomerNo(), "sales01");
        assertEquals(1, versions.size());
        CreditLimitVersion v1 = versions.get(0);
        assertEquals(1, v1.getVersionNo());
        assertEquals(CreditEnums.VERSION_SOURCE_INITIAL, v1.getSource());
        assertEquals(0, BigDecimal.ZERO.compareTo(v1.getLimitBefore()));
        assertEquals(0, new BigDecimal("50000.00").compareTo(v1.getLimitAfter()));
        assertEquals(CreditEnums.VERSION_EFFECTIVE, v1.getStatus());

        CustomerCreateRequest dup = new CustomerCreateRequest();
        dup.setCustomerName(customer.getCustomerName());
        dup.setCreditLimit(new BigDecimal("1"));
        dup.setOperatorName("sales01");
        BusinessException ex = assertThrows(BusinessException.class, () -> customerService.create(dup));
        assertTrue(ex.getMessage().contains("重复建档"));
    }

    @Test
    void 上刊占用额度_单上固化客户与金额_余额不够拦在提交口() {
        Customer customer = newCustomer("5000", "tester");
        String site = newSite();
        PostingOrder o1 = post(site, customer.getCustomerNo(), "4000", LocalDate.of(2026, 9, 14), "tester");
        assertEquals(customer.getId(), o1.getCustomerId());
        assertEquals(customer.getCustomerNo(), o1.getCustomerNo());
        assertEquals(0, new BigDecimal("4000.00").compareTo(o1.getOccupyAmount()));
        assertEquals(0, new BigDecimal("4000.00").compareTo(reload(customer).getOccupiedAmount()));

        CreditOccupancy occupancy = occupancyOf(o1.getOrderNo());
        assertEquals(CreditEnums.OCC_OCCUPYING, occupancy.getStatus());
        assertEquals("2026-09", occupancy.getBizPeriod());

        // 可用只剩 1000，再占 2000 必须拦在提交口，且原占用不被搅乱
        BusinessException ex = assertThrows(BusinessException.class,
                () -> post(newSite(), customer.getCustomerNo(), "2000", LocalDate.of(2026, 9, 14), "tester"));
        assertTrue(ex.getMessage().contains("可用额度不足"));
        assertEquals(0, new BigDecimal("4000.00").compareTo(reload(customer).getOccupiedAmount()));
    }

    @Test
    void 冻结客户上刊被拦_解冻后恢复() {
        Customer customer = newCustomer("5000", "tester");
        customerService.freeze(customer.getCustomerNo(), "finance01", "风控冻结");
        assertEquals(CreditEnums.CREDIT_FROZEN, reload(customer).getCreditStatus());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> post(newSite(), customer.getCustomerNo(), "1000", LocalDate.of(2026, 9, 14), "tester"));
        assertTrue(ex.getMessage().contains("冻结"));

        customerService.unfreeze(customer.getCustomerNo(), "finance01", "风险解除");
        post(newSite(), customer.getCustomerNo(), "1000", LocalDate.of(2026, 9, 14), "tester");
        assertEquals(0, new BigDecimal("1000.00").compareTo(reload(customer).getOccupiedAmount()));

        // 销售无权冻结
        BusinessException denied = assertThrows(BusinessException.class,
                () -> customerService.freeze(customer.getCustomerNo(), "sales01", "越权"));
        assertTrue(denied.getMessage().contains("财务或系统"));
    }

    @Test
    void 落架不释放占用_对冲才释放() {
        Customer customer = newCustomer("10000", "tester");
        PostingOrder order = post(newSite(), customer.getCustomerNo(), "3000", LocalDate.of(2026, 9, 14), "tester");

        // 落架：单关了，占用继续挂着等回款
        ActionRequest down = new ActionRequest();
        down.setOperatorName("tester");
        orderService.takeDown(order.getOrderNo(), down);
        assertEquals(0, new BigDecimal("3000.00").compareTo(reload(customer).getOccupiedAmount()));
        assertEquals(CreditEnums.OCC_OCCUPYING, occupancyOf(order.getOrderNo()).getStatus());

        // 对冲：改错撤单不结钱，剩余占用直接释放
        PostingOrder order2 = post(newSite(), customer.getCustomerNo(), "5000", LocalDate.of(2026, 9, 14), "tester");
        ActionRequest offset = new ActionRequest();
        offset.setOperatorName("tester");
        offset.setReason("画面挂错");
        orderService.offset(order2.getOrderNo(), offset);
        assertEquals(0, new BigDecimal("3000.00").compareTo(reload(customer).getOccupiedAmount()));
        CreditOccupancy released = occupancyOf(order2.getOrderNo());
        assertEquals(CreditEnums.OCC_RELEASED, released.getStatus());
        assertNotNull(released.getReleaseTime());
    }

    @Test
    void 回款按单核销_核满释放额度_部分回款分次核() {
        Customer customer = newCustomer("10000", "tester");
        PostingOrder o1 = post(newSite(), customer.getCustomerNo(), "6000", LocalDate.of(2026, 9, 7), "tester");
        PostingOrder o2 = post(newSite(), customer.getCustomerNo(), "3000", LocalDate.of(2026, 9, 14), "tester");
        assertEquals(0, new BigDecimal("9000.00").compareTo(reload(customer).getOccupiedAmount()));

        // 第一笔回款 4000 先核 o1 一部分：占用只释放 4000
        Payment p1 = pay(customer.getCustomerNo(), "4000", "finance01");
        PaymentDetailView after1 = paymentService.writeoff(p1.getPaymentNo(),
                writeoff("finance01", o1.getOrderNo() + ":4000"));
        assertEquals(0, new BigDecimal("4000.00").compareTo(after1.getPayment().getAllocatedAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(after1.getUnallocatedAmount()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(reload(customer).getOccupiedAmount()));
        assertEquals(CreditEnums.OCC_OCCUPYING, occupancyOf(o1.getOrderNo()).getStatus());
        assertEquals(0, new BigDecimal("4000.00").compareTo(occupancyOf(o1.getOrderNo()).getSettledAmount()));

        // 第二笔回款 2500 一笔核两单：o1 核满结清，o2 核一部分
        Payment p2 = pay(customer.getCustomerNo(), "2500", "finance01");
        paymentService.writeoff(p2.getPaymentNo(),
                writeoff("finance01", o1.getOrderNo() + ":2000", o2.getOrderNo() + ":500"));
        assertEquals(CreditEnums.OCC_SETTLED, occupancyOf(o1.getOrderNo()).getStatus());
        assertEquals(0, new BigDecimal("500.00").compareTo(occupancyOf(o2.getOrderNo()).getSettledAmount()));
        assertEquals(0, new BigDecimal("2500.00").compareTo(reload(customer).getOccupiedAmount()));

        // 已核销完的回款单不能再核
        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.writeoff(p1.getPaymentNo(), writeoff("finance01", o2.getOrderNo() + ":1")));
        assertTrue(ex.getMessage().contains("已核销完"));
    }

    @Test
    void 核销超额与跨客户都拦住() {
        Customer c1 = newCustomer("10000", "tester");
        Customer c2 = newCustomer("10000", "tester");
        PostingOrder o1 = post(newSite(), c1.getCustomerNo(), "1000", LocalDate.of(2026, 9, 7), "tester");
        Payment payment = pay(c1.getCustomerNo(), "500", "finance01");

        // 单未核余额 1000，核 1200 超单余额
        BusinessException overOrder = assertThrows(BusinessException.class,
                () -> paymentService.writeoff(payment.getPaymentNo(), writeoff("finance01", o1.getOrderNo() + ":1200")));
        assertTrue(overOrder.getMessage().contains("未核销余额"));

        // 核 600 超回款余额 500
        BusinessException overPayment = assertThrows(BusinessException.class,
                () -> paymentService.writeoff(payment.getPaymentNo(), writeoff("finance01", o1.getOrderNo() + ":600")));
        assertTrue(overPayment.getMessage().contains("超过回款未核销余额"));

        // 别家的单不能交叉核销
        PostingOrder o2 = post(newSite(), c2.getCustomerNo(), "1000", LocalDate.of(2026, 9, 7), "tester");
        BusinessException cross = assertThrows(BusinessException.class,
                () -> paymentService.writeoff(payment.getPaymentNo(), writeoff("finance01", o2.getOrderNo() + ":100")));
        assertTrue(cross.getMessage().contains("不能交叉核销"));

        // 销售无权登记回款
        PaymentRegisterRequest req = new PaymentRegisterRequest();
        req.setCustomerNo(c1.getCustomerNo());
        req.setAmount(new BigDecimal("100"));
        req.setOperatorName("sales01");
        assertThrows(BusinessException.class, () -> paymentService.register(req));
    }

    @Test
    void 额度调整走审批_前后值留版本_驳回不动额度() {
        Customer customer = newCustomer("5000", "tester");

        LimitAdjustRequest applyReq = new LimitAdjustRequest();
        applyReq.setLimitAfter(new BigDecimal("8000"));
        applyReq.setReason("大客户旺季放量");
        applyReq.setOperatorName("sales01");
        // 客户挂在 tester 名下，sales01 越权申请被拦；改由 tester 提交
        assertThrows(BusinessException.class,
                () -> creditLimitService.apply(customer.getCustomerNo(), applyReq));
        applyReq.setOperatorName("tester");
        CreditAdjustApply apply = creditLimitService.apply(customer.getCustomerNo(), applyReq);
        assertEquals(CreditEnums.APPLY_PENDING, apply.getStatus());
        assertEquals(0, new BigDecimal("5000.00").compareTo(apply.getLimitBefore()));

        ApproveRequest approve = new ApproveRequest();
        approve.setOperatorName("finance01");
        creditLimitService.approve(apply.getApplyNo(), approve);

        assertEquals(0, new BigDecimal("8000.00").compareTo(reload(customer).getCreditLimit()));
        List<CreditLimitVersion> versions = creditLimitService.listVersions(customer.getCustomerNo(), "tester");
        assertEquals(2, versions.size());
        CreditLimitVersion v2 = versions.get(0);
        assertEquals(CreditEnums.VERSION_SOURCE_ADJUST, v2.getSource());
        assertEquals(0, new BigDecimal("5000.00").compareTo(v2.getLimitBefore()));
        assertEquals(0, new BigDecimal("8000.00").compareTo(v2.getLimitAfter()));
        assertEquals(CreditEnums.VERSION_EFFECTIVE, v2.getStatus());
        assertEquals("finance01", v2.getApproverName());
        assertEquals(CreditEnums.VERSION_SUPERSEDED, versions.get(1).getStatus());

        // 再提一条被驳回：额度不动
        LimitAdjustRequest applyReq2 = new LimitAdjustRequest();
        applyReq2.setLimitAfter(new BigDecimal("9000"));
        applyReq2.setReason("再试试");
        applyReq2.setOperatorName("tester");
        CreditAdjustApply apply2 = creditLimitService.apply(customer.getCustomerNo(), applyReq2);
        ApproveRequest reject = new ApproveRequest();
        reject.setOperatorName("finance01");
        reject.setRemark("旺季过了再说");
        creditLimitService.reject(apply2.getApplyNo(), reject);
        assertEquals(0, new BigDecimal("8000.00").compareTo(reload(customer).getCreditLimit()));
        assertEquals(CreditEnums.APPLY_REJECTED,
                creditLimitService.listApplies(CreditEnums.APPLY_REJECTED, "finance01").stream()
                        .filter(a -> a.getApplyNo().equals(apply2.getApplyNo())).findFirst().get().getStatus());
    }

    @Test
    void 审批口径_不能审自己_不能低于当前占用_在途只准一条() {
        Customer customer = newCustomer("10000", "tester");
        post(newSite(), customer.getCustomerNo(), "6000", LocalDate.of(2026, 9, 7), "tester");

        // 申请人不能审自己的单
        LimitAdjustRequest req = new LimitAdjustRequest();
        req.setLimitAfter(new BigDecimal("12000"));
        req.setReason("自提自审");
        req.setOperatorName("finance01");
        CreditAdjustApply apply = creditLimitService.apply(customer.getCustomerNo(), req);
        ApproveRequest self = new ApproveRequest();
        self.setOperatorName("finance01");
        BusinessException selfEx = assertThrows(BusinessException.class,
                () -> creditLimitService.approve(apply.getApplyNo(), self));
        assertTrue(selfEx.getMessage().contains("审批人不能是申请人"));

        // 在途 PENDING 只准一条
        LimitAdjustRequest req2 = new LimitAdjustRequest();
        req2.setLimitAfter(new BigDecimal("15000"));
        req2.setReason("再来一条");
        req2.setOperatorName("tester");
        BusinessException dup = assertThrows(BusinessException.class,
                () -> creditLimitService.apply(customer.getCustomerNo(), req2));
        assertTrue(dup.getMessage().contains("待审批"));

        // 目标额度 5000 低于当前占用 6000，审批时拦下
        LimitAdjustRequest req3 = new LimitAdjustRequest();
        req3.setLimitAfter(new BigDecimal("5000"));
        req3.setReason("降额");
        req3.setOperatorName("tester");
        // 先把在途那条驳回，才能提降额申请
        ApproveRequest reject = new ApproveRequest();
        reject.setOperatorName("demo");
        creditLimitService.reject(apply.getApplyNo(), reject);
        CreditAdjustApply lowApply = creditLimitService.apply(customer.getCustomerNo(), req3);
        ApproveRequest approve = new ApproveRequest();
        approve.setOperatorName("finance01");
        BusinessException low = assertThrows(BusinessException.class,
                () -> creditLimitService.approve(lowApply.getApplyNo(), approve));
        assertTrue(low.getMessage().contains("低于客户当前占用"));
    }

    @Test
    void 销售只能看自己名下的客户() {
        Customer mine = newCustomer("5000", "sales01");
        Customer others = newCustomer("5000", "sales02");

        // 各自列表只见到自己名下；财务全量
        List<String> sales01Seen = customerService.search(null, null, "sales01").stream()
                .map(v -> v.getCustomer().getCustomerNo()).collect(Collectors.toList());
        assertTrue(sales01Seen.contains(mine.getCustomerNo()));
        assertFalse(sales01Seen.contains(others.getCustomerNo()));
        List<String> financeSeen = customerService.search(null, null, "finance01").stream()
                .map(v -> v.getCustomer().getCustomerNo()).collect(Collectors.toList());
        assertTrue(financeSeen.contains(mine.getCustomerNo()) && financeSeen.contains(others.getCustomerNo()));

        // 看别家客户详情被拦
        BusinessException detailEx = assertThrows(BusinessException.class,
                () -> customerService.detail(others.getCustomerNo(), "sales01"));
        assertTrue(detailEx.getMessage().contains("名下"));

        // 给别家客户上刊被拦
        BusinessException postEx = assertThrows(BusinessException.class,
                () -> post(newSite(), others.getCustomerNo(), "100", LocalDate.of(2026, 9, 7), "sales01"));
        assertTrue(postEx.getMessage().contains("名下"));

        // 销售建档不能挂到别人名下
        CustomerCreateRequest req = new CustomerCreateRequest();
        req.setCustomerName(uniqueNo("C"));
        req.setCreditLimit(new BigDecimal("100"));
        req.setOwnerSales("sales02");
        req.setOperatorName("sales01");
        assertThrows(BusinessException.class, () -> customerService.create(req));
    }

    @Test
    void 月底每户占用明细_销售仅名下() {
        Customer customer = newCustomer("100000", "sales01");
        PostingOrder sep = post(newSite(), customer.getCustomerNo(), "6000", LocalDate.of(2026, 9, 14), "sales01");
        post(newSite(), customer.getCustomerNo(), "4000", LocalDate.of(2026, 10, 5), "sales01");
        Payment payment = pay(customer.getCustomerNo(), "2000", "finance01");
        paymentService.writeoff(payment.getPaymentNo(), writeoff("finance01", sep.getOrderNo() + ":2000"));

        OccupancyReportView report = reportService.occupancyReport("2026-09", customer.getCustomerNo(), "finance01");
        assertEquals(1, report.getCustomers().size());
        OccupancyReportView.CustomerOccupancy row = report.getCustomers().get(0);
        assertEquals(1, row.getLines().size());
        assertEquals(sep.getOrderNo(), row.getLines().get(0).getOrderNo());
        assertEquals(0, new BigDecimal("6000.00").compareTo(row.getPeriodOccupied()));
        assertEquals(0, new BigDecimal("2000.00").compareTo(row.getPeriodSettled()));
        assertEquals(0, new BigDecimal("4000.00").compareTo(row.getPeriodOutstanding()));
        assertEquals(0, new BigDecimal("92000.00").compareTo(row.getAvailableAmount()));

        // 销售拉全量时只出自己名下的户
        Customer other = newCustomer("1000", "sales02");
        OccupancyReportView salesView = reportService.occupancyReport("2026-09", null, "sales01");
        List<String> seen = salesView.getCustomers().stream()
                .map(r -> r.getCustomer().getCustomerNo()).collect(Collectors.toList());
        assertTrue(seen.contains(customer.getCustomerNo()));
        assertFalse(seen.contains(other.getCustomerNo()));

        // 指定别家的客户号直接拦
        assertThrows(BusinessException.class,
                () -> reportService.occupancyReport("2026-09", other.getCustomerNo(), "sales01"));

        // CSV 带 BOM 且含明细行
        String csv = reportService.occupancyCsv("2026-09", customer.getCustomerNo(), "finance01");
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains(sep.getOrderNo()));
    }

    @Test
    void 授信版本登记包含f5版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f5-credit-payment-1"));
    }
}
