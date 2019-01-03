package care.better.schema.hibernate.naming;

import org.hibernate.cfg.ImprovedNamingStrategy;

/**
 * @author Bostjan Lah
 */
public class BetterNamingStrategy extends ImprovedNamingStrategy {
    private static final long serialVersionUID = 1L;

    @Override
    public String foreignKeyColumnName(
            String propertyName,
            String propertyEntityName,
            String propertyTableName,
            String referencedColumnName) {
        return super.foreignKeyColumnName(propertyName + "_id", propertyEntityName, propertyTableName, referencedColumnName);
    }
}
