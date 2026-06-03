package com.pooldemo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WITH Connection Pool (HikariCP)
 *
 * Pool is initialized ONCE in the constructor.
 *
 * Two modes available:
 *
 * runQuery()           — sequential, one request at a time (shows reuse via same pid)
 *
 * runConcurrent()      — multiple threads fire simultaneously, each borrows its OWN
 *                        connection from the pool at the same time.
 *                        Watch: DIFFERENT pids in parallel = pool serving multiple
 *                        connections concurrently from its pre-warmed set.
 *                        Also shows what happens when threads > pool size (queuing).
 */
public class WithPool {

    private final HikariDataSource dataSource;

    public WithPool() {
        System.out.println("[WITH POOL] Initializing HikariCP connection pool...");

        HikariConfig config = new HikariConfig();

        // Database coordinates
        config.setJdbcUrl(DBConfig.JDBC_URL);
        config.setUsername(DBConfig.USERNAME);
        config.setPassword(DBConfig.PASSWORD);

        // Pool sizing
        config.setMinimumIdle(DBConfig.POOL_MIN_IDLE);       // keep 5 connections warm always
        config.setMaximumPoolSize(DBConfig.POOL_MAX_SIZE);   // max 10 connections total

        // How long a request waits for a free connection before exception
        config.setConnectionTimeout(DBConfig.POOL_TIMEOUT_MS);

        // Kill connections idle more than 10 minutes
        config.setIdleTimeout(600_000);

        // Recycle any connection older than 30 minutes (avoids stale/zombie conns)
        config.setMaxLifetime(1_800_000);

        // Lightweight query used to verify a borrowed connection is still alive
        config.setConnectionTestQuery("SELECT 1");

        config.setPoolName("DemoPool");

        // *** This line opens min-idle connections to DB immediately ***
        this.dataSource = new HikariDataSource(config);

        System.out.printf("[WITH POOL] Pool ready — min-idle: %d, max-size: %d, timeout: %dms%n",
                DBConfig.POOL_MIN_IDLE, DBConfig.POOL_MAX_SIZE, DBConfig.POOL_TIMEOUT_MS);
        System.out.println();
    }

    /**
     * Fires `totalThreads` requests simultaneously using a thread pool.
     *
     * All threads start at the EXACT same moment (via CountDownLatch).
     * Each thread independently borrows a connection from the HikariCP pool.
     *
     * What to watch:
     *  - When totalThreads <= pool max-size  → all threads get a connection immediately
     *    (DIFFERENT DB pids running in parallel at the same time)
     *  - When totalThreads > pool max-size   → extra threads wait (Borrow time goes up)
     *    This is the pool exhaustion scenario — threads queue for a free connection.
     *
     * @param totalThreads  how many concurrent "users" to simulate
     * @param rounds        how many rounds (waves) of concurrent requests to run
     */
    public void runConcurrent(int totalThreads, int rounds) throws InterruptedException {

        System.out.printf("[CONCURRENT] Simulating %d simultaneous users | Pool max-size: %d%n",
                totalThreads, DBConfig.POOL_MAX_SIZE);

        if (totalThreads > DBConfig.POOL_MAX_SIZE) {
            System.out.printf("[CONCURRENT] *** %d threads > pool size %d → expect some threads to WAIT for a connection ***%n",
                    totalThreads, DBConfig.POOL_MAX_SIZE);
        } else {
            System.out.printf("[CONCURRENT] %d threads <= pool size %d → all threads should get connections immediately%n",
                    totalThreads, DBConfig.POOL_MAX_SIZE);
        }
        System.out.println();

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        AtomicInteger requestCounter = new AtomicInteger(0);

        for (int round = 1; round <= rounds; round++) {
            System.out.printf("--- Round %d (all %d threads fire simultaneously) ---%n", round, totalThreads);

            // Latch holds all threads until everyone is ready — true simultaneous start
            CountDownLatch startGun   = new CountDownLatch(1);
            CountDownLatch allDone    = new CountDownLatch(totalThreads);

            for (int t = 0; t < totalThreads; t++) {
                final int threadId = t + 1;
                final int reqNum   = requestCounter.incrementAndGet();

                executor.submit(() -> {
                    try {
                        startGun.await(); // all threads wait here until we fire the latch
                        runQueryInternal(reqNum, threadId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            startGun.countDown();           // fire! all threads released at same instant
            allDone.await();                // wait for this round to finish
            System.out.println();
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    /**
     * Internal method used by both runQuery() and runConcurrent().
     * Actual DB work happens here.
     */
    private void runQueryInternal(int requestNumber, int threadId) {
        long start = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection();
             Statement stmt  = conn.createStatement()) {

            long connBorrowed = System.currentTimeMillis();

            // pg_sleep(0.05) makes the query hold the connection for 50ms
            // so we can clearly see multiple connections held simultaneously
            ResultSet rs = stmt.executeQuery(
                "SELECT current_timestamp, pg_backend_pid(), pg_sleep(0.05)"
            );

            if (rs.next()) {
                String timestamp = rs.getString(1);
                int backendPid   = rs.getInt(2);

                long end = System.currentTimeMillis();

                System.out.printf(
                    "[CONCURRENT] Thread-%-2d | Req #%-3d | Borrow: %4dms | Query: %3dms | Total: %4dms | DB pid: %-6d%n",
                    threadId,
                    requestNumber,
                    (connBorrowed - start),   // 0ms if connection available, >0ms if had to wait
                    (end - connBorrowed),
                    (end - start),
                    backendPid                // different pids = different connections used in parallel
                );
            }

        } catch (Exception e) {
            long end = System.currentTimeMillis();
            System.err.printf(
                "[CONCURRENT] Thread-%-2d | Req #%-3d | FAILED after %dms: %s%n",
                threadId, requestNumber, (end - start), e.getMessage()
            );
        }
        // Connection returned to pool here — available for next round immediately
    }

    // Keep the original sequential method as-is
    public void runQuery(int requestNumber) {
        runQueryInternal(requestNumber, 1);
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void shutdown() {
        System.out.println("[WITH POOL] Shutting down pool and closing all connections...");
        dataSource.close();
    }
}
