import time
import threading
from psycopg2 import pool as pg_pool
import config

# ---------------------------------------------------------------
# WITH Connection Pool  (psycopg2.pool.ThreadedConnectionPool)
#
# Pool is initialized ONCE in __init__.
#
# Two modes:
#
#   run_query()      — sequential, one request at a time
#                      Watch: same DB pid repeats = same connection reused
#
#   run_concurrent() — multiple threads fire simultaneously, each borrows
#                      its OWN connection from the pool at the same time.
#                      Watch: DIFFERENT pids in parallel = pool serving
#                      multiple connections concurrently.
#                      Also shows queuing when threads > pool size.
# ---------------------------------------------------------------

class WithPool:

    def __init__(self):
        print("[WITH POOL] Initializing psycopg2 ThreadedConnectionPool...")

        # ThreadedConnectionPool is thread-safe — safe to share across threads
        # minconn: opens these many connections immediately on startup
        # maxconn: hard ceiling — getconn() blocks if all are taken
        self._pool = pg_pool.ThreadedConnectionPool(
            minconn=config.POOL_MIN_CONN,
            maxconn=config.POOL_MAX_CONN,
            dsn=config.DSN
        )

        print(
            f"[WITH POOL] Pool ready — "
            f"min-conn: {config.POOL_MIN_CONN}, "
            f"max-conn: {config.POOL_MAX_CONN}"
        )
        print()

    # ------------------------------------------------------------------
    # Sequential mode
    # ------------------------------------------------------------------

    def run_query(self, request_number: int):
        self._run_query_internal(request_number, thread_id=1)
        time.sleep(0.1)

    # ------------------------------------------------------------------
    # Concurrent mode
    # ------------------------------------------------------------------

    def run_concurrent(self, total_threads: int, rounds: int):
        """
        Fires `total_threads` requests simultaneously each round.

        All threads are released at the EXACT same moment via threading.Barrier.

        What to watch:
          threads <= pool max  → all borrow instantly, DIFFERENT pids at same time
          threads >  pool max  → extras WAIT (borrow time spikes) = pool exhaustion
        """
        print(
            f"[CONCURRENT] Simulating {total_threads} simultaneous users | "
            f"Pool max-conn: {config.POOL_MAX_CONN}"
        )

        if total_threads > config.POOL_MAX_CONN:
            print(
                f"[CONCURRENT] *** {total_threads} threads > pool size {config.POOL_MAX_CONN}"
                f" → expect some threads to WAIT for a connection ***"
            )
        else:
            print(
                f"[CONCURRENT] {total_threads} threads <= pool size {config.POOL_MAX_CONN}"
                f" → all threads should get connections immediately"
            )
        print()

        request_counter = [0]           # shared counter (list so closure can mutate)
        counter_lock    = threading.Lock()

        for round_num in range(1, rounds + 1):
            print(f"--- Round {round_num} (all {total_threads} threads fire simultaneously) ---")

            # Barrier makes all threads wait until every thread has called wait()
            # then releases them ALL at the same instant — true simultaneous start
            start_barrier = threading.Barrier(total_threads)
            all_done      = threading.Event()
            done_count    = [0]
            done_lock     = threading.Lock()

            threads = []

            for t in range(total_threads):
                thread_id = t + 1

                with counter_lock:
                    request_counter[0] += 1
                    req_num = request_counter[0]

                def worker(req=req_num, tid=thread_id):
                    start_barrier.wait()        # hold until ALL threads are ready
                    self._run_query_internal(req, tid)
                    with done_lock:
                        done_count[0] += 1
                        if done_count[0] == total_threads:
                            all_done.set()

                thread = threading.Thread(target=worker, daemon=True)
                threads.append(thread)

            for t in threads:
                t.start()

            all_done.wait()     # block main thread until round is complete
            print()

    # ------------------------------------------------------------------
    # Internal — actual DB work, shared by both modes
    # ------------------------------------------------------------------

    def _run_query_internal(self, request_number: int, thread_id: int):
        start = time.monotonic()
        conn  = None

        try:
            # Borrow a connection from the pool — already open, near-zero cost
            # If pool is exhausted, this BLOCKS until one is returned
            conn = self._pool.getconn()
            conn_borrowed = time.monotonic()

            cursor = conn.cursor()

            # pg_sleep(0.05) holds the connection for 50ms so concurrent threads
            # are visibly active at the same time in the output
            cursor.execute("SELECT NOW(), pg_backend_pid(), pg_sleep(0.05)")
            row = cursor.fetchone()

            timestamp   = row[0]
            backend_pid = row[1]   # different pids = different pool connections used

            end = time.monotonic()

            borrow_ms = int((conn_borrowed - start)    * 1000)
            query_ms  = int((end - conn_borrowed)      * 1000)
            total_ms  = int((end - start)              * 1000)

            print(
                f"[CONCURRENT] Thread-{thread_id:<2} | "
                f"Req #{request_number:<3} | "
                f"Borrow: {borrow_ms:>4}ms | "
                f"Query: {query_ms:>3}ms | "
                f"Total: {total_ms:>4}ms | "
                f"DB pid: {backend_pid:<6}"
            )

            cursor.close()

        except Exception as e:
            end = time.monotonic()
            total_ms = int((end - start) * 1000)
            print(
                f"[CONCURRENT] Thread-{thread_id:<2} | "
                f"Req #{request_number:<3} | "
                f"FAILED after {total_ms}ms: {e}"
            )

        finally:
            if conn:
                # Return connection to pool — NOT closed, reused next time
                self._pool.putconn(conn)

    # ------------------------------------------------------------------

    def shutdown(self):
        print("\n[WITH POOL] Shutting down pool and closing all connections...")
        self._pool.closeall()
