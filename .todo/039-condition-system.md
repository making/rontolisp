> **Update 2026-07-05:** lite stopgaps shipped for the load-time idioms:
> `define-condition` is a parsed no-op and `make-condition` expands to its
> `:format-control` value (so `(error (make-condition ...))` signals with the
> intended message). The real condition system remains below.
>
> **Update 2026-07-11:** the concrete engineering plan now lives in
> `.todo/116-error-handling-foundation.md` (unwind-protect + typed conditions
> + handler-case, grounded in the current codebase: the CLOS static subset
> exists now, class-v50 exception tables need only `ByteCodeWriter` emission
> support, WASM catching is gated out for v1). This file stays as the
> API-surface catalog; the "Implementation approach" section below is
> superseded.
>
> **Update 2026-07-12:** todo-116 Phases 1-3 SHIPPED: `unwind-protect`,
> `define-condition`/`make-condition`/condition classes, `signal`, `warn`
> designators, `handler-case`, `ignore-errors` and `with-slots` are real on
> the interpreter/JVM (WASM rejects catching). See `.kb/error-handling.md`.
> Still missing from the catalog below: `handler-bind`, `restart-case` (a
> primary-form no-op) and the whole restart layer (`invoke-restart`,
> `with-simple-restart`, `cerror`, `abort`, `continue`, `break`),
> `muffle-warning`, `condition-format-*` accessors, `typep` as a function.

# Condition system (`handler-case`, `handler-bind`, `restart-case`, `restart-bind`, `invoke-restart`, `invoke-restart-interactively`, `signal`, `error` (done), `warn`, `cerror`, `abort`, `continue`, `break`, `make-condition`, `condition-type`, `simple-condition`, `simple-error`, `simple-warning`, `style-warning`, `serious-condition`, `warning`, `condition`, `storage-condition`, `program`, `control`, `serious-condition`, `error` (condition class))

**Status:** not implemented. Low-Hard priority — the full condition system is one of CL's most complex subsystems.

## What's missing

RontoLisp has the `error` macro (signals a fatal error with a formatted message, expanded by `LispMacroExpander.expandError` into `%error`). The rest of the condition system is absent.

### Missing condition signaling

| Operator | Kind | Purpose |
|----------|------|---------|
| `signal` | Function | Non-fatal condition: `(signal 'my-condition)` |
| `warn` | Function | Non-fatal warning (prints message, continues) |
| `cerror` | Function | Continuable error: `(cerror "continue" 'error 'msg)` |
| `abort` | Function | Transfer to abort restart |
| `continue` | Function | Transfer to continue restart |
| `break` | Function | Interactive debug loop |

### Missing condition handling

| Operator | Kind | Purpose |
|----------|------|---------|
| `handler-case` | Macro | Catch conditions by type: `(handler-case (..) 'error ((c) (print c)))` |
| `handler-bind` | Macro | Bind handlers dynamically: `(handler-bind ((error #'..)) (..))` |
| `restart-case` | Macro | Define restarts: `(restart-case (..) (:retry ()))` |
| `restart-bind` | Macro | Bind restarts dynamically |
| `invoke-restart` | Function | Invoke named restart |
| `invoke-restart-interactively` | Function | Interactive restart |
| `with-simple-restart` | Macro | Define simple restart |

### Missing condition types

| Type | Purpose |
|------|---------|
| `condition` | Root class |
| `serious-condition` | Serious (non-recoverable by default) |
| `warning` | Non-serious |
| `error` | Serious error |
| `simple-condition` | Condition with format string + args |
| `simple-error` | Simple error |
| `simple-warning` | Simple warning |
| `style-warning` | Style warning |
| `control-error` | Control error |
| `program-error` | Program error |
| `storage-condition` | Storage condition |

### Missing condition accessors

| Function | Purpose |
|----------|---------|
| `make-condition` | Construct condition |
| `condition-type` | (Not CL; some implementations) |
| `condition-format-control` | Format string |
| `condition-format-arguments` | Format args |

### Implementation approach

The full CL condition system with CLOS-based condition classes is a massive undertaking. A pragmatic subset:

**Phase 1 — Basic exception handling** (highest ROI):
1. `handler-case` — catch/finally-like exception handling.
   - Expand to try/catch in JVM (`athrow`/catch blocks).
   - In WASM: structured exception handling via `br` to outer block.
   - Interpreter: throw/catch `LispEvalException`.
2. `signal` — throw a condition.
3. `warn` — print warning, continue.
4. `cerror` — error with continue option (without interactive restart, just continue).

**Phase 2 — Condition types** (deferred):
5. `simple-condition`, `simple-error`, `simple-warning`.
6. `make-condition`, `condition-format-control`, `condition-format-arguments`.
7. Condition type hierarchy (without full CLOS).

**Phase 3 — Restart system** (lowest priority):
8. `restart-case`, `invoke-restart`.
9. `break` (interactive debug loop — needs REPL integration).

### Complexity

- `handler-case` is the most valuable operator (like try/catch in other languages).
- The restart system is deeply intertwined with the condition system and CLOS.
- Without CLOS (`defclass`/`defmethod`), condition types are limited to a fixed hierarchy.
- JVM has native try/catch/finally bytecode. WASM has `try`/`catch`/`delegate` in the exception handling proposal (not in GC).

### Related

- `[[40-clos-and-defstruct]]` (condition types are CLOS classes)
- `[[35-type-system]]` (`typep` on condition types)
- `[[34-local-function-definition]]` (handlers are often local functions)
