package com.pooldemo;

/**
 * Entry point — toggle MODE to switch between implementations.
 *
 * HOW TO BUILD:
 *   cd java
 *   mvn package -q
 *
 * HOW TO RUN:
 *   java -jar target/connection-pool-demo-1.0-SNAPSHOT.jar
 *
 * WHAT TO OBSERVE:
 *   NO_POOL              → "Connect" column is high (50-200ms) every single request
 *                          DB pid changes every request (new server process each time)
 *
 *   WITH_POOL            → "Borrow" column is ~0ms after first request
 *                          Same DB pid repeats (same connection reused sequentially)
 *
 *   WITH_POOL_CONCURRENT → Multiple threads fire at the SAME instant
 *                          DIFFERENT DB pids running simultaneously = multiple pool
 *                          connections active at once!
 *                          Threads <= pool size  → everyone gets a connection instantly
 *
 *   POOL_EXHAUSTION      → More threads than pool size
 *                          Extra threads WAIT (Borrow time spikes) until a connection
 *                          is returned. Shows queuing behavior under pressure.
 */
public class Main {

    // ---------------------------------------------------------------
    //  CHANGE THIS to switch between implementations
    //  Options:
    //    "NO_POOL"              — new connection every request (sequential)
    //    "WITH_POOL"            — pool, sequential requests
    //    "WITH_POOL_CONCURRENT" — pool, many threads at same time (threads <= pool size)
    //    "POOL_EXHAUSTION"      — pool, more threads than pool size (shows queuing/wait)
    // ---------------------------------------------------------------
    private static final String MODE = "POOL_EXHAUSTION";
    // ---------------------------------------------------------------

    private static final int TOTAL_REQUESTS   = 15;   // used by sequential modes
    private static final int CONCURRENT_USERS = 5;    // threads fired simultaneously (WITH_POOL_CONCURRENT)
    private static final int OVERLOAD_USERS   = 15;   // intentionally > POOL_MAX_SIZE=10 (POOL_EXHAUSTION)
    private static final int ROUNDS           = 3;    // how many waves of concurrent requests

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=".repeat(90));
        System.out.println("  Connection Pool Demo");
        System.out.println("  MODE    : " + MODE);
        System.out.println("  DB URL  : " + DBConfig.JDBC_URL);
        System.out.println("  Requests: " + TOTAL_REQUESTS);
        System.out.println("=".repeat(90));
        System.out.println();

        long overallStart = System.currentTimeMillis();

        switch (MODE) {
            case "NO_POOL"              -> runWithoutPool();
            case "WITH_POOL"            -> runWithPool();
            case "WITH_POOL_CONCURRENT" -> runConcurrent(CONCURRENT_USERS, ROUNDS);
            case "POOL_EXHAUSTION"      -> runConcurrent(OVERLOAD_USERS,   ROUNDS);
            default -> System.err.println("Unknown MODE: " + MODE);
        }

        long overallEnd = System.currentTimeMillis();

        System.out.println();
        System.out.println("=".repeat(90));
        System.out.printf("  Total wall-clock time: %dms%n", (overallEnd - overallStart));
        System.out.println("=".repeat(90));
    }

    // ------------------------------------------------------------------

    private static void runWithoutPool() {
        System.out.println("Strategy : New connection created and destroyed on EVERY request");
        System.out.println("Watch    : 'Connect' column — full TCP+Auth cost paid each time");
        System.out.println("Watch    : DB pid — changes every request (new server process)");
        System.out.println();

        WithoutPool impl = new WithoutPool();

        for (int i = 1; i <= TOTAL_REQUESTS; i++) {
            impl.runQuery(i);
        }
    }

    private static void runWithPool() {
        System.out.println("Strategy : Pool opened once, connections borrowed and returned (sequential)");
        System.out.println("Watch    : 'Borrow' column — near 0ms after warm-up");
        System.out.println("Watch    : DB pid — same value repeats (same connection reused!)");
        System.out.println();

        WithPool impl = new WithPool();

        for (int i = 1; i <= TOTAL_REQUESTS; i++) {
            impl.runQuery(i);
        }

        impl.shutdown();
    }

    private static void runConcurrent(int numThreads, int rounds) throws InterruptedException {
        if (numThreads <= DBConfig.POOL_MAX_SIZE) {
            System.out.println("Strategy : " + numThreads + " threads fire SIMULTANEOUSLY — all fit in pool");
            System.out.println("Watch    : DIFFERENT DB pids at the same time = multiple connections active");
            System.out.println("Watch    : Borrow time ~0ms for all threads (pool has enough connections)");
        } else {
            System.out.println("Strategy : " + numThreads + " threads fire SIMULTANEOUSLY — MORE than pool size " + DBConfig.POOL_MAX_SIZE);
            System.out.println("Watch    : First " + DBConfig.POOL_MAX_SIZE + " threads get connections immediately");
            System.out.println("Watch    : Remaining " + (numThreads - DBConfig.POOL_MAX_SIZE) + " threads WAIT — Borrow time spikes!");
            System.out.println("Watch    : This is pool exhaustion / connection queuing in action");
        }
        System.out.println();

        WithPool impl = new WithPool();
        impl.runConcurrent(numThreads, rounds);
        impl.shutdown();
    }
}
