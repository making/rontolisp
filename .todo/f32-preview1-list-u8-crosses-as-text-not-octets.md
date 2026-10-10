# f32. A Preview 1 core module crosses a WIT list<u8> as UTF-8 text, so octets that are no UTF-8 change

Difficulty: Medium

A `--component` binding lifts a `list<u8>` exact under `rontolisp:wit-import :octets t`
(`.kb/wit.md`, "`list<u8>` = a string"). A Preview 1 / `--no-gc` core import declares the
member `:string`, so both directions are text: the Clojure tier sends a byte array as its octets
decoded as UTF-8 (`%clojure-bytes-to-text`, JDK replacement) and reads the host's text back as
its UTF-8 encoding; `:octets t` there is refused (`WitImportDirective.refuseOctets`).

## What decides the design

- A value designator for octets both ways. `:bytes` is a parameter `(ptr,len)` of raw octets
  already, but its RESULT is the read(2) shape (caller-passed buffer, length answered), not a
  value; a `list<u8>` result needs the `:string` shape (host reserves through
  `__ronto_alloc`, wrapper lifts) with `_bytes_from_mem` instead of `_str_from_mem`.
- Every consumer of a boundary type follows: `WasmImportCompiler` (incl. `--reentrant` park
  blocks), `NoGcWasmCompiler`, `HostGlueEmitter` (a `Uint8Array` to and from the host),
  `--host-boundary`, the JVM export runtime if the designator is shared with `wasm-export`.
- Byte identity: a P1 program without `:octets t` must stay byte-identical.

## Plan

1. Failing test: `ClojureWitBoundaryTest` under node, a byte array `[-1 0 65]` to the host and
   back exact (today `aListOfOctetsCrossesAPreview1ModuleAsItsUtf8TextUnderNode` pins text).
2. The designator and its consumers; `WitImportDirective` lowers a `list<u8>` member to it
   under `:octets t` instead of refusing; the Clojure lowering drops `TEXT_BYTES`.
3. `.kb/wit.md`, `.kb/clojure-frontend.md` ("Host boundary"), `doc/*/reference/functions/rontolisp-wit-import.md`
   (`:octets`), `doc/*/clojure/reference/wit.md`.
