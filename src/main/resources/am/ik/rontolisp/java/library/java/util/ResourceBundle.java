// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.IOException;
import java.io.InputStream;
import rontolisp.internal.ResourceTable;

// A table of objects by key, with a parent it asks for a key it lacks. getBundle finds
// one of a base name and a locale as the API documentation states, through a Control:
// for each candidate locale it answers, from the least to the most specific, a bundle of
// each format it names in turn (a class of the bundle's name, else a .properties file of
// the loader's resources), each the parent of the next one found; where only the base
// bundle or none is found for a locale that is not the root, the same for its fallback
// locale. The order of the Control's calls is a JDK 25's (.kb/java-frontend.md,
// "Resources"). The files are those the program carries
// (rontolisp.internal.ResourceTable): the lowering makes a call of getBundle name the
// loader of the class calling it, and carries the files of a constant base name, or
// every file for a name computed where the program runs or a Control the program gives.
// A bundle is made once a loader, base name and locale, unless the Control says not to
// keep it.
public abstract class ResourceBundle {

    protected ResourceBundle parent;

    private String name;

    private Locale locale;

    public ResourceBundle() {
    }

    // the bundles made, by the loader's reach (2 the program's, 1 the system's, 0 the
    // platform's), base name and locale; NONE where no class or file is one
    private static final HashMap<String, ResourceBundle> MADE = new HashMap<>();

    // a bundle no file holds
    private static final ResourceBundle NONE = new PropertyResourceBundle(new HashMap<>());

    public static final native ResourceBundle getBundle(String baseName);

    public static final native ResourceBundle getBundle(String baseName, Locale locale);

    public static native ResourceBundle getBundle(String baseName, Locale targetLocale, ClassLoader loader);

    public static final native ResourceBundle getBundle(String baseName, Control control);

    public static final native ResourceBundle getBundle(String baseName, Locale targetLocale, Control control);

    public static native ResourceBundle getBundle(String baseName, Locale targetLocale, ClassLoader loader,
            Control control);

    public static final native ResourceBundle getBundle(String baseName, Module module);

    public static final native ResourceBundle getBundle(String baseName, Locale targetLocale, Module module);

    // what the lowering makes of getBundle(String, Module) and getBundle(String, Locale,
    // Module): the unnamed module of a loader finds that loader's bundles; java.base
    // holds none of a program's
    static ResourceBundle inModule(String baseName, Locale targetLocale, Module module) {
        if (baseName == null || targetLocale == null || module == null) {
            throw new NullPointerException();
        }
        if (module.isNamed()) {
            throw missing(baseName, targetLocale, null);
        }
        return found(baseName, targetLocale, reach(module.getClassLoader()), module.getClassLoader(), null);
    }

    // what the lowering makes of getBundle(String) and getBundle(String, Locale): the
    // loader of the class calling it, by its reach (ClassLoader's); and of the forms
    // with a Control
    static ResourceBundle called(String baseName, Locale locale, int reach) {
        return found(baseName, locale, reach, null, null);
    }

    static ResourceBundle called(String baseName, Locale locale, int reach, Control control) {
        if (control == null) {
            throw new NullPointerException();
        }
        return found(baseName, locale, reach, loader0(reach), control);
    }

    // what the lowering makes of getBundle(String, Locale, ClassLoader), with a Control
    // or not
    static ResourceBundle given(String baseName, Locale locale, ClassLoader loader) {
        if (loader == null) {
            throw new NullPointerException();
        }
        return found(baseName, locale, reach(loader), loader, null);
    }

    static ResourceBundle given(String baseName, Locale locale, ClassLoader loader, Control control) {
        if (loader == null || control == null) {
            throw new NullPointerException();
        }
        return found(baseName, locale, reach(loader), loader, control);
    }

    private static native int reach(ClassLoader loader);

    // the loader of a reach
    private static native ClassLoader loader0(int reach);

