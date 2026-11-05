# i50. Decide whether the Proxy-Wasm SDK becomes a built-in package

Difficulty: Medium

A decision first, then the work if the answer is yes. Today a filter keeps a copy of the SDK beside its source and
says `(require :proxy-wasm "proxy-wasm.lisp")`. Built in, it would say `(require :proxy-wasm)`, or only name the
package, as a program names `linalg` or `rontolisp:json-parse`.

## What exists

`examples/proxy-wasm/`, all of it checked by `examples/proxy-wasm/check.sh` (needs Docker; not in CI):

- `proxy-wasm.lisp`: the SDK, about 700 lines, ABI v0.2.1. One `rontolisp:wasm-import` per host function, the
  `proxy_on_*` exports, the CLOS API. Covers logging, the clock, ticks, header maps, buffers, local responses, outgoing
  HTTP calls, shared data, metrics and properties. Not covered: gRPC calls, shared queues, foreign functions, the TCP
  (network filter) callbacks.
- `gateway.lisp`: a filter using most of that. Measured 2026-11-05 with `--no-wasi --optimize`: 234,589 bytes raw,
  77,610 gzip.
- Run only against Envoy v1.39.3 and v1.40.2 on V8, wasm-GC `--no-wasi`. The `--no-gc` backend cannot host it: no CLOS, no `:octets`.

The compiler half is already general and stays whatever this decides: the reserved import module
`"rontolisp:memory"` and `:u32` on wasm-GC imports (`.kb/wasm-import.md`).

## For and against

For: a filter is one file, and the SDK cannot drift from the compiler. The `:u32` and out-pointer rules are then
nobody's to get right twice.

Against: it is the first built-in package tied to one host ABI, with an API that one filter has exercised. Every name
in it becomes a compatibility promise, and each new ABI version or host quirk becomes the compiler's to track. The two
Envoy behaviours the SDK works around (`examples/proxy-wasm/README.md`) are host behaviour, not ABI, and were measured
on those two versions only. A source file in `examples/` can be copied and edited; a built-in cannot.

Weigh it by evidence not available yet: a second real filter, another host (no other Proxy-Wasm host has been tried),
and whether the API survives both unchanged. If that has not happened, staying an example is a sound answer, and this
item closes as cancelled.

## If the answer is yes

The mechanism to follow is a library spliced by name: `JsonLibrary` and `LinalgLibrary` in
`src/main/java/am/ik/rontolisp/eval/`, with sources under `src/main/resources/am/ik/rontolisp/eval/`, and
`.kb/load-inliner.md` and `.kb/library-defun-pruning.md` for how they reach a compiled program. What this library
adds and none of those has, to check before anything else:

- **Directives inside a spliced library.** The `wasm-import` and `wasm-export` forms must be seen by the compiler's
  directive collection after the splice. `CompileFrontend.expand` is the one place the order is stated.
- **Pruning against roots.** Every `proxy_on_*` export must survive `LibraryDefunPruner` and the tree shaker although
  the filter names none of them, and an export the filter never specializes must still answer the host. The ABI marker
  export `proxy_abi_version_0_2_1` is called by nothing.
- **The package name.** `PackageRegistry.isBuiltinPackageName` and
  `PackageRegistryTest#everySeededPackageIsABuiltinPackageName` fix what seeding means. A program defining its own
  `proxy-wasm` package, as `examples/proxy-wasm/` does now, has to keep working or fail by name.
- **The other backends.** On the interpreter and JVM each directive is a stub that signals when called, so a filter
  loads and its pure logic can be tested there. Keep that; it is the cheapest test a filter author has.

Also `.kb/adding-primitives.md`, reference pages for every exported name in `doc/en` and `doc/ja` (mirrored, with
byte-identical code blocks, `.kb/documentation-site.md`), and the example reduced to the filter.

## Verified means

`examples/proxy-wasm/check.sh` still passes all 23 checks with the filter's `require` changed, and `gateway.lisp`
measures no larger than the numbers above. A size increase is a defect in the splice: the example already pays for the
whole SDK.
