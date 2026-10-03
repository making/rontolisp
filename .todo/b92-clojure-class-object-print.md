# b92. Print a Class object as its name in the Clojure printer

Difficulty: Medium

`String` as a value (b83) answers the host `Class` object, which prints
`#<java java.lang.Class>` (interpreter `LispJavaObject.print`, the JVM runtime
printer, `JavaBridgeTemplate`); the oracle (`clj` 1.12.6.1673) prints
`(println String)` -> `java.lang.String`, `(pr-str String)` ->
`java.lang.String`, but `(str String)` -> `class java.lang.String`.

## Plan

- `%clojure-write`'s fall-through arm (`clojure.lisp`, `(t (princ x stream))`)
  needs a Class test that adds no `java:` reference to wasm programs
  (`.kb/java-interop.md`); the printers are shared with Common Lisp, whose
  `#<java java.lang.Class>` must stay unless the cross-language change is
  taken deliberately. The mechanism exists since `%clojure-host-class`: a
  `clojure.lisp` defun with a `java:` body, swapped for a `java:`-free one by
  `ClojureLibrary.process` in a program that names no `java:` operator
  (`HOST_ARM_REFUSALS`); a printer host arm can be another entry there.
- `str` spells `class <name>`, print/pr-str the bare name.
- Pin in `ClojureInteropTest` (interpreter + JVM), update
  `.kb/clojure-frontend.md` and the `class-member` reference page (en + ja),
  which now state the deviation.
- Also open from b83: the `(thrown? IllegalArgumentException ...)` /
  `(thrown? ClassCastException ...)` assertions of `examples.test.interop`
  (`#^Class` hints are dropped; host-exception mapping).
