# 57: Phase 3 unit 3 -- multiple values (core)

Part of the ASDF Phase 3 split (see `.todo/54-asdf-support.md`, "Phase 3"
section). Wishlist source: `.todo/32-multiple-value-system.md` (this unit is
the core subset; the long tail of secondary-value-returning built-ins stays
in 32). This is the deepest unit -- plan a full session, likely with an
EnterPlanMode design pass first.

## Scope (v1)

- `values`, `multiple-value-bind`, `multiple-value-list`, `nth-value`,
  `multiple-value-call` (in rough order of library usage).
- Secondary values for the highest-usage built-ins only: `floor`/`truncate`
  /`ceiling`/`round` (quotient + remainder) and `gethash` (value +
  present-p). Everything else stays single-value (tracked in 32).
- Single-value contexts discard extra values; a zero-values `(values)` in a
  value context reads as nil (CL semantics).

## Design constraints (from 32 + current architecture)

- Interpreter: a `LispMultipleValues` wrapper is easy, but it must NEVER leak
  into single-value data structures -- collapse to the primary value at every
  non-mv consumer boundary.
- JVM: `Object[]` is taken (cons + funcref). Options: a dedicated record
  type, or expansion-level lowering (see below).
- WASM GC: a struct type or the same lowering.
- **Cheapest viable v1**: expansion-level protocol -- `(values a b c)` in an
  mv-receiving position is recognized SYNTACTICALLY by
  `multiple-value-bind`/`-list`/`-call` when the producer form is a literal
  `values` call or a known two-value built-in (floor etc. get internal
  `%floor2`-style helpers returning a list). A general dynamic mv return from
  arbitrary user functions is what actually needs the runtime representation;
  check how far split-sequence gets with the syntactic tier before paying for
  the runtime one.

## Wiring checklist

Names/registry/expander/evaluator/compilers/FreeVarAnalyzer as usual;
list-macros + list-functions expectations + ci-spec introspection; docs
(en+ja) + catalog; `.kb/multiple-values.md`; native E2E.
