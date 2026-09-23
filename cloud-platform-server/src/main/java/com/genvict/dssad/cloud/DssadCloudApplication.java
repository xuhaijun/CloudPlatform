package com.genvict.dssad.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 车路通 DSSAD 产品云平台 —— 后端服务启动类。
 *
 * <p>本服务承担三类职责：
 * <ol>
 *   <li><b>企业云端 MQTT 通道（云云交互）</b>：作为 MQTT Client 接入监管平台 Broker，
 *       上行发布 {@code uvodp/{enterpriseId}/{mqttType}/up}，下行订阅
 *       {@code uvodp/{enterpriseId}/{mqttType}/down}。</li>
 *   <li><b>企业云端 HTTP 服务</b>：向监管平台开放实时视频、历史视频、远程驾驶接管发起等接口
 *       （{@code /enterprise/api/v1/**}）。</li>
 *   <li><b>企业侧业务云平台</b>：车辆遥测/事件/故障/任务/远驾/地图增强数据的接入、存储、分析与
 *       可视化，并对管理前端开放 {@code /api/v1/**}。</li>
 * </ol>
 *
 * @author 车路通 DSSAD 云平台研发组
 * @since 1.0.0
 */
@EnableAsync
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class DssadCloudApplication {

    public static void main(String[] args) {
        SpringApplication.run(DssadCloudApplication.class, args);
    }
}
