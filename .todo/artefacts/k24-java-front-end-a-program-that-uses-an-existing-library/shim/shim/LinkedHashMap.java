package shim;

import java.util.HashMap;
import java.util.Map;

// Stands where java.util.LinkedHashMap is named, for the measurement only: a HashMap,
// so the order is a HashMap's.
public class LinkedHashMap<K, V> extends HashMap<K, V> {

    public LinkedHashMap() {
    }

    public LinkedHashMap(int capacity) {
        super(capacity);
    }

    public LinkedHashMap(Map<? extends K, ? extends V> from) {
        super(from);
    }
}
