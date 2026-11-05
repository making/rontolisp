# The Proxy-Wasm SDK for `--no-gc`

`examples/proxy-wasm/no-gc/`: a second SDK beside `examples/proxy-wasm/proxy-wasm.lisp`, for a filter of
a few KB on a host with or without wasm-GC. What a filter's author gives up is in that directory's
parent README, the other SDK's choices in `proxy-wasm-sdk.md`, and whether either becomes built in in `.todo/i50`.

## Measured (2026-11-05, Envoy v1.40.2, `--no-gc --optimize=size`)

| Filter | raw | gzip | by hand on `rontolisp:memory` (`.todo/artefacts/i51-proxy-wasm-filter-size/no-gc/`) | on the wasm-GC SDK, `(:on-failure :trap)` |
| --- | ---: | ---: | ---: | ---: |
| `http_body` | 2,166 | 1,246 | 2,101 / 1,109 | 4,872 / 2,563 |
| `http_headers` | 2,583 | 1,388 | 2,556 / 1,372 | 7,114 / 3,433 |
| `test_plugin` | 6,581 | 3,145 | not written | 31,340 / 13,012 |
| A filter with one `remove-header` | 1,193 | 716 | | |

Same size as by hand, since the macros leave what a person writes, and `http_body` was smaller until
`search-bytes` took the module's own search (+102 raw, +94 gzip; `%bytes=` went). What an exchange runs is in
`proxy-wasm-sdk.md`: 64KiB through `http_body` was 5,636,321 instructions and 196,630 calls, 3 per byte, and is 221,979 and 37.

**State**, the question `test_plugin` was ported for: the first `(:state hits)` field adds 395 raw and
199 gzip to that one-`remove-header` filter, and a string field read and set in two callbacks 525 and
256 over the same filter reading the header directly. One allocation per context at peak: 200,000
streams through `test_plugin` under a stub host leave the heap where it was, and 300 alive at once move
it by 300 blocks, once.

Behaviour: `sluice-client`'s `ProxyWasmFilterTest` (25 tests), with these three in the place of its Rust
`http_body`, `http_headers` and `test_plugin`, passes against sluice ec574c2 on Endive, a wasm
interpreter, as it does the wasm-GC SDK's. `no-gc/check.sh` is the Envoy half, 22 checks; neither is in CI.

## Why it looks as it does

- **Macros, not a table of functions.** A keyword is not a value here, so `:request` has to be a number
  by the time the compiler sees it, and `get-header` is a macro that does that. It is also what puts
  a literal `:string` at the import's own call site, where `chooseFoldedImports` makes it two constants:
  a defun between them would keep the header and the wrapper. A non-keyword form passes through as a
  run-time number, for a helper taking a map as a parameter (`http_headers`'s `log-pairs`).
- **`:continue` and `:pause` in a body are replaced by a tree walk** (`%actions`), which is sound because
  no other keyword value can exist on this backend. Only in a `define-filter` clause.
- **Everything is reached or not compiled.** `collectCalls` walks from the exports, so the SDK's imports
  and defuns cost a filter that does not call them nothing, and `%check` can be a defun `define-filter`
  writes for `(:on-failure ...)`, called from defuns earlier in the file.
- **Macro-time helpers may not name what splices a library.** `CompileFrontend`'s splices read every
  form, reached or not. `intern` and `symbol-package` in `%state-accessors` brought in a `defvar`,
  refused at top level, which is why `defsetf` there is the long form and needs no setter name.
- **The callbacks Sluice requires are exported** whatever the filter gives
  (`ProxyWasmFilter.REQUIRED_EXPORTS`). Their bodies are identical, so they fold and each costs its
  export entry. Trailers and `on_http_call_response` are exported only when given, which Sluice and
  Envoy both accept.

## State

A block per live context on a list, `[next][id][root][fields]`, head at address 4 (`.kb/no-gc-scalar-wasm.md`).
No freeing: a deleted context's block has id 0, and `%context-new` looks for id 0 before allocating, so
one function does the lookup, the reuse and the delete. It is linear in the most contexts ever alive,
and an accessor looks up on every access, because a callback gets an id and carrying the block
through a body would need a second mode for a filter with no state.

It works because `proxy_on_context_create` does not pop: the block comes off the heap below every
later callback's mark. Variable-length fields would not: a block reserved in the middle of a callback
sits above that callback's scratch, and has to survive its reset. That is why a string field has a
fixed capacity, and every context has every field. Raising the reset's floor to keep such a block leaks the
scratch under it on each new block; moving the block down at the callback's end, as `with-arena` does,
needs a size-class free list to reuse it. Neither is worth it until a filter asks.

Being a scalar, a field needs no copy to read a number. A string field reads as a fresh copy on the bump
heap, so comparing against a path costs that copy; `test_plugin` does it in each callback.

## Questions the item asked

- **Strings as address pairs, or as values?** Values for what is small and read as text: the backend's
  `format`, `string=`, `concatenate` read like Lisp and share one `__alloc`, `__memcpy` and `__itoa`.
  Address pairs for a body: `search-bytes` is the module's own loop (`.kb/wasm-import.md`), where `char` walks
  from the start at each index (`.kb/no-gc-scalar-wasm.md`, "Indexing here is O(n)"), and it can be upcased in place.
  The wasm-GC SDK took the same pair for a body, as it keeps the body off the engine's heap too.
- **Errors**: a failed call answers NIL after a log line, or traps under `(:on-failure :trap)`, decided at
  compile time. `http-call` answers NIL either way: a trap cannot be handled, and a filter has to be
  able to do without the call.
- **`__ronto_alloc` and its pair**: a program with its own allocator export does not need them, and
  no longer has them (`.kb/no-gc-scalar-wasm.md`, "Host arena API").

## What the compiler gained for it

`"rontolisp:memory"`'s `address` and `size` (`.kb/wasm-import.md`), `format` (`.kb/format.md`), a string in
test position, `null`, a two-operand `/=`, per-test `cond` temporaries and bytes 4 to 7
(`.kb/no-gc-scalar-wasm.md`), and `define-filter` in the formatter's table (`IndentRules`).

## Not covered, on purpose

The three filters use none of them, and an untested helper in an example is a promise nobody checked: the
clock, ticks, shared data, setting a property, `proxy_continue_stream`, `proxy_set_effective_context`.
Each is one `wasm-import` and one macro, and free to a filter that does not call it. So `http-call`'s reply
reaches `:on-http-call-response` on the plugin's context with no way back to the stream yet.
