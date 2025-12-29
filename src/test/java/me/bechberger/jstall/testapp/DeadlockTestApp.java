package me.bechberger.jstall.testapp;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Test application that can create various scenarios for testing thread dump analysis.
 *
 * Usage:
 * - java DeadlockTestApp deadlock    - Creates a classic deadlock
 * - java DeadlockTestApp busy-work   - Creates threads doing busy work
 * - java DeadlockTestApp normal      - Normal execution with some threads
 */
public class DeadlockTestApp {

    private static final Lock lock1 = new ReentrantLock();
    private static final Lock lock2 = new ReentrantLock();

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length > 0 ? args[0] : "normal";

        System.out.println("DeadlockTestApp started in mode: " + mode);
        System.out.println("PID: " + ProcessHandle.current().pid());

        switch (mode) {
            case "deadlock" -> runDeadlockScenario();
            case "busy-work" -> runBusyWorkScenario();
            case "normal" -> runNormalScenario();
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.exit(1);
            }
        }
    }

    /**
     * Creates a classic deadlock between two threads.
     */
    private static void runDeadlockScenario() throws InterruptedException {
        System.out.println("Creating deadlock scenario...");

        Thread thread1 = new Thread(() -> {
            lock1.lock();
            System.out.println("Thread-1 acquired lock1");
            sleep(100);
            System.out.println("Thread-1 waiting for lock2...");
            lock2.lock(); // This will deadlock
            System.out.println("Thread-1 acquired lock2");
            lock2.unlock();
            lock1.unlock();
        }, "DeadlockThread-1");

        Thread thread2 = new Thread(() -> {
            lock2.lock();
            System.out.println("Thread-2 acquired lock2");
            sleep(100);
            System.out.println("Thread-2 waiting for lock1...");
            lock1.lock(); // This will deadlock
            System.out.println("Thread-2 acquired lock1");
            lock1.unlock();
            lock2.unlock();
        }, "DeadlockThread-2");

        thread1.start();
        thread2.start();

        // Wait a bit for deadlock to occur
        Thread.sleep(500);
        System.out.println("Deadlock should be established now. Press Ctrl+C to exit.");

        // Keep main thread alive
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * Creates threads doing CPU-intensive work.
     */
    private static void runBusyWorkScenario() throws InterruptedException {
        System.out.println("Creating busy work scenario...");

        // Create worker threads that do actual computation
        for (int i = 0; i < 3; i++) {
            final int threadNum = i;
            Thread worker = new Thread(() -> {
                while (true) {
                    doWork();
                    sleep(10); // Small pause
                }
            }, "BusyWorker-" + threadNum);
            worker.start();
        }

        // Create some idle threads
        for (int i = 0; i < 2; i++) {
            final int threadNum = i;
            Thread idle = new Thread(() -> {
                while (true) {
                    sleep(1000);
                }
            }, "IdleThread-" + threadNum);
            idle.start();
        }

        System.out.println("Busy work scenario running. Press Ctrl+C to exit.");
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * Normal execution with a few threads.
     */
    private static void runNormalScenario() throws InterruptedException {
        System.out.println("Creating normal scenario...");

        Thread worker = new Thread(() -> {
            while (true) {
                doWork();
                sleep(100);
            }
        }, "Worker-Thread");
        worker.start();

        Thread sleeper = new Thread(() -> {
            while (true) {
                sleep(5000);
            }
        }, "Sleeper-Thread");
        sleeper.start();

        System.out.println("Normal scenario running. Press Ctrl+C to exit.");
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * Simulates CPU-intensive work.
     */
    private static void doWork() {
        // Calculate fibonacci to burn CPU
        long result = fibonacci(30);
        // Prevent optimization
        if (result < 0) {
            System.out.println(result);
        }
    }

    private static long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}