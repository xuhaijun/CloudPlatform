package com.genvict.dssad.cloud.domain.dict;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 车端故障码字典（接口文档<b>附录 A.1</b> 全量实现）。
 *
 * <p>把码表固化在代码中而不是让前端硬编码或依赖数据库配置表，原因有三：
 * <ol>
 *   <li>附录 A 是<b>规范性</b>内容（文档明确标注「（规范性）」），随协议版本发布，不是运营可改数据；</li>
 *   <li>故障码是告警、统计、报表的公共维度，放在代码里才能被单元测试覆盖（码值拼错会立刻暴露）；</li>
 *   <li>接口 {@code /api/v1/dict/fault-codes} 直接把本字典下发给前端，
 *       避免前后端两份码表导致展示不一致。</li>
 * </ol>
 *
 * <p>注意：协议只保证「已知码」的含义，实际运行中可能出现字典外的新码，
 * 因此 {@link #find} 返回 {@link Optional}，展示层对未知码统一显示为「未知故障码(码值)」而不丢弃。
 */
@Getter
public enum FaultCodeDictionary {

    // ==================== 硬件故障 ====================
    BATTERY_SYSTEM("4352", "电池系统故障", Category.HARDWARE, "车端电池系统故障（如电芯损坏、电压异常）"),
    MOTOR_SYSTEM("4353", "电机系统故障", Category.HARDWARE, "车端电机系统故障（如电机转速异常、扭矩失效）"),
    BRAKE_FLUID_LOW("4355", "制动液液位低", Category.HARDWARE, "车端制动液液位低于安全阈值"),
    BATTERY_VOLTAGE_LOW("4356", "蓄电池电压低", Category.HARDWARE, "车端低压蓄电池电压异常（如亏电）"),
    TIRE_PRESSURE_ALARM("4357", "胎压报警", Category.HARDWARE, "车端轮胎压力异常（过高/过低）"),
    POWER_BATTERY_ALARM("4358", "动力电池故障报警", Category.HARDWARE, "电动车动力电池故障（如热失控、电芯不均衡）"),

    // ==================== 基础软件故障 ====================
    DECISION_ABNORMAL("4609", "决策功能异常", Category.BASE_SOFTWARE, "车端智能驾驶决策模块（如行为决策）输出异常"),
    PLANNING_ABNORMAL("4610", "规划功能异常", Category.BASE_SOFTWARE, "车端路径规划模块（如局部轨迹规划）生成失败"),
    LONGITUDINAL_CONTROL_ABNORMAL("4611", "纵向控制异常", Category.BASE_SOFTWARE, "车端纵向控制（如 ACC/AEB）执行失效"),
    LATERAL_CONTROL_ABNORMAL("4612", "横向控制异常", Category.BASE_SOFTWARE, "车端横向控制（如 LKA/LCC）执行失效"),
    GNSS_TIME_FAULT("4613", "GNSS授时故障", Category.BASE_SOFTWARE, "车端 GNSS 时间同步异常（如授时信号丢失）"),

    // ==================== 系统故障 ====================
    GNSS_SIGNAL_FAULT("4864", "GNSS信号故障", Category.SYSTEM, "车端 GNSS 定位信号丢失或精度异常（如无卫星信号）"),
    CAN_BUS_DISCONNECTED("4865", "CAN总线接口断开", Category.SYSTEM, "车端 CAN 总线与 ECU/传感器连接断开"),
    VEHICLE_ETHERNET_FAULT("4866", "车载以太网故障", Category.SYSTEM, "车端以太网（如 ADAS 域控制器交互）通信异常"),
    SENSOR_DATA_INVALID("4867", "传感器数据异常", Category.SYSTEM, "车端传感器（摄像头/雷达）输出数据无效"),
    COMPUTE_PLATFORM_FAULT("4868", "自动驾驶计算平台异常", Category.SYSTEM, "车端自动驾驶域控制器（如 Orin）故障"),
    IN_VEHICLE_NETWORK_HEARTBEAT("4870", "车内网心跳异常",
            Category.SYSTEM, "车内 SOME/IP、DDS 等以太网通信协议心跳检测失败或超时，ECU 间服务连接异常中断或未恢复"),
    POWER_CONTROL_SYSTEM_FAULT("4992", "动力控制系统故障", Category.SYSTEM, "动力控制系统故障"),
    CHASSIS_CONTROL_SYSTEM_FAULT("4993", "底盘控制系统故障", Category.SYSTEM, "底盘控制系统故障"),
    IN_VEHICLE_TIME_SYNC_ABNORMAL("4994", "车内时间同步异常", Category.SYSTEM, "PTP/NTP 内网同步异常"),

    // ==================== 业务服务故障 ====================
    REMOTE_CONTROL_COMMAND_FAILED("5121", "远程控车指令执行失败",
            Category.BUSINESS_SERVICE, "接收云端远程控车指令后无法执行或执行超时"),
    COOPERATIVE_CONTROL_FAILED("5122", "协同控制指令执行失败",
            Category.BUSINESS_SERVICE, "接收云端协同控制指令后无法执行或执行超时"),
    COOPERATIVE_DECISION_FAILED("5123", "协同决策指令执行失败",
            Category.BUSINESS_SERVICE, "接收云端协同决策指令后无法执行或执行超时"),
    CLOUD_TASK_FAILED("5124", "云端任务执行失败", Category.BUSINESS_SERVICE, "接受云端的任务，执行失败"),
    COOPERATIVE_PERCEPTION_FUSION_FAILED("5125", "协同感知融合失败",
            Category.BUSINESS_SERVICE, "无法有效融合路侧或云端提供的感知数据（如遮挡车辆补全失败）"),
    HD_MAP_MATCH_FAILED("5126", "高精地图匹配失败",
            Category.BUSINESS_SERVICE, "车辆定位结果无法与高精地图车道拓扑对齐"),
    LANE_CHANGE_CONFLICT("5136", "变道决策冲突",
            Category.BUSINESS_SERVICE, "多目标博弈下变道策略矛盾，导致长时间犹豫或急刹"),

    // ==================== 运维服务故障 ====================
    OTA_STATUS_UNKNOWN("5380", "OTA升级状态未知",
            Category.OPERATION_MAINTENANCE, "OTA 任务状态未上报或处于卡死状态（如下载完成但未安装）"),
    BLACK_BOX_STORAGE_ABNORMAL("5381", "黑匣子（EDR/DSSAD）存储异常",
            Category.OPERATION_MAINTENANCE, "自动驾驶事件数据记录器写入失败或存储介质损坏");

    private static final Map<String, FaultCodeDictionary> CODE_INDEX = Arrays.stream(values())
            .collect(Collectors.toMap(FaultCodeDictionary::getCode, Function.identity()));

    private final String code;
    private final String name;
    private final Category category;
    private final String description;

    FaultCodeDictionary(String code, String name, Category category, String description) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    /** 按码值查字典，未知返回 {@link Optional#empty()}。 */
    public static Optional<FaultCodeDictionary> find(String code) {
        return code == null ? Optional.empty()
                : Optional.ofNullable(CODE_INDEX.get(code.trim()));
    }

    /** 中文名称，未知码返回「未知故障码(码值)」。 */
    public static String nameOf(String code) {
        return find(code).map(FaultCodeDictionary::getName)
                .orElseGet(() -> "未知故障码(" + code + ")");
    }

    /** 分类，未知码归入 {@link Category#UNKNOWN}。 */
    public static Category categoryOf(String code) {
        return find(code).map(FaultCodeDictionary::getCategory).orElse(Category.UNKNOWN);
    }

    /** 故障码分类。 */
    @Getter
    public enum Category {
        /** 硬件故障。 */
        HARDWARE("硬件故障"),
        /** 基础软件故障。 */
        BASE_SOFTWARE("基础软件故障"),
        /** 系统故障。 */
        SYSTEM("系统故障"),
        /** 业务服务故障。 */
        BUSINESS_SERVICE("业务服务故障"),
        /** 运维服务故障。 */
        OPERATION_MAINTENANCE("运维服务故障"),
        /** 字典外的新码。 */
        UNKNOWN("未知分类");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
