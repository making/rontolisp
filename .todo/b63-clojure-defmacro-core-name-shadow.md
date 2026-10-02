# b63. `defmacro` of a name the lowering already owns

Difficulty: Medium

`with-out-str cannot name a macro: it names a core form` -- a corpus macro
library redefines `with-out-str` (a core spelling the lowering dispatches on),
and macro bodies there also build `(new java.io.StringWriter)` +
`(binding [*out* s#] ...)`.

Two pieces:

1. A `defmacro` whose name matches a lowering-table head must shadow it for
subsequent lowerings of that file/session (the pre-scan already orders
definitions; the dispatch hub consults the macro table first). Scope: which
names win -- user macro over built-in row -- and whether built-in call sites
BEFORE the def in file order keep the built-in (the oracle is order-sensitive
per compilation unit; mirror it per top-level form).
2. `new` in macro bodies expands like any call once the expander runs at lower
time (verify -- the body sees "the core builtins and the clojure.lisp
library"; `new`/interop in expansion output must lower at the call site, and
`*out*` binding already works).

## Oracle

```bash
clj -M -e '
(defmacro with-out-str [& body] `(do ~@body))
(println (with-out-str 1)) ; user macro wins after its def
(println (clojure.core/with-out-str (print "x")))'
```

Pin: before/after-def call sites, qualified core escape, expansion containing
`new` and a `binding` of `*out*`.

## Acceptance

`ClojureLoweringTest` shapes + a `clojure-spec.yaml` case if the shadowing is
pure lowering (it is: expansion happens before backends).
