# i49. A JVM symbol with an empty name throws at every first-character test

Difficulty: Medium

Measured 2026-11-05, against SBCL 2.2.9. A JVM symbol is a bare `String` and a string is one framed in `"`, so the
two are told apart by `charAt(0)`, and `""` has no such character.

```lisp
(symbolp (intern ""))  ; SBCL T; JVM StringIndexOutOfBoundsException, a simple-error
(symbolp '||)          ; the same
```

The interpreter and both WASM backends answer `T` (WASM keeps one byte behind the zero length, `buildInternSym`).
`(make-symbol "")` and `(intern "" :keyword)` are `#:` and `:` and do not hit it; `(print (intern ""))` prints `||`
until a program's gates route the print through a `charAt(0)`, which is why ci-spec spells only those two.

About 30 emission sites test `charAt(0)` on a value (`git grep -A1 'loadConstant(0)\|iconst_0()' codegen/jvm`). A guard
at each costs every program bytes on hot paths; `startsWith("\"")` is no cheaper to verify. Other shape: the empty name
as a marker `String` the name-reading sites (`symbol-name`, the printers, `intern`) map back, which touches fewer sites
and no hot one. Weigh the two by what they cost a program that never spells the name.
