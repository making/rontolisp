// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.net.http;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

// The fields of a request or a reply: kept in the order of their names whatever the
// case, and a name is found whatever its case.
public final class HttpHeaders {

    private final Fields fields;

    private HttpHeaders(Fields fields) {
        this.fields = fields;
    }

    public static HttpHeaders of(Map<String, List<String>> headerMap, BiPredicate<String, String> filter) {
        if (headerMap == null || filter == null) {
            throw new NullPointerException();
        }
        ArrayList<Map.Entry<String, List<String>>> entries = new ArrayList<>();
        for (Map.Entry<String, List<String>> given : headerMap.entrySet()) {
            String name = given.getKey().trim();
            ArrayList<String> kept = new ArrayList<>();
            for (String value : given.getValue()) {
                String trimmed = value.trim();
                if (filter.test(name, trimmed)) {
                    kept.add(trimmed);
                }
            }
            if (kept.isEmpty()) {
                continue;
            }
            int at = 0;
            while (at < entries.size() && entries.get(at).getKey().compareToIgnoreCase(name) < 0) {
                at++;
            }
            if (at < entries.size() && entries.get(at).getKey().equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("duplicate key: " + name);
            }
            entries.add(at, new AbstractMap.SimpleImmutableEntry<String, List<String>>(name,
                    Collections.unmodifiableList(kept)));
        }
        return new HttpHeaders(new Fields(entries));
    }

    public Optional<String> firstValue(String name) {
        List<String> values = fields.get(name);
        return values == null ? Optional.empty() : Optional.of(values.get(0));
    }

    public List<String> allValues(String name) {
        if (name == null) {
            throw new NullPointerException();
        }
        List<String> values = fields.get(name);
        return values == null ? List.of() : values;
    }

    public Map<String, List<String>> map() {
        return fields;
    }

    public boolean equals(Object o) {
        return o instanceof HttpHeaders other && fields.equals(other.fields);
    }

    public int hashCode() {
        int hash = 0;
        for (Map.Entry<String, List<String>> field : fields.entries) {
            hash += field.getKey().toLowerCase(Locale.ROOT).hashCode() ^ field.getValue().hashCode();
        }
        return hash;
    }

    public String toString() {
        return "java.net.http.HttpHeaders@" + Integer.toHexString(hashCode()) + " { " + fields + " }";
    }

    private static final class Fields extends AbstractMap<String, List<String>> {

        final ArrayList<Map.Entry<String, List<String>>> entries;

        Fields(ArrayList<Map.Entry<String, List<String>>> entries) {
            this.entries = entries;
        }

        public Set<Map.Entry<String, List<String>>> entrySet() {
            return new Entries(entries);
        }

        public List<String> get(Object key) {
            if (key instanceof String name) {
                for (Map.Entry<String, List<String>> entry : entries) {
                    if (entry.getKey().equalsIgnoreCase(name)) {
                        return entry.getValue();
                    }
                }
            }
            return null;
        }

        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        public List<String> put(String key, List<String> value) {
            throw new UnsupportedOperationException();
        }

        public List<String> remove(Object key) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Entries extends AbstractSet<Map.Entry<String, List<String>>> {

        private final ArrayList<Map.Entry<String, List<String>>> entries;

        Entries(ArrayList<Map.Entry<String, List<String>>> entries) {
            this.entries = entries;
        }

        public int size() {
            return entries.size();
        }

        public Iterator<Map.Entry<String, List<String>>> iterator() {
            return Collections.unmodifiableList(entries).iterator();
        }
    }
}
