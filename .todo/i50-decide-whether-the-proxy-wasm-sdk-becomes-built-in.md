# i50. Decide whether the Proxy-Wasm SDK becomes a built-in package

Difficulty: Medium

Decided 2026-11-05 by the project owner: the SDK becomes built in, once the optimization of it settles. Do not start
while that is in progress, because its API is still changing, and do not close this item as cancelled. What remains
open is which SDK or SDKs, and the "For and against" below is the background to that.

Today a filter keeps a copy of the SDK beside its source and
says `(require :proxy-wasm "proxy-wasm.lisp")`. Built in, it would say `(require :proxy-wasm)`, or only name the
package, as a program names `linalg` or `rontolisp:json-parse`.

## What exists

`examples/proxy-wasm/`, all of it checked by `examples/proxy-wasm/check.sh` (needs Docker; not in CI):

- `proxy-wasm.lisp`: the SDK, about 950 lines, ABI v0.2.1. One `rontolisp:wasm-import` per host function, the
  `proxy_on_*` exports, `define-filter` (the `defmethod` API went, `.kb/proxy-wasm-sdk.md`). Covers logging, the clock,
  ticks, header maps, buffers, local responses, outgoing HTTP calls, shared data, metrics and properties. Not covered:
  gRPC calls, shared queues, foreign functions, the TCP (network filter) callbacks.
- `gateway.lisp`: a filter using most of that. Measured 2026-11-05 with `--no-wasi --optimize=size`: 73,789 bytes raw,
  28,692 gzip.
- Run against Envoy v1.40.2 on V8 and Sluice on Endive. The `--no-gc` backend cannot host it: no conditions, no `:octets`.

The compiler half is already general and stays whatever this decides: the reserved import module
`"rontolisp:memory"` and `:u32` on wasm-GC imports (`.kb/wasm-import.md`).

A second SDK now exists, `examples/proxy-wasm/no-gc/` (`.kb/proxy-wasm-no-gc.md`): the same ABI as macros for
`--no-gc`, with `http_body`, `http_headers` and `test_plugin` on it at 2 to 7KB, checked in Envoy and against Sluice's suite.
Weigh it here:

- Both have the one form now, and a filter with no state and only what both have may compile on either: `:root` and `:stream` are
  this one's state, `(:state ...)` that one's, and a body there spells `:continue` where the clause can see it. So "the" built-in
  SDK is a choice between them, or two packages with one name and an answer for which `(require :proxy-wasm)` means, by backend.
- Against the size argument: on `--no-gc`, an SDK kept in a file already costs a filter nothing it does not call.
- It adds to the evidence asked for below: a second host (Sluice), and a third filter. It did not leave the API unchanged.
  What it changed is the part `--no-gc` cannot have.
- It makes the "built in" list longer: this SDK needs `define-filter` in the formatter's table, and that is there now.

## For and against

For: a filter is one file, and the SDK cannot drift from the compiler. The `:u32` and out-pointer rules are then
nobody's to get right twice.

Against: it is the first built-in package tied to one host ABI, with an API that one filter has exercised. Every name
in it becomes a compatibility promise, and each new ABI version or host quirk becomes the compiler's to track. The two
Envoy behaviours the SDK works around (`examples/proxy-wasm/README.md`) are host behaviour, not ABI, and were measured
on those two versions only. A source file in `examples/` can be copied and edited; a built-in cannot.

Evidence to look at when this starts: a second real filter, another host, and whether the API has stopped changing.

## The work

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
