# i90. A distinct `single-float`

Difficulty: High

The four float names are one type, all IEEE doubles (`.kb/declarations-type-checks.md`: "Trigger:
if a distinct single-float lands, `canonicalSubtypeName` must stop collapsing AND the lattice must
gain the real edges in the SAME pass"). CLHS 12.1.4.1 permits it; libraries that branch on
`single-float` assume a 32-bit format. Measured 2026-11-06:

- **cl-conspack loses data silently.** Its encoder's `typecase` takes the `single-float` arm first,
  so every double is written as float32: `(conspack:decode (conspack:encode 0.1d0))` answers
  `0.10000000149011612` on the interpreter (SBCL: `0.1d0`); a probe list mixing `1.5` and `2.5d0`
  encodes to 77 bytes on the interpreter and the JVM, 81 on SBCL, and decodes `2.5d0` as `2.5`. Same on any format choosing its float width by type.
- **typo (so petalisp) asserts on the lattice.** `primitive-ntype-information.lisp:63` dedups a
  type list by `alexandria:type=` and asserts no later entry is a subtype of an earlier one;
  `single-float`/`double-float`/`float` collapse into one entry. The assertion that fires first is
  `(subtypep '(unsigned-byte 63) 'fixnum)` = T: our fixnum is 64 bits (`most-positive-fixnum` =
  2^63-1; SBCL 2^62-1). Both the float collapse and the fixnum width are assumptions typo makes of
  every implementation it supports; record which one is ours to change.

Before building: list the other consumers in the Quicklisp cache (`ieee-floats`, `cl-messagepack`,
`numcl`-family, `magicl`, `static-vectors` element types) and the cost per backend of a real
single-float value (JVM `float`, WASM `f32`, array storage already has `single-float` vectors).
If the cost decides against it, the alternative is narrower: make `(typep x 'single-float)` false
for a value that does not survive a round trip through float32, and record why.
