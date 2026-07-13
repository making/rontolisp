# 104 — a way to FREE linear memory: `with-arena`, and `__ronto_alloc_mark`/`_reset` on wasm-GC

> **2026-07-10 (later): the `--no-gc` half is DONE**, executed by
> `.todo/110-nogc-print-io-and-with-arena.md`: `rontolisp:with-arena` is a
> `LispMacroExpander.expandWithArena` lowering to `progn` on the interpreter /
> JVM / wasm-GC (ci-spec case `with-arena-is-observationally-a-progn`) and a
> native mark/body/reset over heap global 0 on `--no-gc`, with the body's
> string / packed-array value copied down to the mark (steps 1+2 below).
> Verified flat: `(with-arena () (vec:ones 1000))` x100000 under a 2-page
> `wasmtime -W max-memory-size` cap where the bare loop traps
> (`noGcWithArenaKeepsALoopFlatWhereTheBareLoopGrows`). Details:
> `.kb/no-gc-scalar-wasm.md`. **What remains of this todo is ONLY the wasm-GC
> string/intern-heap half** (steps 3+4), and on **2026-07-13 that half was split
> out into `.todo/124-wasm-gc-host-arena-api.md`** — with a concrete motivation
> (a wasm-GC reactor host that allocates a fresh input buffer per call grows
> linear memory to ~2.4 MB over 100000 calls, measured in
> `examples/count-vowels/`) and the intern-count guard spelled out. Pick up 124,
> not this file; this one stays as the design record.

**Goal:** give the WASM backends a real reclamation mechanism for the bump-allocated linear heap,
usable from Lisp (not only from the host across an export boundary).

Sequenced AFTER `.todo/103` (destination-passing kernels), which removes the *need* to free in the
common case by making the bump high-water equal the live set. 104 handles what 103 cannot reach:
`vec:zeros`/`from-list` inside a loop on `--no-gc`, and any `--no-gc` string building.

## Status 2026-07-09: `.todo/105` REMOVED the packed-array half of this todo

todo-101 had briefly moved wasm-GC `--simd` packed float arrays into a third linear arena
(`VEC_HEAP_PTR_ADDR = 160`), and this file was rewritten around it as "the urgent one". todo-105
undid that: a packed array under `--simd` is a `TYPE_VBLOCK` over an `(array (mut v128))`, an
ordinary GC object the engine collects (`.kb/vec.md` acceleration layer 3). `VEC_HEAP_PTR_ADDR` and
`_vec_alloc` no longer exist.

**What remains for this todo:**

1. **`--no-gc` intra-call free.** Still the real gap: nothing is freed *within* one export call.
2. **The wasm-GC string/intern heap** (`HEAP_PTR_ADDR = 84`). A separate, harder question — see the
   stack-pointer discipline below. It does not grow with string building (todo-90 fixed that), so
   this is no longer urgent either; what remains is the interned-symbol byte pool and string-stream
   buffers.

Neither is as valuable as the packed arena was, so **this todo is no longer sequenced ahead of
anything.** Reassess whether it is worth doing at all.

## Where things stand

| | `--no-gc` | wasm-GC (strings/intern) |
|---|---|---|
| heap pointer | wasm global 0 | linear memory cell `HEAP_PTR_ADDR = 84` |
| bump allocator | `__alloc` (4-byte aligned) | `__ronto_alloc` (8-byte aligned) |
| exported | when `mem.used()` | when a memory-typed export exists |
| `__ronto_alloc_mark` / `__ronto_alloc_reset` | **yes** (todo-89) | **no** |
| auto-reset on scalar-return export | **yes** (todo-88) | **no** |

`--no-gc` survives because it is *always* a reactor — top level is defuns + `wasm-export`
directives, no `_start` — so "one export call = one arena" always holds and the boundary pops it.
Packed float arrays and strings are the only things it puts there.

## What transfers to wasm-GC, and what does not

**Transfers (cheap).** `__ronto_alloc_mark` = `i32.load HEAP_PTR_ADDR`,
`__ronto_alloc_reset` = `i32.store HEAP_PTR_ADDR`. Two tiny functions, the `--no-gc`
`markBody`/`resetBody` with a memory cell in place of global 0. wasm-GC already exports
`__ronto_alloc` and already has reactor mode (`rontolisp:wasm-export` + `--no-wasi`, exporting
`_initialize` instead of `_start`), so a resident host can bracket its calls immediately.

