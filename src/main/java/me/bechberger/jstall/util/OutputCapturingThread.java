package me.bechberger.jstall.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

class OutputCapturingThread extends Thread {
    private final InputStream inputStream;
    private final AtomicReference<String> outputRef;

    public OutputCapturingThread(InputStream inputStream) {
        this.inputStream = inputStream;
        this.outputRef = new AtomicReference<>();
    }

    @Override
    public void run() {
        try {
            outputRef.set(new String(inputStream.readAllBytes()));
        } catch (IOException e) {
            // Ignore
        }
    }

    String getString() throws IOException {
        try {
            join();
        } catch (InterruptedException e) {
            throw new IOException("Thread interrupted while waiting for output capture", e);
        }
        return outputRef.get();
    }
}