    // the default Control's answers where control is null: a program that gives none
    // holds no Control, and its loader need not be made
    private static ResourceBundle found(String baseName, Locale target, int reach, ClassLoader loader,
            Control control) {
        if (baseName == null || target == null) {
            throw new NullPointerException();
        }
        synchronized (MADE) {
            List<String> formats = control == null ? null : control.getFormats(baseName);
            Throwable[] cause = new Throwable[1];
            ResourceBundle found = null;
            ResourceBundle base = null;
            for (Locale locale = target; locale != null; locale = control == null ? fallback(locale)
                    : control.getFallbackLocale(baseName, locale)) {
                List<Locale> candidates = control == null ? candidates(locale)
                        : control.getCandidateLocales(baseName, locale);
                found = chain(baseName, candidates, 0, formats, reach, loader, control, base, cause);
                if (found != null) {
                    boolean root = found.locale.toString().isEmpty();
                    if (!root || found.locale == locale
                            || (candidates.size() == 1 && found.locale == candidates.get(0))) {
                        break;
                    }
                    if (base == null) {
                        base = found;
                    }
                }
            }
            if (found == null) {
                if (base == null) {
                    throw missing(baseName, target, cause[0]);
                }
                found = base;
            }
            return found;
        }
    }

    private static MissingResourceException missing(String baseName, Locale locale, Throwable cause) {
        return new MissingResourceException("Can't find bundle for base name " + baseName + ", locale " + locale,
                baseName + "_" + locale, "", cause);
    }

    // the bundle of a candidate and its parents, the less specific ones made first; the
    // base bundle found for an earlier locale stands for the root
    private static ResourceBundle chain(String baseName, List<Locale> candidates, int index, List<String> formats,
            int reach, ClassLoader loader, Control control, ResourceBundle base, Throwable[] cause) {
        Locale locale = candidates.get(index);
        ResourceBundle parent = null;
        if (index == candidates.size() - 1) {
            if (base != null && locale.toString().isEmpty()) {
                return base;
            }
        }
        else {
            parent = chain(baseName, candidates, index + 1, formats, reach, loader, control, base, cause);
        }
        ResourceBundle bundle = made(baseName, locale, formats, reach, loader, control, cause);
        if (bundle == null) {
            return parent;
        }
        if (parent != null && bundle.parent == null) {
            bundle.setParent(parent);
        }
        return bundle;
    }

    private static ResourceBundle made(String baseName, Locale locale, List<String> formats, int reach,
            ClassLoader loader, Control control, Throwable[] cause) {
        String key = reach + baseName + "\n" + locale;
        ResourceBundle bundle = MADE.get(key);
        if (bundle != null) {
            return bundle == NONE ? null : bundle;
        }
        if (control == null) {
            String bundleName = bundleName(baseName, locale);
            bundle = reach == 0 ? null : classBundle(bundleName, reach == 2);
            if (bundle == null) {
                try {
                    bundle = properties(bundleName, reach);
                }
                catch (IOException ex) {
                    cause[0] = ex;
                }
            }
        }
        else {
            bundle = control.made(baseName, locale, formats, loader, cause);
        }
        if (bundle != null) {
            bundle.name = baseName;
            bundle.locale = locale;
        }
        if (control == null || control.getTimeToLive(baseName, locale) != Control.TTL_DONT_CACHE) {
            MADE.put(key, bundle == null ? NONE : bundle);
        }
        return bundle;
    }

    // the language and country, the language, then the root: of the five locales every
    // program may have, a language alone is ENGLISH
    private static List<Locale> candidates(Locale locale) {
        if (rontolisp.internal.Locales.foreign()) {
            return anyCandidates(locale);
        }
        List<Locale> candidates = new ArrayList<>();
        if (!locale.getCountry().isEmpty()) {
            candidates.add(locale);
        }
        if (!locale.getLanguage().isEmpty()) {
            candidates.add(locale.getCountry().isEmpty() ? locale : Locale.ENGLISH);
        }
        candidates.add(Locale.ROOT);
        return candidates;
    }

