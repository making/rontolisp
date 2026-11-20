// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.IOException;
import java.io.InputStream;
import rontolisp.internal.ResourceTable;

// A table of objects by key, with a parent it asks for a key it lacks. getBundle finds
// one of a base name and a locale as the API documentation states: for each candidate
// locale from the most to the least specific, a class of the bundle's name or else a
// .properties file of the loader's resources; the first found is the result and the
// ones after it its parents; where only the base bundle or none is found for a locale
// that is not the root, the default locale's candidates instead, unless it is the
// locale asked for. The files are those the program carries
// (rontolisp.internal.ResourceTable): the lowering makes a call of getBundle name the
// loader of the class calling it, and carries the files of a constant base name, or
// every file for a name computed where the program runs (.kb/java-frontend.md,
// "Resources"). A bundle is made once a loader, base name and locale.
public abstract class ResourceBundle {

    protected ResourceBundle parent;

    private String name;

    private Locale locale;

    public ResourceBundle() {
    }

    // the bundles made, by loader (T the program's, S the system's), base name and
    // locale; NONE where no class or file is one
    private static final HashMap<String, ResourceBundle> MADE = new HashMap<>();

    // a bundle no file holds
    private static final ResourceBundle NONE = new PropertyResourceBundle(new HashMap<>());

    public static final native ResourceBundle getBundle(String baseName);

    public static final native ResourceBundle getBundle(String baseName, Locale locale);

    public static native ResourceBundle getBundle(String baseName, Locale targetLocale, ClassLoader loader);

    // what the lowering makes of getBundle(String) and getBundle(String, Locale): the
    // loader of the class calling it, the program's when tree
    static ResourceBundle called(String baseName, Locale locale, boolean tree) {
        if (baseName == null || locale == null) {
            throw new NullPointerException();
        }
        synchronized (MADE) {
            List<Locale> candidates = candidates(locale);
            ResourceBundle found = chain(baseName, candidates, tree);
            if (found != null && (!found.locale.getLanguage().isEmpty() || candidates.size() == 1)) {
                return found;
            }
            Locale fallback = Locale.getDefault();
            if (!fallback.toString().equals(locale.toString())) {
                ResourceBundle other = chain(baseName, candidates(fallback), tree);
                if (other != null && (found == null || !other.locale.getLanguage().isEmpty())) {
                    return other;
                }
            }
            if (found == null) {
                throw new MissingResourceException(
                        "Can't find bundle for base name " + baseName + ", locale " + locale, baseName + "_" + locale,
                        "");
            }
            return found;
        }
    }

    // what the lowering makes of getBundle(String, Locale, ClassLoader)
    static ResourceBundle given(String baseName, Locale locale, ClassLoader loader) {
        if (loader == null) {
            throw new NullPointerException();
        }
        return called(baseName, locale, searchesTree(loader));
    }

    private static native boolean searchesTree(ClassLoader loader);

    // the locale's language and country, its language, then the root: of the locales
    // Locale has, a language alone is ENGLISH
    private static List<Locale> candidates(Locale locale) {
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

    // the first candidate's bundle there is, its parent the next one's, and so on
    private static ResourceBundle chain(String baseName, List<Locale> candidates, boolean tree) {
        ResourceBundle first = null;
        ResourceBundle last = null;
        for (Locale candidate : candidates) {
            ResourceBundle bundle = made(baseName, candidate, tree);
            if (bundle == null) {
                continue;
            }
            if (first == null) {
                first = bundle;
            }
            else {
                last.setParent(bundle);
            }
            last = bundle;
        }
        return first;
    }

    private static ResourceBundle made(String baseName, Locale locale, boolean tree) {
        String bundleName = locale.toString().isEmpty() ? baseName : baseName + "_" + locale;
        String key = (tree ? "T" : "S") + baseName + "\n" + locale;
        ResourceBundle bundle = MADE.get(key);
        if (bundle == null) {
            bundle = classBundle(bundleName, tree);
            InputStream in = bundle != null ? null
                    : ResourceTable.find(tree, bundleName.replace('.', '/') + ".properties");
            if (in != null) {
                try (in) {
                    bundle = new PropertyResourceBundle(in);
                }
                catch (IOException ex) {
                    throw new MissingResourceException("Can't find bundle for base name " + baseName + ", locale "
                            + locale, bundleName, "");
                }
            }
            if (bundle != null) {
                bundle.name = baseName;
                bundle.locale = locale;
            }
            MADE.put(key, bundle == null ? NONE : bundle);
        }
        return bundle == NONE ? null : bundle;
    }

    // a bundle of the class of the name, which the lowering finds where the program
    // names a base name, or null
    private static native ResourceBundle classBundle(String name, boolean tree);

    public static final void clearCache() {
        synchronized (MADE) {
            MADE.clear();
        }
    }

    public static final void clearCache(ClassLoader loader) {
        if (loader == null) {
            throw new NullPointerException();
        }
        boolean tree = searchesTree(loader);
        synchronized (MADE) {
            MADE.keySet().removeIf(key -> key.charAt(0) == (tree ? 'T' : 'S'));
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
