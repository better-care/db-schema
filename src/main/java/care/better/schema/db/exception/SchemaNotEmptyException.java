package care.better.schema.db.exception;

/**
 * @author Bostjan Lah
 */
public class SchemaNotEmptyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SchemaNotEmptyException(String message) {
        super(message);
    }
}
