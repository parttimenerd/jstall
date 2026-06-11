// NIO Selector spin: thread wakes up repeatedly with no work because of a self-pipe.
// Look like a "busy" event-loop thread burning CPU.
import java.nio.channels.*;
import java.io.IOException;

public class NioSelectorSpin {
    public static void main(String[] args) throws Exception {
        Selector sel = Selector.open();
        // Pipe: write side keeps writing, read side never reads — keeps selector returning >0.
        Pipe pipe = Pipe.open();
        pipe.source().configureBlocking(false);
        pipe.source().register(sel, SelectionKey.OP_READ);

        // Saturate the pipe write side so OP_READ keeps firing.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1);
        buf.put((byte) 0); buf.flip();
        try { pipe.sink().write(buf); } catch (IOException e) {}

        Thread spinner = new Thread(() -> {
            try {
                while (true) {
                    sel.select();           // returns immediately since OP_READ is ready
                    sel.selectedKeys().clear();
                }
            } catch (IOException e) {}
        }, "nio-event-loop-spin");
        spinner.setDaemon(true);
        spinner.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
