package com.genvict.dssad.cloud.support;

import com.genvict.dssad.cloud.config.AppProperties;

/**
 * 单元测试用的配置夹具。
 *
 * <p>{@link AppProperties} 是 record（构造器绑定、不可变），无法用 setter 拼装，
 * 若每个测试各自 {@code new} 一遍会重复上百行。此处集中构造一份「与
 * {@code application-test.yml} 保持一致」的配置，既能被纯单元测试直接使用，
 * 也保证测出来的行为与集成测试一致。
 *
 * <p>⚠️ 修改 {@code application-test.yml} 时请同步修改本类，否则会出现
 * 「纯单测与 Spring 测试行为不一致」的诡异现象。
 *
 * <p>⚠️ {@link AppProperties} 每新增一个 record 字段，本类的<b>全部</b>
 * {@code new AppProperties(...)} 调用点都要补上对应实参 ——
 * 漏掉任何一处都是编译错误（这是 record 的好处：不会静默用默认值顶上）。
 */
public final class TestProperties {

    /** 测试企业 ID（与 application-test.yml 一致）。 */
    public static final String ENTERPRISE_ID = "DSSAD-TEST-0001";

    private TestProperties() {
    }

    public static AppProperties defaults() {
        return new AppProperties(
                ENTERPRISE_ID,
                "单测企业",
                new AppProperties.Mqtt(
                        false,
                        "tcp://127.0.0.1:1883",
                        "e_",
                        "",
                        "",
                        60,
                        false,
                        true,
                        new AppProperties.Reconnect(2000, 60000, 2.0),
                        // 与文档两个口径一致：未确认 5s/3 次，异常 3s/5 次
                        new AppProperties.Retry(5000, 3, 3000, 5),
                        10000,
                        4),
                new AppProperties.Regulatory(
                        "http://127.0.0.1:9/regulatory",
                        "/passport/api/v1/auth/access-enterprise-mqtt",
                        "/passport/api/v1/auth/access-token",
                        "/manager/api/v1/event/mapehn",
                        "CMAT",
                        500,
                        new long[]{10, 20}),
                new AppProperties.Storage(
                        "local",
                        "./target/test-media",
                        "http://127.0.0.1:8080/media",
                        209715200L),
                new AppProperties.Srs("http://127.0.0.1:8080", "live"),
                new AppProperties.Api(200, 8.0, 168),
                new AppProperties.RateLimit(true, 100, 10),
                new AppProperties.Audit("full", 0.05, 1),
                new AppProperties.RemoteDriving(new AppProperties.RemoteDriving.Cockpit[]{
                        new AppProperties.RemoteDriving.Cockpit("YK-TEST-01", "测试驾驶员A",
                                "510100199001011234", "510100199001011", "13800000001"),
                        new AppProperties.RemoteDriving.Cockpit("YK-TEST-02", "测试驾驶员B",
                                "510100199002022345", "510100199002022", "13800000002")
                }),
                // 与 application-test.yml 一致：单测默认关闭自动清理，避免定时任务删掉其他用例的数据
                new AppProperties.Retention(0, 0, 100),
                defaultSecurity());
    }

    /**
     * 指定遥测保留策略的配置（供清理逻辑测试使用）。
     *
     * <p>清理行为必须用「保留 1 天」这类能立刻观察到的档位来断言：
     * 默认值 0 表示不清理，直接拿来测会得到「删了 0 行」这个假绿结果。
     */
    public static AppProperties withRetention(int trackPointDays, int stateSnapshotDays, int batchSize) {
        AppProperties base = defaults();
        return new AppProperties(base.enterpriseId(), base.enterpriseName(), base.mqtt(),
                base.regulatory(), base.storage(), base.srs(), base.api(), base.rateLimit(),
                base.audit(), base.remoteDriving(),
                new AppProperties.Retention(trackPointDays, stateSnapshotDays, batchSize),
                base.security());
    }

    /**
     * 限流关闭的配置。
     *
     * <p>批量注入报文（如轨迹 1Hz 测试）会瞬间超过文档约定的 10 条/秒，
     * 这是<b>配置生效</b>而非缺陷，此时应显式关闭限流而不是放宽阈值。
     */
    public static AppProperties rateLimitDisabled() {
        AppProperties base = defaults();
        return new AppProperties(base.enterpriseId(), base.enterpriseName(), base.mqtt(),
                base.regulatory(), base.storage(), base.srs(), base.api(),
                new AppProperties.RateLimit(false, 100, 10),
                base.audit(), base.remoteDriving(), base.retention(), base.security());
    }

    /**
     * 指定离线队列容量的配置。
     *
     * <p>用于验证「队列满时按优先级淘汰」：默认容量 10000，不可能在单测中填满，
     * 因此需要把容量压到个位数来精确断言淘汰顺序。
     */
    public static AppProperties withOfflineCacheSize(int size) {
        AppProperties base = defaults();
        AppProperties.Mqtt m = base.mqtt();
        AppProperties.Mqtt resized = new AppProperties.Mqtt(
                m.enabled(), m.brokerUrl(), m.clientIdPrefix(), m.username(), m.password(),
                m.keepAliveSeconds(), m.cleanSession(), m.subscribeCarUplink(),
                m.reconnect(), m.retry(), size, m.consumerThreads());
        return new AppProperties(base.enterpriseId(), base.enterpriseName(), resized,
                base.regulatory(), base.storage(), base.srs(), base.api(),
                base.rateLimit(), base.audit(), base.remoteDriving(), base.retention(),
                base.security());
    }

    /** 默认 Actuator 来源白名单：仅回环地址（与 {@code application.yml} 的默认值一致）。 */
    public static AppProperties.Security defaultSecurity() {
        return new AppProperties.Security(new AppProperties.Security.Actuator(
                new String[]{"127.0.0.1/32", "::1/128"}));
    }

    /**
     * 指定 Actuator 来源白名单的配置。
     *
     * <p>用于验证 fail-closed 语义：传空数组即代表「拒绝所有来源」，
     * 此时连回环地址也必须被拒 —— 这是白名单最容易实现错的地方
     * （写成「未配置就放行」等于白名单形同虚设）。
     */
    public static AppProperties withActuatorCidrs(String... cidrs) {
        AppProperties base = defaults();
        return new AppProperties(base.enterpriseId(), base.enterpriseName(), base.mqtt(),
                base.regulatory(), base.storage(), base.srs(), base.api(), base.rateLimit(),
                base.audit(), base.remoteDriving(), base.retention(),
                new AppProperties.Security(new AppProperties.Security.Actuator(cidrs)));
    }
}
