// 4-thread circular lock chain: A→B→C→D→A
public class FourThreadDeadlock {
    static final Object L1 = new Object(), L2 = new Object(), L3 = new Object(), L4 = new Object();
    public static void main(String[] args) throws Exception {
        Runnable[] tasks = {
            () -> { synchronized(L1) { try { Thread.sleep(100); } catch (Exception e) {} synchronized(L2) {} } },
            () -> { synchronized(L2) { try { Thread.sleep(100); } catch (Exception e) {} synchronized(L3) {} } },
            () -> { synchronized(L3) { try { Thread.sleep(100); } catch (Exception e) {} synchronized(L4) {} } },
            () -> { synchronized(L4) { try { Thread.sleep(100); } catch (Exception e) {} synchronized(L1) {} } }
        };
        String[] names = {"ftd-t1","ftd-t2","ftd-t3","ftd-t4"};
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(tasks[i], names[i]);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
