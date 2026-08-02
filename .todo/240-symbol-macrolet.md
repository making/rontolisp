# `symbol-macrolet` on all four backends

Difficulty: 高 (a new binding form in the shared expander with setf
integration and shadowing rules; recommend starting with a Fable-class model)

Part of the Mito milestone `.todo/238` (substrate; no dependencies). Also the
long-standing gate noted in `.todo/115` ("symbol-macrolet is still
unsupported" — it kept ironclad's `dotimes-unrolled` users out).

## The blocker

```lisp
(symbol-macrolet ((x 42)) (print x))
;; => The function SYMBOL-MACROLET is undefined
```

Load-bearing consumers on the mito path (grep-verified):

- **trivia level1/impl.lisp** (14, 288, 290, 323) and **level2/impl.lisp**
  (88, 387): `match` EXPANSIONS emit symbol-macrolet — every trivia user
  (sxql, mito) executes these, so this must work at runtime on every backend,
  not just parse.
- **dbi src/driver.lisp:295**: `(symbol-macrolet ((auto-commit (slot-value
  conn 'auto-commit))) ...)` — and the body ASSIGNS via `setf auto-commit`,
  so writing through a symbol macro (setf expands the place) is required.
- **mito src/core/type.lisp:52**.

## Design sketch

`LispMacroExpander` is shared by the evaluator and both compilers, so a
substitution pass there gives all four backends at once (the `.kb` "shared
normalizer over bypass wiring" principle):

- Expand `(symbol-macrolet ((s expansion)...) body...)` by walking the body
  and replacing free references to `s` with `expansion`.
- Shadowing: an inner `let`/`let*`/`lambda`/`do`/`destructuring-bind` binding
  of the same symbol stops the substitution in its scope (trivia relies on
  this — level2/impl.lisp:88 comments "symbol-macrolet could be rebind by
  let"). Quoted data and other symbol positions (function names, go tags,
  block names) must NOT be substituted.
- `setf` of a symbol-macro place must rewrite to a setf of the expansion
  (dbi's `auto-commit` site). Macroexpansion order matters: substitute BEFORE
  the setf lowering so the existing setf machinery sees the real place.
- The walk must macroexpand user macros it meets (or run after their
  expansion) so references produced by nested `match` expansions are seen.
  Check interaction with `.todo/182` (interpreter re-expands user macros per
  evaluation) — the substitution must not be quadratic in hot loops.
- `define-symbol-macro` (global) is NOT needed by this closure — grep found
  zero uses. Leave it out; note it as the re-evaluation trigger.

## Acceptance

- Pinned unit tests: basic substitution, let-shadowing, nested
  symbol-macrolet, `setf` through a slot-value place (the dbi shape),
  reference inside a lambda body.
- `(ql:quickload "trivia.level1")` proceeds past symbol-macrolet.
- ci-spec.yaml case exercising a symbol-macrolet with setf on all backends;
  the native E2E driver run locally.
