package care.better.schema.db.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Dusan Markovic
 */
@SuppressWarnings("HardcodedLineSeparator")
public final class SqlUtils {

    private static final Pattern SPLIT_SCRIPT = Pattern.compile("\n\\s*\n");

    private SqlUtils() {
    }

    public static List<String> getValidScriptParts(String script) {
        return SPLIT_SCRIPT.splitAsStream(script)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(input -> input.replaceAll("--[^\\r\\n]*", ""))
                .map(StringUtils::normalizeSpace)
                .map(input -> input.endsWith(";") ? input.substring(0, input.length() - 1) : input)
                .collect(Collectors.toList());
    }

}
