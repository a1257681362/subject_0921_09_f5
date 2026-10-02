package com.evops.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.credit.CreditEnums;
import com.evops.credit.entity.CreditOccupancy;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CreditOccupancyMapper;
import com.evops.credit.mapper.CustomerMapper;
import com.evops.credit.support.SecurityHelper;
import com.evops.ledger.entity.PostingOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 上刊占用联动：上刊占额度（余额不够拦在提交口）、对冲释放、回款核销减占用。
 * 落架不释放——占用一直挂到核销或对冲，额度管的是客户还没结钱的总盘子。
 *
 * 并发口径：客户占用合计走 t_customer 的守卫更新（tryOccupy/tryRelease），
 * 占用行走状态+已核金额的乐观条件更新，并发冲突直接报错回滚，不产生脏占用。
 */
@Service
public class CreditOccupancyService {

    private final CustomerMapper customerMapper;
    private final CreditOccupancyMapper occupancyMapper;
    private final CustomerService customerService;

    public CreditOccupancyService(CustomerMapper customerMapper,
                                  CreditOccupancyMapper occupancyMapper,
                                  CustomerService customerService) {
        this.customerMapper = customerMapper;
        this.occupancyMapper = occupancyMapper;
        this.customerService = customerService;
    }

    /**
     * 上刊前置校验：客户存在、额度状态正常、销售限名下客户、占用金额为正。
     * 在上刊事务里先调它，校验不过整张单拦在提交口。
     */
    public Customer preflight(String customerNo, BigDecimal occupyAmount, String identity) {
        if (customerNo == null || customerNo.trim().isEmpty()) {
            throw new BusinessException("上刊单必须挂客户：请填写客户编号，先上刊后结钱也要落在授信上");
        }
        if (occupyAmount == null || occupyAmount.signum() <= 0) {
            throw new BusinessException("占用金额必须大于 0：上刊即占用客户信用额度");
        }
        Customer customer = customerService.requireByNo(customerNo);
        SecurityHelper.requireCustomerAccess(identity, customer);
        if (CreditEnums.CREDIT_FROZEN.equals(customer.getCreditStatus())) {
            throw new BusinessException("客户 " + customer.getCustomerName()
                    + " 的信用额度已冻结，不能新上刊占用；如需恢复请联系财务解冻");
        }
        return customer;
    }

