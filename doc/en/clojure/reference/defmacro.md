# defmacro

`(defmacro name doc? attr? [params] body...)` /
`(defmacro name doc? attr? ([params] body...)+)`

Defines a compile-time macro: each call site expands while lowering, before any
backend runs, so every backend executes expanded code. Parameters bind the call's
argument forms unevaluated (`&` the rest as a list, destructuring like `defn`
parameters); several arities dispatch on the argument count, like `defn`. A docstring
and an attr map are skipped. `&form` and `&env` are refused: a body runs with its
arguments only, never with a compilation environment.

The definition answers `nil` and registers a runtime table entry of the same
expander, so `macroexpand-1` expands the same function at run time. A macro body
sees the core builtins and the `clojure.lisp` library, not the program's own
definitions; a call above its definition is an error, and a macro has no function
value.

A `defmacro` of a core name -- a function like `inc` or a form like `with-out-str` --
shadows it from its definition on: a call site above the definition keeps the core
meaning, like the oracle's form-by-form compile, and `clojure.core/name` names the core
var whatever the program defines. A special form (`if`, `do`, `let*`, `new`, ...) or a
head the reader spells (`deref`, `with-meta`, `fn`, `syntax-quote`, `ns`, `in-ns`)
cannot name a macro.

```clojure
(defmacro doc-unless [c t] (list 'if c nil t))
(println (doc-unless false 42)) ; 42
```

```clojure
(defmacro with-out-str [& body] `(str "<" (clojure.core/with-out-str ~@body) ">"))
(println (with-out-str (print 1))) ; <1>
```
