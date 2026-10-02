package com.evops.credit.support;

import com.evops.common.BusinessException;
import com.evops.config.AppAccounts;
import com.evops.credit.entity.Customer;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * 授信域的身份与数据隔离口径：
 * - Web 场景身份取 Shiro 登录主体（请求里的经手人字段只是留底，不能用来抬权）；
 * - 非 Web 场景（测试）按经手人识别；
 * - 销售只看/只动自己名下的客户；财务与系统角色看全部、管审批与回款。
 */
public final class SecurityHelper {

    private SecurityHelper() {}

    /** 当前身份：Shiro 登录主体优先，否则回退到经手人（测试场景）。 */
    public static String currentIdentity(String fallbackOperator) {
        try {
            Subject subject = SecurityUtils.getSubject();
            if (subject.isAuthenticated() && subject.getPrincipal() != null) {
                return subject.getPrincipal().toString();
            }
        } catch (Throwable ignored) {
            // 非 Web 线程 / 无安全管理器上下文，按经手人识别
        }
        return fallbackOperator;
    }

    /** 角色：查不到账号的历史经手人按系统角色兜底（兼容 F1-F4 存量数据与测试）。 */
    public static String roleOf(String identity) {
        String role = AppAccounts.roleOf(identity);
        return role != null ? role : AppAccounts.ROLE_SYSTEM;
    }

    public static boolean isSales(String identity) {
        return AppAccounts.ROLE_SALES.equals(roleOf(identity));
    }

    public static boolean isFinanceOrSystem(String identity) {
        String role = roleOf(identity);
        return AppAccounts.ROLE_FINANCE.equals(role) || AppAccounts.ROLE_SYSTEM.equals(role);
    }

    /** 财务/系统专属动作（审批、回款、冻结等），否则拒绝。 */
    public static void requireFinanceOrSystem(String identity, String action) {
        if (!isFinanceOrSystem(identity)) {
            throw new BusinessException(action + "需要财务或系统角色，当前身份 " + identity + " 无权操作");
        }
    }

    /**
     * 销售数据隔离：返回该身份可见的归属销售过滤值——销售只能看自己名下（返回本人），
     * 财务/系统返回 null 表示不限。
     */
    public static String ownerScope(String identity) {
        return isSales(identity) ? identity : null;
    }

    /** 校验对该客户可见/可动：销售限名下客户。 */
    public static void requireCustomerAccess(String identity, Customer customer) {
        if (isSales(identity) && !identity.equals(customer.getOwnerSales())) {
            throw new BusinessException("客户 " + customer.getCustomerName()
                    + " 不在 " + identity + " 名下，销售只能看自己名下的客户");
        }
    }
}
