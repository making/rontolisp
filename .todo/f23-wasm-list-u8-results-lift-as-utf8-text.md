# f23. A WIT list<u8> a WASM host answers lifts as UTF-8 text, so binary octets come back changed

Difficulty: Medium

`.kb/wit.md` says a `list<u8>` is "a string carrying the bytes one-per-char", but a
`--component` result lifts through `_string_from_mem` (`WasmComponentImportCompiler.emitLiftString`),
a non-validating UTF-8 decode, and a Preview 1 core module declares the member `:string`, the
same decode. Measured 2026-10-10 against `wasmtime run -S keyvalue=y`: a Clojure program
storing the byte array `[-1 0 65]` (`ff 00 41`) with `bucket.set` read back `[-9 -128 -127 -94]`:
the lift decoded `ff 00 41` into one code point (1835106), whose UTF-8 encoding
(`%clojure-bytes-from-host`) is those four octets. Octets that are valid UTF-8
round-trip; `ff`, a lone continuation byte, a truncated sequence do not. Arguments are exact on
a component (a packed vector stages raw, `emitStageBytesParam`); on Preview 1 an argument is
staged as `:string` text too.

## What decides the design

- The Common Lisp tier reads the lifted string (`page-hits.lisp` parses it, `serve.lisp` reads
  `wasi:http` `field-value`s, a `list<u8>`, as header text), so lifting every `list<u8>` to a
  packed `(unsigned-byte 8)` vector changes what every Common Lisp program reading one sees.
- An opt-in the directive carries: the Clojure lowering's `:names` table already marks its
  members; a flag on `rontolisp:wit-import` (or a per-member mark) could make
  `emitLiftString` lift through `_bytes_from_mem` for that import, which the Clojure walker
  already takes as it is (`%clojure-bytes-from-host`'s vector arm).
- Preview 1: a `:string` result is text by declaration; exact octets there need a different
  designator for a `list<u8>` member (a `:bytes` result is the read(2) shape, not a value).

## Plan

1. A `ClojureWitBoundaryTest` case storing `ff 00 41` through wasmtime's keyvalue and reading
   it back exact; the Common Lisp tier's own expectation pinned beside it.
2. The lift, behind whichever opt-in step 1's design settles.
3. `.kb/wit.md` ("`list<u8>`"), `doc/*/clojure/reference/wit.md` ("What crosses"), and
   `doc/*/reference/functions/rontolisp-wit-import.md`'s `list<u8>` row, which still says "a
   string of raw bytes (one per char)".
