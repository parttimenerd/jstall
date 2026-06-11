// Apache Commons Pool2: maxTotal=2 pool, 2 borrowers hold objects indefinitely,
// 5 threads stuck in pool.borrowObject() waiting.
import org.apache.commons.pool2.*;
import org.apache.commons.pool2.impl.*;
public class CommonsPool2Stall {
    static class SlowFactory extends BasePooledObjectFactory<byte[]> {
        public byte[] create() { return new byte[1024]; }
        public PooledObject<byte[]> wrap(byte[] obj) { return new DefaultPooledObject<>(obj); }
    }

    public static void main(String[] args) throws Exception {
        var cfg = new GenericObjectPoolConfig<byte[]>();
        cfg.setMaxTotal(2);
        cfg.setMaxWait(java.time.Duration.ofSeconds(120));
        cfg.setBlockWhenExhausted(true);
        var pool = new GenericObjectPool<>(new SlowFactory(), cfg);

        // 2 holders borrow and never return
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    pool.borrowObject(); // holds forever
                    Thread.sleep(Long.MAX_VALUE);
                } catch (Exception e) {}
            }, "cp2-holder-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(200);

        // 5 waiters block in borrowObject
        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(() -> {
                try { pool.borrowObject(); } catch (Exception e) {}
            }, "cp2-waiter-" + i);
            t.setDaemon(true); t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
