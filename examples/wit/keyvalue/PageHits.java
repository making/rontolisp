// page-hits.lisp in Java: a page-view counter written against the real
// wasi:keyvalue/store, through the @WitImport interface Store.
//
//   java -cp rontolisp-java-api.jar PageHits.java     a JDK: MemoryStore
//   rontolisp PageHits.java                            the interpreter: MemoryStore
//   rontolisp PageHits.java -o PageHits.class          the JVM: MemoryStore
//   rontolisp PageHits.java -o kv.wasm --component
//     + wasmtime run -S keyvalue=y kv.wasm             wasmtime's own wasi:keyvalue
//
// (rontolisp takes --java-source-path rontolisp-java-api/src/main/java, where the
// annotation is.) The program finds its store with ServiceLoader and never says where
// the pairs live: META-INF/services/Store names MemoryStore, which answers under a JDK,
// on the interpreter and on the JVM; in a component the host is the store, and
// ServiceLoader finds the host's alone. It prints the report page-hits.lisp prints.

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.BiConsumer;

import am.ik.rontolisp.wit.WitException;

public class PageHits {

    static final List<String> REQUESTS = List.of("/index", "/pricing", "/index", "/docs", "/index", "/pricing");

    // the default store, which every wasi:keyvalue host recognizes under the empty
    // identifier
    static final String STORE = "";

    // bucket.get answers an option<list<u8>>: the value's bytes, or empty when the key
    // is absent; the counter is stored as its decimal text
    static String hits(Store.Bucket bucket, String page) {
        return bucket.get(page).map(value -> new String(value, StandardCharsets.UTF_8)).orElse("NIL");
    }

    static void recordHit(Store.Bucket bucket, String page) {
        String seen = hits(bucket, page);
        int count = seen.equals("NIL") ? 0 : Integer.parseInt(seen);
        bucket.set(page, Integer.toString(count + 1).getBytes(StandardCharsets.UTF_8));
    }

    // bucket.list-keys answers the record key-response: these stores hand back every
    // key at once, so no cursor is passed and none comes back
    static List<String> sortedKeys(Store.Bucket bucket) {
        return bucket.listKeys(java.util.Optional.empty()).keys().stream().sorted().toList();
    }

    static String shown(List<String> keys) {
        StringBuilder text = new StringBuilder("(");
        for (String key : keys) {
            text.append(text.length() > 1 ? " " : "").append('"').append(key).append('"');
        }
        return text.append(')').toString();
    }

    // the error arm's variant, by its WIT label
    static String label(Store.Error error) {
        return switch (error) {
            case Store.Error.NoSuchStore none -> "NO-SUCH-STORE";
            case Store.Error.AccessDenied denied -> "ACCESS-DENIED";
            case Store.Error.Other(String text) -> "OTHER " + text;
        };
    }

    public static void main(String[] args) {
        Store store = ServiceLoader.load(Store.class).findFirst().orElseThrow();

        // open answers a result<bucket, error>: the ok arm is the bucket
        try (Store.Bucket bucket = store.open(STORE)) {
            for (String page : REQUESTS) {
                recordHit(bucket, page);
            }
            System.out.println();
            System.out.println("hits per page:");
            for (String key : sortedKeys(bucket)) {
                System.out.println("  " + key + " = " + hits(bucket, key));
            }
            System.out.println();
            System.out.println("/docs exists?      " + (bucket.exists("/docs") ? "yes" : "no"));
            bucket.delete("/docs");
            System.out.println("/docs exists now?  " + (bucket.exists("/docs") ? "yes" : "no"));
            System.out.println("keys:              " + shown(sortedKeys(bucket)));
            System.out.println("/nope:             " + hits(bucket, "/nope"));
        }

        // the error arm of a WIT result is a WitException holding the variant that
        // failed: the same no-such-store from each store
        try {
            store.open("not-a-store-anyone-has");
        }
        catch (WitException e) {
            System.out.println("bad store:         " + label((Store.Error) e.error()));
        }

        // a member is a method, so a method reference is a function value
        try (Store.Bucket bucket = store.open(STORE)) {
            BiConsumer<String, byte[]> setKey = bucket::set;
            for (String key : List.of("/a", "/b")) {
                setKey.accept(key, "seeded".getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("seeded:            "
                    + shown(sortedKeys(bucket).stream().filter(key -> hits(bucket, key).equals("seeded")).toList()));
        }
    }
}
