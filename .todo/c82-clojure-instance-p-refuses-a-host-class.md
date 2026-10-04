# c82. Clojure: `instance?` of a host class no kind or chain names is refused

Difficulty: Medium

Interpreter and JVM, measured 2026-10-04 against clj 1.12.6:

```clojure
(println (instance? java.io.File (java.io.File. "x"))
         (instance? java.util.List [1])
         (instance? java.util.AbstractList (java.util.ArrayList.)))
; oracle: true true true
; here:   "instance? needs a core class, not java.io.File"
```

`ClojureDispatchLowering.instanceOf` takes a fixed simple-name switch (`String`, `Number`,
`Long`, ...), records/deftypes and throwable chains; any other class -- `java.util.List`,
which `DISPATCH_CLASS_KEYWORDS` maps to `:list`, included -- is refused. Open: a kind
spelling (`java.util.List`, `java.util.Map`) over a Clojure value is answerable on every
backend from the kind test; a host class over a host object needs `Class.isInstance`
(`%clojure-host-instance-p`, a host arm), the `java:` criterion deciding the splice.
