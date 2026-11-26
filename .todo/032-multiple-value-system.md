# Multiple value system (`values`, `multiple-value-bind`, `multiple-value-call`, etc.)

**Status:** the core shipped — `values`, `multiple-value-bind`/`-list`/`-call`,
`multiple-value-setq`, `nth-value`, `(setf (values …) …)`, and secondary values
for the `floor`-family/`gethash`/`array-displacement`/`parse-integer` producers —
as a SYNTACTIC lowering with a `%mv-spill` runtime channel carrying a user
function's tail values across the call boundary. There is NO runtime
multiple-value representation: consumers recognize the producer form syntactically,
and the spill global is what crosses the boundary. Full mechanics, the spill
protocol, the REPL-echo consumer, and the documented deviations:
`.kb/multiple-values.md`.

## Remaining

- **`multiple-values-limit`** — the implementation-limit constant is not defined.
- **Non-tail `values` and first-class `#'values`** — a `values` call nobody
  consumes leaks its spill into the next consumer (a consumer clears only what it
  takes), and the compiled `#'values` wrapper yields the primary only. Both are
  the same gap — a runtime multiple-value carrier — and are scoped in
  `[[213-a-values-call-in-non-tail-position-leaks-into-the-callers-values]]`.
- **`multiple-value-call` over a fixed-arity builtin wrapper** — spreading values
  into a fixed-arity wrapper does not work; the wrapper pins one arity (the
  naturally-variadic ops have `&rest` wrappers). Documented in
  `.kb/multiple-values.md`.
- **`--no-gc`** — no multiple-value support on the scalar backend: it has no
  reference globals for the spill channel and keeps the pure single-value
  expansion (`expandValuesPrimary`).

## Secondary values still returned single-valued

The concrete inventory of CL built-ins whose secondary value we do not yet return
(`read-from-string`'s stop index, `subtypep`'s valid-p, `decode-universal-time`'s
nine values, …) plus the REPL-vs-SBCL diff harness lives in
`[[214-cl-builtins-with-secondary-values-that-we-return-single-valued]]`; the
`string-trim` family's start/end indices and `random`'s `t` are the same kind of
gap. Check `[[213-a-values-call-in-non-tail-position-leaks-into-the-callers-values]]`
before hand-rolling any of them — if the carrier lands first they become ordinary
multi-value returns.

### Related

- `[[031-lambda-list-extensions]]` (`&whole` + multiple values is a common pattern)
- `[[035-type-system]]` (`coerce` returns multiple values in some cases)
