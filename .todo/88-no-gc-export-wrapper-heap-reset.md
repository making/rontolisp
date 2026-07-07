# 88 - `--no-gc` export wrappers: auto-reset the bump heap for scalar-return exports

## STATUS: NOT STARTED (design agreed; implement in a fresh session)

## Difficulty: SMALL (low risk)

A few opcode runs + one i32 local inside a single method
(`compileWrapperBody`), gated on scalar return type + `mem.used()`. No new
function index, no new global, no section changes, no FUNC_* renumbering. Only
frees dead scratch, so no value changes on any backend -> the risk is minimal
and the parity story is "output unchanged everywhere". ~half a day incl. tests.

## Where this sits in the residency memory strategy

This is the **automatic, zero-host-effort core of the "arena reset" approach
(手段1)** for reclaiming linear-memory string allocations in a long-lived
(resident/edge) `--no-gc` instance. It resets to the *wrapper-entry mark*, which
is the only reset point that is safe with no host cooperation (see Scope
boundary). Two companion tasks build on it:

- **[[89-no-gc-heap-mark-release]] (手段1 opt-in escalation)** -- a host-driven
  `__ronto_mark()`/`__ronto_release(mark)` API that ALSO reclaims the host's own
  `__ronto_alloc` input buffer, making a resident instance fully flat even when
  the host allocates every call. 88 stays the free default underneath it.
- **[[90-wasm-gc-strings-as-arrays]] (手段2, root fix, GC backend only)** --
  represents strings as wasm-GC `array i8` so the engine GC reclaims them; that
  retires the linear-memory string heap on the GC backend entirely. Does NOT
  apply to `--no-gc`, so 88 remains permanent for the non-GC backend.

## Context / motivation

`examples/count-vowels/` embeds a `--no-gc` module in a host (Chicory / Node)
and shares a string through linear memory. It surfaced a question: Chicory's
Rust `count_vowels.wasm` calls `dealloc`, but rontolisp's `--no-gc` module has no
`dealloc` at all.

That is because `ScalarWasmCompiler`'s allocator (`allocBody`, "the bump
allocator over the heap-pointer global index 0") only ever advances the heap
pointer and grows pages -- it never frees. So when the SAME instance is reused
in a loop, its linear memory grows monotonically. For a short-lived host (the
example) this is harmless -- the whole `Instance`/`Memory` is discarded. For a
long-lived, repeatedly-called instance it grows without bound.

A full Rust-style `__ronto_dealloc` (arbitrary free) does not fit a bump
allocator and was rejected. The agreed, well-matched fix is an **automatic,
host-transparent heap reset inside the export wrapper** for exports whose return
type does not point into the heap.

## The precise problem this task fixes

The `:string`-parameter ABI copies the host buffer into a fresh internal
`[len][bytes]` header via `__alloc` **inside the wrapper** (see
`compileWrapperBody`, the `T_STRING` param branch: `CALL mem.allocIndex()`).
Anything the exported function itself allocates during the call (that internal
copy, plus any `concatenate`/`subseq`/`princ-to-string` scratch) is **dead the
moment the call returns a scalar** (`:int`/`:long`/`:float`/`:bool`/`:void`) --
no heap pointer escapes to the host. Today that dead scratch is never reclaimed,
so each call permanently bumps the heap.

Note the scalar set includes **`:long`** (`WasmExportCompiler.T_LONG`, i64), the
`--no-gc`-only boundary designator added in 82e0289 -- like `:int` it is a
non-memory value (an identity i64 pass-through in the wrapper), so it resets the
same way. `:string`/`:s-expr` are the only memory-backed designators and the only
ones excluded.

Fix: snapshot the heap-pointer global at wrapper entry and restore it at wrapper
exit, but ONLY when the export's return type is scalar (never for `:string` /
`:s-expr`, whose result pointer must stay live for the host to read).

## Scope boundary (READ THIS -- it does not make a reused instance fully flat)

