# 89 - `--no-gc` host arena API: `__ronto_mark` / `__ronto_release` (reclaim the input buffer too)

## STATUS: NOT STARTED (do AFTER [[88-no-gc-export-wrapper-heap-reset]])

## Difficulty: MEDIUM (host-facing contract is the risk, not the code)

The code is small -- two tiny exported functions over the existing heap-pointer
global (index 0): `__ronto_mark() -> i32` returns the current heap pointer,
`__ronto_release(i32) -> void` stores it back. `--no-gc` has NO fixed-index
invariant (unlike the GC backend), so adding exported functions is free -- append
them to the function/export sections, no renumbering concerns. The difficulty is
entirely in the **semantics/contract**: it is a manual arena, so misuse
(releasing to a mark taken after live data, or reading a `:string` result whose
bytes were just released) corrupts memory. Most of the work is precise docs +
tests + an example, not codegen. ~1 day.

## Why this exists (what 88 leaves on the table)

[[88-no-gc-export-wrapper-heap-reset]] auto-resets to the *wrapper-entry* mark, so
it frees the wrapper's internal scratch but NOT the host's own input buffer: the
host calls `__ronto_alloc(len)` BEFORE the call, so that buffer sits below the
entry mark and 88 deliberately leaves it alone. A resident host that allocates a
fresh input buffer every call therefore still grows by `len` per call.

`__ronto_mark`/`__ronto_release` closes that gap by letting the host bracket its
OWN allocation:

```
mark = __ronto_alloc_mark()          // snapshot BEFORE allocating the input
ptr  = __ronto_alloc(len)            // host input buffer
write bytes at ptr
result = count_vowels(ptr, len)      // 88 already freed the internal scratch
                                     // (result is a scalar, fully read out here)
__ronto_release(mark)                // pop input buffer + anything above it
```

With 88 + this, a resident `--no-gc` instance stays perfectly flat regardless of
how many times it is called.

The API is boundary-type-agnostic (it operates on the shared heap, not on any
particular export signature), so it composes with every `--no-gc` designator,
including the newer **`:long`** (i64, `WasmExportCompiler.T_LONG`, added 82e0289).
A `:long`-returning export composes with 88 exactly like `:int` does (both are
non-memory scalars; 88 resets their wrapper scratch, this API reclaims the input
buffer).

## Design

Two exported functions over the heap-pointer global (global 0, present iff
`mem.used()`):

- `__ronto_mark() -> i32`: `GET_GLOBAL 0 ; END`. Returns the current top.
- `__ronto_release(i32 mark) -> ()`: `GET_LOCAL 0 ; SET_GLOBAL 0 ; END`. Restores
  the heap pointer. (Absolute-restore, not a per-block free -- a bump allocator's
  natural operation.)

Emit both only when `mem.used()` (no heap global otherwise). Export them under
fixed host names alongside `memory` / `__ronto_alloc` in the export section of
`ScalarWasmCompiler` (the same `if (mem.used())` block).

Naming: pick names that read as a matched pair and won't collide with a user
export alias. Suggest `__ronto_alloc_mark` / `__ronto_alloc_reset` (or
`__ronto_mark`/`__ronto_release`); decide and document once.

### Interaction with 88 (must compose)

88 resets to the wrapper-entry mark on every scalar-return call. That is always at
or ABOVE the host's `__ronto_release` mark (the host marks before its alloc), so
the two never fight: 88 pops internal scratch within a call, `__ronto_release`
pops the host buffer after the call. For a `:string`-RETURNING export (88 does
NOT reset), the host must read the returned bytes out of memory BEFORE calling
`__ronto_release` -- document this loudly.

### Do NOT

- Do not try to make `__ronto_release` a general free of an arbitrary block; it is
  a stack/arena restore. If `mark` is above live data you still need, that is
  caller error (documented).
- Do not emit either function when the module has no linear memory.
- Do not touch the GC backend (it has the fixed-index invariant; a resident GC
  instance is addressed by [[90-wasm-gc-strings-as-arrays]] and the serve
  per-request reset instead).

## Implementation steps

1. `ScalarWasmCompiler`: emit the two bodies (append after the existing memory
   helpers), and add their exports in the `if (mem.used())` export block.
2. `ScalarWasmCompilerTest` (structural): assert both exports are present when a
   `:string` param/return is used and ABSENT for a pure-numeric module; assert the
   body opcodes (`global.get 0` / `global.set 0`).
3. Behavioral (persistent instance, Node): a driver that loops N times doing
   mark -> alloc -> call -> release and asserts `memory.buffer.byteLength` is flat;
   contrast with a no-release loop that grows. Document the one-liner.
4. Example: extend `examples/count-vowels/` (or its README) with a resident-loop
   host snippet that uses mark/release, showing the flat-memory pattern for edge
   use. (Node and/or the Chicory host -- Chicory can call the two exports and read
   `memory().pages()`.)
5. Docs: `doc/{en,ja}/compiling/wasm.md` `--no-gc` / Strings section -- document
   the arena API and the "resident instance stays flat" recipe (en + ja same
   commit). `.kb/no-gc-scalar-wasm.md` note.

## Acceptance criteria

- [ ] `__ronto_mark`/`__ronto_release` (final names TBD) are exported by a
  `--no-gc` module that uses linear memory, and absent from a pure-numeric one
  (structural test).
- [ ] A resident host loop `mark -> __ronto_alloc -> export-call -> release`, run
  N=100000 times, leaves `memory.buffer.byteLength` unchanged; the same loop
  without `release` grows (behavioral test / documented Node driver).
- [ ] Composes with 88: a scalar-return export inside the bracket still returns the
  correct value, and memory is flat.
- [ ] A `:string`-returning export documents (and a test shows) that the result
  must be read before `release`.
- [ ] Full `./mvnw test` green; native + `CiSpecE2eTest` green; all four backends
  unchanged in output.
- [ ] Docs (en+ja) + `.kb` updated; count-vowels example gains the resident recipe.

## Related

- [[88-no-gc-export-wrapper-heap-reset]] (the automatic default this sits on top of).
- [[90-wasm-gc-strings-as-arrays]] (the GC-backend counterpart; different backend).
- [[23-no-gc-scalar-wasm-backend]] (the backend).
- `examples/count-vowels/` (motivating example / where the resident recipe lands).
