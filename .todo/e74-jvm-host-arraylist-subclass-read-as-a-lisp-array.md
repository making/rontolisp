# e74. JVM: a host `ArrayList` subclass is asked its own `isEmpty`/`get` by the Lisp-array test

Difficulty: Medium

Measured 2026-10-08: on the JVM

```lisp
(java:call (java:subclass "java.util.ArrayList" '() '("isEmpty") (lambda (this m &rest a) nil))
           "isEmpty")
```

signals `index out of bounds` (the interpreter answers `NIL`); so does the Clojure
`(.isEmpty (proxy [java.util.ArrayList] [] (isEmpty [] false)))`. The direct sites' `_jlarr`
(`JvmJavaDirectSites.buildLispArray`) tests `instanceof ArrayList`, then calls the object's
own `isEmpty()` and `get(0)` -- an override that says "not empty" on an empty list reaches
`get(0)`. The bridge's `isJavaObject` and `kindOf` read it the same way, and a `LinkedHashMap`
subclass overriding `get` would fool `_jltab` likewise.

## Plan

1. Failing test first: the program above in `JavaImplementationPrograms` (both backends).
2. A compiled Lisp array is exactly a `java.util.ArrayList` and a table exactly a
   `LinkedHashMap` (`RontoHashTable`: "The class is exact"): test `getClass() ==` before
   reading, in `_jlarr`, `_jltab`, `_jkind`'s array arm and the bridge's copies, and grep the
   JVM backend for other `instanceof ArrayList` / `LinkedHashMap` reads that a host subclass
   can reach (`.kb/java-interop.md`, "What a host object is").