    /**
     * 占额度：守卫更新客户占用合计（超占/冻结并发拦截），再落占用明细行。
     * 与上刊单同一事务，任一步失败整体回滚。
     */
    public void occupy(Customer customer, BigDecimal occupyAmount, PostingOrder order) {
        BigDecimal amount = occupyAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        int updated = customerMapper.tryOccupy(customer.getId(), amount);
        if (updated == 0) {
            Customer fresh = customerService.requireById(customer.getId());
            throw new BusinessException("客户 " + fresh.getCustomerName() + " 可用额度不足：当前额度 "
                    + fresh.getCreditLimit().toPlainString() + "，已占用 "
                    + fresh.getOccupiedAmount().toPlainString() + "，可用 "
                    + fresh.getCreditLimit().subtract(fresh.getOccupiedAmount()).toPlainString()
                    + "，本次上刊需占用 " + amount.toPlainString()
                    + "；请回款核销释放额度或申请调额后再提交");
        }
        CreditOccupancy occupancy = new CreditOccupancy();
        occupancy.setCustomerId(customer.getId());
        occupancy.setCustomerNo(customer.getCustomerNo());
        occupancy.setOrderId(order.getId());
        occupancy.setOrderNo(order.getOrderNo());
        occupancy.setAmount(amount);
        occupancy.setSettledAmount(BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        occupancy.setStatus(CreditEnums.OCC_OCCUPYING);
        occupancy.setBizPeriod(order.getBizPeriod());
        occupancy.setOccurDate(order.getOccurDate());
        occupancy.setOccupyTime(LocalDateTime.now());
        try {
            occupancyMapper.insert(occupancy);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该上刊单已落过占用明细，请勿重复占用");
        }
    }

    /**
     * 对冲释放：改错撤单不结钱，释放该单剩余未核销的占用。
     * 没有占用明细的老单（F5 之前）直接跳过。
     */
    public void releaseByOffset(PostingOrder order, String reason) {
        CreditOccupancy occupancy = occupancyMapper.selectOne(new LambdaQueryWrapper<CreditOccupancy>()
                .eq(CreditOccupancy::getOrderId, order.getId()));
        if (occupancy == null || !CreditEnums.OCC_OCCUPYING.equals(occupancy.getStatus())) {
            return;
        }
        BigDecimal remaining = occupancy.getAmount().subtract(occupancy.getSettledAmount());
        int updated = occupancyMapper.update(null, new LambdaUpdateWrapper<CreditOccupancy>()
                .eq(CreditOccupancy::getId, occupancy.getId())
                .eq(CreditOccupancy::getStatus, CreditEnums.OCC_OCCUPYING)
                .set(CreditOccupancy::getStatus, CreditEnums.OCC_RELEASED)
                .set(CreditOccupancy::getReleaseTime, LocalDateTime.now())
                .set(CreditOccupancy::getReleaseNote, "对冲释放：" + reason));
        if (updated == 0) {
            throw new BusinessException("占用明细状态刚发生变化，请刷新后重试对冲");
        }
        if (remaining.signum() > 0) {
            int released = customerMapper.tryRelease(occupancy.getCustomerId(), remaining);
            if (released == 0) {
                throw new BusinessException("客户占用合计与明细不一致，释放失败，请联系财务核对");
            }
        }
    }

    /**
     * 核销联动：占用行累加已核金额（乐观条件更新防并发超核），核满置 SETTLED；
     * 客户占用合计同步减——核多少释放多少额度。由 PaymentService 在核销事务里调用。
     */
    public void settle(CreditOccupancy occupancy, BigDecimal amount, String paymentNo) {
        BigDecimal newSettled = occupancy.getSettledAmount().add(amount);
        boolean full = newSettled.compareTo(occupancy.getAmount()) == 0;
        int updated = occupancyMapper.update(null, new LambdaUpdateWrapper<CreditOccupancy>()
                .eq(CreditOccupancy::getId, occupancy.getId())
                .eq(CreditOccupancy::getStatus, CreditEnums.OCC_OCCUPYING)
                .eq(CreditOccupancy::getSettledAmount, occupancy.getSettledAmount())
                .set(CreditOccupancy::getSettledAmount, newSettled)
                .set(CreditOccupancy::getStatus, full ? CreditEnums.OCC_SETTLED : CreditEnums.OCC_OCCUPYING)
                .set(full, CreditOccupancy::getReleaseTime, LocalDateTime.now())
                .set(full, CreditOccupancy::getReleaseNote, "回款核销结清，末次回款单 " + paymentNo));
        if (updated == 0) {
            throw new BusinessException("单 " + occupancy.getOrderNo() + " 的占用刚被其他核销变动，请刷新后重试");
        }
        int released = customerMapper.tryRelease(occupancy.getCustomerId(), amount);
        if (released == 0) {
            throw new BusinessException("客户占用合计与明细不一致，核销失败，请联系财务核对");
        }
    }

    public CreditOccupancy requireByOrderNo(String orderNo) {
        CreditOccupancy occupancy = occupancyMapper.selectOne(new LambdaQueryWrapper<CreditOccupancy>()
                .eq(CreditOccupancy::getOrderNo, orderNo == null ? "" : orderNo.trim()));
        if (occupancy == null) {
            throw new BusinessException("上刊单 " + orderNo + " 没有占用明细（可能是授信建档前的老单），不能核销");
        }
        return occupancy;
    }

    /** 客户占用明细翻查（可按状态过滤），新的在前。 */
    public List<CreditOccupancy> listByCustomer(Long customerId, String status) {
        LambdaQueryWrapper<CreditOccupancy> wrapper = new LambdaQueryWrapper<CreditOccupancy>()
                .eq(CreditOccupancy::getCustomerId, customerId);
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(CreditOccupancy::getStatus, status.trim());
        }
        wrapper.orderByDesc(CreditOccupancy::getId);
        return occupancyMapper.selectList(wrapper);
    }

    /** 月底拉明细：某归属月内若干客户的占用行。 */
    public List<CreditOccupancy> listByPeriod(String period, List<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return occupancyMapper.selectList(new LambdaQueryWrapper<CreditOccupancy>()
                .eq(CreditOccupancy::getBizPeriod, period)
                .in(CreditOccupancy::getCustomerId, customerIds)
                .orderByAsc(CreditOccupancy::getCustomerId)
                .orderByAsc(CreditOccupancy::getId));
    }
}
