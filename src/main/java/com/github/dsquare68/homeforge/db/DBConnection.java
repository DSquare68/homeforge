package com.github.dsquare68.homeforge.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Every PostgreSQL connection HUB opens comes from here — single connections
 * for one-off checks, pooled DataSources for everything long-lived.
 *
 * <p>Settings (host, credentials, timeouts, pool size) are not repeated in this
 * class; they are read from {@link DBProperties} through {@link HubDBProperties}
 * so changing a value in one place changes every connection HUB opens.
 *
 * <pre>{@code
 * try (Connection c = connections.open()) { ... }        // HUB's own credentials
 * DataSource pool = connections.pool("gym", "gym_schema");
 * DataSource pluginPool = connections.pool("gym", "gym_schema", "plugin_gym", secret);
 * }</pre>
 */
@Component
public class DBConnection {

    private static final Logger log = LoggerFactory.getLogger(DBConnection.class);

    private final DBProperties properties;

    public DBConnection(DBProperties properties) {
        this.properties = properties;
    }
    public Connection open() throws SQLException {
    	return open(properties.getDB_USER(), properties.getDB_PASSWORD(), properties.getDB_SCHEMA());
    }
    /**
     * Opens a connection as an arbitrary role — used to verify a plugin's
     * provisioned credentials actually work.
     *
     * <p>Throws rather than returning {@code null} on failure: callers like
     * {@code StartupCheck} exist to report <em>why</em> the database could not
     * be reached, and that reason only lives on the exception.
     */
    public Connection open(String username, String password, String schema) throws SQLException {
        loadDriver();

        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", username);
        connectionProperties.setProperty("password", password);
        connectionProperties.setProperty("connectTimeout", String.valueOf(properties.DB_CONNECT_TIMEOUT_SECONDS));
        connectionProperties.setProperty("socketTimeout", String.valueOf(properties.DB_SOCKET_TIMEOUT_SECONDS));
        connectionProperties.setProperty("ApplicationName", properties.DB_APPLICATION_NAME);

        return DriverManager.getConnection(schema == null ? url() : url(schema), connectionProperties);
    }

    public DataSource pool(String poolName, String schema, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url());
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(properties.DB_DRIVER);
        config.setSchema(schema);
        config.setPoolName(poolName);
        config.setMaximumPoolSize(properties.DB_MAX_POOL_SIZE);
        config.setConnectionTimeout(properties.DB_CONNECT_TIMEOUT_SECONDS * 1000L);
        config.addDataSourceProperty("ApplicationName", properties.DB_APPLICATION_NAME);

        return new HikariDataSource(config);
    }

    /** Closes a pool created here; anything else is left alone. */
    public void close(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
    }

    /**
     * The PostgreSQL driver registers itself via ServiceLoader, so this is
     * belt-and-braces; if the explicit load fails, let DriverManager report the
     * real problem.
     */
    private void loadDriver() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            log.debug("Could not load JDBC driver {}: {}", "org.postgresql.Driver", e.getMessage());
        }
    }
    
    /** Opens a throwaway connection to see whether PostgreSQL answers right now. */
    public boolean isConnected() {
    	try (Connection connection = open()) {
			return connection != null && !connection.isClosed();
		} catch (SQLException e) {
			log.debug("PostgreSQL is not reachable: {}", e.getMessage());
			return false;
		}
    }

    /** True once real credentials are configured — see {@link DBProperties#isConfigured()}. */
    public boolean isConfigured() {
    	return properties.isConfigured();
    }

    /** The schema holding HUB's own tables. */
    public String getSchema() {
    	return properties.DB_SCHEMA;
    }
    
    /** {@code jdbc:postgresql://host:port/database} */
    public String url() {
        return "jdbc:postgresql://" + properties.DB_HOST + ":" + properties.DB_PORT + "/" + properties.DB_NAME;
    }

    /** The same URL pinned to one schema — what plugins are handed. */
    public String url(String schema) {
        return url() + "?currentSchema=" + schema;
    }

	public String getDrivers() {
		return "org.postgresql.Driver";
	}
	public DataSource pool() {
		return pool(properties.DB_NAME,properties.DB_SCHEMA,properties.DB_USER, properties.DB_PASSWORD);
	}
	
	/** The PostgreSQL user HUB itself connects as. */
	public String getUsername() {
		return properties.DB_USER;
	}

	public String getDatabaseName() {
		return properties.DB_NAME;
	}
}
