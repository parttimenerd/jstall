package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JVMDiscovery filtering functionality.
 */
class JVMDiscoveryFilterTest {

    @Test
    void testListJVMsWithoutFilter() throws IOException {
        List<JVMDiscovery.JVMProcess> jvms = JVMDiscovery.listJVMs();

        assertNotNull(jvms);
        // JVMs list should not include jstack
        for (JVMDiscovery.JVMProcess jvm : jvms) {
            assertFalse(jvm.mainClass().contains("jstack"),
                "JVMs list should not include jstack");
        }
    }

    @Test
    void testListJVMsWithFilter() throws IOException {
        // First get all JVMs
        List<JVMDiscovery.JVMProcess> allJVMs = JVMDiscovery.listJVMs();

        if (allJVMs.isEmpty()) {
            // No JVMs to filter, skip test
            return;
        }

        // Pick a known part of a JVM's main class
        String filterTerm = allJVMs.getFirst().mainClass().split("\\.")[0]; // Get first package name

        List<JVMDiscovery.JVMProcess> filtered = JVMDiscovery.listJVMs(filterTerm);

        assertNotNull(filtered);
        // All filtered JVMs should contain the filter term
        for (JVMDiscovery.JVMProcess jvm : filtered) {
            assertTrue(jvm.mainClass().toLowerCase().contains(filterTerm.toLowerCase()),
                "Filtered JVM should contain filter term: " + filterTerm);
        }
    }

    @Test
    void testListJVMsWithNonMatchingFilter() throws IOException {
        String impossibleFilter = "VeryUnlikelyJVMName9999XYZ";

        List<JVMDiscovery.JVMProcess> filtered = JVMDiscovery.listJVMs(impossibleFilter);

        assertNotNull(filtered);
        assertTrue(filtered.isEmpty(), "Should find no JVMs with impossible filter");
    }

    @Test
    void testListJVMsWithNullFilter() throws IOException {
        List<JVMDiscovery.JVMProcess> jvms = JVMDiscovery.listJVMs(null);

        assertNotNull(jvms);
        // Should behave like no filter
        List<JVMDiscovery.JVMProcess> noFilter = JVMDiscovery.listJVMs();
        assertEquals(noFilter.size(), jvms.size());
    }

    @Test
    void testListJVMsWithEmptyFilter() throws IOException {
        List<JVMDiscovery.JVMProcess> jvms = JVMDiscovery.listJVMs("");

        assertNotNull(jvms);
        // Should behave like no filter
        List<JVMDiscovery.JVMProcess> noFilter = JVMDiscovery.listJVMs();
        assertEquals(noFilter.size(), jvms.size());
    }

    @Test
    void testListJVMsCaseInsensitiveFilter() throws IOException {
        List<JVMDiscovery.JVMProcess> allJVMs = JVMDiscovery.listJVMs();

        if (allJVMs.isEmpty()) {
            return;
        }

        // Get a term from an actual JVM
        String mainClass = allJVMs.getFirst().mainClass();
        if (mainClass.length() < 3) {
            return; // Skip if too short
        }

        String term = mainClass.substring(0, 3);

        // Test with uppercase
        List<JVMDiscovery.JVMProcess> upperCase = JVMDiscovery.listJVMs(term.toUpperCase());

        // Test with lowercase
        List<JVMDiscovery.JVMProcess> lowerCase = JVMDiscovery.listJVMs(term.toLowerCase());

        // Should find same results regardless of case
        assertEquals(upperCase.size(), lowerCase.size(),
            "Filter should be case-insensitive");
    }

    @Test
    void testListJVMsExcludesCurrentJVM() throws IOException {
        long currentPid = ProcessHandle.current().pid();

        List<JVMDiscovery.JVMProcess> jvms = JVMDiscovery.listJVMs();

        for (JVMDiscovery.JVMProcess jvm : jvms) {
            assertNotEquals(currentPid, jvm.pid(),
                "JVMs list should not include the current JVM process");
        }
    }

    @Test
    void testJVMProcessRecord() {
        JVMDiscovery.JVMProcess jvm = new JVMDiscovery.JVMProcess(12345, "com.example.Main");

        assertEquals(12345, jvm.pid());
        assertEquals("com.example.Main", jvm.mainClass());

        String str = jvm.toString();
        assertTrue(str.contains("12345"));
        assertTrue(str.contains("com.example.Main"));
    }
}