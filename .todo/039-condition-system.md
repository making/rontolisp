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
> the interpreter/JVM. See `.kb/error-handling.md`.
>
> **Update 2026-07-14:** WASM catching shipped too, via the wasm exception
> handling proposal (`try_table`/`throw`, `-W exceptions=y`, wasmtime 37+),
> EH-mode gated so a program without a catching form stays byte-identical.
> Only `--no-gc` rejects `unwind-protect`/`handler-case`/`ignore-errors` at
> compile time. The catalog below is superseded by the residual list.

# Condition system — residuals

**Status:** implemented except for the restart layer and the items below.
`handler-case`, `ignore-errors`, `unwind-protect`, `signal`, `warn`,
`define-condition`/`make-condition` and the condition class hierarchy are real
on every backend but `--no-gc`; conditions are CLOS-subset instances and are
caught by type. Mechanics, per-backend details and pinning tests:
`.kb/error-handling.md`.

## Still missing

| Operator | Kind | Note |
|----------|------|------|
| `handler-bind` | Macro | `.kb/error-handling.md` names it Phase 4 with `restart-case` |
| `invoke-restart` | Function | the restart layer, none of which exists |
| `with-simple-restart` | Macro | restart layer |
| `cerror` | Function | restart layer (continuable error) |
| `abort` | Function | restart layer |
| `continue` | Function | restart layer |
| `break` | Function | restart layer + REPL integration |
| `muffle-warning` | Function | restart layer (the `warning` restart) |
| `condition-format-control` | Function | condition accessor |
| `condition-format-arguments` | Function | condition accessor |
| `typep` | Function | exists only as the compile-time type test, not as a function |

`restart-case` exists (`LispNames.java:1129`) but only as a lite lowering to its
primary form: the restart clauses are dead code, reachable only through an
`invoke-restart` that does not exist. A signaling primary form signals as usual.

The restart layer is the lowest-priority piece and is deeply intertwined with
the condition system; `.todo/116-error-handling-foundation.md` records the
Step-0 survey that deferred it (no library-side `invoke-restart` in the
motivating corpus; the real gate is Postmodern proper).

### Related

- `[[035-type-system]]` (`typep` on condition types)
- `[[034-local-function-definition]]` (handlers are often local functions)
- `.kb/clos.md` (condition types are CLOS classes)
