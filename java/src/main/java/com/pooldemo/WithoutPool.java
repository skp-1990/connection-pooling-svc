package com.pooldemo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * WITHOUT Connection Pool
 *
 * Every single call to runQuery() does the full expensive cycle:
 *   1. TCP 3-way handshake
 *   2. TLS negotiation
 *   3. DB authentication
 *   4. Run query
 *   5. Close everything — connection is DESTROYED
 *
 * Watch the output:
 *   - "Connect" time is high every single request (50-200ms)
 *   - DB pid changes every request (new server process each time)
 *   - Total time is dominated by connection cost, not query cost
 */
public class WithoutPool {

    public void runQuery(int requestNumber) {
        long start = System.currentTimeMillis();

        // Opens a brand new TCP connection, authenticates — every single time
        try (Connection conn = DriverManager.getConnection(
                DBConfig.JDBC_URL,
                DBConfig.USERNAME,
                DBConfig.PASSWORD);

             Statement stmt = conn.createStatement()) {

            long connEstablished = System.currentTimeMillis();

            // Simple query — just to measure pure query time vs connection time
            ResultSet rs = stmt.executeQuery("SELECT current_timestamp, pg_backend_pid()");

            if (rs.next()) {
                String timestamp = rs.getString(1);
                int backendPid   = rs.getInt(2);  // Postgres server process ID

                long end = System.currentTimeMillis();

                System.out.printf(
                    "[NO POOL]   Request #%-3d | Connect: %4dms | Query: %3dms | Total: %4dms | DB pid: %-6d | %s%n",
                    requestNumber,
                    (connEstablished - start),    // time just to open connection
                    (end - connEstablished),      // time just for the query
                    (end - start),                // total end-to-end time
                    backendPid,                   // watch: this changes every request
                    timestamp
                );
            }

            // Simulate some processing / think time between requests
            Thread.sleep(100);

        } catch (Exception e) {
            System.err.printf("[NO POOL]   Request #%d FAILED: %s%n", requestNumber, e.getMessage());
        }
        // Connection CLOSED here — next request starts from zero
    }
}
