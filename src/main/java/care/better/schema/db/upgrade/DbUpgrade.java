package care.better.schema.db.upgrade;

import java.sql.Connection;

/**
 * @author Bostjan Lah
 */
@FunctionalInterface
public interface DbUpgrade {
    boolean upgrade(Connection connection, String dialect);
}
