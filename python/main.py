import time
import config
from without_pool import WithoutPool
from with_pool    import WithPool

# ---------------------------------------------------------------
# Entry point — toggle MODE to switch between implementations.
#
# HOW TO INSTALL DEPS:
#   cd python
#   pip install -r requirements.txt
#
# HOW TO RUN:
#   python main.py
#
# WHAT TO OBSERVE:
#   NO_POOL              → "Connect" column high every request
#                          DB pid changes every request (new server process)
#
#   WITH_POOL            → "Borrow" ~0ms after first request
#                          Same DB pid repeats (same connection reused!)
#
#   WITH_POOL_CONCURRENT → Multiple threads fire at the SAME instant
#                          DIFFERENT DB pids = multiple connections active together
#                          Borrow ~0ms for all (pool has enough connections)
#
#   POOL_EXHAUSTION      → More threads than pool size
#                          Extra threads WAIT (Borrow time spikes!)
#                          Shows connection queuing under pressure
# ---------------------------------------------------------------

# ---------------------------------------------------------------
#  CHANGE THIS to switch between implementations
#  Options:
#    "NO_POOL"              — new connection every request (sequential)
#    "WITH_POOL"            — pool, sequential requests
#    "WITH_POOL_CONCURRENT" — pool, many threads simultaneously (threads <= pool size)
#    "POOL_EXHAUSTION"      — pool, more threads than pool size (shows queuing/wait)
# ---------------------------------------------------------------
MODE = "NO_POOL"
# ---------------------------------------------------------------

TOTAL_REQUESTS   = 15   # used by sequential modes
CONCURRENT_USERS = 5    # threads fired simultaneously (WITH_POOL_CONCURRENT)
OVERLOAD_USERS   = 15   # intentionally > POOL_MAX_CONN=10   (POOL_EXHAUSTION)
ROUNDS           = 3    # how many waves of concurrent requests


def run_without_pool():
    print("Strategy : New connection created and destroyed on EVERY request")
    print("Watch    : 'Connect' column — full TCP+Auth cost paid each time")
    print("Watch    : DB pid — changes every request (new server process)")
    print()

    impl = WithoutPool()
    for i in range(1, TOTAL_REQUESTS + 1):
        impl.run_query(i)


def run_with_pool():
    print("Strategy : Pool opened once, connections borrowed and returned (sequential)")
    print("Watch    : 'Borrow' column — near 0ms after warm-up")
    print("Watch    : DB pid — same value repeats (same connection reused!)")
    print()

    impl = WithPool()
    for i in range(1, TOTAL_REQUESTS + 1):
        impl.run_query(i)
    impl.shutdown()


def run_concurrent(num_threads: int):
    if num_threads <= config.POOL_MAX_CONN:
        print(f"Strategy : {num_threads} threads fire SIMULTANEOUSLY — all fit in pool")
        print("Watch    : DIFFERENT DB pids at the same time = multiple connections active")
        print("Watch    : Borrow time ~0ms for all threads (pool has enough connections)")
    else:
        print(f"Strategy : {num_threads} threads fire SIMULTANEOUSLY — MORE than pool size {config.POOL_MAX_CONN}")
        print(f"Watch    : First {config.POOL_MAX_CONN} threads get connections immediately")
        print(f"Watch    : Remaining {num_threads - config.POOL_MAX_CONN} threads WAIT — Borrow time spikes!")
        print("Watch    : This is pool exhaustion / connection queuing in action")
    print()

    impl = WithPool()
    impl.run_concurrent(num_threads, ROUNDS)
    impl.shutdown()


def main():
    print("=" * 90)
    print("  Connection Pool Demo  (Python / psycopg2)")
    print(f"  MODE     : {MODE}")
    print(f"  DB URL   : host={config.HOST} dbname={config.DATABASE}")
    print("=" * 90)
    print()

    overall_start = time.monotonic()

    if   MODE == "NO_POOL":              run_without_pool()
    elif MODE == "WITH_POOL":            run_with_pool()
    elif MODE == "WITH_POOL_CONCURRENT": run_concurrent(CONCURRENT_USERS)
    elif MODE == "POOL_EXHAUSTION":      run_concurrent(OVERLOAD_USERS)
    else:
        print(f"Unknown MODE: {MODE}")
        return

    overall_end = time.monotonic()
    total_ms = int((overall_end - overall_start) * 1000)

    print()
    print("=" * 90)
    print(f"  Total wall-clock time: {total_ms}ms")
    print("=" * 90)


if __name__ == "__main__":
    main()
