# ---------------------------------------------------------------
# Central place to configure your PostgreSQL connection details.
# Change these to match your local Postgres setup.
# ---------------------------------------------------------------

HOST     = "localhost"
PORT     = 5432
DATABASE = "portal"   # change if needed
USERNAME = "postgres"   # change if needed
PASSWORD = "postgres"   # change if needed

# psycopg2 connection string
DSN = f"host={HOST} port={PORT} dbname={DATABASE} user={USERNAME} password={PASSWORD}"

# ------- Pool settings (used only in with_pool) -------
POOL_MIN_CONN   = 5     # connections always kept open (min idle)
POOL_MAX_CONN   = 10    # max connections in pool
