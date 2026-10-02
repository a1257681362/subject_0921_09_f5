package com.evops.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.credit.CreditEnums;
import com.evops.credit.dto.ApproveRequest;
import com.evops.credit.dto.LimitAdjustRequest;
import com.evops.credit.entity.CreditAdjustApply;
import com.evops.credit.entity.CreditLimitVersion;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CreditAdjustApplyMapper;
import com.evops.credit.mapper.CreditLimitVersionMapper;
import com.evops.credit.mapper.CustomerMapper;
import com.evops.credit.support.SecurityHelper;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 信用额度调整：额度动一动要走审批。
 * 申请（销售限名下客户）→ 审批（财务/系统，且审批人不能是申请人）→
 * 通过即换版：旧版置 SUPERSEDED，新版记 ADJUST，前后值都留；驳回留审批意见。
 */
@Service
public class CreditLimitService {

    private final CustomerMapper customerMapper;
    private final CreditLimitVersionMapper versionMapper;
    private final CreditAdjustApplyMapper applyMapper;
    private final CustomerService customerService;

    public CreditLimitService(CustomerMapper customerMapper,
                              CreditLimitVersionMapper versionMapper,
                              CreditAdjustApplyMapper applyMapper,
                              CustomerService customerService) {
        this.customerMapper = customerMapper;
        this.versionMapper = versionMapper;
        this.applyMapper = applyMapper;
        this.customerService = customerService;
    }

