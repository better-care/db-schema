package care.better.schema.db;

import java.io.IOException;
import java.sql.SQLException;

/**
 * @author Bostjan Lah
 */
public interface SchemaInitializer {
    void updateExisting() throws SQLException, IOException;

    void initializeEmpty() throws SQLException, IOException;

    void initializeOrUpdate() throws SQLException, IOException;
}
