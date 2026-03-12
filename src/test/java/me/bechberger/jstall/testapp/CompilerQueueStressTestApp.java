package me.bechberger.jstall.testapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test application that generates JIT compilation activity to stress compiler queues.
 * <p>
 * Creates multiple threads executing hot methods with varied patterns to trigger
 * C1 and C2 compilation, generating visible compiler queue activity.
 */
public class CompilerQueueStressTestApp {

    private static final int NUM_WORKER_THREADS = 4;
    private static final Random random = new Random(42);
    private static volatile boolean running = true;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("CompilerQueueStressTestApp started");
        System.out.println("PID: " + ProcessHandle.current().pid());

        List<Thread> workers = new ArrayList<>();

        // Create worker threads with different workload patterns
        for (int i = 0; i < NUM_WORKER_THREADS; i++) {
            final int workerId = i;
            Thread worker = new Thread(() -> {
                // Each worker uses a different pattern to generate varied compilation
                switch (workerId % 4) {
                    case 0 -> runMathIntensiveLoop();
                    case 1 -> runStringProcessingLoop();
                    case 2 -> runCollectionProcessingLoop();
                    case 3 -> runRecursiveWorkLoop();
                }
            }, "CompilerStressWorker-" + i);
            worker.start();
            workers.add(worker);
        }

        System.out.println("Compiler stress test running with " + NUM_WORKER_THREADS + " workers");
        System.out.println("Generating varied hot methods to trigger compilation...");

        // Run for a reasonable time or until interrupted
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * Math-intensive work to trigger compilation.
     */
    private static void runMathIntensiveLoop() {
        long counter = 0;
        while (running) {
            counter += computePrimeFactors(random.nextInt(10000) + 1000);
            counter += computeFibonacci(random.nextInt(30) + 10);
            counter += computeFactorial(random.nextInt(15) + 5);
            
            // Prevent dead code elimination
            if (counter < 0) {
                System.out.println(counter);
            }
        }
    }

    /**
     * String processing to trigger C1/C2 compilation on string operations.
     */
    private static void runStringProcessingLoop() {
        StringBuilder sb = new StringBuilder();
        while (running) {
            sb.setLength(0);
            for (int i = 0; i < 100; i++) {
                sb.append("test").append(i).append("_");
            }
            String result = processString(sb.toString());
            
            // Force some actual work
        }
    }

    /**
     * Collection processing to trigger compilation on collection operations.
     */
    private static void runCollectionProcessingLoop() {
        while (running) {
            List<Integer> numbers = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                numbers.add(random.nextInt(10000));
            }
            
            int result = processCollection(numbers);
            
            if (result < 0) {
                System.out.println(result);
            }
        }
    }

    /**
     * Recursive work pattern to trigger compilation.
     */
    private static void runRecursiveWorkLoop() {
        while (running) {
            int depth = random.nextInt(20) + 10;
            long result = recursiveComputation(depth, random.nextInt(100));
            
            if (result < 0) {
                System.out.println(result);
            }
        }
    }

    // Hot method candidates for compilation

    private static long computePrimeFactors(int n) {
        long count = 0;
        for (int i = 2; i <= n; i++) {
            while (n % i == 0) {
                count++;
                n /= i;
            }
        }
        return count;
    }

    private static long computeFibonacci(int n) {
        if (n <= 1) return n;
        long a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            long temp = a + b;
            a = b;
            b = temp;
        }
        return b;
    }

    private static long computeFactorial(int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    private static String processString(String input) {
        String result = input;
        result = result.toUpperCase();
        result = result.replace("TEST", "WORK");
        result = result.substring(0, Math.min(100, result.length()));
        return result;
    }

    private static int processCollection(List<Integer> numbers) {
        int sum = 0;
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        
        for (Integer num : numbers) {
            sum += num;
            if (num > max) max = num;
            if (num < min) min = num;
        }
        
        // Do some filtering and mapping
        long count = numbers.stream()
                .filter(n -> n % 2 == 0)
                .map(n -> n * 2)
                .count();
        
        return sum + max - min + (int) count;
    }

    private static long recursiveComputation(int depth, int value) {
        if (depth <= 0) {
            return value;
        }
        
        long result = value;
        result += recursiveComputation(depth - 1, value + 1);
        result += recursiveComputation(depth - 1, value - 1);
        
        // Add some computation to prevent tail call optimization
        return result % 1000000007;
    }
}