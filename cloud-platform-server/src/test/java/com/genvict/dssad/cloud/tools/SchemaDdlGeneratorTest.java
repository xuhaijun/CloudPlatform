package com.genvict.dssad.cloud.tools;

import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import com.genvict.dssad.cloud.domain.entity.FaultItem;
import com.genvict.dssad.cloud.domain.entity.FaultRecord;
import com.genvict.dssad.cloud.domain.entity.MapBarrier;
import com.genvict.dssad.cloud.domain.entity.MediaAsset;
import com.genvict.dssad.cloud.domain.entity.MqttMessageLog;
import com.genvict.dssad.cloud.domain.entity.NavigationRoute;
import com.genvict.dssad.cloud.domain.entity.RemoteDrivingRecord;
import com.genvict.dssad.cloud.domain.entity.TrackTask;
import com.genvict.dssad.cloud.domain.entity.Vehicle;
import com.genvict.dssad.cloud.domain.entity.VehicleStateSnapshot;
import com.genvict.dssad.cloud.domain.entity.VehicleStaticParam;
import com.genvict.dssad.cloud.domain.entity.VehicleTrackPoint;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表脚本生成工具（不是功能测试，默认不执行）。
 *
 * <p><b>为什么要生成而不是手写？</b>手写 DDL 与 JPA 实体一旦不同步，生产环境
 * {@code spring.jpa.hibernate.ddl-auto=validate} 会在启动时直接失败；而人工核对
 * 13 张表的上百个字段极易遗漏。用 Hibernate 自身的元数据导出，可保证
 * 「实体 = 脚本」，脚本再经人工补充分区/字符集后纳入版本管理。
 *
 * <p>执行方式（显式开启，避免污染日常构建）：
 * <pre>
 * mvn test -Dtest=SchemaDdlGeneratorTest -Dddl.gen=true
 * </pre>
 * 输出：{@code db/schema-mysql.generated.sql}。若输出目录不存在会先创建。
 *
 * <p>注意：Hibernate 6 已移除 {@code org.hibernate.tool.hbm2ddl.SchemaExport}，
 * 改为 {@link SchemaManagementToolCoordinator#process} + JPA 脚本生成属性。
 */
@EnabledIfSystemProperty(named = "ddl.gen", matches = "true")
class SchemaDdlGeneratorTest {

    /** 输出路径（相对项目根目录）。 */
    private static final String OUTPUT_FILE = "db/schema-mysql.generated.sql";

    /** 全部 JPA 实体（新增实体时必须同步登记，否则 DDL 会漏表）。 */
    private static final Class<?>[] ENTITIES = {
            Vehicle.class,
            VehicleStaticParam.class,
            VehicleStateSnapshot.class,
            VehicleTrackPoint.class,
            AccidentEvent.class,
            FaultRecord.class,
            FaultItem.class,
            TrackTask.class,
            RemoteDrivingRecord.class,
            MapBarrier.class,
            MediaAsset.class,
            MqttMessageLog.class,
            NavigationRoute.class
    };

    @Test
    void generateMysqlDdl() {
        File output = new File(OUTPUT_FILE);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs(), "无法创建输出目录：" + parent.getAbsolutePath());
        }
        // 必须先删除旧文件：Hibernate 的脚本输出是**追加**模式，
        // 不清理会得到「多次运行的 DDL 叠在一个文件里」，diff 会充满无意义的重复段落，
        // 比对基线也就失去意义（该文件曾累积到 3 份完整的 create table）。
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException("无法删除旧的 DDL 文件：" + output.getAbsolutePath());
        }

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                // 只按元数据推导类型，不连数据库：显式指定方言 + 空连接提供者
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.connection.provider_class",
                        "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl")
                .build();

        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : ENTITIES) {
                sources.addAnnotatedClass(entity);
            }
            Metadata metadata = sources.buildMetadata();

            Map<String, Object> settings = new HashMap<>();
            // 脚本动作与数据库动作相互独立：这里只要脚本，不执行任何 DDL
            settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
            settings.put("jakarta.persistence.schema-generation.scripts.create-target", output.getAbsolutePath());
            settings.put("jakarta.persistence.schema-generation.scripts.create-source", "metadata");
            settings.put("hibernate.hbm2ddl.delimiter", ";");
            settings.put("hibernate.format_sql", "true");

            SchemaManagementToolCoordinator.process(metadata, registry, settings, null);

            if (Boolean.getBoolean("ddl.probe")) {
                for (org.hibernate.mapping.PersistentClass binding : metadata.getEntityBindings()) {
                    org.hibernate.mapping.Table table = binding.getTable();
                    for (org.hibernate.mapping.Column column : table.getColumns()) {
                        System.out.println("[PROBE] " + table.getName() + "." + column.getName()
                                + " -> " + column.getSqlType(metadata));
                    }
                }
            }

            assertTrue(output.exists() && output.length() > 0,
                    "DDL 脚本生成失败：" + output.getAbsolutePath());
            System.out.println("[DDL] 已生成 " + output.getAbsolutePath()
                    + "（" + output.length() + " 字节，含 " + ENTITIES.length + " 个实体）");
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
