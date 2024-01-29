package care.better.schema.db.impl;

import care.better.schema.db.SchemaInitializer;
import care.better.schema.db.exception.DatabaseUpgradeException;
import care.better.schema.db.exception.SchemaNotEmptyException;
import care.better.schema.db.exception.VersionMismatchException;
import care.better.schema.db.upgrade.DbUpgrade;
import com.google.common.base.Preconditions;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static care.better.schema.db.utils.SqlUtils.getValidScriptParts;

public class SchemaInitializerImpl implements SchemaInitializer {
    private static final Logger log = LoggerFactory.getLogger(SchemaInitializerImpl.class);

    private static final String DEPRECATED_DB_UPGRADES_PACKAGE = "com.marand.thinkehr.db.upgrade.impl.";
    private static final String DB_UPGRADES_PACKAGE = "care.better.schema.db.upgrade.impl.";
    private static final String SCHEMA_DIRECTORY = "/schema";
    private static final String SCRIPTS_DIRECTORY = "/schema/upgrades";
    private static final String ADDITIONAL_DIRECTORY = "/schema/additional";

    private final DataSource dataSource;
    private final Boolean azure;
    private final String dialect;

    private final String dialectScriptsDirectory;
    private final String dialectAdditionalDirectory;
    private final String entireSchemaResource;
    private final String schemaVersionTableName;

    public SchemaInitializerImpl(DataSource dataSource, String dialect, Boolean azure, String schemaVersionTableName, String dialectDir) {
        this.dataSource = dataSource;
        this.schemaVersionTableName = schemaVersionTableName;
        this.dialect = dialect;

        Preconditions.checkNotNull(dialectDir, "Unable to find upgrades for dialect " + dialect);
        log.info("Found dialect dir {} for {}", dialectDir, dialect);

        dialectScriptsDirectory = SCRIPTS_DIRECTORY + '/' + dialectDir;
        dialectAdditionalDirectory = ADDITIONAL_DIRECTORY + '/' + dialectDir;

        if (dialectDir.startsWith("mssql")) {
            entireSchemaResource = SCHEMA_DIRECTORY + '/' + dialectDir + (azure ? "azure" : "") + "-schema.sql";
            this.azure = azure;
        } else {
            entireSchemaResource = SCHEMA_DIRECTORY + '/' + dialectDir + "-schema.sql";
            this.azure = false;
        }
    }


    /**
     * initialize empty schema to latest version. will fail if schema not empty.
     *
     * @throws SQLException
     * @throws IOException
     */
    @Override
    public void initializeEmpty() throws SQLException, IOException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Integer version = getVersion(connection);
            if (version != null) {
                throw new SchemaNotEmptyException("Schema not empty, initialization aborted!");
            }
            int latestVersion = calculateLatestVersion();

            log.info("Initializing database schema from scratch to version {}", latestVersion);
            createLatestVersionSchema(connection);

            executeAdditionalScript(connection);
            int newVersion = getVersion(connection);
            connection.commit();

