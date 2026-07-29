> **Update 2026-07-05:** lite stopgaps shipped for the load-time idioms:
> `define-condition` is a parsed no-op and `make-condition` expands to its
> `:format-control` value (so `(error (make-condition ...))` signals with the
> intended message). The real condition system remains below.
>
> **Update 2026-07-11:** the concrete engineering plan now lives in
> todo-116 (unwind-protect + typed conditions
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

> **Update 2026-07-29:** the RESTART layer shipped (`.todo/196`): `handler-bind`,
> `restart-case`, `restart-bind`, `with-simple-restart`, `invoke-restart`,
> `find-restart`, `compute-restarts`, `restart-name`, `muffle-warning`, `abort`,
> `continue` and a continuable `cerror` are real on every backend but `--no-gc`.
> Mechanics: the "Phase 4" section of `.kb/error-handling.md`.

# Condition system — residuals

**Status:** implemented except for the interactive debugger and the items below.
`handler-case`, `ignore-errors`, `unwind-protect`, `signal`, `warn`,
`define-condition`/`make-condition`, the condition class hierarchy AND the
restart layer are real on every backend but `--no-gc`; conditions are
CLOS-subset instances and are caught by type. Mechanics, per-backend details and
pinning tests: `.kb/error-handling.md`.

## Still missing

| Operator | Kind | Note |
|----------|------|------|
| `break` | Function | needs the interactive debugger (REPL integration) |
| `*debugger-hook*` | Variable | same |
| `condition-format-control` | Function | condition accessor |
| `condition-format-arguments` | Function | condition accessor |
| `typep` | Function | exists only as the compile-time type test, not as a function |

Beyond the operator table, the restart layer's own lite deviations (a restart's
`:report` is stored but never rendered, `:interactive` never runs, restarts are
not associated with conditions, `check-type`/`assert`/`ccase` offer no
`store-value` restart) are listed in `.kb/error-handling.md` and on the doc
pages. They all reduce to "there is no interactive debugger".

## `:format-arguments` is never applied (measured 2026-07-27)

`(warn 'some-condition :format-control "..." :format-arguments (list a b))` --
and the `error` spelling of it -- renders the CONTROL STRING VERBATIM: the
arguments are dropped and the tildes are never expanded. The condition
designator expansion takes "a supplied `:format-control` for `simple-*`-style
classes" as the message and stops there (`.kb/error-handling.md`), so nothing
calls `format`.

Reproduce with a fresh database and any `drop table if exists` through
cl-postgres, whose `get-warning` uses exactly this idiom: the server NOTICE
prints as

```
WARNING: PostgreSQL warning: ~A~@[~%~A~]
```

instead of the message and detail it carries -- the diagnostic is not merely
ugly, it is entirely absent. The fix belongs in the shared expansion
(`LispMacroExpander`), so all four backends move together: when
`:format-arguments` is supplied, build the message with `format` instead of
using the control string as-is. Both accessors in the table above
(`condition-format-control` / `condition-format-arguments`) belong to the same
slice.

The restart layer is DONE (`.todo/196`, 2026-07-29). What survives of the old
"no restarts" caveat is only the debugger-shaped part: `check-type`/`assert`/
`ccase` still signal without establishing a `store-value` restart, so their
places lists stay decorative.

### Related

- `[[035-type-system]]` (`typep` on condition types)
- `[[034-local-function-definition]]` (handlers are often local functions)
- `.kb/clos.md` (condition types are CLOS classes)
