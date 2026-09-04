# Bitwise integer built-ins: the fixnum fast path

**Invariant: `logand`/`logior`/`logxor`/`lognot`/`ash`/`integer-length`/`logbitp` use
machine-word arithmetic when every operand fits the word, else the exact
arbitrary-precision path.** Exactness unchanged; a `(unsigned-byte 32)` loop must not
allocate. 64-bit two's complement agrees with infinite two's complement, so
and/or/xor/not need no guard; only a LEFT shift can leave the range.

## Per backend
- Interpreter (`Environment.createGlobal`): test `LispInteger` (the `long` case;
  `LispBigInteger` only holds magnitudes outside it, [core-representation.md](core-representation.md)),
  else `BigInteger`. `allFixnums` covers the variadic three.
- JVM (`JvmNumericRuntimeBuilder`, keys `LOGAND`/`LOGIOR`/`LOGXOR`/`LOGNOT`/`ASH`/
  `INTEGER_LENGTH`/`LOGBITP`): one helper per operator guarded by `instanceof Long`;
  `JvmBitwiseCompiler` emits one `invokestatic`. Trap: inlining `BigInteger.and`/
  `shiftLeft` per site costs a Long->BigInteger->Long round trip each op (5x on SHA-256).
- WASM: `WasmBitwiseCompiler` calls `WasmBigIntRuntimeBuilder`'s `_big_*` helpers with
  their i64 fast path ([wasm-bignum.md](wasm-bignum.md)). Its fixnum is i31, so
  `most-negative-fixnum` and its `integer-length` legitimately differ there.

## `ash` edges the fast path must keep
- Count `<= -64`: result `0` (or `-1` if negative); plain `>>` masks the count to 6 bits.
- Count `>= 64`, or left shift failing `(a << s) >> s == a`: BigInteger path.
- Count narrowed to `int` FIRST, matching the fallback `BigInteger.shiftLeft(int)`.
- `integer-length` is `BigInteger.bitLength` (sign excluded): negatives measure the
  complement, `64 - numberOfLeadingZeros(~x)`.
- `logbitp` at/past the sign bit reads the SIGN; a negative index keeps the BigInteger
  path so it still signals.

## Literal byte specifiers fold at expansion time
`LispMacroExpander.expandLdb`/`expandDpb`/`expandMaskField` fold a literal byte spec --
`(byte 32 0)` or its `(list 32 0)` lowering -- to a constant mask:
`(ldb (byte 32 0) n)` -> `(logand n 4294967295)`. Shared expander, all four backends.
Must preserve (evaluation-order probes pin both): the integer form evaluated exactly
once, and `dpb` emitting new-bits FIRST in the `logior` so newbyte precedes integer as
in the general `let*` order. `LITERAL_BYTESPEC_LIMIT` (1024) bounds size and position.
