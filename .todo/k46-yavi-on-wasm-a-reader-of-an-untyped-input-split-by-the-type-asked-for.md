# k46. YAVI on wasm: a reader of an untyped input split by the type asked for (the user's decision)

Difficulty: Medium

Left by k33 (2026-11-20). The user took it on 2026-11-20 on the condition that YAVI's public
API stays the same (no PR otherwise). Worked the same day and stopped before any change:
**the split needs a new public (or protected) member of YAVI**, so it is back with the user.

- **What**: `am.ik.yavi.core.UntypedInput` holds every type an input is read as in one
  table (`PARSERS`) and one method (`convert`, `convertNumber`). A schema of a string
  and an integer therefore reaches -- and on wasm pays for -- `java.time`, `BigDecimal`,
  `UUID`, `URI` and the `java.sql` tests.
- **Measured** (`.kb/java-frontend.md`, "YAVI compiled to wasm"; the scripts and the
  program: `.todo/artefacts/k33-yavi-compiles-to-wasm-the-milestone-and-what-stands-before-it/README.md`):
  such a schema is 1,144,352 B of wasm (after k45); 951,244 (-16.9 %) without the table,
  the `java.util.Date` / `java.sql` arm and the `Year` arm; 889,037 (-22.3 %) without the
  conversion of a number through `BigDecimal` too. Bounds from cutting, not a design.
- **Why it needs public API** (YAVI `develop` at `5949ecd8`): the callers that know the
  type are outside `core`: `builder/ArgumentDefinition.value(name, type, validator)` (every
  generated `_localDate(name, ..)` etc.) and `arguments/DefaultArguments1Validator.readingWith`
  (each `IntegerValidator`, `LocalDateValidator` .. passes its `Class` to the protected
  constructor). Their only way into `core` is the public `ValueValidator.untyped(Class)`,
  which must keep converting any type at run time, so reaching it reaches every conversion.
  YAVI has no `module-info` (every public member is API), and `PackageCycleArchTest`
  forbids `core` depending on `arguments`. Without a new member the per-type conversions
  could only be restated in `arguments` beside `core`'s (the same rules twice): not taken.
- **For the user to choose**:
  1. A public seam in `core`, e.g. `ValueValidator.untyped(Class<T>, Function<Object, ? extends @Nullable T>)`
     (the conversion of a non-null value, `null` for a mismatch; also useful to a user reading
     their own type from a string, which the doc now sends to `Object.class`), with
     `UntypedInput`'s conversions split into package-private per-type functions that
     `untyped(Class)` picks and the type-specific callers name. Then PR to `develop`,
     measure again, record in "YAVI compiled to wasm".
  2. Leave YAVI as is and close this item.
- **What it does not touch**: the regex engine, 130 KB of every YAVI program
  (`CharSequenceConstraint`'s static patterns and `VariantOptions`' `replaceAll`).
- **If taken**: the sizes in "YAVI compiled to wasm" now; `examples/java/README.md` once a
  release has the change (the examples name `0.18.0`).
