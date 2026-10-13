# g02. Narrowing casts answer fixnums, the oracle answers boxes

Difficulty: High

Found while closing f90 (2026-10-13): `(byte x)`, `(short x)` and `(int x)`
answer fixnums here (`rontolisp::%clojure-byte` etc. via `%clojure-cast-bounded`),
where the oracle answers `Byte`/`Short`/`Integer`. A fixnum is what the oracle's
reflector refuses where `byte` is expected (measured clj 1.12.6:
`(.put (ByteBuffer/allocate 2) 7)` is `No matching method put found taking 1
args`), so `(.put bb (byte 7))` refuses here and answers `[7 0]` there. The
`ClojureInteropTest#anArrayJavaKeepsIsTheByteArrayItself` pin therefore writes
through `putChar` (a character unboxes on both), with the reason in its comment.

The blast radius is the value model, not the call layer: `+`, `=`, `prn`,
`aset-byte`, `byte-array` and callers of `byte-v`/`short-v` all consume fixnums
today, and every `unmarshal` (interpreter, bridge, direct sites) answers a host
`Byte` as a fixnum, so no expression can hand Java a `Byte` at all.

## Plan

1. The oracle's table for the casts first (values, equality, printing,
   arithmetic, range errors), one by one.
2. The representation change with the four-backend pins (a host `Byte` must
   still compute, print and compare as the oracle's).
3. Revisit the `putChar` pin back to `put` with `(byte 7)`.
