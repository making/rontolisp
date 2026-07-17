> **Update 2026-07-05 (split-sequence e2e, .todo/054 Phase 3):** the
> `%mv-spill` runtime channel shipped (+ `values-list` and the two-value
> `parse-integer` expansion, added for parse-number 2026-07-05) -- a `values` result in a USER function
> now reaches the caller's mv consumers on interpreter/JVM/WASM (see
> `.kb/multiple-values.md`). Remaining here: true CL semantics for non-tail
> `values` (stale-spill leak), first-class `#'values` spilling in compiled
> code, and the scalar `--no-gc` backend.

# Multiple value system (`values`, `multiple-value-bind`, `multiple-value-call`, etc.)

**Status:** the core syntactic tier shipped 2026-07-05 (Phase 3 unit 3, see
`.kb/multiple-values.md`): `values`, `multiple-value-bind`/`-list`/`-call`,
`multiple-value-setq`, `nth-value`, secondary values for
`floor`/`ceiling`/`round`/`truncate` and `gethash`, and the floor-family
divisor argument. There is NO runtime
multiple-value representation -- consumers recognize the producer form
syntactically, so a `(values ...)` tail in a user function collapses to its
primary value at the call boundary; the `%mv-spill` global
(`LispNames.MV_SPILL`) carries a user function's tail values to the caller's
consumer. Note the runtime `LispMultipleValues` type this file originally
proposed was NOT adopted -- the shipped design is the syntactic lowering plus
that spill channel.

### Functions that should return secondary values

| Function | Primary | Secondary | Status |
|----------|---------|-----------|--------|
| `floor` / `truncate` / `ceiling` / `round` | quotient | remainder | DONE (`LispMacroExpander.isMvProducerForm`) |
| `gethash` | value | present-p | DONE (same) |
| `array-displacement` | displaced-to | displaced-index-offset | DONE (same) |
| `parse-integer` | integer | position | DONE (two-value expansion) |
| `read-from-string` | object | position | missing |
| `member` / `member-if` | tail | (same as primary if found) | missing |
| `string-trim` / `string-left-trim` / `string-right-trim` | trimmed | start/end indices | missing |
| `random` | random value | t (when called with one arg) | missing |
| `rationalize` | simplest rational | — | missing |

### Remaining gaps

| Item | Notes |
|------|-------|
| `multiple-values-limit` | The implementation-limit constant is not defined. |
| Non-tail `values` | A producer that calls `values` in a NON-tail position and then returns normally leaves a stale spill behind, so the consumer's extra variables may read leftover values instead of nil (deviation from CL; see `isMvProducerForm`'s doc comment). |
| First-class `#'values` | In compiled code the `values` wrapper yields its primary value only -- it does not spill (`BuiltinFunctionWrappers`: `(&rest r)` -> `(car r)`). |
| `multiple-value-call` with builtin wrappers | Spreading values into a fixed-arity builtin wrapper does not work; the wrapper pins one arity. |
| `(setf (values ...) ...)` | Not implemented (would expand to parallel assignment). |
| `--no-gc` | No multiple-value support on the scalar backend. |

### Related

- `[[031-lambda-list-extensions]]` (`&whole` + multiple values is a common pattern)
- `[[035-type-system]]` (`typep`, `coerce` return multiple values in some cases)
