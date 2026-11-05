# i53. A Proxy-Wasm SDK for `--no-gc`

Difficulty: Medium

Written by hand on `rontolisp:memory`, `http_body` is **2,101 bytes raw and 1,109 gzip**, and `http_headers` is 2,556 and 1,372,
against 86,494 and 151,176 on the wasm-GC SDK. They behave the same in Envoy v1.40.2 and in a host's test of these two filters
(`.todo/artefacts/i51-proxy-wasm-filter-size/no-gc/`).
No compiler work on the wasm-GC backend (i52) comes near that, and it also runs on hosts without wasm-GC. What it costs now is
the writing: both files spell out the ABI, walk the serialized pairs by address, and copy literals into memory byte by byte.

`examples/proxy-wasm/proxy-wasm.lisp` cannot run there. `--no-gc` compiles `defun`, `wasm-export` and `wasm-import` at top level
and refuses `defclass`, `defvar`, `defconstant`, `make-hash-table`, `car` and `handler-case`, and `:octets` at the boundary.
`defmacro`, `defpackage` and a macro expanding to a `wasm-export` all compile, so an SDK of macros is possible.

## What to find out

Whether an SDK can give most of the wasm-GC SDK's way of writing a filter at a small cost per filter.

- **Callbacks by macro, not CLOS**: a `define-filter` that expands to the `proxy_on_*` exports and the wrappers, with a default
  for each callback the filter leaves out.
- **State per context**: the wasm-GC SDK keeps objects in a hash table. With no `defvar` here it lives in linear memory: an address
  per context id, or a fixed slot table.
  The hand-written filters have no state, which is why they have no such cost. `test_plugin` holds a path per stream, so port
  it to find out what that costs.
- **Strings**: `:string` literals fold to `(ptr, len)`, and an address pair is the natural value here. `.kb/no-gc-scalar-wasm.md`
  says what `concatenate` and `subseq` allocate and how `length` counts. Add helpers only for what the filters use: a byte
  compare, a `search`, a builder over the bump heap, a decimal.
- **Errors**: `handler-case` is refused on this backend, so a failed call answers a status, or traps.
- **The exports nothing needs**: the module carries `__ronto_alloc`, `__ronto_alloc_mark` and `__ronto_alloc_reset` beside
  `proxy_on_memory_allocate`, about 60 bytes. Say whether a program holding the arena fields still needs them.

## Done when

`http_body`, `http_headers` and `test_plugin` on the new SDK stay within a few KB, read like Lisp rather than like the hand-written
files, and have a check in a real Envoy beside `examples/proxy-wasm/sdk-examples/check.sh`. Relate this to i50, the decision
on making the SDK built in.
