package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for target resolution (formerly TargetResolver, now part of JVMDiscovery).
 */
class TargetResolverTest {

    private final JVMDiscovery discovery =
            new JVMDiscovery(new CommandExecutor.LocalCommandExecutor());

    @Test
    void testResolveExistingFile() throws IOException {
        Path tempFile = Files.createTempFile("test-dump", ".txt");
        try {
            Files.writeString(tempFile, "test thread dump content");

            JVMDiscovery.ResolutionResult result = discovery.resolve(tempFile.toString());

            assertTrue(result.isSuccess());
            assertEquals(1, result.targets().size());
            assertInstanceOf(ResolvedTarget.File.class, result.targets().get(0));

            ResolvedTarget.File file =
                (ResolvedTarget.File) result.targets().get(0);
            assertEquals(tempFile, file.path());

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testResolveNonExistentFile() {
        String nonExistent = "/tmp/nonexistent-file-12345.txt";

        JVMDiscovery.ResolutionResult result = discovery.resolve(nonExistent);

        // Should try to interpret as filter, which will likely find no JVMs
        assertNotNull(result);
    }

    @Test
    void testResolveEmptyTarget() {
        JVMDiscovery.ResolutionResult result = discovery.resolve("");

        assertFalse(result.isSuccess());
        assertNotNull(result.errorMessage());
        assertTrue(result.shouldListJVMs());
    }

    @Test
    void testResolveNullTarget() {
        JVMDiscovery.ResolutionResult result = discovery.resolve(null);

        assertFalse(result.isSuccess());
        assertNotNull(result.errorMessage());
        assertTrue(result.shouldListJVMs());
    }

    @Test
    void testResolveFilter() {
        String filter = "VeryUnlikelyJVMNameXYZ123";

        JVMDiscovery.ResolutionResult result = discovery.resolve(filter);

        assertNotNull(result);
        if (!result.isSuccess()) {
            assertTrue(result.errorMessage().contains(filter));
            assertTrue(result.shouldListJVMs());
        }
    }

    @Test
    void testResolveAll() throws IOException {
        JVMDiscovery.ResolutionResult result = discovery.resolve("all");

        // The set of running JVMs may change between calls, so we just verify
        // that resolve("all") returns a valid result with the same structure as listJVMs()
        if (result.isSuccess()) {
            // At least one JVM should be resolved
            assertTrue(result.targets().size() > 0);
            
            // All targets should be PID targets
            for (ResolvedTarget target : result.targets()) {
                assertInstanceOf(ResolvedTarget.Pid.class, target);
                ResolvedTarget.Pid pidTarget = (ResolvedTarget.Pid) target;
                assertNotNull(pidTarget.mainClass());
                assertTrue(pidTarget.pid() > 0);
            }
        } else {
            // If no result, it should be because no JVMs were found
            assertEquals("No JVMs found", result.errorMessage());
            assertTrue(result.shouldListJVMs());
        }
    }

    @Test
    void testResolveMultipleTargets() throws IOException {
        Path file1 = Files.createTempFile("test-dump-1", ".txt");
        Path file2 = Files.createTempFile("test-dump-2", ".txt");

        try {
            Files.writeString(file1, "dump 1");
            Files.writeString(file2, "dump 2");

            JVMDiscovery.ResolutionResult result =
                    discovery.resolveMultiple(List.of(file1.toString(), file2.toString()));

            assertTrue(result.isSuccess());
            assertEquals(2, result.targets().size());
            assertInstanceOf(ResolvedTarget.File.class, result.targets().get(0));
            assertInstanceOf(ResolvedTarget.File.class, result.targets().get(1));

        } finally {
            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }
    }

    @Test
    void testResolveMultipleEmptyList() {
        JVMDiscovery.ResolutionResult result = discovery.resolveMultiple(List.of());

        assertFalse(result.isSuccess());
        assertNotNull(result.errorMessage());
        assertTrue(result.shouldListJVMs());
    }

    @Test
    void testResolveMultipleMixedTargets() throws IOException {
        Path file1 = Files.createTempFile("test-dump", ".txt");

        try {
            Files.writeString(file1, "dump content");

            JVMDiscovery.ResolutionResult result =
                    discovery.resolveMultiple(List.of(file1.toString(), "somefilter"));

            assertNotNull(result);

        } finally {
            Files.deleteIfExists(file1);
        }
    }

    @Test
    void testResolutionResultIsSuccess() throws IOException {
        Path tempFile = Files.createTempFile("test-target", ".txt");
        try {
            Files.writeString(tempFile, "test");

            JVMDiscovery.ResolutionResult success =
                JVMDiscovery.ResolutionResult.success(
                    List.of(new ResolvedTarget.File(tempFile)));
            assertTrue(success.isSuccess());
            assertFalse(success.shouldListJVMs());

            JVMDiscovery.ResolutionResult error =
                JVMDiscovery.ResolutionResult.error("test error", true);
            assertFalse(error.isSuccess());
            assertTrue(error.shouldListJVMs());
            assertEquals("test error", error.errorMessage());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testResolutionResultIsEmpty() {
        JVMDiscovery.ResolutionResult empty =
            JVMDiscovery.ResolutionResult.success(List.of());
        assertTrue(empty.isEmpty());

        JVMDiscovery.ResolutionResult notEmpty =
            JVMDiscovery.ResolutionResult.error("error", false);
        assertTrue(notEmpty.isEmpty());
    }
}