    // The candidates of any locale: with its script, then without it, each with its
    // variant cut at each underscore from the end, then with its country, then its
    // language, then the root. Chinese takes the script of its country, and the country
    // of its script where it has none; Norwegian Bokmal and Norwegian are each other's,
    // Nynorsk is Norwegian of Norway and the variant NY.
    private static List<Locale> anyCandidates(Locale locale) {
        String language = locale.getLanguage();
        String script = locale.getScript();
        String country = locale.getCountry();
        String variant = locale.getVariant();
        if (language.equals("no") && country.equals("NO") && variant.equals("NY")) {
            language = "nn";
            variant = "";
        }
        String plainCountry = country;
        if (language.equals("zh")) {
            if (script.isEmpty() && !country.isEmpty()) {
                script = country.equals("CN") || country.equals("SG") ? "Hans"
                        : country.equals("TW") || country.equals("HK") || country.equals("MO") ? "Hant" : "";
            }
            else if (country.isEmpty()) {
                plainCountry = script.equals("Hans") ? "CN" : script.equals("Hant") ? "TW" : "";
            }
        }
        List<Locale> candidates = new ArrayList<>();
        if (!script.isEmpty()) {
            listed(candidates, language, script, country, variant);
        }
        listed(candidates, language, "", plainCountry, variant);
        if (language.equals("nb") || language.equals("no")) {
            String other = language.equals("nb") ? "no" : "nb";
            for (int i = candidates.size() - 1; i >= 0; i--) {
                Locale each = candidates.get(i);
                candidates.add(i + 1,
                        Locale.made(other, each.getScript(), each.getCountry(), each.getVariant()));
            }
        }
        else if (language.equals("nn")) {
            candidates.add(Locale.made("no", "", "NO", "NY"));
            candidates.add(Locale.made("no", "", "NO", ""));
            candidates.add(Locale.made("no", "", "", ""));
        }
        candidates.add(Locale.ROOT);
        return candidates;
    }

    // the language, script and country with each cut of the variant, then without it
    private static void listed(List<Locale> candidates, String language, String script, String country,
            String variant) {
        for (String cut = variant; !variant.isEmpty();) {
            candidates.add(Locale.made(language, script, country, cut));
            int end = cut.lastIndexOf('_');
            if (end < 0) {
                break;
            }
            cut = cut.substring(0, end);
        }
        if (!country.isEmpty()) {
            candidates.add(Locale.made(language, script, country, ""));
        }
        if (!script.isEmpty() || !language.isEmpty()) {
            candidates.add(Locale.made(language, script, "", ""));
        }
    }

    // the default locale, unless it is the one asked
    private static Locale fallback(Locale locale) {
        Locale fallback = Locale.getDefault();
        if (rontolisp.internal.Locales.foreign()) {
            return fallback.equals(locale) ? null : fallback;
        }
        return fallback == locale ? null : fallback;
    }

    private static String bundleName(String baseName, Locale locale) {
        if (rontolisp.internal.Locales.foreign()) {
            String language = locale.getLanguage();
            String script = locale.getScript();
            String country = locale.getCountry();
            String variant = locale.getVariant();
            if (language.isEmpty() && country.isEmpty() && variant.isEmpty()) {
                return baseName;
            }
            String name = baseName + "_" + language + (script.isEmpty() ? "" : "_" + script);
            return variant.isEmpty() ? (country.isEmpty() ? name : name + "_" + country)
                    : name + "_" + country + "_" + variant;
        }
        String suffix = locale.toString();
        return suffix.isEmpty() ? baseName : baseName + "_" + suffix;
    }

    // a .properties file of a loader's resources, or null
    private static ResourceBundle properties(String bundleName, int reach) throws IOException {
        InputStream in = ResourceTable.find(reach, bundleName.replace('.', '/') + ".properties");
        if (in == null) {
            return null;
        }
        try (in) {
            return new PropertyResourceBundle(in);
        }
    }

