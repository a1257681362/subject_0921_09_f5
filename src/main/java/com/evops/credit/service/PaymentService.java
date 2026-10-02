package com.evops.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.credit.CreditEnums;
import com.evops.credit.dto.PaymentDetailView;
import com.evops.credit.dto.PaymentRegisterRequest;
import com.evops.credit.dto.WriteoffRequest;
import com.evops.credit.entity.CreditOccupancy;
import com.evops.credit.entity.Customer;
import com.evops.credit.entity.Payment;
import com.evops.credit.entity.PaymentWriteoff;
import com.evops.credit.mapper.PaymentMapper;
import com.evops.credit.mapper.PaymentWriteoffMapper;
import com.evops.credit.support.SecurityHelper;
import com.evops.ledger.support.Operators;
import com.evops.ledger.support.WeekRanges;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 回款：登记（财务/系统）→ 按单核销。
 * 核销口径：一笔回款可分次核多单，一单也可被多笔回款分次核；
 * 核多少释放多少额度（联动 CreditOccupancyService.settle）。
 */
@Service
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final PaymentWriteoffMapper writeoffMapper;
    private final CustomerService customerService;
    private final CreditOccupancyService occupancyService;

    public PaymentService(PaymentMapper paymentMapper,
                          PaymentWriteoffMapper writeoffMapper,
                          CustomerService customerService,
                          CreditOccupancyService occupancyService) {
        this.paymentMapper = paymentMapper;
        this.writeoffMapper = writeoffMapper;
        this.customerService = customerService;
        this.occupancyService = occupancyService;
    }

    /** 回款登记：归属月随回款日固化；凭证号留底。仅财务/系统。 */
    @Transactional(rollbackFor = Exception.class)
    public Payment register(PaymentRegisterRequest request) {
        String operator = Operators.resolve(request.getOperatorName());
        String identity = SecurityHelper.currentIdentity(operator);
        SecurityHelper.requireFinanceOrSystem(identity, "回款登记");

        Customer customer = customerService.requireByNo(request.getCustomerNo());
        LocalDate receivedDate = request.getReceivedDate() != null ? request.getReceivedDate() : LocalDate.now();
        if (receivedDate.isAfter(LocalDate.now())) {
            throw new BusinessException("回款日期不能晚于今天");
        }

        Payment payment = new Payment();
        payment.setPaymentNo(generatePaymentNo());
        payment.setCustomerId(customer.getId());
        payment.setCustomerNo(customer.getCustomerNo());
        payment.setAmount(request.getAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        payment.setAllocatedAmount(BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        payment.setReceivedDate(receivedDate);
        payment.setBizPeriod(WeekRanges.periodOf(receivedDate));
        payment.setVoucherNo(request.getVoucherNo());
        payment.setMethod(request.getMethod());
        payment.setOperatorName(operator);
        payment.setRemark(request.getRemark());
        try {
            paymentMapper.insert(payment);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("回款单号冲突，请重试");
        }
        return payment;
    }

    /**
     * 按单核销：逐行校验同客户、单在占用中、行金额不超单的未核余额；
     * 合计不超回款未核销余额。全部校验过才落库，任一失败整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentDetailView writeoff(String paymentNo, WriteoffRequest request) {
        String operator = Operators.resolve(request.getOperatorName());
        String identity = SecurityHelper.currentIdentity(operator);
        SecurityHelper.requireFinanceOrSystem(identity, "回款核销");

        Payment payment = requireByNo(paymentNo);
        BigDecimal paymentRemaining = payment.getAmount().subtract(payment.getAllocatedAmount());
        if (paymentRemaining.signum() <= 0) {
            throw new BusinessException("回款单 " + paymentNo + " 已核销完，没有可再核销的余额");
        }

        // 先整批校验，再逐条落库
        Set<String> seenOrders = new HashSet<>();
        List<CreditOccupancy> occupancies = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (WriteoffRequest.Line line : request.getLines()) {
            String orderNo = line.getOrderNo().trim();
            if (!seenOrders.add(orderNo)) {
                throw new BusinessException("单 " + orderNo + " 在本次核销里重复出现，请合并成一行");
            }
            CreditOccupancy occupancy = occupancyService.requireByOrderNo(orderNo);
            if (!occupancy.getCustomerId().equals(payment.getCustomerId())) {
                throw new BusinessException("单 " + orderNo + " 不属于该回款的客户，不能交叉核销");
            }
            if (!CreditEnums.OCC_OCCUPYING.equals(occupancy.getStatus())) {
                throw new BusinessException("单 " + orderNo + " 的占用已结清或已释放，不能再核销");
            }
            BigDecimal amount = line.getAmount().setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal orderRemaining = occupancy.getAmount().subtract(occupancy.getSettledAmount());
            if (amount.compareTo(orderRemaining) > 0) {
                throw new BusinessException("单 " + orderNo + " 未核销余额只有 "
                        + orderRemaining.toPlainString() + "，本次核销 " + amount.toPlainString() + " 超了");
            }
            total = total.add(amount);
            occupancies.add(occupancy);
        }
        if (total.compareTo(paymentRemaining) > 0) {
            throw new BusinessException("本次核销合计 " + total.toPlainString()
                    + " 超过回款未核销余额 " + paymentRemaining.toPlainString());
        }

        // 逐条落核销：占用行累加、客户占用合计释放、核销流水留底
        for (int i = 0; i < occupancies.size(); i++) {
            CreditOccupancy occupancy = occupancies.get(i);
            BigDecimal amount = request.getLines().get(i).getAmount()
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            occupancyService.settle(occupancy, amount, payment.getPaymentNo());

            PaymentWriteoff writeoff = new PaymentWriteoff();
            writeoff.setPaymentId(payment.getId());
            writeoff.setPaymentNo(payment.getPaymentNo());
            writeoff.setOccupancyId(occupancy.getId());
            writeoff.setOrderNo(occupancy.getOrderNo());
            writeoff.setCustomerId(payment.getCustomerId());
            writeoff.setAmount(amount);
            writeoff.setOperatorName(operator);
            writeoffMapper.insert(writeoff);
        }
        int updated = paymentMapper.update(null, new LambdaUpdateWrapper<Payment>()
                .eq(Payment::getId, payment.getId())
                .eq(Payment::getAllocatedAmount, payment.getAllocatedAmount())
                .set(Payment::getAllocatedAmount, payment.getAllocatedAmount().add(total)));
        if (updated == 0) {
            throw new BusinessException("回款单 " + paymentNo + " 刚被其他核销变动，请刷新后重试");
        }
        return detail(paymentNo, operator);
    }

    /** 回款单详情：单头 + 核销流水 + 未核销余额。 */
    public PaymentDetailView detail(String paymentNo, String operatorName) {
        String operator = Operators.resolve(operatorName);
        String identity = SecurityHelper.currentIdentity(operator);
        Payment payment = requireByNo(paymentNo);
        SecurityHelper.requireCustomerAccess(identity, customerService.requireById(payment.getCustomerId()));
        PaymentDetailView view = new PaymentDetailView();
        view.setPayment(payment);
        view.setWriteoffs(writeoffMapper.selectList(new LambdaQueryWrapper<PaymentWriteoff>()
                .eq(PaymentWriteoff::getPaymentId, payment.getId())
                .orderByAsc(PaymentWriteoff::getId)));
        view.setUnallocatedAmount(payment.getAmount().subtract(payment.getAllocatedAmount()));
        return view;
    }

    /** 回款翻查：销售仅名下客户；可按客户/归属月过滤。 */
    public List<Payment> list(String customerNo, String period, String operatorName) {
        String operator = Operators.resolve(operatorName);
        String identity = SecurityHelper.currentIdentity(operator);
        LambdaQueryWrapper<Payment> wrapper = new LambdaQueryWrapper<>();
        String scope = SecurityHelper.ownerScope(identity);
        if (scope != null) {
            List<Customer> mine = customerService.search(null, null, identity).stream()
                    .map(v -> v.getCustomer()).collect(java.util.stream.Collectors.toList());
            if (mine.isEmpty()) {
                return new ArrayList<>();
            }
            wrapper.in(Payment::getCustomerId, mine.stream().map(Customer::getId)
                    .collect(java.util.stream.Collectors.toList()));
        }
        if (customerNo != null && !customerNo.trim().isEmpty()) {
            Customer customer = customerService.requireByNo(customerNo);
            SecurityHelper.requireCustomerAccess(identity, customer);
            wrapper.eq(Payment::getCustomerId, customer.getId());
        }
        if (period != null && !period.trim().isEmpty()) {
            wrapper.eq(Payment::getBizPeriod, period.trim());
        }
        wrapper.orderByDesc(Payment::getId);
        return paymentMapper.selectList(wrapper);
    }

    public Payment requireByNo(String paymentNo) {
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPaymentNo, paymentNo == null ? "" : paymentNo.trim()));
        if (payment == null) {
            throw new BusinessException("回款单不存在: " + paymentNo);
        }
        return payment;
    }

    private String generatePaymentNo() {
        long seq = paymentMapper.nextPaymentSeq();
        return String.format("PM%08d", seq);
    }
}
