# WIT import on a component: a `list<T>` parameter (wasi:keyvalue's `batch`)

Difficulty: Medium

Under `--component` an imported function taking a `list<T>` (T other than `u8`) is a compile error
(`WitImportDirective.validateComponentParam`: "does not cross the component import boundary as a
parameter yet"), though the interpreter and the JVM bind it and the same types cross as a result
(`emitLiftList`). That leaves out the whole of `wasi:keyvalue`'s `batch`: `get-many` and `delete-many`
take a `list<string>`, `set-many` a `list<tuple<string, list<u8>>>`.

What it costs, measured 2026-11-10: a counter API (a count per id, listed sorted by count) compiled by
`e7549b2` with `--component`, importing `wasi:keyvalue@0.2.0-draft2`'s `store` and `atomics` (one `.wit`
of the package's interfaces; `atomics` takes `store`'s `bucket` through `use`), served by a sluice wasi
wicket (`sluice-wicket-runtime-wasi`, which links draft2's `store`, `atomics` and `batch`). Everything it
needs crosses but `batch`, so its listing is 8 `list-keys` pages and a `get` per key:

| 774 keys | wicket's sqlite store | a remote store (its `gemfire`, a LAN cluster; a debug wicket) |
| --- | --- | --- |
| listing (8 pages, 774 `get`s) | 0.026 s | 7.1-7.7 s, about 9 ms a call |
| import (774 `set`s) | | 7.2 s |

With `get-many` a page the listing is 16 calls, the import one `set-many`. The wicket's request budget is
10 s by default: at that rate the listing fails past about 1,000 keys.

## Goal

A component calls `batch`'s `set-many`, `get-many` and `delete-many` with proper lists. Done when a program
doing so prints under `wasmtime run -S keyvalue` what it prints on the interpreter and the JVM with a
provider (Preview 1 carries no rich type and stays out), and the docs no longer list `list<T>` as a
result only.

## Direction

- `WasmComponentImportCompiler`: `emitLowerParam`'s `ListOf` arm walks the proper list for its length,
  allocates `length * size` at the element's alignment on the bump heap as `emitStageBytesParam` stages a
  byte string (`emitStagingEpilogue` pops it after the call), stores each element with `emitLowerAt`
  (which already stores a string, `list<u8>`, record, tuple, variant, option and handle at an address) at
  a base advanced by the element's size (its offset is static), and passes the flat `(ptr, len)`.
  `emitLowerAt`'s own `ListOf` arm, a list inside an element, does the same. The mirror of
  `emitLiftList`. The loop's index and base come from the i32 scratch pool, sized as the other shapes'
  are.
- `validateComponentParam`: the `ListOf` arm validates the element as a parameter instead of refusing
  everything but `u8`.
- Decide whether a vector is taken too, as `list<u8>` takes an `(unsigned-byte 8)` vector; the settled
  representation stays a proper list.
- The test: wasmtime 50's `-S keyvalue` links `wasi:keyvalue/batch@0.2.0-draft` beside `store` and
  `atomics` (the version `examples/wit/keyvalue` vendors), with the same list types.
- Docs: `doc/en` + `doc/ja` `rontolisp-wit-import.md` (the `list<T>` row, "Two do not", the Limitations
  bullet), `.kb/wit.md` (the type tiers, "Rich PARAMETERS across the component boundary").
