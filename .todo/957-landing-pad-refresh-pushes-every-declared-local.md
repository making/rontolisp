# 957. A landing pad refreshes every declared local, live or not

Difficulty: High

Found by `.todo/955` while looking for the largest GC-allocation count per function in real
programs. `WasmLandingPad.keepLocalsAlive` pushes EVERY local declared so far
(`ctx.nextLocal`) before each landing block and `refreshLocals` pops them all back
(`.kb/wasm-landing-pad-refresh.md`). The `.kb` "Cost" section counted run time only; in a large
function the push and pop are most of the function.

The hello-ningle Worker (size-report row, `--no-wasi --optimize=size`): fast-http's
`parse-request`, with its `defun-speedy` helpers inlined, has 1,838 locals and 120 pads that
restore **101,756** locals. **156** of them are live after their pad. The function is 624,468
of the module's 2,749,384 bytes (22.7%) and 28.6 s of serial Cranelift time, and the module
takes 34 s in `wasmtime compile`. In the `--optimize` build it is 749,554 bytes and 46.6 s.
Every `--native` build of such a program pays that time too, because the shim precompiles
with Cranelift. The tiny-routes and clack Workers restore under 900 locals; the problem is
the large function. Numbers and tools: `.todo/artefacts/957-landing-pad-refresh-pushes-every-declared-local/`.

## Plan

- The narrowing needs real liveness. A positional rule (keep a local read after the pad or
  inside an enclosing loop) keeps 117,656 of 119,360, because the parser's state loop
  encloses every pad. Keep a local exactly when it is live at the pad's continuation. The
  push and the pop must drop the SAME set, so the stack stays balanced. Dropping a local that
  is dead there keeps the invariant: nothing reads it before a write.
- Where: record each region's push and pop byte ranges at emission. `WasmHandlerCaseCompiler`
  (two sites) and `WasmNlxCompiler` call `keepLocalsAlive`; `WasmUnwindProtectCompiler` calls
  `allSlots` + `keepSlotsAlive`. Once the body
  is complete, run a backward liveness over the decoded body, with exceptional edges from
  every call or throw in a `try_table` to its catch labels, and rewrite the two runs. Branches
  are label-relative, so removing bytes moves no branch. Any other byte offset recorded for
  the body (local-index patches in `buildLocalsAndPatch`, async resume bodies) must shift, or
  the pass runs after them on the final body.
- Pins: `landingPadsReadFreshReferencesAfterACollectionDuringTheUnwind` (Preview 1 and
  component) and ci-spec `landing-pads-read-fresh-references-after-a-collection` stay green.
  Add a shape test: a function whose pad follows many dead `let` scopes restores only the
  live locals. Re-measure ningle's size-report row and its `wasmtime compile` time, and write
  the numbers into the `.kb` "Cost" section.
