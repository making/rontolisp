# i58. coalton's standard library: load it past `library/math/complex.lisp`, and use it

Difficulty: High

Measured 2026-11-05 on the interpreter, `(ql:quickload "coalton")`, on a box under load 40-60. The compiler
loads in about two minutes, and `coalton/library` compiled 24 of its files with no error in 55 minutes, then the
run was stopped inside `library/math/complex.lisp`: `math/num.lisp` takes 10 minutes, `conversions` 8,
`integral` 19, `real` 9. About 35 files remain (`vector` through `prelude`, then `big-float`).

- What the remaining files stop on is unmeasured; every earlier stop was a general bug, listed in `.kb/asdf.md`,
  "Libraries that load". One attempt costs an hour, so a stop deep in the library is expensive to reach. Where the
  time goes is unmeasured too: each `coalton-toplevel` runs coalton's typechecker and codegen interpreted, then
  the interpreter evaluates what it generated.
- Trace progress with `(defmethod asdf:perform :before ((op asdf:load-op) (c asdf:cl-source-file)) ...)`
  printing `asdf:component-pathname`, ahead of the quickload. The same hook can run probes at a given
  component, then `uiop:quit`.
- After the load: `(coalton:coalton-toplevel ...)` and `(coalton:coalton ...)` from a user package, against
  SBCL 2.2.9's answers over the same tree.
- `/` does not honor `with-float-traps-masked` (the shim's macro is its body), so `(/ 1.0 0.0)` under coalton
  signals where SBCL with traps off answers an infinity. Unmeasured whether the library's tests reach it.
- The compile paths are unmeasured: a `coalton-toplevel` needs the compiler in the macro-time image.
