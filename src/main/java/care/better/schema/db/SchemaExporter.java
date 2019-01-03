package care.better.schema.db;

import care.better.schema.db.exception.SchemaExportException;
import com.google.common.base.Preconditions;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import javax.persistence.Entity;
import javax.persistence.spi.PersistenceUnitInfo;
import java.io.File;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Bostjan Lah
 */
public final class SchemaExporter {
    private final Map<String, String> dialects;

    public SchemaExporter(Map<String, String> dialects) {
        this.dialects = dialects;
    }

    public void exportSchemasToFile(PersistenceUnitInfo persistenceUnitInfo, String filename, PhysicalNamingStrategy physicalNamingStrategy) {
        try {
            Set<String> processed = new HashSet<>();
            for (Map.Entry<String, String> entry : dialects.entrySet()) {
                if (!processed.contains(entry.getValue())) {
                    exportSchema(persistenceUnitInfo, filename, entry.getKey(), entry.getValue(), physicalNamingStrategy);
                    processed.add(entry.getValue());
                }
            }
        } catch (ClassNotFoundException e) {
            throw new SchemaExportException(e);
        }
    }

    @SuppressWarnings("HardcodedLineSeparator")
    private void exportSchema(
            PersistenceUnitInfo persistenceUnitInfo,
            String filename,
            String dialect,
            String prefix,
            PhysicalNamingStrategy physicalNamingStrategy) throws ClassNotFoundException {
        Preconditions.checkNotNull(filename, "filename is null!");
        Preconditions.checkNotNull(dialect, "dialect is null!");

        StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();
        builder.applySetting("hibernate.dialect", dialect);
        builder.applySetting("hibernate.dialect.oracle.prefer_long_raw", "true");

        MetadataSources metadataSources = new MetadataSources(builder.build());

        for (String className : persistenceUnitInfo.getManagedClassNames()) {
            metadataSources.addAnnotatedClass(Class.forName(className));
        }

        Metadata metadata = metadataSources.getMetadataBuilder()
                .applyPhysicalNamingStrategy(physicalNamingStrategy)
                .build();

        String path = String.format(filename, prefix);
        File file = new File(path);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        System.err.println("Generating for " + dialect + " to " + file.getAbsolutePath());
        SchemaExport export = new SchemaExport()
                .setOutputFile(path)
                .setFormat(true)
                .setDelimiter(";\n");
        export.createOnly(EnumSet.of(TargetType.SCRIPT), metadata);
    }

    /**
     * Exports generated schema file to the specified file
     *
     * @param dialectName            Hibernate dialect name
     * @param physicalNamingStrategy Hibernate physical naming strategy
     * @param implicitNamingStrategy Hibernate implicit naming strategy
     * @param packageNames           collection of packages to scan for @Entity classes
     * @param filename               output filename
     */
    public static void exportSchemasToFile(
            String dialectName,
            String physicalNamingStrategy,
            String implicitNamingStrategy,
            Collection<String> packageNames,
            String filename,
            Map<String, String> additionalHibernateSettings) {
        Map<String, String> settings = new HashMap<>(additionalHibernateSettings);
        settings.put("hibernate.physical_naming_strategy", physicalNamingStrategy);
        settings.put("hibernate.implicit_naming_strategy", implicitNamingStrategy);
        settings.put("hibernate.dialect", dialectName);

        MetadataSources metadata = new MetadataSources(
                new StandardServiceRegistryBuilder()
                        .applySettings(settings)
                        .build());

        new Reflections(new ConfigurationBuilder()
                                .setUrls(
                                        packageNames.stream()
                                                .flatMap(packageName -> ClasspathHelper.forPackage(packageName).stream())
                                                .collect(Collectors.toSet())
                                ))
                .getTypesAnnotatedWith(Entity.class)
                .forEach(metadata::addAnnotatedClass);

        File file = new File(filename);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        SchemaExport export = new SchemaExport()
                .setOutputFile(filename)
                .setFormat(true)
                .setDelimiter(";\n");
        export.createOnly(EnumSet.of(TargetType.SCRIPT), metadata.buildMetadata());
    }
}
