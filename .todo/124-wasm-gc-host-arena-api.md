# 124 — `__ronto_alloc_mark` / `__ronto_alloc_reset` on the wasm-GC backend

Split out of `.todo/104` step 3 (its `--no-gc` half was executed by `.todo/110`; what
remains of 104 is only the wasm-GC string/intern-heap question, which is this file).
Motivated concretely by `examples/count-vowels/` (2026-07-13), which now drives the
same Lisp source three ways and exposes the gap.

## The gap

A wasm-GC reactor (`--no-wasi` + `rontolisp:wasm-export`) exports `memory` and the
bump allocator `__ronto_alloc` so a host can hand a `:string` across as a
`(ptr, len)` pair. Everything the *module* allocates is a GC object the engine
reclaims — but the buffer the *host* writes the bytes into lives in linear memory,
which the engine never traces, and this backend has **no `__ronto_alloc_mark` /
`__ronto_alloc_reset`** (only `--no-gc` got them, todo-89) and no todo-88-style
auto-reset. So a resident host that allocates a fresh input buffer per call grows
linear memory forever.

Measured on `examples/count-vowels/` (100000 calls, `"Hello, World! " + i`):

| backend | fresh buffer per call | one reused buffer |
|---|---|---|
| `--no-gc` + arena API | 65536 -> 65536 (flat, mark/reset) | n/a |
| wasm-GC (`--no-wasi`) | 262144 -> **2424832** | 262144 -> 262144 (flat) |

The workaround shipped in the example is "allocate one buffer up front and reuse
it" (`CountVowelsGc.java`, and the Node snippet in its README). That is fine for a
bounded input size and wrong for a variable one: the host must then re-allocate a
bigger buffer and the old one is unreclaimable.

## Why it is not just "copy the two functions over"

`--no-gc`'s heap pointer is wasm global 0 and the only things in linear memory are
strings and packed arrays; `collectCalls` rejects cons/closures/hash/`eval`/global
`setq`, so nothing allocated during a call can outlive it **by construction** — the
arena pop (and todo-88's auto-reset) is sound with no guard.

wasm-GC's `HEAP_PTR_ADDR` (cell 84) is **a stack pointer shared by two disciplines**
(`.kb/wasm-gc-strings.md`):

- a permanent low-water region: the interned-symbol byte pool (`_intern` copies into
  it and advances it) plus string-stream buffers;
- a transient region above it: runtime string builds, assembled and popped.

Popping below the intern high-water frees bytes the intern registry
(`RT_INTERN_COUNT_ADDR`, cell 100) still points at. This is exactly why the serve
adapter's per-request reset is **intern-count-guarded**; any arena here needs the
same guard.

## Plan

1. `__ronto_alloc_mark () -> i32` = `i32.load HEAP_PTR_ADDR`,
   `__ronto_alloc_reset (i32) -> ()` = `i32.store HEAP_PTR_ADDR` — the `--no-gc`
   `markBody`/`resetBody` with a memory cell in place of global 0. Emit + export
   them only when the module already exports `memory` (a memory-typed export
   exists), mirroring the `--no-gc` gating.
2. **Intern-count guard.** Snapshot `RT_INTERN_COUNT_ADDR` in `__ronto_alloc_mark`
   (return a mark that carries it, or store it in a second cell); in
   `__ronto_alloc_reset`, skip the pop if the count grew. Settle todo-104's open
   question first: **skip silently vs. warn vs. trap** — recommend skipping the pop
   and documenting it (a host cannot recover from a trap here), but a warn-once is
   cheap on the wasm-GC backend since it has I/O.
   Alternative to evaluate: reset only down to the intern high-water instead of the
   mark, which is always safe and still reclaims the host's buffer when the mark is
   above it.
3. **Index invariant.** Unlike `--no-gc`, the GC backend has a fixed-`FUNC_*`-index
   invariant and byte-identical component blobs (`.kb/wasi-component.md`). New
   functions must be appended where the existing `__ronto_alloc`/`_string_from_mem`
   helpers are (`FUNC_USER_BASE + numDefuns + numLambdas`), and every component blob
   / adapter that hardcodes an index must be re-checked.
4. **Do NOT port todo-88's auto-reset** to wasm-GC reactor exports: cons, closures,
   hash tables and global `setq` all exist here, so "nothing outlives the call" is
   not true by construction. The host-driven mark/reset is the whole feature.
5. `rontolisp:with-arena` on wasm-GC stays a plain `progn` unless step 2 lands a
   guard good enough to make it real; if it does, decide whether to wire it (the GC
   already reclaims Lisp values — the only wasm-GC win would be the string/intern
   heap, which todo-90 already stopped from growing on transient string building).

## Update the example when this lands

`examples/count-vowels/` is the acceptance test and the documentation:

- README case 2 ("wasm-GC: the engine collects the Lisp side") currently teaches
  "allocate one buffer and reuse it" and links here. Rewrite it to the same
  mark / alloc / call / reset bracket as case 1, and drop the `BUFFER_SIZE` limit.
- `src/main/java/CountVowelsGc.java` — same change (it currently throws on an input
  longer than the reused buffer).
- Keep the measured "fresh buffer per call grows to ~2.4 MB" line as the motivation.

## Verify

- Structural: the two functions are exported only when `memory` is; the `FUNC_*`
  indices of every existing runtime function are unchanged (byte-pin).
- Integration: the count-vowels wasm-GC module, 100000 calls with a fresh input
  buffer per call + the mark/reset bracket, `memory.pages()` constant (Node and
  Endive 1.0.1, which supports GC).
- Guard: a body that interns a symbol (`read`/`intern`/`gensym`) inside the bracket
  must not corrupt the intern registry — assert the interned symbol still compares
  equal after a reset.

## Related

- `.todo/104` — the parent (its `--no-gc` half is done; this file is its step 3).
- `.kb/no-gc-scalar-wasm.md` — todo-88 auto-reset + todo-89 host arena API, the
  shape to mirror.
- `.kb/wasm-gc-strings.md` — the `HEAP_PTR` stack-pointer discipline and the intern
  pool.
- `.kb/wasi-component.md` — the fixed function indices / byte-identical blobs.
