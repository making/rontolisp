# The LOOP anaphoric `it` is unbound in any package but `CL-USER`

Difficulty: Low

Found 2026-08-08 while spiking tiny-routes (`.todo/291`). `loop`'s anaphoric
`it` -- documented and pinned in `doc/{en,ja}/reference/macros/loop.md` -- is
recognised only when the loop is read in `CL-USER`. Read in any other package the
`IT` symbol comes out of `PackageResolver` package-QUALIFIED, the substitution
misses it, and the expansion references a variable nothing binds.

```console
$ cat it.lisp
(print (loop for x in '(nil nil 3 4) when x return it))
(defpackage :zz (:use :cl))
(in-package :zz)
(print (loop for x in '(nil nil 3 4) when x return it))

$ rontolisp it.lisp                       # interpreter
3
LispEvalException: The variable ZZ::IT is unbound

$ rontolisp it.lisp -o It.class           # JVM
UnsupportedOperationException: Cannot compile symbol reference: ZZ::IT

$ rontolisp it.lisp -o it.wasm            # both WASM backends
UnsupportedOperationException: Cannot compile symbol: ZZ::IT
```

All four backends, because the substitution is in the shared expander. The
interpreter fails at run time and the two compile paths at compile time, which is
the worse shape: a library whose `it` sits on a cold branch still refuses to
compile.

## Cause

`LispMacroExpander.LoopExpander.substituteIt` matches the symbol by RAW name:

```java
v -> (v instanceof LispSymbol s && s.name().equals("IT")) ? itVar : null
```

A rontolisp symbol name carries its package (`ZZ::IT`, `TINY-ROUTES::IT`), and the
expander runs AFTER `PackageResolver`, so the raw compare only ever hits the
unqualified `CL-USER` spelling. The rule `.kb/packages.md` states for the
pre-resolution scanners applies just as much here, only in the other direction:
a symbol match goes through `PackageRegistry.splitQualified` and compares the
MEMBER, never the whole string.

Matching the member in ANY package is the right rule, not just the current one:
`it` is whatever symbol named `IT` the loop was read with, and no `cl:it` exists
to collide with.

`IT_SKIP_HEADS` (quote / function / nested `loop`) already scopes the walk
correctly and needs no change.

## Why it matters

`tiny:routes` -- the dispatch function every tiny-routes application runs
through -- is

```lisp
(loop for handler in handlers
      when (funcall handler request) return it)
```

inside `(in-package :tiny-routes)`. Any third-party library that uses the
documented anaphor from its own package hits this; ours never did because our
Lisp sources and every example are read in `CL-USER` or use an explicit
variable.

## Done when

- The probe above prints `3` twice on all four backends.
- The same holds for the other selectable-clause shapes, not just `return it`:
  `collect it`, `do (f it)`, `when ... else ... it`, and a nested conditional
  whose inner `it` must still shadow the outer one -- all read inside a non-`CL-USER`
  package.
- Pinned in `LispEvaluatorTest`, `JvmLispCompilerTest` and
  `WasmLispCompilerIntegrationTest` (the compile paths fail at COMPILE time, so
  a test that only runs the interpreter would not have caught this).
- `doc/{en,ja}/reference/macros/loop.md` keeps its `it` section unchanged -- the
  documented behavior was right, the implementation was not.
