# Native binary: no JDK class the image registers answers a constructor

Difficulty: Low

A JDK class the native image holds but registers without members answers no
constructor, so a Clojure program constructing one fails where the JVM's
interpreter answers it:

```clojure
(println (try (do (java.util.HashMap.) :ok) (catch Exception e :failed)))
```

```
error constructing java.util.HashMap: java.lang.IllegalArgumentException:
No matching ctor found for class java.util.HashMap
```

The JVM's interpreter prints `:ok` (as for `java.util.ArrayList`,
`java.util.HashSet`). Measured 2026-11-27 (GraalVM 25.0.3, macOS aarch64): the
image's reflection of the type lists no constructors, and the front end reads its
member tables from that reflection where it finds no JDK
(`.kb/clojure-frontend.md`, "Host classes"). A Java map -- schema's `WeakHashMap`
registry, an `(IdentityHashMap. 16)` -- cannot be made there at all, so a class
value round-tripped through one is untestable on the binary.

Register the constructors of the JDK types the front end can construct
(`reachability-metadata.json`; whether as a `methods` entry named `<init>` with
its parameter types, or as `allPublicConstructors` for the types), and extend
`ClojureHostImageTypesTest`, whose entry check accepts one key (a type) or the
members of `ClojureInteropLowering.HOST_STATIC_CLASSES` only.

Done when `(java.util.HashMap.)`, `(java.util.ArrayList.)` and
`(java.util.IdentityHashMap. 16)` run on the binary as on the JVM's interpreter,
pinned by a run leg comparing the two (`e2e/ClojureNativeLoweringE2eTest`).
