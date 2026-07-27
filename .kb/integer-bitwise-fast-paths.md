# Bitwise integer built-ins: the fixnum fast path

**Invariant: `logand` / `logior` / `logxor` / `lognot` / `ash` /
`integer-length` / `logbitp` must answer with machine-word arithmetic when every
operand fits the word, and fall back to the exact arbitrary-precision path
otherwise.** The exactness contract is unchanged -- results are never truncated,
`(ash 1 256)` is still the full 78-digit integer -- but a `(unsigned-byte 32)`
loop must not allocate.

64-bit two's complement agrees with an infinite two's-complement representation
on every value a `long` can hold, so `and`/`or`/`xor`/`not` need no guard at all;
only a LEFT shift can leave the range, and it is taken only when the result
shifts back to the input.

Per backend:

- **Interpreter** (`Environment.createGlobal`): each builtin tests its arguments
  for `LispInteger` (the `long` case; `LispBigInteger` only ever holds a
  magnitude outside that range, see [core-representation.md](core-representation.md))
  and uses the Java operator, else `BigInteger`. `allFixnums` covers the variadic
  three.
- **JVM** (`JvmNumericRuntimeBuilder`, keys `LOGAND`/`LOGIOR`/`LOGXOR`/`LOGNOT`/
  `ASH`/`INTEGER_LENGTH`/`LOGBITP`): one runtime helper per operator, guarded by
  `instanceof Long`. `JvmBitwiseCompiler` emits a single `invokestatic` to it --
  it used to inline `BigInteger.and`/`shiftLeft`/... at every call site
  unconditionally, so a `(ldb (byte 32 0) x)` cost a Long -> BigInteger -> Long
  round trip per operation. Worth **5x** on ironclad's SHA-256 (86% of the
  profile was `java.math.BigInteger`).
- **WASM**: already correct before this work -- `WasmBitwiseCompiler` calls the
  `_big_*` helpers of `WasmBigIntRuntimeBuilder`, which keep an i64 fast path and
  switch to limbs only past it ([wasm-bignum.md](wasm-bignum.md)). Note the
  backend's fixnum is i31, so `most-negative-fixnum` (and therefore its
  `integer-length`) legitimately differs there; that is not a fast-path artifact.

## `ash` edge cases the fast path must keep

- Count `<= -64`: the value shifts out entirely, leaving `0` (or `-1` when
  negative). A plain `>>` would mask the count to 6 bits and answer wrongly.
- Count `>= 64`, or a left shift whose result does not satisfy
  `(a << s) >> s == a`: overflow, so the BigInteger path.
- The count is narrowed to an `int` BEFORE anything else, because the fallback
  (`BigInteger.shiftLeft(int)`) always did, and the two paths must agree.
- `integer-length` is `BigInteger.bitLength`: the minimal two's-complement width
  excluding the sign, so a negative value measures its complement
  (`64 - numberOfLeadingZeros(~x)`).
- `logbitp` with an index at or past the sign bit reads the SIGN; a negative
  index keeps the BigInteger path so it still signals.

## Literal byte specifiers fold at expansion time

`LispMacroExpander.expandLdb` / `expandDpb` / `expandMaskField` recognise a byte
specifier written with literal integers -- `(byte 32 0)`, or the `(list 32 0)` it
lowers to -- and emit the mask as a CONSTANT: `(ldb (byte 32 0) n)` becomes
`(logand n 4294967295)`. The general shape builds a bytespec list and two `let*`
scopes and recomputes `(- (ash 1 size) 1)` on every evaluation: ~25 interpreted
nodes where the folded form is 3. Shared expander, so all four backends get it.

Two properties the fold must preserve, both pinned by evaluation-order probes:

- The integer form is still evaluated exactly ONCE.
- `dpb` puts the new-bits operand FIRST in the emitted `logior` so the newbyte
  form is still evaluated before the integer form, matching the general
  expansion's `let*` order (`logior` over integers does not care about operand
  order).

`LITERAL_BYTESPEC_LIMIT` (1024) bounds size and position, so a pathological
`(byte 1000000000 0)` falls to the general path instead of building a gigabyte
constant.
