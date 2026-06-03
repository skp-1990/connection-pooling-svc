# Connection Pooling — Concept, Demo & Trade-offs

A hands-on learning project demonstrating **connection pooling** vs **no pooling** with working code in both **Java** (HikariCP) and **Python** (psycopg2), backed by PostgreSQL.

---

## Table of Contents

- [What is Connection Pooling?](#what-is-connection-pooling)
- [The Problem It Solves](#the-problem-it-solves)
- [How a Connection is Actually Made](#how-a-connection-is-actually-made)
  - [TCP 3-Way Handshake](#tcp-3-way-handshake)
  - [TLS Negotiation](#tls-negotiation)
- [How Connection Pooling Works](#how-connection-pooling-works)
- [Key Pool Configuration Parameters](#key-pool-configuration-parameters)
- [Pool Sizing Formula](#pool-sizing-formula)
- [Pool Placement — In-Process vs External Proxy](#pool-placement--in-process-vs-external-proxy)
- [Trade-offs](#trade-offs)
- [Project Structure](#project-structure)
- [Running the Demo](#running-the-demo)
  - [Java (HikariCP)](#java-hikaricp)
  - [Python (psycopg2)](#python-psycopg2)
- [Demo Modes Explained](#demo-modes-explained)
- [What to Observe in the Output](#what-to-observe-in-the-output)

---

## What is Connection Pooling?

Connection pooling is a technique of **maintaining a set of pre-opened, reusable database connections** so that applications don't have to pay the cost of establishing a new connection for every database request.

Without pooling:
```
Request → [open connection] → [query] → [close connection]   ← full cost every time
Request → [open connection] → [query] → [close connection]
Request → [open connection] → [query] → [close connection]
```

With pooling:
```
App starts → [open N connections and keep them warm]

Request → [borrow connection] → [query] → [return connection]   ← near zero overhead
Request → [borrow connection] → [query] → [return connection]
Request → [borrow connection] → [query] → [return connection]
```

---

## The Problem It Solves

Every new database connection involves multiple expensive steps:

| Step | Typical cost (localhost) | Typical cost (cross-region) |
|---|---|---|
| TCP 3-way handshake | ~1ms | ~100ms |
| TLS negotiation | ~5ms | ~150ms |
| DB authentication | ~2ms | ~10ms |
| **Total overhead** | **~8ms** | **~260ms** |

On top of latency, each open connection on PostgreSQL consumes **~5–10 MB of RAM** on the server and one OS process/thread. Without pooling, a burst of 500 concurrent users = 500 simultaneous `connect()` calls = potential DB crash.

---

## How a Connection is Actually Made

### TCP 3-Way Handshake

Before any data flows, two machines must synchronize. This costs **1 full round trip**.

```
Client                        Server
  │                             │
  │ ── SYN (seq=100) ─────────> │   "I want to connect"
  │                             │
  │ <── SYN-ACK (seq=200) ───── │   "OK, I'm ready"
  │                             │
  │ ── ACK (ack=201) ─────────> │   "Let's go"
  │                             │
  │ ══ data can flow now ══════ │
```

- **SYN** — client picks a random sequence number, sends it
- **SYN-ACK** — server acknowledges client's number, sends its own
- **ACK** — client acknowledges server's number; connection established

### TLS Negotiation

If the connection is encrypted (recommended for production), TLS runs on top of TCP.

**TLS 1.2** (older) — costs **2 round trips** after TCP:
```
Client                              Server
  │ ── ClientHello ───────────────> │   cipher suites I support + random value
  │ <── ServerHello + Certificate ─ │   chosen cipher + server's public cert
  │ ── ClientKeyExchange ─────────> │   secret encrypted with server's public key
  │ <── Finished ─────────────────  │   handshake complete
  │ ══ encrypted data flows ══════  │
```

**TLS 1.3** (modern) — costs **1 round trip** after TCP:
```
Client                              Server
  │ ── ClientHello + key_share ───> │   key material sent upfront
  │ <── ServerHello + Finished ──── │   server responds + already encrypted
  │ ══ encrypted data flows ══════  │
```

**Full timeline before your first query runs:**
```
t=0ms    TCP SYN         ──>
t=50ms   TCP SYN-ACK     <──
t=50ms   TCP ACK         ──>
t=50ms   TLS ClientHello ──>
t=100ms  TLS ServerHello <──
t=100ms  TLS Finished    ──>
t=150ms  TLS Finished    <──
t=150ms  DB Auth         ──>
t=200ms  DB Auth OK      <──

t=200ms  YOUR QUERY FINALLY RUNS  ← 200ms wasted just saying hello
```

Connection pooling eliminates this entire block after the first connection is opened.

---

## How Connection Pooling Works

```
┌─────────────────────────────────────────────────────┐
│                  Connection Pool                     │
│                                                      │
│  conn-1 [idle]  ──────────────────────────────────  │
│  conn-2 [idle]  ──────────────────────────────────  │
│  conn-3 [IN USE] ← borrowed by Request A            │
│  conn-4 [idle]  ──────────────────────────────────  │
│  conn-5 [IN USE] ← borrowed by Request B            │
│                                                      │
└─────────────────────────────────────────────────────┘

Request A  →  getConnection()  →  runs query  →  putConnection()  (returned, not closed)
Request B  →  getConnection()  →  runs query  →  putConnection()  (returned, not closed)
Request C  →  getConnection()  →  [waits if all busy]  →  runs query  →  putConnection()
```

**Connection lifecycle states:**
```
[idle] ──borrow──> [in-use] ──return──> [idle]
                                           │
                              (idle_timeout exceeded)
                                           │
                                       [closed]
```

---

## Key Pool Configuration Parameters

| Parameter | What it controls | Impact |
|---|---|---|
| `min_pool_size` | Connections always kept open | Floor of warm connections ready immediately |
| `max_pool_size` | Hard ceiling on total connections | Requests block/fail when this is hit |
| `connection_timeout` | How long a request waits to borrow | Prevents infinite wait under exhaustion |
| `idle_timeout` | Max idle time before a connection is closed | Saves DB memory during quiet periods |
| `max_lifetime` | Max age of any connection | Prevents stale/zombie connections after DB failover |
| `connection_test_query` | SQL run to validate a connection before borrowing | Catches dead connections before your code sees them |

---

## Pool Sizing Formula

```
pool_size = (core_count × 2) + effective_spindle_count
```

| Term | Meaning |
|---|---|
| `core_count` | Physical CPU cores on the **DB server** (not your app server) |
| `× 2` | While one query uses CPU, another waits for I/O — slight oversubscription helps |
| `effective_spindle_count` | Number of storage disks (HDD = 1 per disk; SSD ≈ 1–4; NVMe ≈ 4–8) |

**Examples:**

```
4-core DB server + 1 SSD     →  (4 × 2) + 1  =  9  connections
8-core DB server + 4 HDDs    →  (8 × 2) + 4  =  20 connections
```

> This feels counterintuitively small — but the DB is I/O-bound, not CPU-bound.
> **More connections ≠ faster DB.** Beyond the formula, threads fight for CPU and throughput drops.

---

## Pool Placement — In-Process vs External Proxy

### In-Process Pool (this demo — HikariCP, psycopg2)

The pool lives **inside your application process**.

```
App Instance 1  [pool: 10 conns] ─────┐
App Instance 2  [pool: 10 conns] ─────┼──> PostgreSQL  (30 connections total)
App Instance 3  [pool: 10 conns] ─────┘
```

✅ Simple, no extra infrastructure  
❌ Each app instance has its own pool — N instances × pool_size = many DB connections

### External Proxy Pool (PgBouncer, RDS Proxy, ProxySQL)

A dedicated proxy sits between your app and DB, sharing one pool across all instances.

```
App Instance 1  [pool: 50 conns] ─────┐
App Instance 2  [pool: 50 conns] ─────┼──> PgBouncer [pool: 20 conns] ──> PostgreSQL
App Instance 3  [pool: 50 conns] ─────┘
```

✅ DB sees far fewer connections regardless of how many app instances you scale to  
✅ Essential for serverless / FaaS (Lambda, Cloud Functions) where in-process pools are useless  
❌ Extra infrastructure to deploy and monitor

---

## Trade-offs

| Trade-off | Detail |
|---|---|
| **Pool size vs DB capacity** | Bigger pool = more responsive app but more RAM/threads consumed on DB server |
| **Latency vs throughput** | Small pool → low DB load but high queue wait under burst traffic |
| **In-process vs proxy** | In-process is simpler; proxy scales better when you have many app instances |
| **Connection max_lifetime** | Too long → stale connections survive DB failovers; too short → frequent reconnects |
| **min_idle connections** | Keep too many warm → waste DB resources during quiet hours; too few → cold-start spikes |
| **connection_timeout** | Too long → users wait forever under exhaustion; too short → false failures under brief spikes |
| **Serverless / FaaS** | In-process pools are useless — each function invocation is a fresh process. Use RDS Proxy or Neon |
| **Prepared statement caching** | Pools cache prepared statements per connection. Switching connections (transaction mode) can invalidate them |
| **Session-level features** | PgBouncer transaction mode breaks `SET`, temp tables, advisory locks — they require session-level connections |

---

## Project Structure

```
connection-pooling-svc/
│
├── java/                                  ← Java demo (Maven + HikariCP)
│   ├── pom.xml
│   └── src/main/java/com/pooldemo/
│       ├── DBConfig.java                  ← DB credentials & pool settings
│       ├── WithoutPool.java               ← new connection every request
│       ├── WithPool.java                  ← HikariCP pool (sequential + concurrent)
│       └── Main.java                      ← toggle MODE here
│
└── python/                                ← Python demo (psycopg2)
    ├── requirements.txt
    ├── config.py                          ← DB credentials & pool settings
    ├── without_pool.py                    ← new connection every request
    ├── with_pool.py                       ← ThreadedConnectionPool (sequential + concurrent)
    └── main.py                            ← toggle MODE here
```

---

## Running the Demo

### Prerequisites

- PostgreSQL running locally on port `5432`
- Update credentials in `java/src/main/java/com/pooldemo/DBConfig.java` and `python/config.py`

### Java (HikariCP)

```bash
cd java

# Build (downloads HikariCP + PostgreSQL driver automatically)
mvn package -q

# Run
java -jar target/connection-pool-demo-1.0-SNAPSHOT.jar
```

### Python (psycopg2)

```bash
cd python

# Install dependency (one time)
pip install -r requirements.txt

# Run
python main.py
```

---

## Demo Modes Explained

Change the `MODE` constant in `Main.java` (Java) or `main.py` (Python):

| MODE | What it does |
|---|---|
| `NO_POOL` | Creates a brand-new connection for every request. Sequential. |
| `WITH_POOL` | Borrows from pool. Sequential. Shows connection reuse. |
| `WITH_POOL_CONCURRENT` | 5 threads fire simultaneously. All fit in pool. Shows parallel connections. |
| `POOL_EXHAUSTION` | 15 threads fire simultaneously against a pool of max 10. Shows queuing. |

---

## What to Observe in the Output

### NO_POOL
```
[NO POOL]   Request #1  | Connect: 142ms | Query: 2ms | Total: 244ms | DB pid: 8921
[NO POOL]   Request #2  | Connect: 138ms | Query: 1ms | Total: 239ms | DB pid: 8934  ← different pid!
[NO POOL]   Request #3  | Connect: 145ms | Query: 2ms | Total: 247ms | DB pid: 8951  ← different pid!
```
👉 `Connect` dominates. New DB pid every request = new OS process on the DB server.

### WITH_POOL
```
[WITH POOL] Request #1  | Borrow:  11ms | Query: 2ms | Total:  13ms | DB pid: 9001
[WITH POOL] Request #2  | Borrow:   0ms | Query: 1ms | Total:   1ms | DB pid: 9001  ← SAME pid!
[WITH POOL] Request #3  | Borrow:   0ms | Query: 2ms | Total:   2ms | DB pid: 9001  ← SAME pid!
```
👉 `Borrow` drops to ~0ms. Same pid = same connection reused. No TCP/TLS cost.

### WITH_POOL_CONCURRENT
```
--- Round 1 (all 5 threads fire simultaneously) ---
[CONCURRENT] Thread-1  | Borrow:  0ms | Query: 52ms | DB pid: 9001  ┐ all different pids
[CONCURRENT] Thread-2  | Borrow:  0ms | Query: 51ms | DB pid: 9002  │ all at the same time
[CONCURRENT] Thread-3  | Borrow:  0ms | Query: 53ms | DB pid: 9003  │ borrow ~0ms for all
[CONCURRENT] Thread-4  | Borrow:  0ms | Query: 52ms | DB pid: 9004  │
[CONCURRENT] Thread-5  | Borrow:  0ms | Query: 51ms | DB pid: 9005  ┘
```
👉 Different pids at the same moment = pool serving multiple connections in parallel.

### POOL_EXHAUSTION
```
--- Round 1 (all 15 threads fire simultaneously) ---
[CONCURRENT] Thread-1  | Borrow:   0ms | Query: 52ms | DB pid: 9001  ┐ first 10 instant
[CONCURRENT] Thread-5  | Borrow:   0ms | Query: 51ms | DB pid: 9005  ┘
[CONCURRENT] Thread-11 | Borrow:  54ms | Query: 52ms | DB pid: 9001  ← waited for a conn!
[CONCURRENT] Thread-14 | Borrow:  55ms | Query: 51ms | DB pid: 9003  ← queued!
```
👉 Once pool is full, new requests queue. `Borrow` time spikes = pool exhaustion in action.

---

## Key Takeaways

1. **Connection cost is real** — TCP + TLS + auth can cost 150–300ms on remote hosts
2. **Pool eliminates repeated handshakes** — connections established once, reused thousands of times
3. **Same DB pid = proof of reuse** — you can verify pooling is working by watching the pid
4. **Right-size your pool** — more connections hurt beyond the formula; contention wins
5. **Concurrent threads need concurrent connections** — sequential pool + parallel threads = queuing
6. **External proxy (PgBouncer) for scale** — when you have many app instances, in-process pools multiply connections
