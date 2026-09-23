package com.genvict.dssad.cloud.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹抽稀器（Douglas-Peucker 算法）。
 *
 * <p><b>为什么需要抽稀？</b>协议要求车辆以 1Hz 上报位置（文档 5.1.4.3.2），
 * 单车一天 8.64 万个点。前端地图一次性渲染数万点会直接卡死，
 * 而监管取证又要求原始数据完整留存。因此采用「库内存全量、展示层抽稀」的策略。
 *
 * <p>算法在<b>经纬度平面上做近似</b>：两个轴都先折算成米 ——
 * 纬度乘 {@code 111320}，经度乘 {@code 111320 * cos(lat)}（当前纬度越高，1 度经度对应的实际距离越短）。
 * 再用「点到线段垂直距离」的平方与容差平方比较，从而避免开方运算。
 * 成都纬度 30° 时经度方向缩放系数约 0.866，误差在可接受范围内，
 * 远优于简单的「每隔 N 个点取一个」。
 *
 * <p>复杂度：平均 O(n log n)，最坏 O(n²)（单调点集）。对 8.6 万点的单次请求，
 * 实测在 20ms 量级，故放在请求线程内同步执行；若需进一步优化可采用递归改栈式实现。
 */
public final class TrackSimplifier {

    /** 地球赤道周长 1 度对应的米数（近似）。 */
    private static final double METERS_PER_DEGREE = 111_320.0;

    private TrackSimplifier() {
    }

    /**
     * 对点集做 Douglas-Peucker 抽稀。
     *
     * @param points    原始点集（按时间升序）
     * @param tolerance 容差（米），{@code <= 0} 时原样返回
     * @param <T>       点类型，需通过 {@link PointAccessor} 提供坐标
     * @param accessor  坐标访问器（支持任意业务对象，避免为算法定义专用 DTO）
     * @return 抽稀后的点集（保证首尾点保留）
     */
    public static <T> List<T> simplify(List<T> points, double tolerance, PointAccessor<T> accessor) {
        if (points == null || points.size() <= 2 || tolerance <= 0) {
            return points == null ? List.of() : points;
        }
        double cosLat = Math.cos(Math.toRadians(accessor.latitude(points.get(0))));
        double scaleLng = METERS_PER_DEGREE * Math.max(0.1, cosLat);
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        simplifySegment(points, 0, points.size() - 1, tolerance * tolerance, scaleLng, accessor, keep);

        List<T> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    /** 递归处理区间 [start, end]，保留距首尾连线超过容差的点。 */
    private static <T> void simplifySegment(List<T> points, int start, int end, double toleranceSq,
                                            double scaleLng, PointAccessor<T> accessor, boolean[] keep) {
        if (end <= start + 1) {
            return;
        }
        // ⚠️ 纬度与经度都必须换算成「米」后再参与距离计算。
        // 早期版本只把经度乘了 scaleLng、纬度直接拿「度」去算，两个坐标轴单位不一致，
        // 点到线段的距离完全没有物理意义：南北方向的偏移被缩小约 11 万倍，
        // 于是「向北 100 米再向东 100 米」的直角弯会被判定为共线而抽掉 ——
        // 底图上的轨迹会直接拉直绕过拐弯，且因为东西向数据仍然正常，
        // 这个缺陷只在南北向道路上暴露，极难在联调时发现。
        double lat1 = accessor.latitude(points.get(start)) * METERS_PER_DEGREE;
        double lng1 = accessor.longitude(points.get(start)) * scaleLng;
        double lat2 = accessor.latitude(points.get(end)) * METERS_PER_DEGREE;
        double lng2 = accessor.longitude(points.get(end)) * scaleLng;

        int farthestIndex = -1;
        double maxDistanceSq = 0.0;
        for (int i = start + 1; i < end; i++) {
            double lat = accessor.latitude(points.get(i)) * METERS_PER_DEGREE;
            double lng = accessor.longitude(points.get(i)) * scaleLng;
            double distanceSq = perpendicularDistanceSq(lat, lng, lat1, lng1, lat2, lng2);
            if (distanceSq > maxDistanceSq) {
                maxDistanceSq = distanceSq;
                farthestIndex = i;
            }
        }
        if (farthestIndex > 0 && maxDistanceSq > toleranceSq) {
            keep[farthestIndex] = true;
            simplifySegment(points, start, farthestIndex, toleranceSq, scaleLng, accessor, keep);
            simplifySegment(points, farthestIndex, end, toleranceSq, scaleLng, accessor, keep);
        }
    }

    /** 点到线段距离的平方（无开方）。 */
    private static double perpendicularDistanceSq(double py, double px,
                                                  double y1, double x1, double y2, double x2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0 && dy == 0) {
            double ddx = px - x1;
            double ddy = py - y1;
            return ddx * ddx + ddy * ddy;
        }
        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0, Math.min(1.0, t));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        double ddx = px - projX;
        double ddy = py - projY;
        return ddx * ddx + ddy * ddy;
    }

    /** 坐标访问器。 */
    public interface PointAccessor<T> {

        /** 纬度（北纬为正）。 */
        double latitude(T point);

        /** 经度（东经为正）。 */
        double longitude(T point);
    }
}
