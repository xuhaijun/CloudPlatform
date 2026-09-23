package com.genvict.dssad.cloud.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用工具测试：时间处理、JSON 解析、雪花 ID、轨迹抽稀。
 *
 * <p>这四者都是「被全平台高频复用、出错后果隐蔽」的基础设施：
 * 时间格式错 1 秒会导致超时判定误报；JSON 解析不宽容会让对端升版即断链；
 * 雪花 ID 重复会导致主键冲突丢数据；抽稀算错会让轨迹图变形但没人能一眼看出来。
 */
@DisplayName("通用工具")
class CommonUtilsTest {

    // ==================== 时间处理 ====================

    @Nested
    @DisplayName("TimeUtils：东八区 + 13 位毫秒")
    class TimeHandling {

        @Test
        @DisplayName("格式化输出为 yyyy-MM-dd HH:mm:ss（文档约定的时间文本格式）")
        void formatUsesDocumentPattern() {
            assertEquals("yyyy-MM-dd HH:mm:ss", TimeUtils.PATTERN_DATETIME);

            // 2024-01-01 00:00:00 +08:00 == 1704038400000
            assertEquals("2024-01-01 00:00:00", TimeUtils.format(1704038400000L));
        }

        @Test
        @DisplayName("毫秒与 LocalDateTime 双向转换自洽")
        void millisRoundTrip() {
            long millis = 1736840005000L;
            LocalDateTime dateTime = TimeUtils.toLocalDateTime(millis);
            assertEquals(millis, TimeUtils.toEpochMillis(dateTime));
            assertEquals(millis, TimeUtils.toEpochMillis(TimeUtils.toInstant(millis)));
        }

        @ParameterizedTest
        @CsvSource({
                "'2024-01-01 00:00:00', 1704038400000",
                "'2024-01-01 08:00:00', 1704067200000"
        })
        @DisplayName("parseFlexible 支持文档要求的 yyyy-MM-dd HH:mm:ss 文本")
        void parseFlexibleAcceptsDocumentFormat(String text, long expected) {
            assertEquals(expected, TimeUtils.parseFlexible(text));
        }

        @Test
        @DisplayName("parseFlexible 兼容 ISO-8601（监管平台可能用 T 分隔）")
        void parseFlexibleAcceptsIso() {
            assertEquals(TimeUtils.parseDateTime("2024-01-01 08:00:00"),
                    TimeUtils.parseFlexible("2024-01-01T08:00:00"));
        }

        @Test
        @DisplayName("非法时间文本抛异常而不是静默返回 0（0 会被误判为 1970 年）")
        void parseInvalidThrows() {
            assertThrows(RuntimeException.class, () -> TimeUtils.parseFlexible("2024/01/01"));
            assertThrows(RuntimeException.class, () -> TimeUtils.parseFlexible("not-a-date"));
        }

        @Test
        @DisplayName("nowMillis 返回 13 位毫秒时间戳且单调不减")
        void nowMillisIsThirteenDigits() {
            long now = TimeUtils.nowMillis();
            assertTrue(String.valueOf(now).length() == 13, "必须是 13 位毫秒时间戳，实际：" + now);
            assertTrue(TimeUtils.nowMillis() >= now);
        }
    }

    // ==================== JSON ====================

    @Nested
    @DisplayName("JsonUtils：宽容解析与泛型支持")
    class JsonHandling {

        @Test
        @DisplayName("未知字段不抛异常（对端升版新增字段时平台必须继续可用）")
        void unknownPropertiesAreIgnored() {
            String json = "{\"vin\":\"VIN1\",\"brandNewField\":123}";
            Sample sample = JsonUtils.parse(json, Sample.class);
            assertEquals("VIN1", sample.vin());
        }

        @Test
        @DisplayName("listOf / listOfListOf 支持嵌套泛型（路径点 [[lat,lng],...] 必需）")
        void nestedGenericParsing() {
            String json = "[[29.1,106.1],[29.2,106.2]]";
            List<List<Double>> points = JsonUtils.parse(json, JsonUtils.listOfListOf(Double.class));

            assertEquals(2, points.size());
            assertEquals(29.1, points.get(0).get(0), 1e-9);
            assertEquals(106.2, points.get(1).get(1), 1e-9);
        }

        @Test
        @DisplayName("parseList 解析对象数组")
        void parseObjectList() {
            String json = "[{\"vin\":\"V1\"},{\"vin\":\"V2\"}]";
            List<Sample> list = JsonUtils.parseList(json, Sample.class);
            assertEquals(2, list.size());
            assertEquals("V2", list.get(1).vin());
        }

        @Test
        @DisplayName("toJson 输出 13 位毫秒时间戳（与文档 timestamp 字段一致）")
        void serializesInstantAsMillis() {
            assertTrue(JsonUtils.mapper().isEnabled(
                            com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                    "必须开启时间戳序列化，否则 Instant 会输出成 ISO 字符串，车端解析失败");
            assertNotNull(JsonUtils.toJson(Instant.ofEpochMilli(1704038400000L)));
        }

        @Test
        @DisplayName("toJson 保留值为 null 的字段（协议对账友好，与 REST 层 non_null 策略不同）")
        void toJsonHandlesNulls() {
            // 这是一条「刻意的行为约定」而不是疏漏：
            // MQTT 报文的序列化面向对端联调，显式输出 "vin":null 能让对端确认
            // 「字段存在、只是本次无值」，比直接省略更利于对账定位；
            // REST 响应则由 application.yml 的 default-property-inclusion=non_null 控制，
            // 走「精简响应体」策略。两套策略的差异已在《文档缺陷与澄清清单》中登记。
            assertEquals("{\"vin\":null}", JsonUtils.toJson(new Sample(null)));
            assertEquals("{\"vin\":\"LSV9123456789012\"}",
                    JsonUtils.toJson(new Sample("LSV9123456789012")));
        }

        /** JSON 映射样例。 */
        private record Sample(String vin) {
        }
    }

