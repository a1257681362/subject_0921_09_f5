package com.evops.credit;

/** 授信与回款状态口径。 */
public final class CreditEnums {
    private CreditEnums() {}

    /** 信用额度状态：正常 / 冻结（冻结拦截新占用，回款核销照走） */
    public static final String CREDIT_NORMAL = "NORMAL";
    public static final String CREDIT_FROZEN = "FROZEN";

    /** 信用额度版本来源：建档初始 / 调整审批 */
    public static final String VERSION_SOURCE_INITIAL = "INITIAL";
    public static final String VERSION_SOURCE_ADJUST = "ADJUST";

    /** 信用额度版本状态：生效中 / 已被新版取代 */
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";

    /** 额度调整审批状态：待审批 / 已通过 / 已驳回 */
    public static final String APPLY_PENDING = "PENDING";
    public static final String APPLY_APPROVED = "APPROVED";
    public static final String APPLY_REJECTED = "REJECTED";

    /** 占用状态：占用中 / 已核销（回款核满）/ 已释放（对冲改错） */
    public static final String OCC_OCCUPYING = "OCCUPYING";
    public static final String OCC_SETTLED = "SETTLED";
    public static final String OCC_RELEASED = "RELEASED";
}
