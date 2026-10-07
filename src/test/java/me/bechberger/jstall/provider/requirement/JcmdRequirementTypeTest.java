package me.bechberger.jstall.provider.requirement;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that JcmdRequirement.getType() produces the correct kebab-case key
 * for all known jcmd commands so that analyzer lookups match the stored data.
 */
class JcmdRequirementTypeTest {

    private static JcmdRequirement req(String command) {
        return new JcmdRequirement(command, null, CollectionSchedule.once());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        // Special renames
        "Thread.print,            thread-dumps",
        "VM.system_properties,    system-properties",
        // Standard dot-separated commands
        "VM.flags,                vm-flags",
        "VM.command_line,         vm-command-line",
        "VM.info,                 vm-info",
        "VM.uptime,               vm-uptime",
        "VM.metaspace,            vm-metaspace",
        "VM.native_memory,        vm-native-memory",
        "VM.classloader_stats,    vm-classloader-stats",
        "VM.classloaders,         vm-classloaders",
        "VM.classes,              vm-classes",
        "VM.class_hierarchy,      vm-class-hierarchy",
        "VM.vitals,               vm-vitals",
        "GC.heap_info,            gc-heap-info",
        "GC.class_histogram,      gc-class-histogram",
        "GC.finalizer_info,       gc-finalizer-info",
        "Compiler.queue,          compiler-queue",
        "Compiler.codecache,      compiler-codecache",
    })
    void commandMapsToExpectedType(String command, String expectedType) {
        assertThat(req(command.strip()).getType()).isEqualTo(expectedType.strip());
    }
}
