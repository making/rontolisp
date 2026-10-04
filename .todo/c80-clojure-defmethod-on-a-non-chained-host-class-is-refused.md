# c80. Clojure: `defmethod` on a host class that is no kind and no chained class is refused

Difficulty: Medium

Interpreter and JVM, measured 2026-10-04 against clj 1.12.6:

```clojure
(defmulti f class)
(defmethod f java.io.File [x] :file)          ; here: "defmethod needs a core class, not java.io.File"
(defmethod f java.util.AbstractList [x] :alist)
(defmethod f :default [x] :default)
(println (f (java.io.File. "x")) (f (java.util.ArrayList.)) (f 1))  ; oracle :file :alist :default
```

`ClojureDispatchLowering.dispatchClassKey` maps a class spelling to a kind, a tag or a chained
class keyword and refuses anything else. Since the host class walk (`.kb/clojure-frontend.md`,
"Class chains", host classes) a host class object dispatches through `C%H-ISA?` by its supers,
so a dispatch value of the class object itself (the `Class.forName` a `hierarchyArg` lowers a
non-chained spelling to) would be found by the exact lookup and the miss search alike. Open:
the key is a run-time value (a `java:` call in the `defmethod`), so a program naming such a class
becomes a `java:` program (wasm refuses it where it now refuses at lowering); and the dispatch
table hashes it under `equal`.
