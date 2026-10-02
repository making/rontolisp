# Macros

Code that writes code. A `defmacro` defines a compile-time expander: call sites
expand while lowering, before any backend runs, and the same expander answers
`macroexpand-1` at run time. Templates are syntax-quote, a var the defining namespace
sees qualifying with its namespace, with `~`/`~@` and per-expansion `x#` gensyms. The
`unless` below is
`(defmacro unless [c t] (list 'if c nil t))`.

| Name | Example | Result |
|---|---|---|
| `defmacro` | `(do (defmacro unless [c t] (list 'if c nil t)) (unless false 1))` | `1` |
| `syntax-quote` | `(do (defmacro w [c & b] `(if ~c (do ~@b))) (macroexpand-1 '(w true 1)))` | `(IF true (DO 1))` |
| `gensym` | `(= (gensym "g") (gensym "g"))` | `false` |
| `macroexpand-1` | `(macroexpand-1 '(unless true 1))` | `(IF true nil 1)` |
| `macroexpand` | `(macroexpand '(unless false 1))` | `(IF false nil 1)` |
