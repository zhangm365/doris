// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.qe;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for the partition JSON serialization logic in AuditLogHelper.
 *
 * The partition collection logic (PhysicalOlapScan / PhysicalOlapTableSink traversal) requires
 * a running Nereids planner and is covered by integration tests. The JSON serialization
 * and edge-case handling are isolated in buildPartitionJson() and tested here.
 */
public class AuditLogHelperTest {

    // -----------------------------------------------------------------------
    // buildPartitionJson tests
    // -----------------------------------------------------------------------

    /**
     * Case 1: Normal SELECT accessing two partitions on one table.
     * Expected: a compact JSON object with the table key and an array of partition names.
     */
    @Test
    public void testBuildPartitionJsonNormal() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        Set<String> parts = new LinkedHashSet<>();
        parts.add("p_2024_q1");
        parts.add("p_2024_q2");
        map.put("internal.demo.orders", parts);

        String result = AuditLogHelper.buildPartitionJson(map);
        Assert.assertEquals(
                "{\"internal.demo.orders\":[\"p_2024_q1\",\"p_2024_q2\"]}",
                result);
    }

    /**
     * Case 2: Empty map (no partitions collected at all, e.g. plan pruned everything).
     * Expected: null — caller should NOT call setQueriedPartitions.
     */
    @Test
    public void testBuildPartitionJsonEmptyMapReturnsNull() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        String result = AuditLogHelper.buildPartitionJson(map);
        Assert.assertNull(result);
    }

    /**
     * Case 3: INSERT INTO t PARTITION(p1) SELECT * FROM src — both read side and write side.
     * Expected: read table uses plain key; write table uses "[write]" prefix.
     */
    @Test
    public void testBuildPartitionJsonWithWritePrefix() {
        Map<String, Set<String>> map = new LinkedHashMap<>();

        Set<String> readParts = new LinkedHashSet<>();
        readParts.add("p_2024_q1");
        map.put("internal.demo.orders", readParts);

        Set<String> writeParts = new LinkedHashSet<>();
        writeParts.add("p_2024_q1");
        map.put("[write]internal.demo.orders_bak", writeParts);

        String result = AuditLogHelper.buildPartitionJson(map);
        Assert.assertEquals(
                "{\"internal.demo.orders\":[\"p_2024_q1\"],"
                        + "\"[write]internal.demo.orders_bak\":[\"p_2024_q1\"]}",
                result);
    }

    /**
     * Case 4: Same partition added twice (simulates same table scanned in two scan nodes, e.g.
     * self-join). LinkedHashSet in the map ensures each partition name appears exactly once.
     */
    @Test
    public void testBuildPartitionJsonDeduplication() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        // LinkedHashSet naturally deduplicates — simulate what the collection loop produces
        // after merging two scan nodes that both selected "p1"
        Set<String> parts = new LinkedHashSet<>();
        parts.add("p1");
        parts.add("p1"); // duplicate — Set ignores it
        parts.add("p2");
        map.put("internal.demo.t", parts);

        String result = AuditLogHelper.buildPartitionJson(map);
        // p1 must appear only once
        Assert.assertEquals("{\"internal.demo.t\":[\"p1\",\"p2\"]}", result);
    }

    /**
     * Case 5: Partition name contains a double-quote character.
     * Expected: the quote is escaped as \" in the output so the result is valid JSON.
     */
    @Test
    public void testBuildPartitionJsonEscapesDoubleQuote() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        Set<String> parts = new LinkedHashSet<>();
        parts.add("p_with\"quote");
        map.put("internal.demo.t", parts);

        String result = AuditLogHelper.buildPartitionJson(map);
        Assert.assertNotNull(result);
        // The partition name " must be escaped to \"
        Assert.assertTrue("Quote in partition name must be escaped",
                result.contains("p_with\\\"quote"));
    }

    /**
     * Case 6: Table key contains a backslash character.
     * Expected: the backslash is escaped as \\ in the output.
     */
    @Test
    public void testBuildPartitionJsonEscapesBackslash() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        Set<String> parts = new LinkedHashSet<>();
        parts.add("p1");
        map.put("internal.demo.t\\backslash", parts);

        String result = AuditLogHelper.buildPartitionJson(map);
        Assert.assertNotNull(result);
        Assert.assertTrue("Backslash in table key must be escaped",
                result.contains("t\\\\backslash"));
    }

    /**
     * Case 7: Output exceeds MAX_QUERIED_PARTITIONS_LENGTH (4096 chars).
     * Expected: result is truncated and ends with "...}" as a truncation marker.
     */
    @Test
    public void testBuildPartitionJsonTruncatesLongOutput() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        Set<String> parts = new LinkedHashSet<>();
        // Add 200 partition names — each ~30 chars, total > 4096
        for (int i = 0; i < 200; i++) {
            parts.add(String.format("partition_name_very_long_%03d", i));
        }
        map.put("internal.demo.very_long_table_name", parts);

        String result = AuditLogHelper.buildPartitionJson(map);
        Assert.assertNotNull(result);
        Assert.assertTrue("Result must end with truncation marker",
                result.endsWith("...}"));
        Assert.assertTrue("Result must not exceed max length + marker",
                result.length() <= AuditLogHelper.MAX_QUERIED_PARTITIONS_LENGTH + "...}".length());
    }

    /**
     * Case 8: Multiple tables in a single query (e.g. JOIN).
     * Expected: the JSON preserves insertion order (LinkedHashMap) and includes all tables.
     */
    @Test
    public void testBuildPartitionJsonMultipleTables() {
        Map<String, Set<String>> map = new LinkedHashMap<>();

        Set<String> parts1 = new LinkedHashSet<>();
        parts1.add("p1");
        map.put("internal.db.t1", parts1);

        Set<String> parts2 = new LinkedHashSet<>();
        parts2.add("p1");
        parts2.add("p2");
        map.put("internal.db.t2", parts2);

        String result = AuditLogHelper.buildPartitionJson(map);
        Assert.assertEquals(
                "{\"internal.db.t1\":[\"p1\"],\"internal.db.t2\":[\"p1\",\"p2\"]}",
                result);
    }

    /**
     * Case 9: Single table with a single partition (non-partitioned table default partition).
     * This is the observed behavior in logs for internal tables like column_statistics.
     * Even though the partition name equals the table name, it should still be recorded correctly.
     */
    @Test
    public void testBuildPartitionJsonNonPartitionedTable() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        Set<String> parts = new LinkedHashSet<>();
        parts.add("column_statistics"); // default partition = table name
        map.put("internal.__internal_schema.column_statistics", parts);

        String result = AuditLogHelper.buildPartitionJson(map);
        Assert.assertEquals(
                "{\"internal.__internal_schema.column_statistics\":[\"column_statistics\"]}",
                result);
    }

    // -----------------------------------------------------------------------
    // truncateByBytes tests (existing public method)
    // -----------------------------------------------------------------------

    @Test
    public void testTruncateByBytesWithinLimit() {
        String result = AuditLogHelper.truncateByBytes("hello world", 100, "...");
        Assert.assertEquals("hello world", result);
    }

    @Test
    public void testTruncateByBytesExceedsLimit() {
        String result = AuditLogHelper.truncateByBytes("hello world", 5, "...");
        Assert.assertEquals("hello...", result);
    }

    @Test
    public void testTruncateByBytesExactLimit() {
        // "hello" is exactly 5 bytes — should NOT truncate
        String result = AuditLogHelper.truncateByBytes("hello world", 11, "...");
        Assert.assertEquals("hello world", result);
    }
}
