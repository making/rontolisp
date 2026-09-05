# Bitwise integer built-ins: the fixnum fast path

**Invariant: `logand`/`logior`/`logxor`/`lognot`/`ash`/`integer-length`/`logbitp` use
machine-word arithmetic when every operand fits the word, else the exact BigInteger
path.** Only a LEFT shift can leave the range; and/or/xor/not need no guard.

Sites: `Environment.createGlobal` (`allFixnums`); `JvmNumericRuntimeBuilder` keys
`LOGAND`/`LOGIOR`/`LOGXOR`/`LOGNOT`/`ASH`/`INTEGER_LENGTH`/`LOGBITP` + `JvmBitwiseCompiler`;
`WasmBitwiseCompiler` -> `WasmBigIntRuntimeBuilder` `_big_*` ([wasm-bignum.md](wasm-bignum.md)),
whose i31 fixnum makes `most-negative-fixnum` differ. Per-site `BigInteger.and`/`shiftLeft`
costs a Long->BigInteger->Long round trip per op (5x on SHA-256).

## Edges the fast path must keep
- `ash` count `<= -64` gives `0`, or `-1` if negative (plain `>>` masks the count to 6 bits);
  count `>= 64`, or a left shift failing `(a << s) >> s == a`, takes the BigInteger path;
  count narrowed to `int` FIRST, matching `BigInteger.shiftLeft(int)`.
- `integer-length` = `BigInteger.bitLength`; negatives `64 - numberOfLeadingZeros(~x)`.
- `logbitp` at/past the sign bit reads the SIGN; a negative index keeps the BigInteger path.
- `LispMacroExpander.expandLdb`/`expandDpb`/`expandMaskField` fold a literal byte spec,
  `(ldb (byte 32 0) n)` -> `(logand n 4294967295)`, bounded by `LITERAL_BYTESPEC_LIMIT`
  (1024). Preserve: integer form evaluated once, `dpb` emitting new-bits FIRST in the `logior`.
