# Better DB Schema Management implementation guidelines (DB schema initialization)

## Licence
[![License](https://img.shields.io/badge/license-apache%202.0-60C060.svg)](https://choosealicense.com/licenses/apache-2.0/)

## Release

[![Release Artifacts](https://maven-badges.herokuapp.com/maven-central/care.better.schema/db-schema/badge.svg)](https://search.maven.org/artifact/care.better.schema/db-schema)


Usage with maven dependency:

```xml

<dependency>
  <groupId>care.better.schema</groupId>
  <artifactId>db-schema</artifactId>
  <version>2.1.0</version>
</dependency>
```

## Anatomy of this tool
#### Script types and locations
1. `{name}-schema.sql` named scripts are meant for initial setup of database schema and is called first. Should be located in `classpath:/schema/`
2. `{number[1-x]}.sql` named scripts are for sequential updates to db schema and should be located in `classpath:/schema/upgrades/{db_name}/`. The name of the script reflects the version of schema.

Currently supported databases (`db_name`):
* pgsql = [PostgreSQL](https://www.postgresql.org)
* ora = [Oracle](https://www.oracle.com/database)
* h2 = [H2](http://www.h2database.com)
* mssql, mssql2012 and mssql2012azure = [SQL Server](https://www.microsoft.com/en-us/sql-server) (2008, 2012 and 2012 on azure)
* mysql = [MySQL](https://www.mysql.com)
* ignite = [Apache Ignite](https://ignite.apache.org)

## Creating initial schema scripts
Creating base db schema sql scripts from @Entity classes is easiest with a JUnit test (because of a relative ease of import of ApplicationContext):
```java
@DirtiesContext
@ContextConfiguration(classes = DbSchemaInitializerGenConfiguration.class)
@TestPropertySource(locations = "classpath:system.properties", properties = {
        "platform.db.packages-to-scan=care.better.platform.model.entities,care.better.platform.auth.entities",
        "platform.db.schema-location=../../platform-server/src/main/resources/schema/%s-schema.sql"})
public class GenerateSchemaTest extends AbstractTestNGSpringContextTests {
    @Resource(name = "&dbCreatorFactory")
    private LocalContainerEntityManagerFactoryBean factoryBean;

    @Value("${platform.db.schema-location}")
    private String schemaLocation;

    @Test(groups = "manual")
    public void generate() {
        SchemaExporter.exportSchemasToFile(factoryBean.getPersistenceUnitInfo(), schemaLocation);
    }
}
```
where mandatory properties are:
* **platfrom.db.packages-to-scan**: Java packages of entities you want tables for.
* **platfrom.db.schema-location**: Location of schema scripts. *WARNING: existing scripts will be overwritten!*
* **datasource properties**: 
  * jdbc.driver/spring.datasource.driver-class-name
  * jdbc.url/spring.datasource.url
  * jdbc.username/spring.datasource.username
  * jdbc.password/spring.datasource.password

## Database schema upgrade
There are 2 ways to upgrade db schema:
* with sql scripts
* with implementations of `care.better.schema.db.upgrade.DbUpgrade`


#### Implementations of `care.better.schema.db.upgrade.DbUpgrade`
For more complex upgrades/updates you can implement interface `care.better.schema.db.upgrade.DbUpgrade`. Resulting class should be part of package `care.better.schema.db.upgrade.impl`. Class should be named UpgradeTo + version number, e.g. `UpgradeTo11`. Java implemented upgrade steps will be run together with but *before* sql script upgrades.

Example of changing hibernate sequence from a table to an actual sequence, specific to `SQLServerDialect`:
```java
public class UpgradeTo10 implements DbUpgrade {
    @Override
    public boolean upgrade(Connection connection, String dialectName) {
        try {
            if (Objects.equals("org.hibernate.dialect.SQLServerDialect", dialectName) && !hibernateSequenceExists(connection)) {
                convertTableToSequence(connection);
            }
            return false;
        } catch (SQLException e) {
            throw new DatabaseUpgradeException(e);
        }
    }

    private boolean hibernateSequenceExists(Connection connection) throws SQLException {
        SQLServerDialect dialect = new SQLServerDialect();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(dialect.getQuerySequencesString())) {
            while (rs.next()) {
                if (rs.getString(1).contains("hibernate_sequence")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void convertTableToSequence(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            long nextVal = getNextVal(statement);
            statement.execute("DROP TABLE hibernate_sequence");
            statement.execute("CREATE SEQUENCE hibernate_sequence START WITH " + nextVal + " INCREMENT BY 1000");
        }
    }

    private long getNextVal(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT next_val FROM hibernate_sequence")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 1L;
    }
}
```