    // ==================== 雪花 ID ====================

    @Nested
    @DisplayName("SnowflakeIdGenerator：应用侧主键分配")
    class Snowflake {

        @Test
        @DisplayName("单线程连续生成 5 万个 ID 不重复且严格递增")
        void generatesUniqueMonotonicIds() {
            SnowflakeIdGenerator generator = new SnowflakeIdGenerator(-1L);
            Set<Long> seen = new HashSet<>();
            long previous = -1L;

            for (int i = 0; i < 50_000; i++) {
                long id = generator.nextId();
                assertTrue(id > previous, "ID 必须严格递增，便于按时间排序与批量插入");
                previous = id;
                assertTrue(seen.add(id), "出现重复 ID：" + id);
            }
            assertEquals(50_000, seen.size());
        }

        @Test
        @DisplayName("多线程并发生成不重复（这是应用侧主键能替代自增的前提）")
        void generatesUniqueIdsUnderConcurrency() throws InterruptedException {
            SnowflakeIdGenerator generator = new SnowflakeIdGenerator(-1L);
            int threads = 8;
            int perThread = 5_000;
            Set<Long> ids = java.util.Collections.synchronizedSet(new HashSet<>());
            AtomicInteger failures = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            if (!ids.add(generator.nextId())) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发生成超时");
            pool.shutdownNow();

            assertEquals(0, failures.get(), "并发下出现重复 ID");
            assertEquals(threads * perThread, ids.size());
        }

        @Test
        @DisplayName("workerId 落在合法区间（超出会被掩码截断而不是产生非法 ID）")
        void workerIdIsMasked() {
            SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9999L);
            assertTrue(generator.workerId() >= 0 && generator.workerId() <= 31,
                    "workerId 必须落在 0~31：实际 " + generator.workerId());
            assertTrue(generator.datacenterId() >= 0 && generator.datacenterId() <= 31);
        }
    }

    // ==================== 轨迹抽稀 ====================

    @Nested
    @DisplayName("TrackSimplifier：Douglas-Peucker 抽稀")
    class Simplification {

        /** 经纬度点。 */
        private record Point(double lat, double lng) {
        }

        private final TrackSimplifier.PointAccessor<Point> accessor = new TrackSimplifier.PointAccessor<>() {
            @Override
            public double latitude(Point point) {
                return point.lat();
            }

            @Override
            public double longitude(Point point) {
                return point.lng();
            }
        };

        @Test
        @DisplayName("点数不足 3 个或容差非正时原样返回（不做无意义的计算）")
        void trivialInputsReturnedAsIs() {
            List<Point> two = List.of(new Point(29.0, 106.0), new Point(29.1, 106.1));
            assertEquals(two, TrackSimplifier.simplify(two, 8.0, accessor));
            assertEquals(List.of(), TrackSimplifier.simplify(null, 8.0, accessor));

            List<Point> three = List.of(new Point(29.0, 106.0), new Point(29.5, 106.5), new Point(30.0, 107.0));
            assertEquals(three, TrackSimplifier.simplify(three, 0, accessor), "容差为 0 表示不抽稀");
        }

        @Test
        @DisplayName("与容差相比偏离极小的点被移除，但首尾点必须保留")
        void removesNearCollinearPointsKeepingEndpoints() {
            // 一条正东方向的近似直线（纬度恒定，经度均匀递增）
            List<Point> line = new ArrayList<>();
            for (int i = 0; i <= 100; i++) {
                line.add(new Point(29.0, 106.0 + i * 0.0001));
            }

            List<Point> simplified = TrackSimplifier.simplify(line, 8.0, accessor);

            assertTrue(simplified.size() < line.size(), "共线点应被抽稀");
            assertEquals(line.get(0), simplified.get(0), "起点必须保留");
            assertEquals(line.get(line.size() - 1), simplified.get(simplified.size() - 1), "终点必须保留");
        }

        @Test
        @DisplayName("偏离超过容差的转折点必须保留（否则轨迹图会绕过拐弯）")
        void keepsSignificantTurns() {
            // 一个直角转弯：向北 100 米后向东 100 米
            List<Point> corner = List.of(
                    new Point(29.0, 106.0),
                    new Point(29.0009, 106.0),   // 约 100 米北
                    new Point(29.0009, 106.001)); // 约 97 米东

            List<Point> simplified = TrackSimplifier.simplify(corner, 8.0, accessor);
            assertEquals(3, simplified.size(), "明显的拐点不能被抽掉");
        }

        @Test
        @DisplayName("抽稀结果保持原始顺序（轨迹回放不能乱序）")
        void preservesOrder() {
            List<Point> zigzag = List.of(
                    new Point(29.0000, 106.0000),
                    new Point(29.0010, 106.0005),
                    new Point(29.0000, 106.0010),
                    new Point(29.0010, 106.0015),
                    new Point(29.0020, 106.0020));

            List<Point> simplified = TrackSimplifier.simplify(zigzag, 5.0, accessor);

            List<Point> expectedOrder = zigzag.stream().filter(simplified::contains).toList();
            assertEquals(expectedOrder, simplified, "结果顺序必须与原始点序一致");
        }
    }
}
