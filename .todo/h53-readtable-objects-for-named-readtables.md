# h53. Readtable objects and the reader-macro API (blocks named-readtables and its dependents)

Difficulty: High

Measured 2026-10-30. `named-readtables` is a dependency of `cl-interpol`, `cl-csv`,
`jonathan`, `cl-redis` and `parenscript`, and none of them load. `cl-annot` loads, but
`annot:enable-annot-syntax` dies on `set-macro-character`.

Order of failures in named-readtables:
1. `define-cruft` bodies are empty without `:common-lisp` (h31 item 2).
2. With `--feature common-lisp`: `DEFMETHOD: unknown specializer READTABLE`.

Current state, interpreter:

```lisp
(type-of *readtable*)                     ; NULL  (*readtable* is NIL)
(copy-readtable nil)                      ; NIL
(set-macro-character #\! fn)              ; undefined function
(set-dispatch-macro-character #\# #\? fn) ; accepted, no effect: (read-from-string "#?x") => #?X
```

`get-macro-character`, `get-dispatch-macro-character`, `make-dispatch-macro-character`,
`set-syntax-from-char`, `readtablep` and `read-delimited-list` are undefined (h39 list).

Needed: a `readtable` class/type with real instances (`*readtable*`, `copy-readtable`,
`readtable-case` per table), and a READ path that consults the current readtable's macro
characters (the runtime `read`/`read-from-string` and the `load`ed source of a library).
The compile path reads source in Java (`LispLexer`), so a reader macro installed by a
library must also run there at compile time (the compiler's own interpreter). Design that
split first. The reader has a fixed configuration in FOUR places: the frontend `LispReader`
plus the embedded read runtimes of the JVM and both WASM backends. ironclad's `#N@(...)`
literal is native in `LispLexer` and must keep working. `readtable-case` is a constant
`:upcase` stub (todo-195). (Moved here from `.todo/041`, which keeps the printing half.)
