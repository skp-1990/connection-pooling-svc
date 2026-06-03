import time
import psycopg2
import config

# ---------------------------------------------------------------
# WITHOUT Connection Pool
#
# Every single call to run_query() does the full expensive cycle:
#   1. TCP 3-way handshake
#   2. TLS negotiation
#   3. DB authentication
#   4. Run query
#   5. Close everything — connection is DESTROYED
#
# Watch the output:
#   - "connect" time is high every single request
#   - DB pid changes every request (new server process each time)
#   - Total time is dominated by connection cost, not query cost
# ---------------------------------------------------------------

class WithoutPool:

    def run_query(self, request_number: int):
        start = time.monotonic()

        # Opens a brand new TCP connection, authenticates — every single time
        try:
            conn = psycopg2.connect(config.DSN)
            conn_established = time.monotonic()

            cursor = conn.cursor()

            # Simple query — measures pure query time vs connection overhead
            cursor.execute("SELECT NOW(), pg_backend_pid()")
            row = cursor.fetchone()

            timestamp  = row[0]
            backend_pid = row[1]   # Postgres server process ID

            end = time.monotonic()

            connect_ms = int((conn_established - start)    * 1000)
            query_ms   = int((end - conn_established)      * 1000)
            total_ms   = int((end - start)                 * 1000)

            print(
                f"[NO POOL]   Request #{request_number:<3} | "
                f"Connect: {connect_ms:>4}ms | "
                f"Query: {query_ms:>3}ms | "
                f"Total: {total_ms:>4}ms | "
                f"DB pid: {backend_pid:<6} | "
                f"{timestamp}"
            )

            cursor.close()

        except Exception as e:
            print(f"[NO POOL]   Request #{request_number} FAILED: {e}")

        finally:
            try:
                conn.close()   # connection DESTROYED here — next call starts from zero
            except Exception:
                pass

        # Simulate some think time between requests
        time.sleep(0.1)
