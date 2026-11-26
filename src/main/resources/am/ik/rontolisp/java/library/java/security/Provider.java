// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import rontolisp.internal.InstalledProvider;

// A provider is a table of texts: an entry "<type>.<algorithm>" names the class of a
// service, "<type>.<algorithm> <attribute>" one of its attributes and
// "Alg.Alias.<type>.<alias>" another name of its algorithm. Its services are read off
// those entries where they are asked for, the type as written and the algorithm or an
// alias whatever its case. The subset's own providers (rontolisp.internal's
// InstalledProvider) hold the subset's services so; a provider of the program's own
// holds what it puts.
//
// The entries are put in the table when a member first reads or writes it, so that a
// program asking a provider its name alone holds no table of entries: each member of the
// table enters them first.
public abstract class Provider extends Properties {

    private final String name;

    private final String versionStr;

    private final String info;

    private boolean entered;

    protected Provider(String name, String versionStr, String info) {
        this.name = name;
        this.versionStr = versionStr;
        this.info = info;
    }

    @Deprecated(since = "9")
    protected Provider(String name, double version, String info) {
        this(name, Double.toString(version), info);
    }

    public String getName() {
        return name;
    }

    public String getVersionStr() {
        return versionStr;
    }

    // The number the version starts with, past any blanks: digits with at most one point
    // among them, 0 for none.
    @Deprecated(since = "9")
    public double getVersion() {
        String text = versionStr.strip();
        int end = 0;
        boolean point = false;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == '.' && !point) {
                point = true;
            } else if (c < '0' || c > '9') {
                break;
            }
            end++;
        }
        String number = text.substring(0, end);
        return number.isEmpty() || number.equals(".") ? 0.0 : Double.parseDouble(number);
    }

    public String getInfo() {
        return info;
    }

    public String toString() {
        return name + " version " + versionStr;
    }

    public Provider configure(String configArg) {
        throw new UnsupportedOperationException("configure is not supported");
    }

    public boolean isConfigured() {
        return true;
    }

    // The service of a type and an algorithm or an alias of it, null for none.
    public Service getService(String type, String algorithm) {
        if (type == null || algorithm == null) {
            throw new NullPointerException();
        }
        String standard = null;
        String aliased = null;
        String prefix = type + ".";
        String aliasPrefix = "Alg.Alias." + prefix;
        for (Object key : keySet()) {
            if (key instanceof String entry) {
                if (entry.startsWith(prefix) && entry.indexOf(' ') < 0
                        && entry.substring(prefix.length()).equalsIgnoreCase(algorithm)) {
                    standard = entry.substring(prefix.length());
                } else if (entry.startsWith(aliasPrefix) && entry.substring(aliasPrefix.length()).equalsIgnoreCase(algorithm)
                        && get(entry) instanceof String target) {
                    aliased = target;
                }
            }
        }
        if (standard == null && aliased != null) {
            for (Object key : keySet()) {
                if (key instanceof String entry && entry.startsWith(prefix) && entry.indexOf(' ') < 0
                        && entry.substring(prefix.length()).equalsIgnoreCase(aliased)) {
                    standard = entry.substring(prefix.length());
                }
            }
        }
        return standard == null ? null : service(type, standard);
    }

    // Every service the entries name.
    public Set<Service> getServices() {
        Set<Service> services = new LinkedHashSet<>();
        for (Object key : keySet()) {
            if (key instanceof String entry && entry.indexOf(' ') < 0 && !entry.startsWith("Alg.Alias.")
                    && !entry.startsWith("Provider.")) {
                int dot = entry.indexOf('.');
                if (dot > 0 && dot < entry.length() - 1) {
                    services.add(service(entry.substring(0, dot), entry.substring(dot + 1)));
                }
            }
        }
        return Collections.unmodifiableSet(services);
    }

    // A service's entries: its class, its aliases and its attributes.
    protected void putService(Service s) {
        if (s == null) {
            throw new NullPointerException();
        }
        String entry = s.getType() + "." + s.getAlgorithm();
        put(entry, s.getClassName());
        for (String alias : s.aliases) {
            put("Alg.Alias." + s.getType() + "." + alias, s.getAlgorithm());
        }
        for (Map.Entry<String, String> attribute : s.attributes.entrySet()) {
            put(entry + " " + attribute.getKey(), attribute.getValue());
        }
    }

    protected void removeService(Service s) {
        if (s == null) {
            throw new NullPointerException();
        }
        String entry = s.getType() + "." + s.getAlgorithm();
        remove(entry);
        for (String alias : s.aliases) {
            remove("Alg.Alias." + s.getType() + "." + alias);
        }
        for (String attribute : s.attributes.keySet()) {
            remove(entry + " " + attribute);
        }
    }

    // The service of the entry of a type and an algorithm as the entries spell it.
    private Service service(String type, String algorithm) {
        String entry = type + "." + algorithm;
        String aliasPrefix = "Alg.Alias." + type + ".";
        List<String> aliases = new ArrayList<>();
        Map<String, String> attributes = new HashMap<>();
        for (Object key : keySet()) {
            if (key instanceof String name) {
                Object value = get(name);
                if (name.startsWith(aliasPrefix) && algorithm.equals(value)) {
                    aliases.add(name.substring(aliasPrefix.length()));
                } else if (name.startsWith(entry + " ") && value instanceof String text) {
                    attributes.put(name.substring(entry.length() + 1), text);
                }
            }
        }
        return new Service(this, type, algorithm, String.valueOf(get(entry)), aliases, attributes);
    }

    // The entries a provider has before it has any put: its name, version, description
    // and class, and the services of the subset's own.
    private void entered() {
        if (!entered) {
            entered = true;
            super.put("Provider.id name", String.valueOf(name));
            super.put("Provider.id version", String.valueOf(versionStr));
            super.put("Provider.id info", String.valueOf(info));
            super.put("Provider.id className", getClass().getName());
            if (this instanceof InstalledProvider installed) {
                installed.held();
            }
        }
    }

    // ---- the members of the table, each after the entries are in

    public String getProperty(String key) {
        entered();
        return super.getProperty(key);
    }

    public Enumeration<?> propertyNames() {
        entered();
        return super.propertyNames();
    }

    public Set<String> stringPropertyNames() {
        entered();
        return super.stringPropertyNames();
    }

    public void list(PrintStream out) {
        entered();
        super.list(out);
    }

    public void list(PrintWriter out) {
        entered();
        super.list(out);
    }

    public void load(Reader reader) throws IOException {
        entered();
        super.load(reader);
    }

    public void load(InputStream inStream) throws IOException {
        entered();
        super.load(inStream);
    }

    public void store(Writer writer, String comments) throws IOException {
        entered();
        super.store(writer, comments);
    }

    public void store(OutputStream out, String comments) throws IOException {
        entered();
        super.store(out, comments);
    }

    public void storeToXML(OutputStream os, String comment, Charset charset) throws IOException {
        entered();
        super.storeToXML(os, comment, charset);
    }

    public Object clone() {
        entered();
        return super.clone();
    }

    public int size() {
        entered();
        return super.size();
    }

    public boolean isEmpty() {
        entered();
        return super.isEmpty();
    }

    public Enumeration<Object> keys() {
        entered();
        return super.keys();
    }

    public Enumeration<Object> elements() {
        entered();
        return super.elements();
    }

    public boolean contains(Object value) {
        entered();
        return super.contains(value);
    }

    public boolean containsValue(Object value) {
        entered();
        return super.containsValue(value);
    }

    public boolean containsKey(Object key) {
        entered();
        return super.containsKey(key);
    }

    public Object get(Object key) {
        entered();
        return super.get(key);
    }

    public Object put(Object key, Object value) {
        entered();
        return super.put(key, value);
    }

    public Object remove(Object key) {
        entered();
        return super.remove(key);
    }

    public void putAll(Map<?, ?> t) {
        entered();
        super.putAll(t);
    }

    public void clear() {
        entered();
        super.clear();
    }

    public Set<Object> keySet() {
        entered();
        return super.keySet();
    }

    public Collection<Object> values() {
        entered();
        return super.values();
    }

    public Set<Map.Entry<Object, Object>> entrySet() {
        entered();
        return super.entrySet();
    }

    public boolean equals(Object o) {
        entered();
        return super.equals(o);
    }

    public int hashCode() {
        entered();
        return super.hashCode();
    }

    public Object getOrDefault(Object key, Object defaultValue) {
        entered();
        return super.getOrDefault(key, defaultValue);
    }

    public void forEach(BiConsumer<? super Object, ? super Object> action) {
        entered();
        super.forEach(action);
    }

    public void replaceAll(BiFunction<? super Object, ? super Object, ?> function) {
        entered();
        super.replaceAll(function);
    }

    public Object putIfAbsent(Object key, Object value) {
        entered();
        return super.putIfAbsent(key, value);
    }

    public boolean remove(Object key, Object value) {
        entered();
        return super.remove(key, value);
    }

    public boolean replace(Object key, Object oldValue, Object newValue) {
        entered();
        return super.replace(key, oldValue, newValue);
    }

    public Object replace(Object key, Object value) {
        entered();
        return super.replace(key, value);
    }

    public Object computeIfAbsent(Object key, Function<? super Object, ?> mappingFunction) {
        entered();
        return super.computeIfAbsent(key, mappingFunction);
    }

    public Object computeIfPresent(Object key, BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        entered();
        return super.computeIfPresent(key, remappingFunction);
    }

    public Object compute(Object key, BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        entered();
        return super.compute(key, remappingFunction);
    }

    public Object merge(Object key, Object value, BiFunction<? super Object, ? super Object, ?> remappingFunction) {
        entered();
        return super.merge(key, value, remappingFunction);
    }

    public static class Service {

        private final Provider provider;

        private final String type;

        private final String algorithm;

        private final String className;

        private final List<String> aliases;

        private final Map<String, String> attributes;

        public Service(Provider provider, String type, String algorithm, String className, List<String> aliases,
                Map<String, String> attributes) {
            if (provider == null || type == null || algorithm == null || className == null) {
                throw new NullPointerException();
            }
            this.provider = provider;
            this.type = type;
            this.algorithm = algorithm;
            this.className = className;
            this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
            this.attributes = attributes == null ? Map.of() : new HashMap<>(attributes);
        }

        public final String getType() {
            return type;
        }

        public final String getAlgorithm() {
            return algorithm;
        }

        public final Provider getProvider() {
            return provider;
        }

        public final String getClassName() {
            return className;
        }

        public final String getAttribute(String name) {
            if (name == null) {
                throw new NullPointerException();
            }
            return attributes.get(name);
        }

        public String toString() {
            return provider.getName() + ": " + type + "." + algorithm + " -> " + className
                    + (aliases.isEmpty() ? "" : "\n  aliases: " + aliases)
                    + (attributes.isEmpty() ? "" : "\n  attributes: " + attributes) + "\n";
        }
    }
}
