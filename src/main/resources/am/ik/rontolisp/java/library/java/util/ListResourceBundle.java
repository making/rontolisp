// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A bundle of the pairs getContents answers, read at its first lookup into a HashMap of
// as many as there are, in their order (the order of its keys is a JDK 25's). A key or an
// object that is null is refused there.
public abstract class ListResourceBundle extends ResourceBundle {

    private Map<String, Object> lookup;

    public ListResourceBundle() {
    }

    protected abstract Object[][] getContents();

    private synchronized Map<String, Object> lookup() {
        if (lookup == null) {
            Object[][] contents = getContents();
            HashMap<String, Object> read = new HashMap<>(contents.length);
            for (Object[] pair : contents) {
                String key = (String) pair[0];
                Object value = pair[1];
                if (key == null || value == null) {
                    throw new NullPointerException();
                }
                read.put(key, value);
            }
            lookup = read;
        }
        return lookup;
    }

    public final Object handleGetObject(String key) {
        if (key == null) {
            throw new NullPointerException();
        }
        return lookup().get(key);
    }

    public Enumeration<String> getKeys() {
        return ResourceBundle.keys(lookup().keySet(), parent);
    }

    protected Set<String> handleKeySet() {
        return lookup().keySet();
    }
}