This reclaims only the **wrapper-internal** scratch. It does NOT free the host's
own input buffer: the host calls `__ronto_alloc(len)` BEFORE the call, so that
buffer sits *below* the wrapper's entry mark and the reset deliberately leaves it
alone (it is still the live `(ptr,len)` argument during the call, and the host
owns it). A host that calls `__ronto_alloc` every iteration therefore still grows
by `len` per call.

To make a reused instance fully flat you additionally need the host to release
its own buffer. Two options, both **out of scope here** (note them, don't build):

1. Host reuses ONE buffer across iterations (allocate once, overwrite the bytes)
   -- pure host-side discipline, needs no module change; combined with this
   task's wrapper reset the instance stays flat.
2. A host-driven `__ronto_mark()`/`__ronto_release(mark)` arena API -- see
   **[[89-no-gc-heap-mark-release]]**. It reclaims the input buffer too, but
   requires the host to bracket its own alloc. Out of scope here; 88 is the
   automatic default it sits on top of. (A LIFO-only `__ronto_dealloc(ptr,len)`
   is a weaker variant of the same idea and only becomes viable *because* 88's
   wrapper reset removes the internal copy that would otherwise sit on top of the
   host buffer -- folded into the 89 discussion.)

## Design

In `compileWrapperBody` (`ScalarWasmCompiler.java`), when
`decl.returnType()` is any non-memory designator -- `:int` / `:long` / `:float` /
`:bool` / `:void` (i.e. NOT `WasmExportCompiler.T_STRING` / `T_S_EXPR`; gate on
that exclusion so a future scalar type is covered automatically) **and**
`mem.used()` is true (otherwise there is no heap global -- a pure-numeric export
has none):

- Allocate one extra i32 wrapper local `mark` (add `Ty.STRING`/i32 to
  `wrapperLocals`, take its index from `nextLocal++`). It must be the FIRST thing
  emitted, before the argument-boxing loop.
- Emit at wrapper entry (before arg boxing):
  `GET_GLOBAL 0 ; SET_LOCAL mark`
- Emit at wrapper exit, after the result has been unboxed to the host type and is
  on the stack, immediately before `END`:
  `GET_LOCAL mark ; SET_GLOBAL 0`
  (`local.get` pushes `mark` above the result, `global.set` pops it -- the host
  result stays on top; for `:void` the stack is empty and this is still valid.)

Opcodes match `allocBody`: `Instruction.GET_GLOBAL, 0x00` / `Instruction.SET_GLOBAL, 0x00`.

No new function index, no new global, no change to `FUNC_*` numbering or the
memory/global sections -- purely two extra opcode runs + one local inside the
wrapper body. Composes with `--optimize` (`WasmTreeShaker` already decodes
global.get/set).

### Do NOT

- Do not reset for `:string` / `:s-expr` returns (would free the result the host
  is about to read).
- Do not reset when `!mem.used()` (global 0 does not exist).
- Do not touch `compileDefunBody`, `allocBody`, the memory/global/export sections,
  or the GC backend. This is wrapper-local only.

## Implementation steps

1. `ScalarWasmCompiler.compileWrapperBody`: add the snapshot/restore around the
   existing body per the design above; gate on scalar return type + `mem.used()`.
2. `ScalarWasmCompilerTest` (structural, no Docker): assert the wrapper of a
   `:string -> :int` export contains a `global.get 0` / `global.set 0` pair, and
   that a `:string -> :string` export does NOT reset. Assert a pure-numeric
   `:int -> :int` export (no memory) is byte-identical to before (no reset, no
   global).
3. Behavioral check (persistent instance -- `--invoke` cannot show accumulation
   because each invoke is a fresh instance): a Node driver that allocates ONE
   input buffer, then calls the export N times and asserts
   `ex.memory.buffer.byteLength` is unchanged between call 1 and call N. Document
   the exact one-liner in the TODO close-out / the example README. (Equivalently a
   Chicory `memory().pages()` assertion, but do not add Chicory as a build dep.)
4. Re-verify parity: `./mvnw spring-javaformat:apply test`, then the native image
   + `CiSpecE2eTest` (a `--no-gc` case runs there). Confirm interpreter / JVM /
   WASM-GC / WASM-component output is UNCHANGED (this fix only frees dead scratch;
   no value changes anywhere).
5. Update `.kb/no-gc-scalar-wasm.md` (Strings / boundary paragraph) to note the
   wrapper auto-reset for scalar returns.

## Acceptance criteria

- [ ] A `--no-gc` export with a `:string` param and a scalar return
  (`:int`/`:long`/`:float`/`:bool`/`:void`) resets the heap-pointer global to its
  wrapper-entry value before returning; verified structurally in
  `ScalarWasmCompilerTest`. Include a `:long`-return case (the i64 designator) so
  the newest scalar type is pinned.
- [ ] A `:string`/`:s-expr`-returning export does NOT reset (structural test) and
  still returns a correct, readable string to the host (existing
  `noGcSupportsStringConcatenationAtTheBoundary` / string-primitive E2E stay
  green).
- [ ] A pure-numeric export (no memory) is byte-identical to the pre-change output
  (no global, no reset emitted).
- [ ] Behavioral: calling a `:string -> :int` export N=100000 times on ONE
  persistent instance, reusing a single pre-allocated input buffer, leaves
  `memory.buffer.byteLength` unchanged (before the fix it grows by ~`(4+len)` per
  call). Reproducible via the documented Node one-liner.
- [ ] `count_vowels(ptr,len)` still returns 3 for "Hello, World!" on Chicory and
  Node (example unchanged in behavior).
- [ ] Full `./mvnw test` green; native image + `CiSpecE2eTest` green; all four
  backends' output unchanged.
- [ ] `.kb/no-gc-scalar-wasm.md` updated.

## Follow-up: count-vowels example wording (do AFTER the fix lands)

`count_vowels` is a `:string -> :int` export, so it is exactly the case this task
fixes. The current wording overstates the leak and must be corrected:

- `examples/count-vowels/README.md` -- the paragraph ending
  "`__ronto_alloc` is a bump allocator that never frees -- the instance is
  discarded instead." Update to: the wrapper now auto-frees its per-call internal
  scratch for scalar-return exports, so **repeated calls on one instance no longer
  leak the internal string copy**; the host is still responsible for its own
  `__ronto_alloc` input buffer (allocate once and reuse, or discard the instance).
  Keep the "no general `dealloc`" point (bump allocator), just stop implying every
  call leaks.
- `examples/count-vowels/count-vowels.lisp` -- the header comment line
  "(there is no `dealloc`: `__ronto_alloc` is a bump allocator that never frees)".
  Same softening.
- `examples/count-vowels/CountVowels.java` -- the comment
  "There is no `dealloc` ... the whole instance is discarded instead." Same
  softening; optionally add a sentence that for a long-lived host you reuse one
  buffer across calls.
- If any wording in `doc/en|ja/compiling/wasm.md` (`--no-gc` / Strings section)
  claims the reactor "never frees", mirror the nuance there too (en + ja in the
  same commit, per CLAUDE.md docs rule).

Only touch these once the acceptance criteria above are met -- if the task is
descoped or changed, revisit the wording plan.

## Related

- `examples/count-vowels/` (the example that motivated this).
- [[89-no-gc-heap-mark-release]] (手段1 opt-in: reclaim the host input buffer too).
- [[90-wasm-gc-strings-as-arrays]] (手段2 root fix for the GC backend).
- [[23-no-gc-scalar-wasm-backend]] (the backend this lives in).
- [[27-wasm-gc-heap-never-grows]] (the GC backend's analogous heap; different
  code path, `ScalarWasmCompiler.allocBody` is the grow-on-bump reference).
- [[21-wasm-export-memory-abi-ci-coverage]] (memory-ABI E2E coverage).
