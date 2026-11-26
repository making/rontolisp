// A store of the program's own: wasi:keyvalue/store over a map, which answers under a
// JDK, on the interpreter and on the JVM -- META-INF/services/Store names it, and
// ServiceLoader finds it there. A WebAssembly component's host is its store, and the
// one ServiceLoader finds there; this class goes unused.

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import am.ik.rontolisp.wit.WitException;

public class MemoryStore implements Store {

    // the pairs live under the store's identifier: a bucket is a handle on them, and
    // closing one leaves them where they are
    private final Map<String, Map<String, byte[]>> stores = new HashMap<>();

    public Store.Bucket open(String identifier) {
        // like every wasi:keyvalue host, the default store is the empty identifier, and
        // any other is the error arm no-such-store
        if (!identifier.isEmpty()) {
            throw new WitException("memory store: no such store " + identifier, new Store.Error.NoSuchStore());
        }
        Map<String, byte[]> pairs = this.stores.computeIfAbsent(identifier, key -> new HashMap<>());
        return new Store.Bucket() {

            public Optional<byte[]> get(String key) {
                return Optional.ofNullable(pairs.get(key));
            }

            public void set(String key, byte[] value) {
                pairs.put(key, value);
            }

            public void delete(String key) {
                pairs.remove(key);
            }

            public boolean exists(String key) {
                return pairs.containsKey(key);
            }

            // every key at once: no cursor for a next page
            public Store.KeyResponse listKeys(Optional<Long> cursor) {
                return new Store.KeyResponse(new ArrayList<>(pairs.keySet()), Optional.empty());
            }

            public void close() {
            }
        };
    }
}
