# `eq`/`eql` on two distinct strings with equal contents diverges across backends

Difficulty: Medium

Found while working `.todo/838` (2026-09-17). Measured on all four backends:

```lisp
(let ((a (copy-seq "ab")) (b (copy-seq "ab")))
  (print (list (eq a b) (eql a b))))
```

prints `(T T)` on the interpreter and the JVM and `(NIL NIL)` on both WASM backends. The same
split holds for `make-array :element-type 'character` with and without `:fill-pointer`.
ANSI says `NIL` (strings are identity under `eql`), and `.kb/hash-tables.md` currently
describes `eql`/`eq` as "value-compared on numbers/symbols/strings", which is the
interpreter/JVM side.

A related shared deviation, identical on all four backends: a fill-pointer string stored as
a key of an `eq` table and then grown with `vector-push-extend` loses its entry, because the
key hashes by content.

## To do

1. Decide the target semantics (ANSI identity, or value everywhere) with the blast radius
   measured: symbol names, keyword comparisons and library code (`member`/`assoc`/`case` on
   strings) may rely on the value comparison on the interpreter/JVM.
2. Make all four backends agree, change `.kb/hash-tables.md` with it, and pin the result
   with a `ci-spec.yaml` case.
