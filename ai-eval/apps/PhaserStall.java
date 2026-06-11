// Phaser with 4 registered parties but only 3 arrive — phase never advances.
public class PhaserStall {
    public static void main(String[] args) throws Exception {
        java.util.concurrent.Phaser phaser = new java.util.concurrent.Phaser(4);

        // 3 parties arrive and wait; 4th never comes
        for (int i = 0; i < 3; i++) {
            final int id = i;
            Thread t = new Thread(() -> phaser.arriveAndAwaitAdvance(), "phaser-party-" + id);
            t.setDaemon(true);
            t.start();
        }
        // 4th party is never started — phase 0 never completes

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
