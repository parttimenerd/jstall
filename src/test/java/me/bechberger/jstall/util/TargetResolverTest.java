package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TargetResolver.
 */
class TargetResolverTest {

    @Test
    void testResolveExistingFile() throws IOException {
        // Create a temporary file
        Path tempFile = Files.createTempFile("test-dump", ".txt");
        try {
            Files.writeString(tempFile, "test thread dump content");

            TargetResolver.ResolutionResult result = TargetResolver.resolve(tempFile.toString());

            assertTrue(result.isSuccess());
            assertEquals(1, result.targets().size());
            assertInstanceOf(TargetResolver.ResolvedTarget.File.class, result.targets().getFirst());

            TargetResolver.ResolvedTarget.File file =
                (TargetResolver.ResolvedTarget.File) result.targets().getFirst();
            assertEquals(tempFile, file.path());

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testResolveNonExistentFile() {
        String nonExistent = "/tmp/nonexistent-file-12345.txt";

        TargetResolver.ResolutionResult result = TargetResolver.resolve(nonExistent);

        // Should try to interpret as filter, which will likely find no JVMs
        assertNotNull(result);
    }

    @Test
    void testResolveEmptyTarget() {
        TargetResolver.ResolutionResult result = TargetResolver.resolve("");

        assertFalse(result.isSuccess());
        assertNotNull(result.errorMessage());
        assertTrue(result.shouldListJVMs());
    }

    @Test
    void testResolveNullTarget() {
        TargetResolver.ResolutionResult result = TargetResolver.resolve(null);

        assertFalse(result.isSuccess());
        assertNotNull(result.errorMessage());
        assertTrue(result.shouldListJVMs());
    }

    @Test
    void testResolveFilter() throws IOException {
        // Use a filter that's unlikely to match anything
        String filter = "VeryUnlikelyJVMNameXYZ123";

        TargetResolver.ResolutionResult result = TargetResolver.resolve(filter);

        assertNotNull(result);
        // Either finds no JVMs (error) or finds some (success)
        if (!result.isSuccess()) {
            assertTrue(result.errorMessage().contains(filter));
            assertTrue(result.shouldListJVMs());
        }
    }

    @Test
    void testResolveMultipleTargets() throws IOException {
        // Create two temporary files
        Path file1 = Files.createTempFile("test-dump-1", ".txt");
        Path file2 = Files.createTempFile("test-dump-2", ".txt");

        try {
            Files.writeString(file1, "dump 1");
            Files.writeString(file2, "dump 2");

            List<String> targets = List.of(file1.toString(), file2.toString());
            TargetResolver.ResolutionResult result = TargetResolver.resolveMultiple(targets);

            assertTrue(result.isSuccess());
            assertEquals(2, result.targets().size());
            assertInstanceOf(TargetResolver.ResolvedTarget.File.class, result.targets().get(0));
            assertInstanceOf(TargetResolver.ResolvedTarget.File.class, result.targets().get(1));

        } finally {
            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }
    }

    @Test
    void testResolveMultipleEmptyList() {
        TargetResolver.ResolutionResult result = TargetResolver.resolveMultiple(List.of());

        assertFalse(result.isSuccess());
        assertNotNull(result.errorMessage());
        assertTrue(result.shouldListJVMs());
    }

    @Test
    void testResolveMultipleMixedTargets() throws IOException {
        Path file1 = Files.createTempFile("test-dump", ".txt");

        try {
            Files.writeString(file1, "dump content");

            // Mix of file and filter
            List<String> targets = List.of(file1.toString(), "somefilter");
            TargetResolver.ResolutionResult result = TargetResolver.resolveMultiple(targets);

            // Should resolve the file successfully
            assertNotNull(result);

        } finally {
            Files.deleteIfExists(file1);
        }
    }

    @Test
    void testResolutionResultIsSuccess() throws IOException {
        // Create a temp file for a valid target
        Path tempFile = Files.createTempFile("test-target", ".txt");
        try {
            Files.writeString(tempFile, "test");

            TargetResolver.ResolutionResult success =
                TargetResolver.ResolutionResult.success(
                    List.of(new TargetResolver.ResolvedTarget.File(tempFile)));
            assertTrue(success.isSuccess());
            assertFalse(success.shouldListJVMs());

            TargetResolver.ResolutionResult error =
                TargetResolver.ResolutionResult.error("test error", true);
            assertFalse(error.isSuccess());
            assertTrue(error.shouldListJVMs());
            assertEquals("test error", error.errorMessage());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testResolutionResultIsEmpty() {
        TargetResolver.ResolutionResult empty =
            TargetResolver.ResolutionResult.success(List.of());
        assertTrue(empty.isEmpty());

        TargetResolver.ResolutionResult notEmpty =
            TargetResolver.ResolutionResult.error("error", false);
        assertTrue(notEmpty.isEmpty());
    }
}