    // a bundle of the class of the name, which the lowering finds where the program
    // names a base name, or null
    private static native ResourceBundle classBundle(String name, boolean tree);

    // How getBundle finds a bundle: the formats, the candidate locales, the fallback
    // locale, how a bundle is made of a format, how long it is kept. A time to live is
    // kept or not: a bundle kept is never made again (needsReload is not asked).
    public static class Control {

        public static final List<String> FORMAT_DEFAULT = List.of("java.class", "java.properties");

        public static final List<String> FORMAT_CLASS = List.of("java.class");

        public static final List<String> FORMAT_PROPERTIES = List.of("java.properties");

        public static final long TTL_DONT_CACHE = -1;

        public static final long TTL_NO_EXPIRATION_CONTROL = -2;

        private static final Control INSTANCE = new Control(FORMAT_DEFAULT);

        private static final Control CLASS = new Control(FORMAT_CLASS);

        private static final Control PROPERTIES = new Control(FORMAT_PROPERTIES);

        private final List<String> formats;

        protected Control() {
            this(FORMAT_DEFAULT);
        }

        Control(List<String> formats) {
            this.formats = formats;
        }

        public static final Control getControl(List<String> formats) {
            if (formats.equals(FORMAT_PROPERTIES)) {
                return PROPERTIES;
            }
            if (formats.equals(FORMAT_CLASS)) {
                return CLASS;
            }
            if (formats.equals(FORMAT_DEFAULT)) {
                return INSTANCE;
            }
            throw new IllegalArgumentException();
        }

        public static final Control getNoFallbackControl(List<String> formats) {
            if (formats.equals(FORMAT_DEFAULT)) {
                return NoFallbackControl.DEFAULT;
            }
            if (formats.equals(FORMAT_PROPERTIES)) {
                return NoFallbackControl.PROPERTIES;
            }
            if (formats.equals(FORMAT_CLASS)) {
                return NoFallbackControl.CLASS;
            }
            throw new IllegalArgumentException();
        }

        public List<String> getFormats(String baseName) {
            if (baseName == null) {
                throw new NullPointerException();
            }
            return formats;
        }

        public List<Locale> getCandidateLocales(String baseName, Locale locale) {
            if (baseName == null || locale == null) {
                throw new NullPointerException();
            }
            return candidates(locale);
        }

        public Locale getFallbackLocale(String baseName, Locale locale) {
            if (baseName == null || locale == null) {
                throw new NullPointerException();
            }
            return fallback(locale);
        }

        // the bundle of the first format newBundle makes one of; what it throws is the
        // cause of the refusal if none is found
        ResourceBundle made(String baseName, Locale locale, List<String> formats, ClassLoader loader,
                Throwable[] cause) {
            for (int i = 0; i < formats.size(); i++) {
                try {
                    ResourceBundle bundle = newBundle(baseName, locale, formats.get(i), loader, false);
                    if (bundle != null) {
                        return bundle;
                    }
                }
                catch (Exception ex) {
                    cause[0] = ex;
                }
            }
            return null;
        }

        // a public class of the bundle's name under ResourceBundle the loader has, or a
        // .properties file of its resources
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader,
                boolean reload) throws IllegalAccessException, InstantiationException, IOException {
            String bundleName = toBundleName(baseName, locale);
            int reach = reach(loader);
            if (format.equals("java.class")) {
                return reach == 0 ? null : classBundle(bundleName, reach == 2);
            }
            if (format.equals("java.properties")) {
                return properties(bundleName, reach);
            }
            throw new IllegalArgumentException("unknown format: " + format);
        }

        public long getTimeToLive(String baseName, Locale locale) {
            if (baseName == null || locale == null) {
                throw new NullPointerException();
            }
            return TTL_NO_EXPIRATION_CONTROL;
        }

        public boolean needsReload(String baseName, Locale locale, String format, ClassLoader loader,
                ResourceBundle bundle, long loadTime) {
            if (bundle == null) {
                throw new NullPointerException();
            }
            return false;
        }

