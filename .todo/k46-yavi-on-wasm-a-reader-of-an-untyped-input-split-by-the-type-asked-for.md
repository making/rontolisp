# k46. YAVI on wasm: a reader of an untyped input split by the type asked for (the user's decision)

Difficulty: Medium

Left by k33 (2026-11-20) for the user to weigh. Nothing here is this repository's to do
until it is decided: it is a change to YAVI's own design. YAVI has the owner this project
has and may be changed where the change is right for YAVI too
(`.kb/java-frontend-provenance.md`, "A library a program uses"); a change stays a local
commit of a clone until the user says to push.

- **What**: `am.ik.yavi.core.UntypedInput` holds every type an input is read as in one
  table (`PARSERS`) and one method (`convert`, `convertNumber`). A schema of a string
  and an integer therefore reaches -- and on wasm pays for -- `java.time`, `BigDecimal`,
  `UUID`, `URI` and the `java.sql` tests. A reader chosen where the type is declared
  (`_localDate` handing over its own conversion) would reach what the schema names.
- **Measured** (`.kb/java-frontend.md`, "YAVI compiled to wasm"; the scripts and the
  program: `.todo/artefacts/k33-yavi-compiles-to-wasm-the-milestone-and-what-stands-before-it/README.md`):
  such a schema is 1,216,741 B of wasm; 951,244 (-21.8 %) over a copy of YAVI without the
  table, the `java.util.Date` / `java.sql` arm and the `Year` arm; 889,037 (-26.9 %)
  without the conversion of a number through `BigDecimal` too. These are bounds from
  cutting, not a design: a split reader still converts a `long` given for an `Integer`.
- **What it does not touch**: the regex engine, 130 KB of every YAVI program
  (`CharSequenceConstraint`'s static patterns and `VariantOptions`' `replaceAll`), and
  the 54 KB dispatcher that is this repository's own (`.todo/k45`).
- **If taken**: measure again with the scripts above, then the sizes in "YAVI compiled to
  wasm" and `examples/java/README.md` once a release has the change (the examples name
  `0.18.0`).
