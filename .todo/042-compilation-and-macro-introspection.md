# Compilation and macro introspection (`compile`, `compile-file`, `load-time-value`, `compiler-macroexpand`, implementation introspection, random state)

**Status:** partially implemented. Low-Medium priority — useful for development
and metaprogramming.

## Done

| Operator | Note |
|----------|------|
| `macroexpand` | `LispEvaluator.macroexpand` (see below) |
| `macroexpand-1` | `LispEvaluator.macroexpand1` (see below) |
| `eval-when` | `LispNames.java:1171` + doc: expands to `progn`, top-level bodies spliced into top-level forms |
| `deftype` | `LispNames.java:1092` + doc: parsed no-op, the type name is not registered |
| `documentation` | `LispNames.java:1158` + doc: lite — reads expand to nil, `(setf (documentation ...) "...")` discards |
| `define-compiler-macro` | `LispNames.java:1126` + doc: parsed no-op (the ordinary function definition stays authoritative) |
| `macrolet` | `LispNames.java:1143` + doc: expands its body with the local macros active, then drops them |

`define-compiler-macro` is the CL name; this file's header calls it
`defcompiler-macro`, which is not.

## What's missing

### Compilation

| Operator | Purpose |
|----------|---------|
| `compile` | Compile a function at runtime: `(compile 'f (lambda (x) (+ x 1)))` |
| `compile-file` | Compile file to fasl |
| `load-time-value` | Evaluate at load time only |

### Macro introspection

| Operator | Purpose |
|----------|---------|
| `compiler-macroexpand` | Compiler macro expansion |

### Implementation introspection

| Operator | Purpose |
|----------|---------|
| `lisp-implementation-type` | Implementation name string |
| `lisp-implementation-version` | Version string — `rontolisp:version` (`LispNames.java:1964`) is the non-CL analog we already ship |
| `member` (done) | Already implemented |

### Random state

| Operator | Purpose |
|----------|---------|
| `make-random-state` | Create random state: `(make-random-state t)` |
| `random-state-p` | Random state predicate |
| `random` (done) | Already implemented (but doesn't accept state arg) |

### Implementation approach

**Note (2026-07):** `defmacro` (user macros), `macroexpand-1`/`macroexpand` and `gensym` are now implemented (interpreter-native + compile-path pre-pass `eval.UserMacroExpander`; see `.todo/044-defmacro-followups.md`).

**Macro introspection** — done:
1. `macroexpand-1` — single-step expansion (`LispEvaluator.macroexpand1`).
2. `macroexpand` — full expansion (repeated `macroexpand-1`).

**Compilation**:
3. `compile` — runtime compilation (use the same compiler pipeline).
4. `load-time-value` — evaluate at load time, embed result.

**Implementation introspection**:
5. `lisp-implementation-type` -> "RontoLisp".
6. `lisp-implementation-version` -> version string (`rontolisp:version` already
   returns one; this is the CL-named front for it).
7. `features` -> feature list (e.g., `:rontolisp`, `:lisp`, `:ansi-cl`).

**Random state**:
8. `make-random-state` — create a seeded random state.
9. Extend `random` to accept a state argument.

### Related

- `[[034-local-function-definition]]` (compiler macros use `macrolet`-like scoping)
- `[[035-type-system]]` (`deftype`, declarations)
