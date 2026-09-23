package com.genvict.dssad.cloud.mqtt.model;

import lombok.Getter;

/**
 * 消息接收标志（报文字段 {@code res}）。
 *
 * <p>对应文档各回复报文的 {@code res} 枚举：
 * <pre>
 * 1：确认      2：消息有误      3：不支持
 * </pre>
 */
@Getter
public enum AckCode {

    /** 已确认并成功处理。 */
    CONFIRM("1", "确认"),

    /** 报文有误（字段缺失、格式非法、业务校验不通过）。对端将按 8.1 节重发。 */
    INVALID("2", "消息有误"),

    /** 消息类型不支持。 */
    UNSUPPORTED("3", "不支持");

    private final String code;
    private final String description;

    AckCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /** 是否成功。 */
    public boolean isSuccess() {
        return this == CONFIRM;
    }

    /** 按 {@code res} 值解析，未知返回 {@code null}。 */
    public static AckCode fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AckCode value : values()) {
            if (value.code.equals(normalized)) {
                return value;
            }
        }
        return null;
    }
}