    /**
     * 提交调整申请：目标额度必须不同于当前值；同一客户同时只准一条待审批。
     */
    @Transactional(rollbackFor = Exception.class)
    public CreditAdjustApply apply(String customerNo, LimitAdjustRequest request) {
        String operator = Operators.resolve(request.getOperatorName());
        String identity = SecurityHelper.currentIdentity(operator);
        Customer customer = customerService.requireByNo(customerNo);
        SecurityHelper.requireCustomerAccess(identity, customer);

        BigDecimal target = request.getLimitAfter().setScale(2, java.math.RoundingMode.HALF_UP);
        if (target.compareTo(customer.getCreditLimit()) == 0) {
            throw new BusinessException("目标额度与当前额度相同（"
                    + customer.getCreditLimit().toPlainString() + "），无需调整");
        }
        Long pending = applyMapper.selectCount(new LambdaQueryWrapper<CreditAdjustApply>()
                .eq(CreditAdjustApply::getCustomerId, customer.getId())
                .eq(CreditAdjustApply::getStatus, CreditEnums.APPLY_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException("客户 " + customer.getCustomerName()
                    + " 还有一条待审批的额度调整，请先等审批完再提");
        }

        CreditAdjustApply apply = new CreditAdjustApply();
        apply.setApplyNo(generateApplyNo());
        apply.setCustomerId(customer.getId());
        apply.setLimitBefore(customer.getCreditLimit());
        apply.setLimitAfter(target);
        apply.setReason(request.getReason().trim());
        apply.setStatus(CreditEnums.APPLY_PENDING);
        apply.setApplicantName(operator);
        try {
            applyMapper.insert(apply);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("申请单号冲突，请重试");
        }
        return apply;
    }

    /**
     * 审批通过：审批人不能是申请人；目标额度不得低于当前占用（不能让可用变负）；
     * 通过后换版——旧版置 SUPERSEDED，新版记 ADJUST 留前后值。
     */
    @Transactional(rollbackFor = Exception.class)
    public CreditAdjustApply approve(String applyNo, ApproveRequest request) {
        String approver = Operators.resolve(request.getOperatorName());
        String identity = SecurityHelper.currentIdentity(approver);
        SecurityHelper.requireFinanceOrSystem(identity, "额度调整审批");
        CreditAdjustApply apply = requirePending(applyNo);
        requireNotApplicant(apply, identity);

        Customer customer = customerService.requireById(apply.getCustomerId());
        if (apply.getLimitAfter().compareTo(customer.getOccupiedAmount()) < 0) {
            throw new BusinessException("目标额度 " + apply.getLimitAfter().toPlainString()
                    + " 低于客户当前占用 " + customer.getOccupiedAmount().toPlainString()
                    + "，可用额度不能为负，请先回收占用或提高目标额度");
        }

        // 条件更新抢审批：同一申请只准被处理一次
        int claimed = applyMapper.update(null, new LambdaUpdateWrapper<CreditAdjustApply>()
                .eq(CreditAdjustApply::getId, apply.getId())
                .eq(CreditAdjustApply::getStatus, CreditEnums.APPLY_PENDING)
                .set(CreditAdjustApply::getStatus, CreditEnums.APPLY_APPROVED)
                .set(CreditAdjustApply::getApproverName, approver)
                .set(CreditAdjustApply::getApproveTime, LocalDateTime.now())
                .set(CreditAdjustApply::getApproveRemark, request.getRemark()));
        if (claimed == 0) {
            throw new BusinessException("申请 " + applyNo + " 刚被处理，请刷新后查看");
        }

        // 换版：旧生效版置 SUPERSEDED，新版记前后值
        CreditLimitVersion previous = versionMapper.selectOne(new LambdaQueryWrapper<CreditLimitVersion>()
                .eq(CreditLimitVersion::getCustomerId, customer.getId())
                .eq(CreditLimitVersion::getStatus, CreditEnums.VERSION_EFFECTIVE)
                .last("LIMIT 1"));
        int nextNo = (previous == null ? 0 : previous.getVersionNo()) + 1;
        if (previous != null) {
            CreditLimitVersion superseded = new CreditLimitVersion();
            superseded.setId(previous.getId());
            superseded.setStatus(CreditEnums.VERSION_SUPERSEDED);
            versionMapper.updateById(superseded);
        }
        CreditLimitVersion version = new CreditLimitVersion();
        version.setCustomerId(customer.getId());
        version.setVersionNo(nextNo);
        version.setSource(CreditEnums.VERSION_SOURCE_ADJUST);
        version.setLimitBefore(customer.getCreditLimit());
        version.setLimitAfter(apply.getLimitAfter());
        version.setApplyId(apply.getId());
        version.setApplyNo(apply.getApplyNo());
        version.setStatus(CreditEnums.VERSION_EFFECTIVE);
        version.setOperatorName(apply.getApplicantName());
        version.setApproverName(approver);
        version.setApproveTime(LocalDateTime.now());
        version.setRemark(apply.getReason());
        versionMapper.insert(version);

        customerMapper.update(null, new LambdaUpdateWrapper<Customer>()
                .eq(Customer::getId, customer.getId())
                .set(Customer::getCreditLimit, apply.getLimitAfter()));
        return applyMapper.selectById(apply.getId());
    }

    /** 驳回：留审批人与驳回原因，额度不动。 */
    @Transactional(rollbackFor = Exception.class)
    public CreditAdjustApply reject(String applyNo, ApproveRequest request) {
        String approver = Operators.resolve(request.getOperatorName());
        String identity = SecurityHelper.currentIdentity(approver);
        SecurityHelper.requireFinanceOrSystem(identity, "额度调整审批");
        CreditAdjustApply apply = requirePending(applyNo);
        requireNotApplicant(apply, identity);

        int claimed = applyMapper.update(null, new LambdaUpdateWrapper<CreditAdjustApply>()
                .eq(CreditAdjustApply::getId, apply.getId())
                .eq(CreditAdjustApply::getStatus, CreditEnums.APPLY_PENDING)
                .set(CreditAdjustApply::getStatus, CreditEnums.APPLY_REJECTED)
                .set(CreditAdjustApply::getApproverName, approver)
                .set(CreditAdjustApply::getApproveTime, LocalDateTime.now())
                .set(CreditAdjustApply::getApproveRemark, request.getRemark()));
        if (claimed == 0) {
            throw new BusinessException("申请 " + applyNo + " 刚被处理，请刷新后查看");
        }
        return applyMapper.selectById(apply.getId());
    }

    /** 额度版本翻查：新版本号在前；销售限名下客户。 */
    public List<CreditLimitVersion> listVersions(String customerNo, String operatorName) {
        String operator = Operators.resolve(operatorName);
        String identity = SecurityHelper.currentIdentity(operator);
        Customer customer = customerService.requireByNo(customerNo);
        SecurityHelper.requireCustomerAccess(identity, customer);
        return versionMapper.selectList(new LambdaQueryWrapper<CreditLimitVersion>()
                .eq(CreditLimitVersion::getCustomerId, customer.getId())
                .orderByDesc(CreditLimitVersion::getVersionNo));
    }

    /** 审批单翻查：销售只看自己提交的；财务/系统看全部，可按状态过滤。 */
    public List<CreditAdjustApply> listApplies(String status, String operatorName) {
        String operator = Operators.resolve(operatorName);
        String identity = SecurityHelper.currentIdentity(operator);
        LambdaQueryWrapper<CreditAdjustApply> wrapper = new LambdaQueryWrapper<>();
        if (SecurityHelper.isSales(identity)) {
            wrapper.eq(CreditAdjustApply::getApplicantName, identity);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(CreditAdjustApply::getStatus, status.trim());
        }
        wrapper.orderByDesc(CreditAdjustApply::getId);
        return applyMapper.selectList(wrapper);
    }

    // ---- 内部方法 ----

    private CreditAdjustApply requirePending(String applyNo) {
        CreditAdjustApply apply = applyMapper.selectOne(new LambdaQueryWrapper<CreditAdjustApply>()
                .eq(CreditAdjustApply::getApplyNo, applyNo == null ? "" : applyNo.trim()));
        if (apply == null) {
            throw new BusinessException("额度调整申请不存在: " + applyNo);
        }
        if (!CreditEnums.APPLY_PENDING.equals(apply.getStatus())) {
            throw new BusinessException("申请 " + applyNo + " 已处理（" + apply.getStatus() + "），不能重复审批");
        }
        return apply;
    }

    private void requireNotApplicant(CreditAdjustApply apply, String approverIdentity) {
        if (apply.getApplicantName().equals(approverIdentity)) {
            throw new BusinessException("审批人不能是申请人本人（" + approverIdentity + "），额度动一动要换人把关");
        }
    }

    private String generateApplyNo() {
        long seq = applyMapper.nextApplySeq();
        return String.format("CA%08d", seq);
    }
}
