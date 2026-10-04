# c99. Clojure printer: keyword, string and float kinds sit behind many library kind tests

Difficulty: Low

After the integer arm (`%clojure-write`, `clojure.lisp`), the other common scalars still pass
the same chain: a keyword after the `lazy`, `re-pattern`, `re-matcher` tests (and the
`print-meta`, `print-deep`, cycle-label arms before them), a string after the keyword, var,
unbound, ns-object, label, ns-map, cut, record, set and atom tests, a float after all of those
plus the symbol, hash-table, vector, sorted and cons tests. Each is a library call on the
interpreter.

Plan: measure `pr-str` of a vector of 50k keywords, of strings and of doubles on the
interpreter and the compiled backends; if worthwhile, test `stringp`/`characterp`/`floatp`
(the float arm keeps its `symbolic-float-p` split) before the library kind tests, but only
where no earlier arm can claim the value (`print-meta-p` needs metadata, which a string or
float cannot carry; a keyword is a cons wrapper, so it must stay ahead of `consp` and behind
nothing that accepts it). Record the size every printing program pays (wasm P1,
`--optimize=size`, component, JVM class; the integer arm cost wasm +46 B, JVM class about
+290 B) and stop if the compiled gain is within noise and only the interpreter moves little.
