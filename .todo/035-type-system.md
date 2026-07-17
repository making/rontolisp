> **Update 2026-07-05:** `deftype` is accepted as a parsed no-op (name NOT
> registered); `makeTypeTest` grew `unsigned-byte`, `function` (via the new
> `functionp` builtin), `vector`/`array`/`sequence` (via internal `%arrayp`)
> and package-stripped name matching. See `.kb/declarations-type-checks.md`.

# Type system (`typep`, `type-error-datum`, `type-error-type`, `ctypecase`, `ignore`, `ignorable`, `inline`, `notinline`, `optimize`)

**Status:** partially implemented. Medium-Low priority — the full CL type system
is large; implement the useful subset.

## What's missing

RontoLisp has type predicates (`numberp`, `integerp`, `floatp`, `stringp`, `symbolp`, `listp`, `consp`, `keywordp`, `rationalp`, `characterp`, `hash-table-p`, `arrayp` via `make-array`), the `typecase`/`ecase`/`etypecase`/`ccase` macros, `coerce`, and `check-type`. The declaration system is present as parsed no-ops. What remains absent is the dynamic type check (`typep`) and any declaration having a real effect.

### Missing type operators

| Operator | Kind | Purpose |
|----------|------|---------|
| `typep` | Function | Dynamic type check: `(typep x 'integer)` |
| `ctypecase` | Macro | Type dispatch using type specifiers (`ccase` IS implemented) |
| `type-error-datum` | Function | Accessor |
| `type-error-type` | Function | Accessor |

The `type-error` condition class itself exists (seeded in `ClosRegistry.java:46`
with `datum` / `expected-type` slots), so the data is already reachable via
`slot-value` — only the CL-named accessor functions are missing.

### Declaration system

`declare` / `declaim` / `proclaim` / `the` / `deftype` all shipped as parsed
no-ops (see `.kb/declarations-type-checks.md`) — EXCEPT the `special`
declaration, which is real: a top-level `(declaim (special ...))` /
`(proclaim '(special ...))` genuinely proclaims the name special (see
`.kb/dynamic-special-variables.md` and `[[084-dynamic-special-variable-binding]]`).
Still missing is any *effect* for `ignore`, `ignorable`, `inline`, `notinline`
and `optimize` — they parse and are discarded.

### Type specifiers supported by `typecase`

The current `typecase` implementation uses predicate names (`integer`, `float`, `string`, `symbol`, `list`, `null`, `number`, `character`, `hash-table`, `array`, `consp`, `t`). `ctypecase` would use type specifiers (`integer`, `float`, `string`, `symbol`, `list`, `null`, `number`, `character`, `array`, `cons`, `t`, `(array t (5))`, `(vector t)`, `(cons integer string)`, etc.).

### Implementation approach

For a minimal useful subset:

1. **`typep`**: Map type specifiers to checks. Start with the atomic types (`integer`, `float`, `string`, `symbol`, `list`, `null`, `number`, `character`, `array`, `cons`, `t`, `atom`, `sequence`, `real`, `rational`). Compound types like `(cons t t)` and `(array t (5))` can be deferred. `makeTypeTest` (already used by `check-type` / `etypecase`) is the natural basis.
2. **`ctypecase`**: Macro expanding to `cond` with `typep` checks, mirroring the existing `ccase`.
3. **`type-error-datum` / `type-error-type`**: thin accessors over the seeded `type-error` slots.
4. **Declaration system**: giving `optimize` / `inline` a real effect is a compiler feature, not a front-end one.

### Complexity

- `typep` is the core — `ctypecase` builds on it.
- The declarations that remain no-ops (`ignore`, `optimize`, `inline`) have no effect without compiler type checking and inlining.
- Full type specifiers (including structured types like `(vector t 10)`) are a large undertaking.

### Related

- `[[032-multiple-value-system]]`
- `[[033-sequence-and-set-extensions]]`
