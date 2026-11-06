# i76. JSON bodies past ~256 KiB within sluice's defaults: an edit over the host's bytes

Difficulty: High

Reported 2026-11-06 by the sluice session: `json_plugin` (sluice f408d6e,
`/nvme1n1-disk/sluice/sluice-client/src/test/guest/rontolisp/json_plugin.lisp`) parses a response
body, adds a field and stringifies it, and lost bodies past 8 KiB to `max-memory` (16 MiB) and
`call-timeout` (1 s) on Endive's interpreter.

Done 2026-11-06, both halves of sluice's first step (a cheaper parse and tree, the API kept):
- A native parser and serializer on wasm-GC (`.kb/json.md`, "The wasm-GC codec"): the largest
  body at the defaults 8 KiB to 192 KiB.
- A table of at most eight keys a vector of pairs on wasm-GC, for every program
  (`.kb/hash-tables.md`, "Representation"): the tree 46 to 23.5 bytes a byte of JSON, all that is
  charged at 1 MiB 71.7 to 49.9; the largest body 192 KiB to 256 KiB. The time, about 2 us a
  byte, is the instructions' (107 a byte at 8 KiB, 30 of them the host copies) and did not move.

What the measurement says about the rest of that step: sluice's target (the tree near 10 bytes a
byte, 1 MiB parsed, changed and stringified within 16 MiB, about 1 us a byte) is out of reach of
`json-parse` and `json-stringify` over strings. Endive holds an `i8` array's element in a long,
so the input string is 8 bytes a byte and the output at least 8 more: 16 MiB at 1 MiB before
the tree or the linear memory. Cheaper strings (`TYPE_STRING`'s struct is 168 of a short
string's 304 bytes, and symbols share it) or table headers (cell and cons, 208 of a small
table's 424) would move the 256 KiB, not reach 1 MiB.

Plan, sluice's second step, when the orchestrator takes it up:
- A `rontolisp:` function (the operation is JSON's, not the ABI's, so not the SDK's): over the
  bytes the host placed (`proxy-wasm:with-buffer`'s address and size), never copied to the GC
  heap, it validates the document and answers the byte range of a path's value -- the top-level
  object's closing brace, or a key's value -- where a filter inserts, replaces or deletes, and
  writes back by `proxy-wasm:set-buffer` / `set-buffer-bytes`, whose `:start`/`:size` splice
  the host's buffer. General, not "add a field": sluice's filters read fields (`usage`), replace
  or remove nested values and mask them.
- Open: what a path is (keys and indexes), and the form on the interpreter and the JVM, which
  have no linear memory (an octet vector, or a string with bounds?) -- the four backends answer
  alike.
- What it would cost: the host's buffer, a byte a byte, and a scan the decoder's (a string byte
  is 8 instructions), against 49.9 bytes and 107 instructions a byte now.
- Bench: `.todo/artefacts/i76-json-tree-cost/` (sluice's `JsonBench`, bytes per type, and
  instructions with `.todo/artefacts/i69-proxy-wasm-instructions/`).
