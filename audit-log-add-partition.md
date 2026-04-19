

Read [](file:///Users/zhangmao/github/doris/fe/fe-core/src/main/java/org/apache/doris/plugin/AuditEvent.java#110-110), lines 110 to 175

Read [](file:///Users/zhangmao/github/doris/fe/fe-core/src/main/java/org/apache/doris/qe/AuditLogHelper.java#280-280), lines 280 to 395

Read [](file:///Users/zhangmao/github/doris/fe/fe-core/src/main/java/org/apache/doris/plugin/audit/AuditLoader.java#40-40), lines 40 to 100

Read [](file:///Users/zhangmao/github/doris/fe/fe-core/src/main/java/org/apache/doris/plugin/audit/AuditLogBuilder.java#95-95), lines 95 to 181

以下是技术设计文档：

---

# Apache Doris 审计日志增加表分区字段 — 技术设计方案

**版本：** 1.1  
**日期：** 2026-04-19  

---

## 一、背景与动机

### 1.1 问题描述

Apache Doris 的审计日志（`fe.audit.log`）已记录了每条 SQL 的表名、视图名（`queriedTablesAndViews`）等信息，但缺乏**分区粒度**的访问记录。在以下场景中，这一缺失导致可观测性不足：

- **数据访问审计**：安全合规要求记录用户实际访问了哪些分区的数据，仅知道访问了哪张表是不够的；
- **性能问题排查**：慢查询分析时需要知道扫描了哪些分区，判断是否存在分区裁剪失效；
- **数据血缘追踪**：INSERT/UPDATE/DELETE 操作写入了哪些分区，对数据流转链路的治理有重要价值。

### 1.2 目标

在不影响现有审计日志格式（兼容旧字段）的前提下，新增 `queriedPartitions` 字段，覆盖 `SELECT`、`INSERT`、`UPDATE`、`DELETE` 四类 DML 语句，记录每条 SQL 实际涉及的表分区信息。

---

## 二、现有架构分析

### 2.1 审计日志整体流程

```
SQL 执行完成
     │
     ▼
AuditLogHelper.logAuditLog()        ← 入口，捕获所有异常防止影响主流程
     │
     ▼
logAuditLogImpl()                    ← 核心：从 ConnectContext + NereidsPlanner 提取信息
     │  填充 AuditEventBuilder
     ▼
AuditEvent (POJO)                    ← 数据载体，所有字段通过 @AuditField 注解声明
     │
     ├─► AuditLogBuilder             ← 反射遍历 @AuditField，拼装 fe.audit.log 日志行
     │        fe.audit.log           ← 文本文件，格式 |Key=Value|...
     │
     └─► AuditLoader                 ← Stream Load 写入内部表
              __internal_schema.audit_log（列顺序与 InternalSchema.java 定义一致）
```

### 2.2 关键组件

| 组件 | 文件 | 职责 |
|---|---|---|
| `AuditEvent` | `plugin/AuditEvent.java` | 审计事件 POJO，字段通过 `@AuditField` 声明，驱动日志生成 |
| `AuditLogHelper` | `qe/AuditLogHelper.java` | SQL 执行后回调，从 `ConnectContext`/`NereidsPlanner` 提取字段值 |
| `AuditLogBuilder` | `plugin/audit/AuditLogBuilder.java` | 反射读取 `@AuditField` 字段，写 `fe.audit.log` 文件 |
| `AuditLoader` | `plugin/audit/AuditLoader.java` | 按固定列顺序拼 CSV，Stream Load 写内部审计表 |
| `InternalSchema` | `catalog/InternalSchema.java` | 定义 `audit_log` 内部表的 Schema |

### 2.3 物理执行计划树结构（Nereids）

Doris Nereids 优化器将 SQL 编译为**物理计划树**，其叶节点和根节点承载了分区信息：

```
INSERT INTO t PARTITION(p1) SELECT * FROM src WHERE dt='2024-01'

PhysicalOlapTableSink (Root)      ← 写入节点，partitionIds = [p1_id]
    targetTable = t
    partitionIds = [明确指定的分区 ID 列表]
    │
    └── ... (中间算子)
            │
            └── PhysicalOlapScan (Leaf)  ← 扫描节点，经过 partition pruning
                    table = src
                    selectedPartitionIds = [经谓词裁剪后实际扫描的分区 ID 列表]
```

**关键洞察：**
- `PhysicalOlapScan.selectedPartitionIds`：优化器 partition pruning 后的**实际读取分区集合**，比 SQL 中写的 `PARTITION(...)` 包含更多信息（谓词隐式裁剪）；
- `PhysicalOlapTableSink.partitionIds`：INSERT 时**显式指定**的写入分区，若未指定则为空（运行时路由，编译期不可知）。

---

## 三、设计方案

### 3.1 数据采集时机

分区信息**只能在物理计划生成后**才能获取（partition pruning 发生在优化阶段），因此采集点选在 `AuditLogHelper.logAuditLogImpl()` 中已有的 `nereidsPlanner.getPhysicalPlan() != null` 分支内，此时物理计划已完整生成。

### 3.2 字段设计

**字段名：** `queriedPartitions`  
**日志 Key：** `queriedPartitions`（写 `fe.audit.log`）  
**内部表列名：** `queried_partitions`（类型 `STRING`）  
**值格式：** JSON Object，key 为全限定表名，value 为分区名列表

```json
{
  "internal.db.src_table": ["p_2024_q1", "p_2024_q2"],
  "[write]internal.db.dst_table": ["p_2024_q1"]
}
```

**Key 命名规则：**
- 读表（`PhysicalOlapScan`）：`catalog.database.table`（三段式全限定名）
- 写表（`PhysicalOlapTableSink`）：`[write]catalog.database.table`（加 `[write]` 前缀，区分读写）

### 3.3 覆盖范围

| 语句类型 | 分区来源 | 收集条件 |
|---|---|---|
| `SELECT` | `PhysicalOlapScan.selectedPartitionIds` | 总是有（partition pruning 后） |
| `INSERT INTO t PARTITION(p1,p2) VALUES(...)` | `PhysicalOlapTableSink.partitionIds` | `partitionIds` 非空时 |
| `INSERT INTO t SELECT * FROM src` | `PhysicalOlapScan`（源表）+ `PhysicalOlapTableSink`（目标，若指定） | 均可收集 |
| `UPDATE` / `DELETE` | `PhysicalOlapScan.selectedPartitionIds`（过滤后扫描的分区） | 总是有 |
| `INSERT INTO t VALUES(...)` 不指定分区 | 无 | 分区由运行时路由决定，编译期不可知，**不输出** |

---

## 四、实现细节

### 4.1 文件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `plugin/AuditEvent.java` | 新增字段 + setter | 新增 `queriedPartitions` 字段及 `setQueriedPartitions()` |
| `qe/AuditLogHelper.java` | 新增逻辑 | 从物理计划树提取分区信息并填充 |
| `catalog/InternalSchema.java` | 新增列 | `audit_log` 内部表 schema 新增 `queried_partitions` 列 |
| `plugin/audit/AuditLoader.java` | 新增字段输出 | CSV 拼装中追加 `event.queriedPartitions` |

### 4.2 AuditEvent.java — 字段声明

```java
// table, view, m-view
@AuditField(value = "queriedTablesAndViews", colName = "queried_tables_and_views")
public String queriedTablesAndViews = "";
@AuditField(value = "chosenMViews", colName = "chosen_m_views")
public String chosenMViews = "";
// 新增
@AuditField(value = "queriedPartitions", colName = "queried_partitions")
public String queriedPartitions = "";
```

`@AuditField` 注解由 `AuditLogBuilder` 在运行时通过反射扫描，**零侵入地**自动写入 `fe.audit.log`，无需修改 `AuditLogBuilder` 本身。

### 4.3 AuditLogHelper.java — 分区提取逻辑

```
NereidsPlanner
    │
    ├─ getPhysicalPlan()
    │   ├─ collectToList(PhysicalOlapScan.class::isInstance)   → 所有读表节点
    │   │       ↓
    │   │   scan.getSelectedPartitionIds()                     → partition ID 列表
    │   │       ↓
    │   │   table.getPartition(partId).getName()              → 分区名
    │   │
    │   └─ collectToList(PhysicalOlapTableSink.class::isInstance) → 所有写表节点
    │           ↓
    │       sink.getPartitionIds()（非空则处理）                → partition ID 列表
    │           ↓
    │       table.getPartition(partId).getName()              → 分区名
    │
    └─ 聚合为 Map<tableKey, List<partitionName>>
           ↓
       序列化为 JSON String → auditEventBuilder.setQueriedPartitions(...)
```

**多 Scan 节点的合并处理**：同一张表可能出现多个 `PhysicalOlapScan`（如 JOIN 或子查询），通过 `Map.merge()` 合并分区列表，避免重复 key。

### 4.4 InternalSchema.java — 内部表 Schema

新增列紧跟 `chosen_m_views` 之后，保证 `AuditLoader` 的列序与 Schema 定义一致：

```java
AUDIT_SCHEMA.add(new ColumnDef("chosen_m_views",
        new TypeDef(new ArrayType(ScalarType.STRING)), ColumnNullableType.NULLABLE));
// 新增
AUDIT_SCHEMA.add(new ColumnDef("queried_partitions",
        TypeDef.create(PrimitiveType.STRING), ColumnNullableType.NULLABLE));
```

选择 `STRING` 而非 `ArrayType`，原因是该字段是 **map 结构**（表 → 分区列表），不适合用数组表达。

### 4.5 AuditLoader.java — 内部表写入

```java
// queried tables, views and m-views
logBuffer.append(event.queriedTablesAndViews).append(AUDIT_TABLE_COL_SEPARATOR);
logBuffer.append(event.chosenMViews).append(AUDIT_TABLE_COL_SEPARATOR);
logBuffer.append(event.queriedPartitions).append(AUDIT_TABLE_COL_SEPARATOR); // 新增
```

⚠️ `AuditLoader` 使用**固定列顺序**拼 CSV（Stream Load），列追加位置必须与 `InternalSchema` 中的列定义顺序严格对应。

---

## 五、日志输出示例

### `fe.audit.log` 新增字段

```
|queriedTablesAndViews=["internal.demo.orders"]|chosenMViews=[]|queriedPartitions={"internal.demo.orders":["p_2024_q1","p_2024_q2"]}|
```

### 典型场景示例

**场景 1：SELECT with partition pruning**
```sql
SELECT * FROM orders WHERE create_date BETWEEN '2024-01-01' AND '2024-03-31'
```
```
queriedPartitions={"internal.demo.orders":["p_2024_q1"]}
```

**场景 2：INSERT INTO ... SELECT**
```sql
INSERT INTO orders_bak PARTITION(p_2024_q1)
SELECT * FROM orders WHERE create_date < '2024-04-01'
```
```
queriedPartitions={"internal.demo.orders":["p_2024_q1"],"[write]internal.demo.orders_bak":["p_2024_q1"]}
```

**场景 3：DELETE with filter**
```sql
DELETE FROM orders WHERE create_date < '2023-01-01'
```
```
queriedPartitions={"internal.demo.orders":["p_2023_q1","p_2023_q2","p_2023_q3","p_2023_q4"]}
```

---

## 六、约束与局限

| 约束 | 说明 |
|---|---|
| 仅支持 Nereids 优化器 | 旧版 Legacy Planner 无 `PhysicalOlapScan` 节点，不覆盖 |
| 仅支持 OlapTable | 外表（Hive/Iceberg 等）使用不同 Scan 节点，本次不包含 |
| INSERT 不指定分区时写入侧为空 | 分区路由在 BE 运行时决定，FE 编译期不可知 |
| 内部表 Schema 随 FE 升级自动重建 | `__internal_schema.audit_log` 由 FE 自动创建和管理，无法手动 `ALTER TABLE ADD COLUMN`；升级部署后 FE 将以新 Schema 重建该表，历史数据中新列值为 NULL |

---

## 七、代码质量优化（v1.1）

初版实现经过 Review 后，发现以下问题并已修复。

### 7.1 问题清单

| 问题 | 严重程度 | 描述 |
|---|---|---|
| JSON 无转义 | 高 | 表名和分区名直接拼入 JSON 字符串，含 `"` 或 `\` 时产生非法 JSON |
| 分区名重复 | 中 | 同一张表出现多个 `PhysicalOlapScan`（如 self-join、CTE 展开）时，同一分区会重复出现 |
| 空列表写入 | 中 | partition pruning 将所有分区裁剪干净时，`partNames` 为空列表仍被写入 map，产生 `{"t":[]}` 的无意义记录 |
| 无长度上界 | 中 | JOIN 几十张大分区表时输出可超过数 KB，影响 Stream Load 性能，而 `stmt` 字段已有长度保护 |
| 代码重复 | 低 | Scan 侧和 Sink 侧的 ID→分区名解析逻辑完全相同，重复约 10 行 |

### 7.2 修改内容

**新增常量**

```java
// max length for queriedPartitions field to avoid oversized audit log entries
static final int MAX_QUERIED_PARTITIONS_LENGTH = 4096;
```

**提取辅助方法**

将 JSON 序列化 + 转义 + 截断逻辑提取为独立的 package-visible 方法，便于单元测试：

```java
// Escapes " and \ in a JSON string value to produce valid JSON output.
private static String escapeJsonValue(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
}

/**
 * Serializes the tableToPartitions map to a compact JSON string.
 * Returns null if the map is empty.
 * Output is truncated to MAX_QUERIED_PARTITIONS_LENGTH to bound log entry size.
 */
static String buildPartitionJson(Map<String, Set<String>> tableToPartitions) {
    if (tableToPartitions.isEmpty()) {
        return null;
    }
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Set<String>> entry : tableToPartitions.entrySet()) {
        if (!first) { sb.append(","); }
        sb.append("\"").append(escapeJsonValue(entry.getKey())).append("\":[");
        sb.append(entry.getValue().stream()
                .map(p -> "\"" + escapeJsonValue(p) + "\"")
                .collect(Collectors.joining(",")));
        sb.append("]");
        first = false;
    }
    sb.append("}");
    String result = sb.toString();
    if (result.length() > MAX_QUERIED_PARTITIONS_LENGTH) {
        return result.substring(0, MAX_QUERIED_PARTITIONS_LENGTH) + "...}";
    }
    return result;
}
```

**分区收集逻辑变更**

| 变更点 | 修改前 | 修改后 |
|---|---|---|
| 分区名容器类型 | `List<String>`（允许重复） | `LinkedHashSet<String>`（自动去重，保持插入顺序） |
| map 合并策略 | `ArrayList` 直接 `addAll` | `LinkedHashSet` 合并，天然去重 |
| 空集合处理 | 无守卫，空列表直接 merge | `if (!partNames.isEmpty())` 后再 merge |
| JSON 序列化 | 内联手动拼接，无转义 | 调用 `buildPartitionJson()`，含转义和截断 |

### 7.3 数据准确性说明

审计日志记录时机是 SQL 执行完成之后，但 `queriedPartitions` 字段来源于**规划阶段**的物理计划，而非运行时执行结果：

| 情况 | 准确性 |
|---|---|
| SELECT / DELETE / UPDATE 读取侧 | ✅ 与实际执行一致（partition pruning 结果即扫描范围） |
| INSERT 显式指定 `PARTITION(p1)` 写入侧 | ✅ 准确，规划即执行 |
| INSERT 未指定 `PARTITION` 写入侧 | ❌ FE 不记录，分区路由由 BE 运行时决定，FE 无法感知 |
| 非分区表（单一隐式分区） | ⚠️ 分区名等于表名（Doris 默认行为），正常记录，语义上无意义但无害 |

---

## 八、单元测试

针对 `buildPartitionJson()` 方法创建独立单元测试，覆盖所有边界情况。

**测试文件：** `fe/fe-core/src/test/java/org/apache/doris/qe/AuditLogHelperTest.java`

| 测试用例 | 验证点 |
|---|---|
| `testBuildPartitionJsonNormal` | 正常多分区输出格式 |
| `testBuildPartitionJsonEmptyMapReturnsNull` | 空 map 返回 null，不设置字段 |
| `testBuildPartitionJsonWithWritePrefix` | `[write]` 前缀区分读写 |
| `testBuildPartitionJsonDeduplication` | 同分区名不重复（Set 去重） |
| `testBuildPartitionJsonEscapesDoubleQuote` | 分区名含 `"` 时正确转义 |
| `testBuildPartitionJsonEscapesBackslash` | 表名含 `\` 时正确转义 |
| `testBuildPartitionJsonTruncatesLongOutput` | 超过 4096 字符时截断，末尾附 `...}` |
| `testBuildPartitionJsonMultipleTables` | 多表 JOIN 场景，保持插入顺序 |
| `testBuildPartitionJsonNonPartitionedTable` | 非分区表（隐式分区名=表名）正常处理 |
| `testTruncateByBytesWithinLimit` | `truncateByBytes` 不截断场景 |
| `testTruncateByBytesExceedsLimit` | `truncateByBytes` 截断并附后缀 |
| `testTruncateByBytesExactLimit` | 恰好等于限制长度时不截断 |

---

## 九、扩展方向

1. **外表分区支持**：扩展至 `PhysicalFileScan`，覆盖 Hive/Iceberg 等外表分区；
2. **Tablet 粒度**：在 `selectedPartitionIds` 基础上进一步收集 `selectedTabletIds`，支持更细粒度审计；
3. **旧 Planner 兼容**：从 Legacy Planner 的 `ScanNode.selectedPartitionIds` 中提取，覆盖未迁移到 Nereids 的场景。