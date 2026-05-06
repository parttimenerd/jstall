package me.bechberger.jstall.cli;

import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.CollectionSchedule;
import me.bechberger.jstall.provider.requirement.DataRequirement;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class CollectedDataHelperTest {

    /** Minimal DataRequirement stub for testing. */
    private static DataRequirement stubRequirement(String type) {
        return new DataRequirement() {
            @Override public String getType() { return type; }
            @Override public CollectionSchedule getSchedule() { return CollectionSchedule.once(); }
            @Override public CollectedData collect(JMXDiagnosticHelper helper, int idx) { return null; }
            @Override public void persist(ZipOutputStream z, String p, List<CollectedData> s) {}
            @Override public List<CollectedData> load(ZipFile z, String p) { return List.of(); }
        };
    }

    private static CollectedData sample(long timestamp, String data) {
        return new CollectedData(timestamp, data, Map.of());
    }

    // --- toByTypeMap tests ---

    @Test
    void toByTypeMap_groupsByType() {
        var req1 = stubRequirement("thread-dumps");
        var req2 = stubRequirement("gc-heap-info");
        Map<DataRequirement, List<CollectedData>> raw = new LinkedHashMap<>();
        raw.put(req1, List.of(sample(100, "dump1"), sample(200, "dump2")));
        raw.put(req2, List.of(sample(150, "heap")));

        var result = CollectedDataHelper.toByTypeMap(raw);

        assertEquals(2, result.size());
        assertEquals(2, result.get("thread-dumps").size());
        assertEquals(1, result.get("gc-heap-info").size());
    }

    @Test
    void toByTypeMap_sortsWithinTypeByTimestamp() {
        var req = stubRequirement("thread-dumps");
        Map<DataRequirement, List<CollectedData>> raw = Map.of(
                req, List.of(sample(300, "c"), sample(100, "a"), sample(200, "b"))
        );

        var result = CollectedDataHelper.toByTypeMap(raw);
        var dumps = result.get("thread-dumps");

        assertEquals(100, dumps.get(0).timestamp());
        assertEquals(200, dumps.get(1).timestamp());
        assertEquals(300, dumps.get(2).timestamp());
    }

    @Test
    void toByTypeMap_skipsNullAndBlankTypes() {
        var reqNull = stubRequirement(null);
        var reqBlank = stubRequirement("  ");
        var reqValid = stubRequirement("valid");
        Map<DataRequirement, List<CollectedData>> raw = new LinkedHashMap<>();
        raw.put(reqNull, List.of(sample(1, "x")));
        raw.put(reqBlank, List.of(sample(2, "y")));
        raw.put(reqValid, List.of(sample(3, "z")));

        var result = CollectedDataHelper.toByTypeMap(raw);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("valid"));
    }

    @Test
    void toByTypeMap_emptyInput() {
        var result = CollectedDataHelper.toByTypeMap(Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void toByTypeMap_mergesMultipleRequirementsWithSameType() {
        var req1 = stubRequirement("thread-dumps");
        var req2 = stubRequirement("thread-dumps");
        Map<DataRequirement, List<CollectedData>> raw = new LinkedHashMap<>();
        raw.put(req1, List.of(sample(100, "a")));
        raw.put(req2, List.of(sample(200, "b")));

        var result = CollectedDataHelper.toByTypeMap(raw);

        assertEquals(1, result.size());
        assertEquals(2, result.get("thread-dumps").size());
    }

    // --- merge tests ---

    @Test
    void merge_combinesDisjointKeys() {
        Map<String, List<CollectedData>> first = Map.of("a", List.of(sample(1, "x")));
        Map<String, List<CollectedData>> second = Map.of("b", List.of(sample(2, "y")));

        var result = CollectedDataHelper.merge(first, second);

        assertEquals(2, result.size());
        assertEquals(1, result.get("a").size());
        assertEquals(1, result.get("b").size());
    }

    @Test
    void merge_combinesOverlappingKeysAndSorts() {
        Map<String, List<CollectedData>> first = Map.of("t", List.of(sample(300, "c")));
        Map<String, List<CollectedData>> second = Map.of("t", List.of(sample(100, "a"), sample(200, "b")));

        var result = CollectedDataHelper.merge(first, second);

        assertEquals(1, result.size());
        var list = result.get("t");
        assertEquals(3, list.size());
        assertEquals(100, list.get(0).timestamp());
        assertEquals(200, list.get(1).timestamp());
        assertEquals(300, list.get(2).timestamp());
    }

    @Test
    void merge_emptyMaps() {
        var result = CollectedDataHelper.merge(Map.of(), Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void merge_firstEmptySecondNot() {
        Map<String, List<CollectedData>> second = Map.of("x", List.of(sample(5, "val")));

        var result = CollectedDataHelper.merge(Map.of(), second);

        assertEquals(1, result.size());
        assertEquals("val", result.get("x").get(0).rawData());
    }

    // --- extractSystemProps tests ---

    @Test
    void extractSystemProps_returnsNullWhenNoProps() {
        var result = CollectedDataHelper.extractSystemProps(Map.of());
        assertNull(result);
    }

    @Test
    void extractSystemProps_returnsNullForEmptyList() {
        Map<String, List<CollectedData>> byType = Map.of("system-properties", List.of());
        assertNull(CollectedDataHelper.extractSystemProps(byType));
    }

    @Test
    void extractSystemProps_parsesProperties() {
        // Mimics jcmd VM.system_properties output format
        String propsOutput = "java.version=17.0.1\njava.vendor=Eclipse Adoptium\n";
        Map<String, List<CollectedData>> byType = Map.of(
                "system-properties", List.of(sample(1, propsOutput))
        );

        var result = CollectedDataHelper.extractSystemProps(byType);

        assertNotNull(result);
        assertEquals("17.0.1", result.get("java.version"));
        assertEquals("Eclipse Adoptium", result.get("java.vendor"));
    }
}