        public String toBundleName(String baseName, Locale locale) {
            if (baseName == null || locale == null) {
                throw new NullPointerException();
            }
            return bundleName(baseName, locale);
        }

        public final String toResourceName(String bundleName, String suffix) {
            if (bundleName == null || suffix == null) {
                throw new NullPointerException();
            }
            return bundleName.replace('.', '/') + "." + suffix;
        }
    }

    private static final class NoFallbackControl extends Control {

        static final Control DEFAULT = new NoFallbackControl(Control.FORMAT_DEFAULT);

        static final Control CLASS = new NoFallbackControl(Control.FORMAT_CLASS);

        static final Control PROPERTIES = new NoFallbackControl(Control.FORMAT_PROPERTIES);

        NoFallbackControl(List<String> formats) {
            super(formats);
        }

        public Locale getFallbackLocale(String baseName, Locale locale) {
            if (baseName == null || locale == null) {
                throw new NullPointerException();
            }
            return null;
        }
    }

    public static final void clearCache() {
        synchronized (MADE) {
            MADE.clear();
        }
    }

    public static final void clearCache(ClassLoader loader) {
        if (loader == null) {
            throw new NullPointerException();
        }
        int reach = reach(loader);
        synchronized (MADE) {
            MADE.keySet().removeIf(key -> key.charAt(0) == '0' + reach);
        }
    }

    public String getBaseBundleName() {
        return name;
    }

    public Locale getLocale() {
        return locale;
    }

    protected void setParent(ResourceBundle parent) {
        this.parent = parent;
    }

    public final String getString(String key) {
        return (String) getObject(key);
    }

    public final String[] getStringArray(String key) {
        return (String[]) getObject(key);
    }

    // this bundle's object, else its parent's; the last one asked refuses with its own
    // class's name
    public final Object getObject(String key) {
        Object found = handleGetObject(key);
        if (found != null) {
            return found;
        }
        if (parent != null) {
            return parent.getObject(key);
        }
        if (key == null) {
            throw new NullPointerException();
        }
        throw new MissingResourceException(
                "Can't find resource for bundle " + getClass().getName() + ", key " + key, getClass().getName(), key);
    }

    public boolean containsKey(String key) {
        if (key == null) {
            throw new NullPointerException();
        }
        for (ResourceBundle bundle = this; bundle != null; bundle = bundle.parent) {
            if (bundle.handleKeySet().contains(key)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> keySet() {
        Set<String> keys = new HashSet<>();
        for (ResourceBundle bundle = this; bundle != null; bundle = bundle.parent) {
            keys.addAll(bundle.handleKeySet());
        }
        return keys;
    }

    // the keys getKeys answers whose objects this bundle holds itself
    protected Set<String> handleKeySet() {
        Set<String> keys = new HashSet<>();
        for (Enumeration<String> all = getKeys(); all.hasMoreElements();) {
            String key = all.nextElement();
            if (handleGetObject(key) != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    public abstract Enumeration<String> getKeys();

    protected abstract Object handleGetObject(String key);

    // a bundle's own keys, then those of its parent it does not have
    static Enumeration<String> keys(Set<String> own, ResourceBundle parent) {
        Iterator<String> mine = own.iterator();
        Enumeration<String> above = parent == null ? null : parent.getKeys();
        return new Enumeration<String>() {

            private String next;

            public boolean hasMoreElements() {
                if (next == null) {
                    if (mine.hasNext()) {
                        next = mine.next();
                    }
                    else {
                        while (above != null && above.hasMoreElements()) {
                            String key = above.nextElement();
                            if (!own.contains(key)) {
                                next = key;
                                break;
                            }
                        }
                    }
                }
                return next != null;
            }

            public String nextElement() {
                if (!hasMoreElements()) {
                    throw new NoSuchElementException();
                }
                String key = next;
                next = null;
                return key;
            }
        };
    }
}
