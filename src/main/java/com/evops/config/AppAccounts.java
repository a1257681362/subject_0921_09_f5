package com.evops.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地演示账号的唯一来源：Shiro 登录认证与 service 层角色判断（数据隔离、审批权限）都从这里取。
 * 演示环境账号即密码；生产应替换为数据库 realm，角色判断口径不变。
 */
public final class AppAccounts {

    private AppAccounts() {}

    /** 角色：系统（全能）/ 销售（仅名下客户）/ 财务（审批、回款、全量明细） */
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_SALES = "sales";
    public static final String ROLE_FINANCE = "finance";

    /** username -> [password, role] */
    private static final Map<String, String[]> ACCOUNTS;

    static {
        Map<String, String[]> accounts = new LinkedHashMap<>();
        accounts.put("bootstrap", new String[]{"bootstrap", ROLE_SYSTEM});
        // 周例会演示账号
        accounts.put("demo", new String[]{"demo", ROLE_SYSTEM});
        // 销售：只能看自己名下的客户
        accounts.put("sales01", new String[]{"sales01", ROLE_SALES});
        accounts.put("sales02", new String[]{"sales02", ROLE_SALES});
        // 财务：额度审批、回款登记与核销、全量占用明细
        accounts.put("finance01", new String[]{"finance01", ROLE_FINANCE});
        ACCOUNTS = Collections.unmodifiableMap(accounts);
    }

    public static Map<String, String[]> all() {
        return ACCOUNTS;
    }

    /** 账号角色；查不到返回 null（调用方按系统角色兜底，兼容历史经手人）。 */
    public static String roleOf(String username) {
        String[] account = ACCOUNTS.get(username);
        return account == null ? null : account[1];
    }
}
