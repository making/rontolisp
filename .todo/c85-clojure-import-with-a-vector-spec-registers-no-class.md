# c85. Clojure: `(:import [pkg Class])` registers no class

Difficulty: Low

All backends, measured 2026-10-04 against clj 1.12.6:

```clojure
(ns c (:import [java.io File]))
(println (instance? File (File. "x")))
; oracle: true
; here:   "unknown name: File"
```

The list spelling `(:import (java.io File))` works. `ClojureNamespaceLowering.importSpecs`
reads a vector's items including the reader's `VECTOR` marker as the package name, so it
registers `<marker>.java.io` and `<marker>.File`. Skip the marker like `referNames` does
(`from = elements.get(0) == ClojureReader.VECTOR ? 1 : 0`); the same holds for `(import ...)`
if it shares the path. Pin with a clojure-spec case over a vector import of a host class and
of another namespace's protocol interface (`(:import [a.b P])`, `(instance? P x)`).
