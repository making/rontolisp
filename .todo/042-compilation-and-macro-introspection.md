# Compilation and macro introspection (`compile`, `compile-file`, `load-time-value`, `compiler-macroexpand`, implementation introspection, random state)

**Status:** partially implemented. Low-Medium priority — useful for development
and metaprogramming.

## Done

| Operator | Note |
|----------|------|
| `macroexpand` | `LispEvaluator.macroexpand` (see below); answers CL's `expanded-p` second value since 2026-08-15 (`.todo/378`) |
| `macroexpand-1` | `LispEvaluator.macroexpand1` (see below); same second value |
| `macro-function` | `.todo/378` (2026-08-15): the real expander on the interpreter, a signalling stub in compiled output, non-nil on all four backends for every macro name |
| `special-operator-p` | `.todo/378` (2026-08-15): t for the 25 ANSI special operators, and it partitions the operators with no function value against `macro-function` |
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
| `machine-instance` | The host name. Nothing here can answer it today, which is why `uiop:hostname` returns nil on all four backends (`.kb/uiop.md`, `uiop/os`'s decisions) — that member becomes one line over this |
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
3. `macro-function` / `special-operator-p` — `.kb/symbol-runtime-api.md`.

**Compilation**:
4. `compile` — runtime compilation (use the same compiler pipeline).
5. `load-time-value` — evaluate at load time, embed result.

**Implementation introspection**:
6. `lisp-implementation-type` -> "RontoLisp".
7. `lisp-implementation-version` -> version string (`rontolisp:version` already
   returns one; this is the CL-named front for it).
8. `features` -> feature list (e.g., `:rontolisp`, `:lisp`, `:ansi-cl`).

**Random state**:
9. `make-random-state` — create a seeded random state.
10. Extend `random` to accept a state argument.

### Related

- `[[034-local-function-definition]]` (compiler macros use `macrolet`-like scoping)
- `[[035-type-system]]` (`deftype`, declarations)
