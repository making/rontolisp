# f12. Clojure: byte arrays across the wasm boundary

Difficulty: Medium

A Clojure byte array (`.kb/clojure-frontend.md`, "Byte arrays") is `(:C%BYTES octets)` over the
`(unsigned-byte 8)` vector a `:bytes` crossing transfers, but neither boundary converts one:

- `rontolisp.wasm`: `(wasm/defimport f {:params [:bytes]})` is refused while lowering
  (`ClojureWasmLowering.designated`, `:bytes does not cross from Clojure yet`), so a Clojure
  program cannot declare an import or export that takes or answers octets.
- `rontolisp.wit`: a `list<u8>` is "spelled alike" (`clojure.lisp`, "rontolisp.wit"), so the
  boundary's octet vector reaches the program as itself, which Clojure reads as a vector of
  numbers 0-255, and a byte array handed out is the wrapper list.

## What decides the design

- A `Crossing.BYTES` (`toHost`: a byte array's octets, anything else refused as the oracle's
  cast to `byte[]`; `fromHost`: `%clojure-bytes-of` over the vector), and a producer the
  byte-array family reads in the wrapper, so a program declaring one keeps the arms.
- Whether a WIT `list<u8>` becomes a byte array both ways (the descriptor walker's `NIL` case
  splits off a `:BYTES` one) -- a change to what a program reading one today sees.

## Plan

1. `ClojureWasmBoundaryTest` and `ClojureWitBoundaryTest` cases for both directions on both
   wasm backends.
2. The crossing, then the WIT descriptor.
3. `doc/*/clojure/reference/wasm.md`, `wit.md`; `semantics.md`'s `:bytes` row goes.
