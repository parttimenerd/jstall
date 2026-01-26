package me.bechberger.jstall.cli;

import me.bechberger.minicli.annotations.Command;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.minicli.annotations.Parameters;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command to list running JVM processes.
 */
@Command(
    name = "list",
    description = "List running JVM processes (excluding this tool)"
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
                return 1;
            }

            // Print each JVM
            for (JVMDiscovery.JVMProcess jvm : jvms) {
                System.out.println(jvm);
            }

            return 0;

        } catch (IOException e) {
            System.err.println("Error listing JVMs: " + e.getMessage());
            return 1;
        }
    }
}