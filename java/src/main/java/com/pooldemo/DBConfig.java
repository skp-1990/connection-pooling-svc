package com.pooldemo;

/**
 * Central place to configure your PostgreSQL connection details.
 * Change these to match your local Postgres setup.
 */
public class DBConfig {

    public static final String HOST     = "localhost";
    public static final int    PORT     = 5432;
    public static final String DATABASE = "portal";   // change if needed
    public static final String USERNAME = "postgres";   // change if needed
    public static final String PASSWORD = "postgres";   // change if needed

    // Full JDBC URL built from above
    public static final String JDBC_URL =
            "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE;

    // ------- Pool settings (used only in WithPool implementation) -------
    public static final int  POOL_MIN_IDLE    = 5;     // connections always kept warm
    public static final int  POOL_MAX_SIZE    = 10;    // max connections in pool
    public static final long POOL_TIMEOUT_MS  = 3000;  // wait time to borrow a connection
}
