package com.genvict.dssad.cloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 平台业务配置（前缀 {@code dssad}）。
 *
 * <p>用 record + {@link DefaultValue} 做<b>构造器绑定</b>：不可变、无 setter，
 * 避免运行期被人改配置导致行为漂移；同时每个参数都有安全默认值，
 * 保证「零配置即可在本地跑起来」（MQTT 默认关闭、存储默认落本地磁盘）。
 *
 * @param enterpriseId   准入平台分配的企业 ID（云云 Topic 的身份标识）
 * @param enterpriseName 企业名称，用于日志与前端展示
 * @param mqtt           MQTT 通道配置
 * @param regulatory     监管平台 HTTP 对接配置
 * @param storage        媒体存储配置
 * @param srs            流媒体（SRS）配置
 * @param api            平台自身接口约束
 * @param rateLimit      限流配置
 * @param retention      遥测流水保留策略（轨迹点/状态流水的清理周期）
 * @param security       安全相关配置（Actuator 端点来源 IP 白名单）
 */
@ConfigurationProperties(prefix = "dssad")
public record AppProperties(
        @DefaultValue("DSSAD-ENT-0001") String enterpriseId,
        @DefaultValue("车路通DSSAD云平台") String enterpriseName,
        @DefaultValue Mqtt mqtt,
        @DefaultValue Regulatory regulatory,
        @DefaultValue Storage storage,
        @DefaultValue Srs srs,
        @DefaultValue Api api,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Audit audit,
        @DefaultValue RemoteDriving remoteDriving,
        @DefaultValue Retention retention,
        @DefaultValue Security security) {

    /**
     * MQTT 通道配置。
     *
     * @param enabled            是否启用真实 Broker 连接（本地开发/单测默认 false，零依赖启动）
     * @param brokerUrl          Broker 地址，文档 5.1.1：{@code mqtts://{host}:1883}；本地可用 {@code tcp://}
     * @param clientIdPrefix     clientId 前缀，文档 5.1.1：{@code e_{enterpriseId}}
     * @param username           username，文档 5.1.2：企业 ID
     * @param password           password，留空时自动通过监管平台接口换取（见 RegulatoryPassportClient）
     * @param keepAliveSeconds   心跳，文档约定 60s
     * @param cleanSession       文档 5.1.1 明确为 false（持久会话，断线期间消息不丢）
     * @param subscribeCarUplink 是否订阅车云上行（企业自建网关/车端直连场景，通道 B）
     * @param reconnect          断线重连策略（文档 8.2）
     * @param retry              未确认消息的重发策略（文档 5.1.4.1 与 8.1）
     * @param offlineCacheSize   离线队列容量上限（超出按优先级丢弃最低优先级数据）
     * @param consumerThreads    入站报文消费线程数（隔离 MQTT 回调线程用）
     */
    public record Mqtt(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("tcp://127.0.0.1:1883") String brokerUrl,
            @DefaultValue("e_") String clientIdPrefix,
            @DefaultValue("") String username,
            @DefaultValue("") String password,
            @DefaultValue("60") int keepAliveSeconds,
            @DefaultValue("false") boolean cleanSession,
            @DefaultValue("true") boolean subscribeCarUplink,
            @DefaultValue Reconnect reconnect,
            @DefaultValue Retry retry,
            @DefaultValue("10000") int offlineCacheSize,
            @DefaultValue("4") int consumerThreads) {
    }

    /**
     * 断线重连策略（文档 8.2：首次立即，后续指数退避 2s/4s/8s/16s，最大 60s）。
     *
     * @param initialBackoffMillis 首次退避间隔
     * @param maxBackoffMillis     退避上限
     * @param multiplier           退避倍数
     */
    public record Reconnect(
            @DefaultValue("2000") long initialBackoffMillis,
            @DefaultValue("60000") long maxBackoffMillis,
            @DefaultValue("2.0") double multiplier) {
    }

    /**
     * 重发策略。
     *
     * <p>文档存在两处口径，本实现<b>并存支持</b>：
     * <ul>
     *   <li>5.1.4.1 / 6.1.4.1：对端未确认（{@code res != 1}）时，<b>间隔 5s、最多 3 次</b>；</li>
     *   <li>8.1：收到异常回复时，<b>间隔 3s、至少 5 次</b>（原文「至少发送 5 次」与「已重发满 3 次停止」自相矛盾）。</li>
     * </ul>
     * 实现取「更保守」的并集：未确认走 confirm 策略，明确异常走 invalid 策略。
     *
     * @param confirmIntervalMillis 未确认重发间隔
     * @param confirmMaxAttempts    未确认最大重发次数
     * @param invalidIntervalMillis 异常回复重发间隔
     * @param invalidMaxAttempts    异常回复最大重发次数
     */
    public record Retry(
            @DefaultValue("5000") long confirmIntervalMillis,
            @DefaultValue("3") int confirmMaxAttempts,
            @DefaultValue("3000") long invalidIntervalMillis,
            @DefaultValue("5") int invalidMaxAttempts) {
    }

    /**
     * 监管平台 HTTP 对接配置。
     *
     * @param baseUrl         平台 Base Url，文档 6.2.1
     * @param mqttAccessPath  换取 MQTT 连接密钥，文档 6.2.2.1.1
     * @param tokenPath       换取平台临时 token，文档 6.2.2.1.2
     * @param mapehnPath      今日地图增强信息，文档 6.2.2.2.1
     * @param secretSalt      密钥生成盐，文档为 {@code SHA256(clientId + "CMAT")}
     * @param areaCode        企业所在监管区域编码（地图拉取用，P-06）。定时任务以此为准；
     *                        手动拉取接口未传参时也回落到该值。为空时拉取将得到空结果
     * @param timeoutMillis   单次请求超时（文档 8.3：30s）
     * @param retryDelaysMillis 重试间隔（文档 8.3：1s, 2s, 4s）
     */
    public record Regulatory(
            @DefaultValue("https://reg-platform.example.com") String baseUrl,
            @DefaultValue("/passport/api/v1/auth/access-enterprise-mqtt") String mqttAccessPath,
            @DefaultValue("/passport/api/v1/auth/access-token") String tokenPath,
            @DefaultValue("/manager/api/v1/event/mapehn") String mapehnPath,
            @DefaultValue("CMAT") String secretSalt,
            @DefaultValue("") String areaCode,
            @DefaultValue("30000") int timeoutMillis,
            @DefaultValue({"1000", "2000", "4000"}) long[] retryDelaysMillis) {
    }

    /**
     * 媒体存储配置。
     *
     * @param type          存储类型：{@code local} 本地文件系统（开发/单机）或 {@code minio} 对象存储（生产）
     * @param localRoot     本地存储根目录
     * @param publicBaseUrl 对外可访问的基础地址，用于拼接历史视频下载地址
     */
    public record Storage(
            @DefaultValue("local") String type,
            @DefaultValue("./data/media") String localRoot,
            @DefaultValue("http://127.0.0.1:8080/media") String publicBaseUrl,
            @DefaultValue("209715200") long maxUploadBytes) {
    }

    /**
     * SRS 流媒体配置（实时视频拉流地址由 SRS 提供，文档 6.3.2.1.1）。
     *
     * @param httpBaseUrl 播放地址前缀（HTTP-FLV / HLS）
     * @param app         应用名，默认 {@code live}
     */
    public record Srs(
            @DefaultValue("http://127.0.0.1:8080") String httpBaseUrl,
            @DefaultValue("live") String app) {
    }

    /**
     * 平台接口约束。
     *
     * @param maxPageSize          分页上限，防止前端一次拉全表
     * @param trackSimplifyTolerance 轨迹抽稀容差（米），0 表示不抽稀
     * @param historyVideoMaxSpanHours 历史视频单次查询最大跨度（小时）
     */
    public record Api(
            @DefaultValue("200") int maxPageSize,
            @DefaultValue("8.0") double trackSimplifyTolerance,
            @DefaultValue("168") int historyVideoMaxSpanHours) {
    }

    /**
     * 限流配置（文档 9.1：MQTT 单车辆 10 条/秒，HTTP 单车辆 100 次/分钟）。
     *
     * @param enabled       是否开启
     * @param httpPerMinute 单车辆 HTTP 每分钟上限
     * @param mqttPerSecond 单车辆 MQTT 每秒发布上限
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100") int httpPerMinute,
            @DefaultValue("10") int mqttPerSecond) {
    }

    /**
     * 报文留痕策略。
     *
     * <p>留痕是监管取证的硬要求，但 1Hz 报文全量落库的成本很高（1000 台车 ≈ 8640 万条/天），
     * 因此提供三档模式由部署方按存储预算选择。
     *
     * @param mode          留痕模式：{@code full} 全量 / {@code sampled} 采样 / {@code event-only} 仅事件类
     * @param sampleRate    {@code sampled} 模式下的采样率（0~1）
     * @param retentionDays 保留天数，超期由定时任务清理，避免表无限膨胀
     */
    public record Audit(
            @DefaultValue("full") String mode,
            @DefaultValue("0.05") double sampleRate,
            @DefaultValue("180") int retentionDays) {
    }

    /**
     * 远程驾驶接管配置（对应接口文档 6.3.2.2.1「远程驾驶接管发起」）。
     *
     * <p>监管平台/交巡警发起远程接管请求时，企业云端需返回实际执行接管的远驾舱与驾驶员信息。
     * 这些信息属于企业侧运力资源，故通过配置维护（生产环境可改为从远驾舱管理系统实时查询）。
     *
     * @param cockpits 远驾舱资源池；为空时使用内置兜底资源，保证接口可用
     */
    public record RemoteDriving(
            @DefaultValue({}) Cockpit[] cockpits) {

        /** 远驾舱资源。 */
        public record Cockpit(
                @DefaultValue("YK0001") String cockpitId,
                @DefaultValue("待分配") String driver,
                @DefaultValue("") String identityNumber,
                @DefaultValue("") String driverLicenseNumber,
                @DefaultValue("") String mobile) {
        }
    }

    /**
     * 遥测流水保留策略（对应两张「流水大表」的清理任务）。
     *
     * <p>为什么不放在 {@link Audit} 里：留痕表（{@code t_mqtt_message_log}）的保留期由
     * <b>监管取证要求</b>决定（越长越好），而遥测流水的保留期由<b>存储预算</b>决定
     * （轨迹表 1000 台车 ≈ 8640 万行/天，是所有表中增长最快的一张）。
     * 两者依据不同、调整节奏不同，混在一个配置项里迟早出现「为了省磁盘把取证数据清了」的事故。
     *
     * <p>取值为 {@code 0} 表示<b>不清理</b>。这个默认档位只给「刚接入、数据量极小」的场景用；
     * 生产环境必须显式配置正数，否则表会在数周内涨到无法维护。
     *
     * @param trackPointDays    轨迹点保留天数（{@code t_vehicle_track_point}），0 表示不清理
     * @param stateSnapshotDays 运行状态流水保留天数（{@code t_vehicle_state_snapshot}），0 表示不清理
     * @param batchSize         单批删除行数上限（分批是为了避免长事务与主从复制延迟）
     */
    public record Retention(
            @DefaultValue("90") int trackPointDays,
            @DefaultValue("180") int stateSnapshotDays,
            @DefaultValue("5000") int batchSize) {
    }

    /**
     * 安全相关配置。
     *
     * @param actuator Actuator 端点访问控制
     */
    public record Security(
            @DefaultValue Actuator actuator) {

        /**
         * Actuator 端点（{@code /actuator/**}）的<b>来源 IP 白名单</b>。
         *
         * <p>为什么必须有这一层：{@code /actuator} 不受 {@code AdminTokenInterceptor} 保护
         * （该拦截器只注册在 {@code /api/v1/**} 上，其 {@code startsWith("/actuator")} 分支
         * 实际永不执行），因此一旦端口可达就等于<b>无鉴权暴露</b>：
         * {@code /actuator/metrics}、{@code /configprops}、{@code /heapdump} 足以拼出系统说明书。
         *
         * <p>生产侧虽由 Nginx 把 {@code /actuator/} 收敛成 404，但那只是外围一层 ——
         * 容器网络内的其它容器、同宿主机进程、误开的端口映射都能绕过它。
         *
         * <p><b>fail-closed 语义</b>（三条同时成立才算）：
         * <ul>
         *   <li>不配置 → 使用下方默认值，而不是「不限制」；</li>
         *   <li>显式配成空列表 → 拒绝所有来源（含回环），等于彻底关闭 HTTP 访问，
         *       <b>不会</b>退化成放行；</li>
         *   <li>配置非法（如 {@code 10.0.0.0/33}）→ 启动直接失败，绝不静默降级。</li>
         * </ul>
         *
         * <p><b>安全前提</b>：判定基于 TCP 对端地址，<b>不采信</b> {@code X-Forwarded-For}。
         * 因为 {@code server.forward-headers-strategy: framework} 会把 XFF 还原进
         * {@code getRemoteAddr()}，若直接读该方法，任何人加一个
         * {@code X-Forwarded-For: 127.0.0.1} 就能伪造来源绕过白名单。
         *
         * <p>部署形态对应的取值：
         * <pre>
         * 本机 / systemd（Nginx 同机）      127.0.0.1/32,::1/128                     ← 默认值
         * docker compose（Nginx 在 Docker） 127.0.0.1/32,::1/128,172.16.0.0/12
         * K8s（kubelet 从节点探活）         127.0.0.1/32,::1/128,10.0.0.0/8
         * 完全不需要 Actuator 的 HTTP 访问  （留空）
         * </pre>
         *
         * @param allowedCidrs 允许访问的来源 CIDR 列表（支持单 IP、IPv4/IPv6、IPv4-mapped IPv6）
         */
        public record Actuator(
                @DefaultValue({"127.0.0.1/32", "::1/128"}) String[] allowedCidrs) {
        }
    }

    /** 云云 MQTT username：文档 5.1.2 / 6.1.2 规定为企业 ID。 */
    public String mqttUsername() {
        return mqtt.username() == null || mqtt.username().isBlank() ? enterpriseId : mqtt.username();
    }

    /** MQTT clientId：{@code e_{enterpriseId}}。 */
    public String mqttClientId() {
        return mqtt.clientIdPrefix() + enterpriseId;
    }
}
