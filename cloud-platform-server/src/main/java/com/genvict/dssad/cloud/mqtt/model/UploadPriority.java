package com.genvict.dssad.cloud.mqtt.model;

/**
 * 断线期间数据的补传优先级。
 *
 * <p>文档 8.4「重连后优先上传：<b>事件数据 &gt; 基础数据 &gt; 状态数据</b>」。
 * 枚举序号越小优先级越高，离线队列按此排序出队。
 */
public enum UploadPriority {

    /** 事件数据：事故、故障、远驾接管、任务、媒体请求回复。 */
    EVENT(0, "事件数据"),

    /** 基础数据：准静态参数、导航路径、地图增强回复。 */
    BASE(1, "基础数据"),

    /** 状态数据：低频运行状态、低频基础信息（1s 一条，量最大，优先级最低）。 */
    STATE(2, "状态数据");

    private final int order;
    private final String description;

    UploadPriority(int order, String description) {
        this.order = order;
        this.description = description;
    }

    public int order() {
        return order;
    }

    public String description() {
        return description;
    }
}
