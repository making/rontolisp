# WASM float printing: no more traps; the remaining gap is E-notation SHAPE parity

Discovered 2026-07-03 while adding `rontolisp:json-parse` (a 13-digit JSON
integer parses to a float on every backend, but printing it trapped on WASM).

**2026-07-10 (todo-108 group C): the trap class is FIXED.** `buildPrintF64Core`
now takes the sign from the bit pattern (`-0.0` prints as `-0.0`), prints
`Infinity` / `-Infinity` / `NaN` as text, extracts the integer part through an
i64 MSD digit loop up to 2^63, and above 2^63 normalizes into [1, 10) and
appends `E<exp>` (approximate — each /10 rounds). Covered by
`WasmLispCompilerIntegrationTest.floatPrinterHandlesSignedZeroInfinityNanAndLargeMagnitudes`
and the `ieee-float-*` ci-spec cases.

What REMAINS of this todo is cosmetic cross-backend parity of the printed
SHAPE for large finite floats:

```lisp
(print (* 1.5 (expt 10.0 12)))
; interpreter/JVM: 1.5E12          (Java shortest round-trip, E-form from 1e7)
; WASM:            1500000000000.0 (all digits, exact integer part, up to 2^63)
```

**The SMALL end is worse than cosmetic (measured 2026-08-08).** The same printer
has no E-form below 1 either, and it does not fall back to more digits -- it
just runs out:

```lisp
(princ 1e-8)
; interpreter/JVM: 1.0E-8
; WASM:            0.0        <-- a non-zero value prints as zero
(princ (/ 1.0 3))
; interpreter/JVM: 0.3333333333333333
; WASM:            0.333333
```

so the fraction loop stops at six places and anything smaller than that rounds
to `0.0`. Whatever emitter closes the large end has to cover this at the same
time; a shortest-round-trip emitter does both, a "Java-shaped E-form from 1e7"
patch would only do half.

The ci-spec case `ieee-float-large-magnitude-printing` pins today's split via
`expectedByBackend`. Closing it means switching the WASM printer to exponent
normalization with shortest-round-trip digits (a Ryu/Grisu-style emitter, or at
least Java-shaped E-form from 1e7 with trailing-zero trimming) — then collapse
that ci-spec case to a single expected value. Also affects
`rontolisp:json-stringify` output shape (documented in
`doc/{en,ja}/compiling/wasm.md` and `.kb/json.md`).
