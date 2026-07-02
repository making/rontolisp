# Type system (`typep`, `coerce`, `type-error`, `type-error-datum`, `type-error-type`, `ctypecase`, `check-type`, `declaim`, `declare`, `the`, `ignore`, `ignorable`, `special`, `notinline`, `inline`, `optimize`, `speed`, `safety`, `space`, `debug`, `compilation-speed`)

**Status:** not implemented. Medium-Low priority — the full CL type system is large; implement the useful subset.

## What's missing

RontoLisp has type predicates (`numberp`, `integerp`, `floatp`, `stringp`, `symbolp`, `listp`, `consp`, `keywordp`, `rationalp`, `characterp`, `hash-table-p`, `arrayp` via `make-array`) and `typecase`/`ecase`/`etypecase`/`ccase` macros. The dynamic type system and declaration system are absent.

### Missing type operators

| Operator | Kind | Purpose |
|----------|------|---------|
| `typep` | Function | Dynamic type check: `(typep x 'integer)` |
| `coerce` | Function | Type coercion: `(coerce 1.5 'integer)` -> `1` |
| `type-error` | Function | Create a type error condition |
| `type-error-datum` | Function | Accessor |
| `type-error-type` | Function | Accessor |
| `ctypecase` | Macro | Type dispatch using type specifiers (vs `typecase` which uses predicates) |
| `check-type` | Macro | Signal error if type doesn't match |
| `stylize` | — | (Not CL) |

### Missing declaration system

| Operator | Kind | Purpose |
|----------|------|---------|
| `declare` | Special form | Inline declarations |
| `declaim` | Macro | File-level declarations |
| `the` | Special form | Typed expression: `(the integer x)` |
| `ignore` | Declaration | Suppress unused variable warnings |
| `ignorable` | Declaration | Weaker than `ignore` |
| `special` | Declaration | Dynamic binding |
| `inline` / `notinline` | Declaration | Compilation hints |
| `optimize` | Declaration | Quality specs: `(optimize (speed 3) (safety 1))` |

### Type specifiers supported by `typecase`

The current `typecase` implementation uses predicate names (`integer`, `float`, `string`, `symbol`, `list`, `null`, `number`, `character`, `hash-table`, `array`, `consp`, `t`). `ctypecase` would use type specifiers (`integer`, `float`, `string`, `symbol`, `list`, `null`, `number`, `character`, `array`, `cons`, `t`, `(array t (5))`, `(vector t)`, `(cons integer string)`, etc.).

### Implementation approach

For a minimal useful subset:

1. **`typep`**: Map type specifiers to checks. Start with the atomic types (`integer`, `float`, `string`, `symbol`, `list`, `null`, `number`, `character`, `array`, `cons`, `t`, `atom`, `sequence`, `real`, `rational`). Compound types like `(cons t t)` and `(array t (5))` can be deferred.
2. **`coerce`**: Between the types already represented (`integer` <-> `float` <-> `string`, `list` <-> `vector`).
3. **`check-type`**: Macro expanding to `if` + `error`.
4. **`ctypecase`**: Macro expanding to `cond` with `typep` checks.
5. **Declaration system**: Start with `ignore` (no-op) and `the` (no-op without a type system in the compilers). Full `optimize` declarations are a compiler feature.

### Complexity

- `typep` and `coerce` are the core — everything else builds on them.
- The declaration system (`declare`, `declaim`, `the`) has no effect without compiler type checking.
- Full type specifiers (including structured types like `(vector t 10)`) are a large undertaking.

### Related

- `[[32-multiple-value-system]]` (`coerce` can return multiple values)
- `[[33-sequence-and-set-extensions]]` (`coerce` for sequence-to-sequence conversion)