**Does NOT transfer: todo-88's automatic reset on a scalar return.** `--no-gc`'s `collectCalls`
rejects cons, closures, hash tables, `eval` and global `setq` outright, so nothing allocated during
an export call can outlive it except the returned pointer — the auto-reset is sound *by
construction*. wasm-GC has all of those. (Packed arrays are no longer a hazard here — they are GC
objects — but a string-stream buffer or a freshly interned symbol still is.)

**`HEAP_PTR` on wasm-GC is not a pure bump.** Per `.kb/wasm-gc-strings.md` it is a *stack pointer*
with two disciplines sharing it: a permanent low-water (the interned-symbol byte pool, which
`_intern` copies into and advances; plus string-stream buffers) and a transient region above it
(runtime string builds, assembled then popped). Popping below the intern high-water discards bytes
the runtime intern registry (`RT_INTERN_COUNT_ADDR`, cell 100) still points at. This is exactly why
the serve adapter's per-request reset is **intern-count-guarded**; any wasm-GC arena needs the same
guard.

## The design: `with-arena` is mark/reset with the boundary named in Lisp

The default wasm-GC output is a `_start` command module — there is no host call boundary to hang a
reset on. The boundary has to come from the source:

```lisp
(dotimes (i epochs)
  (rontolisp:with-arena ()
    (train-step net batch)))     ; everything allocated inside is popped here
```

`with-arena` = `mark` on entry, `reset` on exit. Same primitive as the host-facing pair, exposed to
the program. Consequences:

- Its value is on `--no-gc`, where it closes the intra-call hole (the one 103 cannot reach).
- On the interpreter, the JVM and wasm-GC (with or without `--simd`) it expands to a plain `progn` —
  a real GC is already doing this. Cross-backend semantics are uniform; only the reclamation is
  backend-specific.
- Escape contract, inherited from `__ronto_alloc_reset`'s existing one: **nothing allocated inside
  the body may be reachable after it**, except the body's own value. A `--no-gc` packed-array or
  string value must be copied down to the mark before the pop (`dst < src`, so a single forward byte
  copy is a safe memmove). On wasm-GC everything a program allocates is a GC object.
- On wasm-GC (were it ever wired to `HEAP_PTR`) it must be intern-count-guarded — skip the pop if a
  new symbol was interned inside the body — or reject a body that can intern
  (`read`/`load`/`intern`/`gensym`).

Open question worth settling before implementing: whether `with-arena` should refuse to pop (and
warn) rather than silently skip when the guard trips.

## Steps

1. `rontolisp:with-arena` as a `LispMacroExpander` lowering: `progn` everywhere except `--no-gc`,
   where it is mark/body/reset over global 0.
2. The copy-down of a `--no-gc` packed-array / string result value.
3. Only if the wasm-GC string heap turns out to matter: `__ronto_alloc_mark` / `__ronto_alloc_reset`
   on wasm-GC (note the GC backend HAS a fixed-`FUNC_*`-index invariant and byte-identical component
   blobs, unlike `--no-gc`, so check where new function indices may be inserted —
   `.kb/wasi-component.md`), plus the intern-count guard.
4. Decide on todo-88-equivalent auto-reset for wasm-GC reactor exports (probably: don't).

## Verify

- `--no-gc`: a loop of `(with-arena () (vec:zeros 1000))` leaves `memory.size` flat; without the
  arena it grows. Same shape as the `count-vowels` host-arena measurement in
  `.kb/no-gc-scalar-wasm.md` (100000 calls, 65536 → 65536 with the bracket).
- Interpreter / JVM / wasm-GC (± `--simd`): `with-arena` is observationally a `progn` (same value,
  same side effects, ci-spec cross-backend case).
- A pinning test for the escape contract: a value allocated inside and stored to a global is
  documented-undefined; assert only that the guard cases (intern inside the body) do not corrupt.

## Related

- `.todo/103` — destination-passing kernels (done; makes the `--no-gc` leak bounded).
- `.todo/105` — wasm-GC `--simd` packed arrays back on the GC heap (done; removed this todo's
  highest-value piece).
- `.kb/no-gc-scalar-wasm.md` (todo-88 / todo-89), `.kb/wasm-gc-strings.md` (HEAP_PTR discipline),
  `.kb/wasi-component.md` (fixed function indices).
