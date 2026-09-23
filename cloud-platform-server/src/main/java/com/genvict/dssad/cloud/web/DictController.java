package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.domain.dict.FaultCodeDictionary;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典下发接口。
 *
 * <p><b>为什么字典要走接口下发？</b>故障码、摄像头方向、报文类型这些枚举
 * 同时被「协议解析」与「前端展示」使用。如果前端各写一份，一旦协议升版就会出现
 * 前后端不一致的显示错误（历史上这类问题排查成本极高）。
 * 统一由后端下发，前端只做渲染，字典变更只需改后端一处并被单元测试覆盖。
 */
@RestController
@RequestMapping("/api/v1/dict")
@RequiredArgsConstructor
public class DictController {

    /** 故障码全量字典（附录 A.1）。 */
    @GetMapping("/fault-codes")
    public ApiResponse<List<Map<String, Object>>> faultCodes() {
        List<Map<String, Object>> result = new ArrayList<>(FaultCodeDictionary.values().length);
        for (FaultCodeDictionary item : FaultCodeDictionary.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.getCode());
            row.put("name", item.getName());
            row.put("category", item.getCategory().name());
            row.put("categoryLabel", item.getCategory().getLabel());
            row.put("description", item.getDescription());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    /** 故障分类字典（5 大类 + 未知）。 */
    @GetMapping("/fault-categories")
    public ApiResponse<List<Map<String, Object>>> faultCategories() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FaultCodeDictionary.Category category : FaultCodeDictionary.Category.values()) {
            // UNKNOWN 是展示兜底分类，不属于协议定义，不下发给前端做筛选项
            if (category == FaultCodeDictionary.Category.UNKNOWN) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", category.name());
            row.put("label", category.getLabel());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    /** 摄像头方向字典（1 前 / 2 右 / 3 后 / 4 左）。 */
    @GetMapping("/camera-directions")
    public ApiResponse<List<Map<String, Object>>> cameraDirections() {
        List<Map<String, Object>> result = new ArrayList<>();
        MediaService.directionDictionary().forEach((value, label) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", value);
            row.put("label", label);
            result.add(row);
        });
        return ApiResponse.ok(result);
    }

    /** MQTT 报文类型字典（16 种，含归属、方向、QoS 与文档章节号，便于排障时对照文档）。 */
    @GetMapping("/mqtt-types")
    public ApiResponse<List<Map<String, Object>>> mqttTypes() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MqttType type : MqttType.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", type.getCode());
            row.put("description", type.getDescription());
            row.put("scope", type.getScope().name());
            row.put("naturalDirection", type.getNaturalDirection().name());
            row.put("qos", type.getQos());
            row.put("docRef", type.getDocRef());
            row.put("needAck", type.needAck());
            row.put("ackTypeCode", type.getAckTypeCode());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    /** 平台错误码字典（前端据此把 code 映射为可读提示）。 */
    @GetMapping("/error-codes")
    public ApiResponse<List<Map<String, Object>>> errorCodes() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ErrorCode code : ErrorCode.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code.getCode());
            row.put("message", code.getMessage());
            result.add(row);
        }
        return ApiResponse.ok(result);
    }

    /** 视频流类型字典。 */
    @GetMapping("/stream-types")
    public ApiResponse<List<Map<String, Object>>> streamTypes() {
        return ApiResponse.ok(List.of(
                Map.of("value", "1", "label", "m3u8"),
                Map.of("value", "2", "label", "flv")));
    }
}
