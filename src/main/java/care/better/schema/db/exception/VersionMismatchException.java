package care.better.schema.db.exception;

/**
 * @author Bostjan Lah
 */
public class VersionMismatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public VersionMismatchException(int dbVersion) {
        super("Database version " + dbVersion + " exceeds server version!");
    }
}
