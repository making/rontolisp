# The printer spells an accessible symbol with its package qualifier

Difficulty: High

Found while landing the rove milestone (`RoveE2eTest`): every backend prints a
symbol's CANONICAL spelling verbatim, so a symbol that is accessible in the
runtime `*package*` -- and that CL therefore prints bare -- comes out qualified:

```lisp
(defpackage #:lib (:use #:cl) (:export #:fn))
(in-package #:lib)
(defun fn (x) x)
(defpackage #:app (:use #:cl #:lib))
(in-package #:app)
(print 'fn)        ; rontolisp: LIB:FN      SBCL: FN
(defun own-fn (y) y)
(print 'own-fn)    ; rontolisp: APP::OWN-FN SBCL: OWN-FN
```

Note the second case: even a symbol whose HOME is the current package prints
qualified. CLHS 22.1.3.3.1: no qualifier when the symbol is accessible in
`*package*` (own, inherited via `:use` of an external, or imported);
`pkg:name` / `pkg::name` only otherwise.

## Why it matters

- It is the ONE divergence from SBCL 2.2.9 in `RoveE2eTest`'s pinned report
  (`Expect (= (MY-APP/MAIN:ADD 1 2) 3)` vs `(= (ADD 1 2) 3)`): every test
  framework's failure report prints the user's forms, so every library in this
  family inherits the wrong spelling. Flip that test's expected lines to the
  SBCL spellings when this lands.
- Any library that formats its own symbols from its own package (`~A` of a
  parsed token, an error report naming an operator) is off-by-a-qualifier.

## Sketch

Symbols are textual (canonical spelling); accessibility is a resolver-time
notion, `*package*` is a runtime dynamic. So the printer needs, per backend:

- the current `*package*` (a runtime read -- already real, todo-255), and
- an accessibility answer for (home-pkg, name, external?) against it.

Interpreter: `PackageResolver` is live -- can answer directly at the render
seam. Compile paths: no registry at run time, so bake a table (the
`runtimePackageTable()` precedent): per user package, its use list + externals
+ import redirects is enough to decide "print bare". Gating for emitted-output
byte-identity (`.kb/emitted-output-determinism.md`): programs with no user
packages never print a qualified symbol, and rove-class programs already carry
the `*print-case*` rewrite; decide whether this rides the same
`%print-cased`-style rewrite or the raw renderers.

Watch: `prin1` `|escape|` cases, keywords (`:k` stays), uninterned `#:g`,
`~A`/`~S`/`~W` and the three `-to-string`s must all agree (the todo-041 list),
and `format-symbol`-style consumers. Differential-test against SBCL 2.2.9 like
todo-041 did.

This dissolves naturally into the intern-table redesign (`.todo/156`) if that
lands first -- a real symbol object knows its home package and the runtime
would know accessibility; check 156 before building the baked table.
