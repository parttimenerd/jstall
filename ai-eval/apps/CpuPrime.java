// Single-thread CPU saturation: one thread pinning a core at 100% computing primes.
// Different from HotLoop (which busy-spins on counters) — this is "real" computation.
// Should be reported as CPU hot-spot.
public class CpuPrime {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            long n = 2;
            long primes = 0;
            while (true) {
                boolean prime = true;
                for (long i = 2; i * i <= n; i++) {
                    if (n % i == 0) { prime = false; break; }
                }
                if (prime) primes++;
                n++;
                if (n > 10_000_000L) { System.out.println("primes=" + primes); n = 2; primes = 0; }
            }
        }, "prime-cruncher");
        t.setDaemon(true);
        t.start();
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
