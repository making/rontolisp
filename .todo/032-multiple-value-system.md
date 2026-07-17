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
`nth-value`, secondary values for `floor`/`ceiling`/`round`/`truncate` and
`gethash`, and the floor-family divisor argument. There is NO runtime
multiple-value representation -- consumers recognize the producer form
syntactically, so a `(values ...)` tail in a user function collapses to its
primary value at the call boundary. What remains here: the runtime
representation (needed for user-function producers, e.g. split-sequence's
index value), the long tail of secondary-value built-ins below
(`parse-integer`, `read-from-string`, `member`, ...), `multiple-values-limit`,
and `(setf (values ...) ...)`.

## What's missing

RontoLisp currently has single-value returns. Functions like `floor`, `truncate`, `ceiling`, `round` (which in CL return quotient AND remainder) only return the primary value. `gethash`, `member`, `string-trim`, `read-from-string`, `parse-integer` all have secondary values in CL.

### Missing operators

| Operator | Kind | Purpose |
|----------|------|---------|
| `values` | Special form | Return multiple values: `(values 1 2 3)` |
| `multiple-value-bind` | Special form | Bind: `(mv-bind (q r) (floor 7 2))` |
| `multiple-value-list` | Function | Collect into list: `(mv-list (floor 7 2))` -> `(3 1)` |
| `multiple-value-call` | Special form | Call with all values: `(mv-call #'+ (values 1 2 3))` |
| `multiple-values-limit` | Variable | Implementation limit (constant) |
| `mtvp` | Function | (Deprecated; always signals error) |

### Functions that should return secondary values

| Function | Primary | Secondary |
|----------|---------|-----------|
| `floor` / `truncate` / `ceiling` / `round` | quotient | remainder |
| `gethash` | value | present-p |
| `member` / `member-if` | tail | (same as primary if found) |
| `string-trim` / `string-left-trim` / `string-right-trim` | trimmed | start/end indices |
| `read-from-string` | object | position |
| `parse-integer` | integer | position |
| `random` | random value | t (when called with one arg) |
| `rationalize` | simplest rational | — |
| `decimal-string` / `exhaustion` | — | — |

### Design considerations

- **Representation**: Multiple values need a runtime representation. Options:
  - (a) A dedicated `LispMultipleValues` type (array of `LispVal`).
  - (b) Convention: caller knows how many values an operator returns.
  - (c) Dynamic: return value carries count metadata.
- **JVM**: Could use `Object[]` with a sentinel, but `Object[]` is already used for cons cells and function refs. Need a new distinction (e.g., a record or specific array layout).
- **WASM GC**: A `TYPE_CELL` box holding the values array.
- **WASM scalar**: Multiple values cross the `wasm-export` boundary? Probably not (boundary is single-value).
- **Interpreter**: Easiest to implement — `LispMultipleValues` wrapper around `List<LispVal>`.
- **`setf`**: `(setf (values a b) (values x y))` expands to parallel assignment.

### Implementation approach

1. Add `LispMultipleValues` type.
2. Implement `values` special form (returns `LispMultipleValues`).
3. Implement `multiple-value-bind` (destructures into bindings).
4. Implement `multiple-value-list` and `multiple-value-call`.
5. Update existing functions (`floor`, `gethash`, etc.) to return secondary values.
6. Compilers: thread through JVM and WASM.

### Related

- `[[31-lambda-list-extensions]]` (`&whole` + multiple values is a common pattern)
- `[[35-type-system]]` (`typep`, `coerce` return multiple values in some cases)
