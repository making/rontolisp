# c05. The WASM runtime reader rounds a decimal float token more than once

Difficulty: Medium

Measured 2026-10-03, `(print (read-from-string s))` on WASM (Preview 1 and the component)
against the interpreter and the JVM (Java's `parseDouble`):

| token | interpreter / JVM | WASM |
| --- | --- | --- |
| `0.30000000000000004` | `0.30000000000000004` | `0.3000000000000001` |
| `1e-300` | `1.0e-300` | `9.999999999999999e-301` |
| `2.2250738585072014e-308` | `2.2250738585072014e-308` | `2.2250738585072024e-308` |
| `4.9e-324` | `4.9e-324` | `0.0` |
| `8.98846567431158e307` | `8.98846567431158e307` | `8.988465674311578e307` |
| `6.02214076e23` | `6.02214076e23` | `6.0221407599999985e23` |

`WasmReadRuntimeBuilder.emitTryFloat` accumulates the digits in f64 and scales by a power of
ten built through repeated `* 10.0` -- documented in `doc/*/guides/read-load-limitations.md`
as "can sit a few ulps from the frontend's". The module can now do it exactly: the digits as
an exact integer (`_big_grow`, as the integer classifier does) and the double nearest
`mantissa * 10^(exponent - scale)` through `_rat_to_f64` / `_big_to_f64`, both correctly
rounded since 2026-10-03 (`.kb/wasm-bignum.md`, "Ratios") -- what the Scheme and Clojure
readers' `%decimal-double` does in Lisp, with its range guard for a huge exponent.

## Plan

- Rewrite the float classifier's value path: exact mantissa, decimal exponent, then the
  guarded conversion. Keep the token grammar (exponent markers, `.5`, `5.`) unchanged.
- Measure the module size of a program that reads (the reader's float path gains the ratio
  conversion) and the speed of a float-heavy `read` loop.
- Update `doc/{en,ja}/guides/read-load-limitations.md`.

## Pin

- ci-spec: `read-from-string` of the six tokens above, all four backends.
- `WasmLispCompilerIntegrationTest`: subnormal, halfway and overflow tokens.
