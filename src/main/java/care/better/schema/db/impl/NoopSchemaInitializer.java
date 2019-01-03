package care.better.schema.db.impl;

import care.better.schema.db.SchemaInitializer;

import java.io.IOException;
import java.sql.SQLException;

/**
 * @author Bostjan Lah
 */
public class NoopSchemaInitializer implements SchemaInitializer {
    @Override
    public void updateExisting() throws SQLException, IOException {
    }

    @Override
    public void initializeEmpty() throws SQLException, IOException {
    }

    @Override
    public void initializeOrUpdate() throws SQLException, IOException {
    }
}
