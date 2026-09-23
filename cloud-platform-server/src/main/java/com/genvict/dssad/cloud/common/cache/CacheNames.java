package com.genvict.dssad.cloud.common.cache;

/**
 * 平台缓存名常量。
 *
 * <p>集中定义避免 {@code @Cacheable("dashboardOverview")} 这类<b>字符串字面量</b>
 * 散落各处 —— 拼错一个字母不会编译报错，只会静默地「永远不命中」，
 * 是最难排查的一类缓存问题。
 */
public final class CacheNames {

    /** 运营总览（大屏首屏），变化快 → 短 TTL。 */
    public static final String DASHBOARD_OVERVIEW = "dashboardOverview";

    /** 近 N 天事故/故障趋势。 */
    public static final String DASHBOARD_TREND = "dashboardTrend";

    /** 故障码分类分布。 */
    public static final String DASHBOARD_FAULT_CATEGORY = "dashboardFaultCategory";

    /** 车辆故障榜。 */
    public static final String DASHBOARD_FAULT_VEHICLES = "dashboardFaultVehicles";

    /** 全部缓存名，供 {@code CacheManager} 预创建与监控端点枚举。 */
    public static final String[] ALL = {
            DASHBOARD_OVERVIEW,
            DASHBOARD_TREND,
            DASHBOARD_FAULT_CATEGORY,
            DASHBOARD_FAULT_VEHICLES
    };

    private CacheNames() {
    }
}
