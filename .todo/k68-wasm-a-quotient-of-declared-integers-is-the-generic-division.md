# k68. wasm: a quotient of declared integers is the generic division

Difficulty: Medium

Left by k56 (2026-11-20). `truncate` of two integers is no fused operation
(`.kb/wasm-int-fusion.md`, "When fusion does NOT trigger": the site's inline i31 division
beat a fused `mod`), so Java's `a / b` is `(%mask-signed-field 32 (the (integer -2^31 2^31)
(truncate a b)))`: a trusted leaf (no fallback around it) whose own evaluation is the
generic division, its float arms included, even where both operands are declared
integers -- the same for `%` as `rem` over a computed divisor.

- **Measured** (k56's build): 7 `truncate` sites in the zone reader, 4 of them in
  `ZoneChanges.rule` (2,355 B).
- **To do**: measure an `i64.div_s` (zero divisor reported as the generic division reports
  it, `MIN_VALUE / -1` past i64 for a `long` left to the generic path) for a division whose
  operands are raw or declared, against the inline i31 division, for size and for time.
