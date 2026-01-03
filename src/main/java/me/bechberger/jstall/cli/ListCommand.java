package me.bechberger.jstall.cli;

import me.bechberger.jstall.util.JVMDiscovery;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command to list running JVM processes.
 */
@Command(
    name = "list",
    description = "List running JVM processes",
    mixinStandardHelpOptions = true
)
public class ListCommand implements Callable<Integer> {

    @Parameters(
        index = "0",
        arity = "0..1",
        description = "Optional filter - only show JVMs whose main class contains this text"
    )
    private String filter;

    @Override
    public Integer call() throws Exception {
        try {
            List<JVMDiscovery.JVMProcess> jvms = JVMDiscovery.listJVMs(filter);

            if (jvms.isEmpty()) {
                if (filter != null && !filter.isBlank()) {
                    System.out.println("No JVMs found matching filter: " + filter);
                } else {
                    System.out.println("No running JVMs found.");
                }
                return 0;
            }

            // Print header
            System.out.println("Available JVM processes:");
            System.out.println();

            // Print each JVM
            for (JVMDiscovery.JVMProcess jvm : jvms) {
                System.out.printf("  %d\t%s%n", jvm.pid(), jvm.mainClass());
            }

            System.out.println();
            System.out.printf("Total: %d JVM(s)%s%n",
                jvms.size(),
                filter != null && !filter.isBlank() ? " (filtered)" : "");

            return 0;

        } catch (IOException e) {
            System.err.println("Error listing JVMs: " + e.getMessage());
            System.err.println("Make sure 'jps' is available in your PATH.");
            return 1;
        }
    }
}