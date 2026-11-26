# Condition system — residuals

**Status:** implemented except the interactive debugger and the items below.
`handler-case`, `ignore-errors`, `unwind-protect`, `signal`, `warn`,
`define-condition`/`make-condition`, the condition class hierarchy, AND the
restart layer (`handler-bind`/`restart-case`/`restart-bind`/`with-simple-restart`/
`invoke-restart`/`find-restart`/`compute-restarts`/`muffle-warning`/`abort`/
`continue`/`cerror`) are real on every backend but `--no-gc`; conditions are
CLOS-subset instances caught by type. Mechanics, per-backend details, the
restart lite deviations, and pinning tests: `.kb/error-handling.md`.

## Still missing

| Operator | Kind | Note |
|----------|------|------|
| `break` | Function | needs the interactive debugger (REPL integration) |
| `*debugger-hook*` | Variable | same |
| `condition-format-control` | Function | generic condition accessor — only the `simple-condition-` variants exist |
| `condition-format-arguments` | Function | same |
| `typep` | Function | a call-position expansion (`LispMacroExpander.expandTypep`), not a first-class function value |

The restart layer's own lite deviations (a restart's `:report` stored but never
rendered, `:interactive` never run, no condition-restart association, and
`check-type`/`assert`/`ccase`/`ctypecase` offering no `store-value` restart) are
listed in `.kb/error-handling.md`; the `store-value` half is scoped in
`[[433-the-correctable-operators-offer-no-store-value-restart]]`. They all reduce
to "there is no interactive debugger."

## `:format-arguments` is not always applied

`(warn/error 'some-condition :format-control "..." :format-arguments (list a b))`:
on the non-report-routing path the supplied `:format-control` is used VERBATIM as
the message and the arguments are dropped — `LispMacroExpander`'s
`expandSignalDesignator` carries the comment "lite: :format-arguments are carried
in the instance but not rendered into the message." The condition-report path
(`.kb/error-handling.md`, where a `simple-*` report is
`(apply #'format stream control arguments)`) renders them for the classes it
routes, so the common case is covered; the lite fallback still drops them, and a
class reached without report routing prints the raw control string (cl-postgres'
`get-warning` idiom used to surface this as
`WARNING: PostgreSQL warning: ~A~@[~%~A~]`). The fix belongs in the shared
expansion so all four backends move together: when `:format-arguments` is
supplied, build the message with `format` instead of using the control string
as-is. The two generic accessors above belong to the same slice.

### Related

- `[[035-type-system]]` (`typep` on condition types)
- `[[034-local-function-definition]]` (handlers are often local functions)
- `.kb/clos.md` (condition types are CLOS classes)
