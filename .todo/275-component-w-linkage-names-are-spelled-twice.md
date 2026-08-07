# The component's `"w"` linkage field names are spelled twice, in full

Difficulty: Low

A decision, not a discovery: todo-273 measured this, weighed it and deliberately
did NOT take it. This file exists so the next visitor can overturn that with the
numbers in hand instead of re-deriving them.

After todo-273 and the shortest-encoding pass a print-only WASI 0.3 component is
1,776 B (2026-08-07; it was 1,820 when this file was written):

| part | bytes |
| --- | ---: |
| shaken core module | 627 (`.todo/271` owns ~104) |
| preview1 adapter | 547 |
| import block (`wasi:cli/types` + `wasi:cli/stdout`) | 197 |
| shared memory module | 25 |
| aliases / canon / instances / exports, preamble, module framing | 380 |

Of the adapter's import section and the synthesized `"w"` core instance,
**~232 B is the field NAMES** -- and each is spelled twice, once as the adapter's
`(import "w" "<name>" …)` and once as the `(export "<name>" (func n))` of the
instance built to satisfy it. For the nine members a printing program keeps:

```
stdout-write(12) stream-new(10) stream-write(12) stream-drop-w(13)
future-read-cli(16) future-drop-cli(16) waitable-set-new(16)
waitable-join(13) waitable-set-wait(17)          = 125 chars x 2
```

One character each would leave 9 x 2, saving ~232 B -- **13.1% of the whole
component**, which would take hello from 1,776 to roughly 1,544.

## Why it was not taken

`"w"` is a private linkage between two artifacts this repo ships together, so the
names carry no information the reader cannot get elsewhere: `adapter.wat` names
every import with a `$symbolic` local right beside it, and `W_MEMBERS` is keyed by
the descriptive name. Renaming only the wire field would leave both readable. It is
cheap and safe -- `fixedSurface` already throws when the adapter imports a `w`
member the wiring does not declare, so a table/WAT drift is a build failure, not a
silent bug.

Against: it makes `wasm-tools print` on a shipped component opaque (`(import "w" "3"
…)`), it introduces a name-to-ordinal mapping that has to be read in two places, and
`adapter-http-server-p1.wat` / `WasmServeComponentBuilder.BRIDGE_FUNCS` would have
to follow or the two adapters diverge in convention.

**And the decisive one: the bytes are coming back anyway.** WASI 0.3 streams are
asynchronous, so one constant write still builds a stream, a future and a waitable
set -- ~279 B, measured. The synchronous `stream.write` / `future.read` built-ins
delete all of it (1,820 -> ~1,541, measured by hand on the 1,820-byte component of the
day; the same ~279 B off today's 1,776) and
need only `component-model-more-async-builtins`, which wasmtime 47 does not yet
enable by default. Spending legibility now for bytes an upstream default will hand
over for free is the wrong trade.

## What to do

Nothing, until one of these is true:

- **the async gate opens** -- more-async-builtins becomes default-on. Then do THAT
  first (`.kb/optimize-dead-code-elimination.md` carries the trigger and the
  recipe: drop the waitable trio from `adapter.wat`, drop `async` from the two canon
  encodings), re-measure, and re-read this file. It may be enough on its own;
- **or a host makes the component floor matter more than its legibility** -- a
  wasmCloud-shaped registry where transfer size is the cost. Then take the ~232 B,
  and take it for BOTH adapters in one pass.

If it is taken: keep `W_MEMBERS` keyed by the descriptive name and give each member
an explicit wire field, so the mapping lives in exactly one table next to the
encoders (the shape `WMember.realloc()` already uses).

Closing this as "decided: no" is a legitimate outcome. Record which of the two
conditions failed.

## Non-goals

- The core module (`.todo/271`) and how what is emitted is SPELLED
  (`.kb/wasm-shortest-encoding.md`, which took the ~44 B this component used to carry
  as non-minimal encoding); this is only about the linkage names.
- The serve variant's floor, which is a ~280 KB core -- 232 B there is noise.
