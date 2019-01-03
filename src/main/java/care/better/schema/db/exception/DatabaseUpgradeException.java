package care.better.schema.db.exception;

/**
 * @author Bostjan Lah
 */
public class DatabaseUpgradeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DatabaseUpgradeException(Throwable cause) {
        super(cause);
    }

    public DatabaseUpgradeException(String message) {
        super(message);
    }
}
