package com.github.dsquare68.homeforge.db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * The single place every PostgreSQL setting in HUB lives.
 *
 * <p>Values come from the environment, {@code secrets.properties} or
 * {@code application.properties}; the defaults after each {@code :} are what a
 * fresh checkout runs with. {@link HubDBProperties} overlays the details typed
 * on the first-run setup page on top of these fields, so read them through that
 * class (or through {@link DBConnection}) rather than injecting this bean
 * directly — otherwise you may read a value before the overlay is applied.
 */
@Component
@Getter
public class DBProperties {

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    @Value("${DB_HOST:localhost}")
    public String DB_HOST;

    @Value("${DB_PORT:5432}")
    public int DB_PORT;

    @Value("${DB_NAME:home_forge_db}")
    public String DB_NAME;

    @Value("${DB_USER:user}")
    public String DB_USER;

    @Value("${DB_PASSWORD:pass}")
    public String DB_PASSWORD;

    /** Schema holding HUB's own tables; plugins each get their own. */
    @Value("${DB_SCHEMA:hub_schema}")
    public String DB_SCHEMA;

    @Value("${DB_DRIVER:org.postgresql.Driver}")
    public String DB_DRIVER;

    // ------------------------------------------------------------------
    // Tuning — change these to tune every connection HUB opens
    // ------------------------------------------------------------------

    /** Connections per pool; HUB opens one pool per plugin schema. */
    @Value("${DB_MAX_POOL_SIZE:5}")
    public int DB_MAX_POOL_SIZE;

    @Value("${DB_CONNECT_TIMEOUT_SECONDS:5}")
    public int DB_CONNECT_TIMEOUT_SECONDS;

    @Value("${DB_SOCKET_TIMEOUT_SECONDS:10}")
    public int DB_SOCKET_TIMEOUT_SECONDS;

    /** Shows up in {@code pg_stat_activity}, so HUB's connections are identifiable. */
    @Value("${DB_APPLICATION_NAME:homeforge}")
    public String DB_APPLICATION_NAME;

    // ------------------------------------------------------------------
    // Derived
    // ------------------------------------------------------------------

    /**
     * True once a real user/password is available — as opposed to a blank value
     * or an unresolved {@code ${DB_USER}} placeholder. Used to keep SQL-backed
     * pages and plugin provisioning from touching the database before first-run
     * setup has completed.
     */
    public boolean isConfigured() {
        return isResolved(DB_USER) && isResolved(DB_PASSWORD);
    }

    private static boolean isResolved(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
    }
}