            log.info("Update complete, version set to {}", newVersion);
        }
    }

    /**
     * update existing schema to latest version. will fail on empty schema
     *
     * @throws SQLException
     * @throws IOException
     */
    @Override
    public void updateExisting() throws SQLException, IOException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            Integer initialVersion = getVersion(connection);
            if (initialVersion == null) {
                throw new DatabaseUpgradeException("Schema is uninitialized, upgrade aborted!");
            }

            int newVersion = initialVersion;
            while (updateToVersion(connection, newVersion + 1)) {
                newVersion++;
            }

            if (newVersion > initialVersion) {
                log.info("Update complete, version set to {}", newVersion);
            } else if (newVersion == initialVersion) {
                if (newVersion > 0) {
                    validateVersionUpgradeFileExists(initialVersion);
                }
                log.info("Update not needed, version already at {}", newVersion);
            }
            connection.commit();
        } catch (SQLException | IOException | DatabaseUpgradeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.close();
        }
    }

    /**
     * initialize/update schema to latest version, however is needed.
     *
     * @throws SQLException
     * @throws IOException
     */
    @Override
    public void initializeOrUpdate() throws SQLException, IOException {
        Integer version;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            version = getVersion(connection);
            connection.rollback();
        }
        if (version == null) {
            initializeEmpty();
        } else if (version >= 0) {
            updateExisting();
        } else {
            throw new DatabaseUpgradeException("Database schema version is [" + version + "]. This state is undefined. Manual upgrade necessary!");
        }
    }

    protected DataSource getDataSource() {
        return dataSource;
    }

    protected Boolean getAzure() {
        return azure;
    }

    protected String getDialect() {
        return dialect;
    }

    protected String getSchemaVersionTableName() {
        return schemaVersionTableName;
    }


    private boolean updateToVersion(Connection connection, int version) throws IOException, SQLException {
        try (InputStream inputStream = getClass().getResourceAsStream(dialectScriptsDirectory + '/' + version + ".sql")) {
            if (inputStream == null) {
                return false;
            } else {
                log.info("Updating schema to version {}", version);

                executeJavaUpgrade(connection, version);
                executeScript(connection, inputStream);

                setSchemaVersion(connection, version);

                log.info("Successfully updated schema to version {}", version);

                return true;
            }
        }
    }

    private int createLatestVersionSchema(Connection connection) throws SQLException, IOException {
        int latestVersion = calculateLatestVersion();

        executeScript(connection, getClass().getResourceAsStream(entireSchemaResource));

        createEmptySchemaVersionTable(connection);
        setSchemaVersion(connection, latestVersion);

        return getVersion(connection);
    }

    private void executeJavaUpgrade(Connection connection, int version) {
        if (!executeJavaUpgrade(connection, version, DB_UPGRADES_PACKAGE)) {
            executeJavaUpgrade(connection, version, DEPRECATED_DB_UPGRADES_PACKAGE);
        }
    }

    private Boolean executeJavaUpgrade(Connection connection, int version, String classPackage) {
        try {
            Class<?> upgradeClass = Class.forName(classPackage + "UpgradeTo" + version);
            DbUpgrade dbUpgrade = (DbUpgrade) upgradeClass.getConstructor().newInstance();
            dbUpgrade.upgrade(connection, dialect);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new DatabaseUpgradeException(e);
        }
    }

    private void executeScript(Connection connection, InputStream scriptStream) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            String scriptContents = IOUtils.toString(scriptStream, StandardCharsets.UTF_8);
            for (String script : getValidScriptParts(scriptContents)) {
                log.debug("Executing upgrade script {}", script);
                if (dialectScriptsDirectory.endsWith("ora") && StringUtils.endsWithIgnoreCase(script, "END")) {
                    statement.execute(script + ';');
                } else {
                    statement.execute(script);
                }
            }
        }
    }

    private void executeAdditionalScript(Connection connection) throws SQLException, IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(dialectAdditionalDirectory + "/add.sql")) {
            if (inputStream != null) {
                executeScript(connection, inputStream);
            }
        }
    }

    protected void setSchemaVersion(Connection connection, int newVersion)
            throws SQLException {
        try (PreparedStatement updateVersionSt = connection.prepareStatement("UPDATE " + schemaVersionTableName + " SET version = ?")) {
            updateVersionSt.setInt(1, newVersion);
            updateVersionSt.executeUpdate();
        }
    }

    protected void createEmptySchemaVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (azure) {
                statement.execute(
                        "CREATE TABLE " + schemaVersionTableName + " (id INTEGER NOT NULL, version INTEGER NOT NULL, primary key clustered (id))");
                statement.executeUpdate("INSERT INTO " + schemaVersionTableName + " (id, version) VALUES (1, 0)");
            } else if (dialect.contains("Ignite")) {
                statement.execute("CREATE TABLE " + schemaVersionTableName + " (id INT PRIMARY KEY, version INTEGER NOT NULL) WITH " +
                                          "\"template=replicated,atomicity=transactional_snapshot,cache_name=" + schemaVersionTableName + "\";");
                statement.executeUpdate("INSERT INTO " + schemaVersionTableName + " (id, version) VALUES (1, 0)");
            } else {
                statement.execute("CREATE TABLE " + schemaVersionTableName + " (version INTEGER NOT NULL)");
                statement.executeUpdate("INSERT INTO " + schemaVersionTableName + " (version) VALUES (0)");
            }
        }
    }

    protected Integer getVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (tableExists(connection, schemaVersionTableName)) {
                try (ResultSet rs = statement.executeQuery("SELECT version FROM " + schemaVersionTableName)) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } else {
                return null;
            }
        }
    }

    private int calculateLatestVersion() throws IOException {
        int i = 0;
        while (true) {
            try (InputStream stream = getClass().getResourceAsStream(dialectScriptsDirectory + '/' + ++i + ".sql")) {
                if (stream == null) {
                    break;
                }
            }
        }
        return i - 1;
    }

    protected boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String databaseName = connection.getCatalog() != null && connection.getCatalog().isBlank() ? null : connection.getCatalog();
        boolean exists;
        try (ResultSet tableExists = metaData.getTables(databaseName, dialectScriptsDirectory.endsWith("ora") ? connection.getSchema() : null, tableName, null)) {
            exists = tableExists.next();
        }
        if (!exists) {
            try (ResultSet tableExists = metaData.getTables(databaseName, dialectScriptsDirectory.endsWith("ora") ? connection.getSchema() : null, tableName.toLowerCase(), null)) {
                exists = tableExists.next();
            }
        }
        if (!exists) {
            try (ResultSet tableExists = metaData.getTables(databaseName, dialectScriptsDirectory.endsWith("ora") ? connection.getSchema() : null, tableName.toUpperCase(), null)) {
                exists = tableExists.next();
            }
        }
        return exists;
    }

    private void validateVersionUpgradeFileExists(int initialVersion) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(dialectScriptsDirectory + '/' + initialVersion + ".sql")) {
            if (stream == null) {
                throw new VersionMismatchException(initialVersion);
            }
        }
    }
}
