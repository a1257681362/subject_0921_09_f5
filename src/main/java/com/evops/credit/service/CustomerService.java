package com.evops.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.credit.CreditEnums;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.dto.CustomerView;
import com.evops.credit.entity.CreditLimitVersion;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CreditLimitVersionMapper;
import com.evops.credit.mapper.CustomerMapper;
import com.evops.credit.support.SecurityHelper;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 客户档案：建档即给信用额度（初始额度记 v1 INITIAL 版本），同名客户不许重复建档。
 * 销售只能看/动自己名下的客户；财务与系统角色看全部。
 */
@Service
public class CustomerService {

    private final CustomerMapper customerMapper;
    private final CreditLimitVersionMapper versionMapper;

    public CustomerService(CustomerMapper customerMapper, CreditLimitVersionMapper versionMapper) {
        this.customerMapper = customerMapper;
        this.versionMapper = versionMapper;
    }

    /**
     * 建档：初始额度直接生效并记 v1（来源 INITIAL，前后值 0 → 初始额度）。
     * 归属销售默认取当前身份；财务/系统可显式指定名下销售。
     */
    @Transactional(rollbackFor = Exception.class)
    public Customer create(CustomerCreateRequest request) {
        String operator = Operators.resolve(request.getOperatorName());
        String identity = SecurityHelper.currentIdentity(operator);
        String ownerSales = resolveOwner(request.getOwnerSales(), identity);
        BigDecimal initialLimit = request.getCreditLimit().setScale(2, java.math.RoundingMode.HALF_UP);

        Customer customer = new Customer();
        customer.setCustomerNo(generateCustomerNo());
        customer.setCustomerName(request.getCustomerName().trim());
        customer.setOwnerSales(ownerSales);
        customer.setContact(request.getContact());
        customer.setCreditLimit(initialLimit);
        customer.setOccupiedAmount(BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        customer.setCreditStatus(CreditEnums.CREDIT_NORMAL);
        customer.setOperatorName(operator);
        customer.setRemark(request.getRemark());
        try {
            customerMapper.insert(customer);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("客户「" + request.getCustomerName().trim() + "」已建档，同名客户不许重复建档");
        }

        CreditLimitVersion v1 = new CreditLimitVersion();
        v1.setCustomerId(customer.getId());
        v1.setVersionNo(1);
        v1.setSource(CreditEnums.VERSION_SOURCE_INITIAL);
        v1.setLimitBefore(BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        v1.setLimitAfter(initialLimit);
        v1.setStatus(CreditEnums.VERSION_EFFECTIVE);
        v1.setOperatorName(operator);
        v1.setRemark("建档初始额度");
        versionMapper.insert(v1);
        return customer;
    }

    /** 翻查：销售仅名下；财务/系统可看全部，可按归属销售过滤。 */
    public List<CustomerView> search(String keyword, String ownerSales, String operatorName) {
        String operator = Operators.resolve(operatorName);
        String identity = SecurityHelper.currentIdentity(operator);
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        String scope = SecurityHelper.ownerScope(identity);
        if (scope != null) {
            wrapper.eq(Customer::getOwnerSales, scope);
        } else if (ownerSales != null && !ownerSales.trim().isEmpty()) {
            wrapper.eq(Customer::getOwnerSales, ownerSales.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            // 关键词的 OR 必须包进嵌套条件，否则会冲掉前面的归属隔离
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Customer::getCustomerName, kw)
                    .or().like(Customer::getCustomerNo, kw));
        }
        wrapper.orderByDesc(Customer::getId);
        return customerMapper.selectList(wrapper).stream()
                .map(this::toView).collect(Collectors.toList());
    }

    /** 详情：档案 + 可用额度 + 当前版本号；销售限名下。 */
    public CustomerView detail(String customerNo, String operatorName) {
        String operator = Operators.resolve(operatorName);
        String identity = SecurityHelper.currentIdentity(operator);
        Customer customer = requireByNo(customerNo);
        SecurityHelper.requireCustomerAccess(identity, customer);
        return toView(customer);
    }

    /** 冻结：冻结后新上刊拦截（回款核销照走）。仅财务/系统。 */
    @Transactional(rollbackFor = Exception.class)
    public Customer freeze(String customerNo, String operatorName, String reason) {
        return changeStatus(customerNo, operatorName, reason, CreditEnums.CREDIT_NORMAL, CreditEnums.CREDIT_FROZEN, "冻结");
    }

    /** 解冻：恢复正常占用。仅财务/系统。 */
    @Transactional(rollbackFor = Exception.class)
    public Customer unfreeze(String customerNo, String operatorName, String reason) {
        return changeStatus(customerNo, operatorName, reason, CreditEnums.CREDIT_FROZEN, CreditEnums.CREDIT_NORMAL, "解冻");
    }

    public Customer requireByNo(String customerNo) {
        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getCustomerNo, customerNo == null ? "" : customerNo.trim()));
        if (customer == null) {
            throw new BusinessException("客户不存在: " + customerNo);
        }
        return customer;
    }

    public Customer requireById(Long id) {
        Customer customer = customerMapper.selectById(id);
        if (customer == null) {
            throw new BusinessException("客户不存在: id=" + id);
        }
        return customer;
    }

    /** 当前生效版本号（翻版本历史时一并展示）。 */
    public Integer currentVersionNo(Long customerId) {
        CreditLimitVersion effective = versionMapper.selectOne(new LambdaQueryWrapper<CreditLimitVersion>()
                .eq(CreditLimitVersion::getCustomerId, customerId)
                .eq(CreditLimitVersion::getStatus, CreditEnums.VERSION_EFFECTIVE)
                .last("LIMIT 1"));
        return effective == null ? null : effective.getVersionNo();
    }

    // ---- 内部方法 ----

    private String resolveOwner(String requested, String identity) {
        if (SecurityHelper.isSales(identity)) {
            if (requested != null && !requested.trim().isEmpty() && !identity.equals(requested.trim())) {
                throw new BusinessException("销售建档归属只能是自己，不能挂到 " + requested.trim() + " 名下");
            }
            return identity;
        }
        if (requested != null && !requested.trim().isEmpty()) {
            return requested.trim();
        }
        return identity;
    }

    private Customer changeStatus(String customerNo, String operatorName, String reason,
                                  String from, String to, String action) {
        String operator = Operators.resolve(operatorName);
        String identity = SecurityHelper.currentIdentity(operator);
        SecurityHelper.requireFinanceOrSystem(identity, "信用额度" + action);
        Customer customer = requireByNo(customerNo);
        int updated = customerMapper.update(null, new LambdaUpdateWrapper<Customer>()
                .eq(Customer::getId, customer.getId())
                .eq(Customer::getCreditStatus, from)
                .set(Customer::getCreditStatus, to)
                .set(Customer::getRemark, reason != null && !reason.trim().isEmpty()
                        ? reason.trim() : customer.getRemark()));
        if (updated == 0) {
            throw new BusinessException("客户 " + customer.getCustomerName() + " 当前不是可" + action
                    + "的状态，请勿重复操作");
        }
        return customerMapper.selectById(customer.getId());
    }

    private CustomerView toView(Customer customer) {
        CustomerView view = new CustomerView();
        view.setCustomer(customer);
        view.setAvailableAmount(customer.getCreditLimit().subtract(customer.getOccupiedAmount()));
        view.setCurrentVersionNo(currentVersionNo(customer.getId()));
        return view;
    }

    private String generateCustomerNo() {
        long seq = customerMapper.nextCustomerSeq();
        return String.format("CU%08d", seq);
    }
}
