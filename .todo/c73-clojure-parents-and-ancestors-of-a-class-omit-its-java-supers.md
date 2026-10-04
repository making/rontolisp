# c73. Clojure: `parents`/`ancestors` of a class omit its Java supers, and class chains carry no interface or `Object`

Difficulty: Medium

Since 2026-10-04 a throwable or stream class spelled in `defmethod`, `isa?`, `derive` or
`underive` is the keyword `class` answers, and `isa?` walks its superclass edges
(`.kb/clojure-frontend.md`, "Class chains"). `parents`, `ancestors` and `descendants` still
lower a class spelling as the host class object (a call-time error on wasm) and read only the
hierarchy. Measured against clj 1.12.6:

```clojure
(parents NumberFormatException)       ; oracle #{java.lang.IllegalArgumentException}, here #{}
(ancestors IllegalArgumentException)  ; oracle #{Object RuntimeException Serializable Throwable Exception}, here #{}
(derive Exception ::f)
(ancestors Exception)                 ; oracle #{:user/f java.lang.Object java.io.Serializable java.lang.Throwable}, here #{}
(isa? NumberFormatException java.io.Serializable)       ; oracle true, here false
(isa? (class (java.io.StringWriter.)) java.io.Closeable) ; oracle true, here false
(isa? (class "a") Object)                                ; oracle true, here false
```

The oracle's `parents` adds `bases`, `ancestors` adds `supers` and their hierarchy ancestors,
both including interfaces and `Object`. Needs: `hierarchyArg` for the three readers, the
interface rows in the chain tables (`ClojureThrowables.PARENTS` has superclasses only,
`ClojureDispatchLowering.STREAM_SUPERS` too), and what a core-kind keyword (`:string`)
answers -- its host class is not one value here. Also: the class of a host throwable no
construction names (one a host method returned) has no edges. All four backends; pin in
clojure-spec; measure the hierarchy runtime's size.
