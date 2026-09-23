package com.genvict.dssad.cloud.mqtt.model;

/**
 * 报文方向（对应附录 B.1 的 up / down）。
 *
 * <p>站在<b>企业云端</b>视角理解本枚举容易混淆，这里固定按<b>Topic 语义</b>定义：
 * <ul>
 *   <li>{@link #UP}：发布方向，「车端/企业云端 → 监管平台」；</li>
 *   <li>{@link #DOWN}：下发方向，「监管平台 → 车端/企业云端」。</li>
 * </ul>
 * 因此企业云端的实际行为是：<b>发布 up 主题、订阅 down 主题</b>。
 */
public enum TopicDirection {

    /** 上行：发布到监管平台。 */
    UP("up"),

    /** 下行：从监管平台订阅。 */
    DOWN("down");

    private final String segment;

    TopicDirection(String segment) {
        this.segment = segment;
    }

    /** Topic 末段字面量。 */
    public String segment() {
        return segment;
    }

    /**
     * 取反方向。
     *
     * <p>用于推导 ACK 的发布方向：请求方向与回复方向在协议里恒为相反，
     * 手写 {@code DOWN} 会在「请求本身是下行」的场景（如平台下发 {@code mapehn/down}、
     * 企业云端回复 {@code mapehn-resp/up}）悄悄写错，因此统一由本方法推导。
     */
    public TopicDirection opposite() {
        return this == UP ? DOWN : UP;
    }

    /** 由 Topic 末段解析方向，非法值返回 {@code null}。 */
    public static TopicDirection fromSegment(String segment) {
        for (TopicDirection direction : values()) {
            if (direction.segment.equalsIgnoreCase(segment)) {
                return direction;
            }
        }
        return null;
    }
}
