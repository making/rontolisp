# Compilation and macro introspection (`compile`, `compile-file`, `load-time-value`, `eval-when`, `macroexpand` (done), `macroexpand-1` (done), `macrolet` (see #34), `compiler-macroexpand`, `defcompiler-macro`, `function-lambda-args`, `function-information`, `documentation`, `cookie`, `implementation`, `lisp-implementation-type`, `lisp-implementation-version`, `lisp-version`, `lisp`, `features`, `member` (done), `random` (done), `make-random-state`, `random-state-p`, `code-consumption-signal`, `code-declaration`, `code-error`, `code-reader-extensivity`, `code-termination`, `declaration`, `ignore` (see #35), `ignorable` (see #35), `inline` (see #35), `notinline` (see #35), `optimize` (see #35), `special` (see #35), `speed`, `safety`, `space`, `debug`, `compilation-speed`, `declaim` (see #35), `declare` (see #35), `the` (see #35))

**Status:** not implemented. Low-Medium priority — useful for development and metaprogramming.

## What's missing

### Compilation

| Operator | Purpose |
|----------|---------|
| `compile` | Compile a function at runtime: `(compile 'f (lambda (x) (+ x 1)))` |
| `compile-file` | Compile file to fasl |
| `load-time-value` | Evaluate at load time only |
| `eval-when` | Conditional evaluation: `(eval-when (:compile-toplevel :load-toplevel :execute) ...)` |
| `deftype` | Define type specifier |

### Macro introspection

| Operator | Purpose |
|----------|---------|
| `macroexpand` (done) | Fully expand macro |
| `macroexpand-1` (done) | Single-step macro expansion |
| `compiler-macroexpand` | Compiler macro expansion |
| `defcompiler-macro` | Define compiler macro |
| `function-lambda-args` | Introspect function lambda list |
| `function-information` | Check function availability |

### Documentation

| Operator | Purpose |
|----------|---------|
| `documentation` | Get/set documentation string |
| `doc` | (Not CL standard; SLIME extension) |

### Implementation introspection

| Operator | Purpose |
|----------|---------|
| `lisp-implementation-type` | Implementation name string |
| `lisp-implementation-version` | Version string |
| `lisp-version` | ANSI version string ("ANSI") |
| `lisp` | (Not CL; returns t) |
| `features` | Feature list |
| `member` (done) | Already implemented |
| `cookie` | (Not CL; CMUCL) |
| `implementation` | (Not CL) |

### Random state

| Operator | Purpose |
|----------|---------|
| `make-random-state` | Create random state: `(make-random-state t)` |
| `random-state-p` | Random state predicate |
| `random` (done) | Already implemented (but doesn't accept state arg) |

### Implementation approach

**Note (2026-07):** `defmacro` (user macros), `macroexpand-1`/`macroexpand` and `gensym` are now implemented (interpreter-native + compile-path pre-pass `eval.UserMacroExpander`; see `.todo/44-defmacro-followups.md`).

**Macro introspection** — done:
1. `macroexpand-1` — single-step expansion (`LispEvaluator.macroexpand1`).
2. `macroexpand` — full expansion (repeated `macroexpand-1`).

**Compilation**:
3. `compile` — runtime compilation (use the same compiler pipeline).
4. `load-time-value` — evaluate at load time, embed result.
5. `eval-when` — conditional evaluation control.

**Implementation introspection**:
6. `lisp-implementation-type` -> "RontoLisp".
7. `lisp-implementation-version` -> version string.
8. `lisp-version` -> "ANSI".
9. `features` -> feature list (e.g., `:rontolisp`, `:lisp`, `:ansi-cl`).

**Random state**:
10. `make-random-state` — create a seeded random state.
11. Extend `random` to accept a state argument.

### Related

- `[[34-local-function-definition]]` (compiler macros use `macrolet`-like scoping)
- `[[35-type-system]]` (`deftype`, declarations)
