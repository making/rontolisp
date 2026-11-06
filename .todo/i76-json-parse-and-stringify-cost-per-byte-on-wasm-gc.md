# i76. JSON bodies past ~200 KiB within sluice's defaults: an edit that builds no tree

Difficulty: High

Reported 2026-11-06 by the sluice session: `json_plugin` (sluice f408d6e,
`/nvme1n1-disk/sluice/sluice-client/src/test/guest/rontolisp/json_plugin.lisp`) parses a response
body, adds a field and stringifies it, and lost bodies past 8 KiB to `max-memory` (16 MiB) and
`call-timeout` (1 s) on Endive's interpreter.

Done 2026-11-06, the per-byte half (`.kb/json.md`, "The wasm-GC codec"): a native parser and
serializer on wasm-GC. Instructions a byte 1,518 to 109 (`--optimize=size`; 96 at `--optimize`),
bytes charged a byte 1,130 to 83; the largest body at the defaults 8 KiB to 192 KiB.

What the measurement overturned: the goal was 1 MiB within the defaults by cutting the per-byte
cost. No parse that builds the tree reaches it. At 1 MiB 71.7 bytes are charged a byte, the tree
alone about 46 (an item's hash table 1,128 bytes and its value string 320, for 32 bytes of JSON),
and the 1 s call holds about 450 KiB.

Plan, rewritten around that:
- Agree with sluice on the operation the plugin needs. Adding a field to the top-level object is a
  splice of the body's bytes: the decoder's scan validates without building, the encoder writes
  the field, and the rest is copied, about the host copies' 8 bytes a byte. Whether that is a
  `rontolisp:` function (jzon has none) or the SDK's is the decision.
- Not this: the SDK parsing from and writing to the host buffer in place saves the body's two GC
  strings (16 bytes and about 30 instructions a byte), which is about 256 KiB, not 1 MiB.
- Not this either: a smaller hash table for a few entries would touch every backend's table
  runtime for a third of the tree.
- Bench: `JsonBench.java` under
  `/tmp/claude-1000/-nvme1n1-disk-sluice/3dfe6d1d-ce33-4497-8db9-91c119351091/scratchpad/json-bench/`;
  the instruction and byte counts: `.todo/artefacts/i69-proxy-wasm-instructions/` plus a byte count
  per allocating type in `PluginHeap`.
