package me.bechberger.jstall.testframework;

/**
 * Prints the exact argv it receives, used to verify Windows quoting round-trips through fake ssh/cf tools.
 */
public final class FakeToolProbe {

    private FakeToolProbe() {
    }

    public static void main(String[] args) {
        String toolName = args.length > 0 ? args[0] : "<unknown>";
        String[] toolArgs = args.length > 0 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];

        System.out.println("TOOL=" + toolName);
        System.out.println("ARGC=" + toolArgs.length);
        for (int index = 0; index < toolArgs.length; index++) {
            System.out.println("ARG" + index + "=" + toolArgs[index]);
        }
        System.out.println("12345 com.example.FakeJvm");
    }
}