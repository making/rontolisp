// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Objects;

// A level is its value: two of one value are equal whatever their names. Every level
// made is known to parse, in the order made. The default locale is en_US, whose
// localized names are the names.
public class Level implements Serializable {

    private static final String BUNDLE = "sun.util.logging.resources.logging";

    private static final ArrayList<Level> known = new ArrayList<>();

    public static final Level OFF = new Level("OFF", Integer.MAX_VALUE, BUNDLE);

    public static final Level SEVERE = new Level("SEVERE", 1000, BUNDLE);

    public static final Level WARNING = new Level("WARNING", 900, BUNDLE);

    public static final Level INFO = new Level("INFO", 800, BUNDLE);

    public static final Level CONFIG = new Level("CONFIG", 700, BUNDLE);

    public static final Level FINE = new Level("FINE", 500, BUNDLE);

    public static final Level FINER = new Level("FINER", 400, BUNDLE);

    public static final Level FINEST = new Level("FINEST", 300, BUNDLE);

    public static final Level ALL = new Level("ALL", Integer.MIN_VALUE, BUNDLE);

    private final String name;

    private final int value;

    private final String resourceBundleName;

    protected Level(String name, int value) {
        this(name, value, null);
    }

    protected Level(String name, int value, String resourceBundleName) {
        this.name = Objects.requireNonNull(name);
        this.value = value;
        this.resourceBundleName = resourceBundleName;
        register(this);
    }

    private static synchronized void register(Level level) {
        known.add(level);
    }

    public String getResourceBundleName() {
        return resourceBundleName;
    }

    public String getName() {
        return name;
    }

    public String getLocalizedName() {
        return name;
    }

    public final String toString() {
        return name;
    }

    public final int intValue() {
        return value;
    }

    /**
     * A level by its name, then by its value written in decimal: a known level of the
     * value, or a new one named by the text.
     */
    public static synchronized Level parse(String name) throws IllegalArgumentException {
        name.length();
        for (Level level : known) {
            if (level.name.equals(name)) {
                return level;
            }
        }
        int value;
        try {
            value = Integer.parseInt(name);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad level \"" + name + "\"");
        }
        for (Level level : known) {
            if (level.value == value) {
                return level;
            }
        }
        return new Level(name, value);
    }

    public boolean equals(Object other) {
        return other instanceof Level level && level.value == value;
    }

    public int hashCode() {
        return value;
    }

}
