# A wasm landing pad refreshes every local live after it

**Invariant**: on the wasm-GC backend, no wasm local's value may reach a `try_table`
landing pad through the catch block. Every region whose landing runs user code -- or
continues into it -- pushes locals onto the operand stack immediately before its landing
block (`WasmLandingPad.keepLocalsAlive`: every local declared so far) and pops them back
into the locals as the pad's first act (`WasmLandingPad.refresh`). Once the body is a
complete code entry, both runs are narrowed to the locals LIVE after the pad
(`WasmLandingPad.narrowCarries` -> `am.ik.wasm.WasmCarriedLocals`, from `buildLocalsAndPatch`
and from `WasmAsyncEmit.compileResume`'s assembly). A variable the protected body
assigns is boxed (`WasmLandingPad.regionAssignedVars`, folded into every binder's boxed
set beside the closure-capture answer). Regions: `WasmUnwindProtectCompiler`,
`WasmHandlerCaseCompiler.compile` and `compileGuard` (`%hb-guard`),
`WasmNlxCompiler.emitCatch` (`catch`, `%nlx-catch`), and a special `let`'s binding-restore region
(`WasmLetCompiler` through `WasmUnwindProtectCompiler.compileRegion`), whose pad pushes ONLY its
save slots (`WasmLandingPad.keepSlotsAlive`): it reads nothing else, rethrows, and
no user code runs in or after it, so the invariant is kept at a fraction of the push
(`.kb/dynamic-special-variables.md`). `am.ik.wasm.WasmInliner` never moves a body that holds a
`try_table`: moved, it would put the caller's live locals across the catch edge, and the body's
pad refreshes only its own. Pinned by
`WasmLispCompilerIntegrationTest.landingPadsReadFreshReferencesAfterACollectionDuringTheUnwind`
(Preview 1 and component) and ci-spec `landing-pads-read-fresh-references-after-a-collection`
(all four backends); the narrowing by `WasmCarriedLocalsTest` (hand-assembled pads, and
`aCompiledPadAfterManyDeadLetScopesRefreshesOnlyWhatItReads` end to end), the inliner rule by
`WasmInlinerTest.aBodyHoldingATryTableStaysOutOfLine`. Compiler-internal pads whose body is a
single call (the async entry wrapper, the future runtime's resume, the export prologues) are
exempt by construction: one predecessor, no block parameter.

## Why: the exceptional edge carries a pre-call value

Cranelift's frontend (`cranelift-frontend` SSA builder + safepoint spiller, wasmtime 47.0.3
through 49.0.0, unfixed upstream as of 2026-09-24: the wat below still traps on 49.0.0) resolves a wasm local read in a
landing pad by looking the variable up through the catch block's predecessors -- one per
call inside the `try_table` body, since every such call is a `try_call` whose exception
table names the catch block. When all predecessors agree, the parameter is removed and the
pad's use is rewritten to a reload from the stack-mapped slot, in the pad -- correct. They
DISAGREE whenever the body contains a loop with a two-predecessor merge inside it (an
`if ... else ... end` with a result, e.g. the inlined `gethash`/`assoc` bucket walk) and a
call after the merge: the loop header acquires a block parameter for every variable live
around it that the builder's nested trivial-phi removal never revisits
(`finish_predecessors_lookup` aliases the inner phi after the outer one was judged), so
calls before the loop resolve the local to its definition and calls inside or after the
loop to the header's parameter. The catch block then keeps its own parameter and each
`try_call` passes the value as `default: blockN(exn0, vK)` -- an ARGUMENT, evaluated before
the call, that the safepoint pass reloads *before* the `try_call` and regalloc keeps alive
in a spill slot the stack map does not list (`gguf:read`: `movq %rdx, 0xc8(%rsp)` before
the call, `movq 0xc8(%rsp), %rcx` in the pad; the stack map covered `0x20`/`0x28` only).
A copying collection during the call moves the object and updates every stack-mapped
slot; the handler receives the pre-move reference. In the corpus that was `s` in
`with-open-file`'s cleanup: `%io-close`'s `ref.test` failed on the stale header, the raw
path handed the struct to `FUNC_CLOSE`, and `ref.cast (ref i31)` trapped `cast failure`
3587 lines into a 3590-line run.

What decided pass or trap was only whether a collection fell inside that call: the
pre-grow scales with emitted code bytes (16x), so any code-size change -- reordering
`linalg::%la-make`'s arms, one more ci-spec case, a directory with more entries -- moved
the collection and flipped the coin. `-C collector=drc` passes because it does not move
objects; `-O opt-level=0` still traps (it is the frontend, not the mid-end).

Minimal reproducer, 30 lines of wat, no rontolisp (traps under
`wasmtime run -W gc=y -W exceptions=y`, passes with `-C collector=drc`; the one-definition
variant without the pre-loop call passes, which is the agreement case above):

```wat
(module
  (type $cell (struct (field (mut i32))))
  (tag $t)
  (global $g (mut (ref null $cell)) (ref.null $cell))
  (func $test (param eqref) (result i32) (i32.const 0))
  (func $churn_then_throw (param $n i32)
    (local $i i32)
    (loop $l
      (drop (struct.new $cell (i32.const 0)))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br_if $l (i32.lt_u (local.get $i) (local.get $n))))
    (throw $t))
  (func (export "_start")
    (local $c (ref null $cell)) (local $cur eqref) (local $k i32) (local $e exnref)
    (local.set $c (struct.new $cell (i32.const 42)))
    (global.set $g (local.get $c))
    (local.set $cur (struct.new $cell (i32.const 7)))
    (block $h (result exnref)
      (try_table (catch_all_ref $h)
        (drop (call $test (ref.null eq)))                 ;; a call BEFORE the loop
        (loop $l                                          ;; a loop with a two-way merge
          (drop (call $test
                  (if (result eqref) (ref.test (ref $cell) (local.get $cur))
                    (then (local.get $cur))
                    (else (ref.null eq)))))
          (local.set $k (i32.add (local.get $k) (i32.const 1)))
          (br_if $l (i32.lt_u (local.get $k) (i32.const 3))))
        (call $churn_then_throw (i32.const 20000000)))    ;; collections, then the throw
      (unreachable))
    (local.set $e)
    (if (i32.eqz (ref.eq (local.get $c) (global.get $g))) (then (unreachable)))))
```

`wasmtime compile --emit-clif DIR` shows the catch block as `block11(v78: i64, v118: i32)`
with three different `default: block11(exn0, vN)` arguments; the mitigated shape shows
`block16(v197: i64)` and every `default: block16(exn0)` bare. `wasmtime objdump --stack-maps
--exception-tables --addrmap` on a `.cwasm` gives the machine-code view.

## Why this shape

Values on the wasm operand stack are not variables: a `local.get` before the landing
block yields the current SSA value, the frontend spills it to a stack-mapped slot at its
definition and reloads it at every use, and the pad's use is a use -- so the reload lands
in the pad, after the collection. Popping them into the locals defines each local inside
the pad, so every later read resolves locally and the catch block carries nothing. The
normal exit and the return trampolines `br` out of the block, which discards the pushed
values; they cost the non-exceptional path nothing. Each landing block needs its own push
inside the innermost block enclosing it -- a catch branch unwinds the stack to its target
block's entry height, so values pushed inside the landing block are gone and values
pushed outside an enclosing block are unreachable within it. The payload temp is
allocated AFTER the push: a slot among the kept ones would be popped back over the
payload just stashed in it.

**Only a local LIVE after the pad needs the round trip.** Cranelift resolves a variable
where it is used, so a local that every path from the pad writes before it reads is never
looked up through the catch block and gets no block parameter. What is live is known only
once the body is complete, so emission pushes every local declared so far and
`WasmCarriedLocals` narrows both runs afterwards: a backward liveness over the whole body,
with an edge from every call and throw inside a `try_table` to every enclosing catch
target. A kept local is READ by its push, so keeping it at one pad can make it live after
an earlier one whose continuation reaches that region's entry: the kept set is the least
fixed point. Push and refresh drop the same locals, so the operand stack stays balanced.
The full push was worse than wasteful: a pad's push READ locals that had reached an
earlier pad without being refreshed there (declared after that pad's push), the very read
the invariant forbids. 586 of the ci-spec corpus module's 1,777 pads had a reference-typed
local live on entry that way. The narrowed module has none, and neither do the ningle
modules below (`padcheck.py` in the artefacts).

The snapshot is entry-time, so a variable the body assigns must not be refreshed from it
-- boxing keeps the local (the cell reference) constant and reads the latest value through
the heap. Alternatives weighed and rejected: a shadow table or per-task array (a second
root store with write-through and async-interleaving hazards), outlining the body into a
closure (changes `return-from`/`go`/await semantics), or waiting for upstream
(wasmtime 49.0.0 still traps). `unwind-protect` catches `$lisp-cond` -- plus
`$block-exit` when the program lowers a cross-lambda exit -- and rethrows the eqref payload
on its tag, instead of `catch_all_ref`/`throw_ref`: an exnref cannot be stashed in an
eqref local or cell, and the module throws no other tag.

## Cost

A region entry emits one `local.get` per kept local and the pad one `local.set` each;
the values are already SSA values, so the normal path gains no instructions -- only the
liveness (and stack-map entries) of locals that would otherwise be dead across the body.
On the ci-spec corpus module every one of the 19 functions that carried a handler-block
argument lost it and nothing else changed.

**Unnarrowed, the push and the refresh were most of a large function** (measured
2026-09-24, wasmtime 49.0.0, 64 cores). The hello-ningle Worker's fast-http
`parse-request` has its parser helpers inlined and 120 pads, with 1,599 locals in the
size-report build and 1,848 in the `--optimize` one:

| build | pushed and refreshed | function bytes | module bytes | function, serial Cranelift | `wasmtime compile`, wall |
| --- | ---: | ---: | ---: | ---: | ---: |
| `worker.lisp --no-wasi --optimize=size` (size-report row), every declared local | 101,636 | 624,468 | 2,749,384 | 30.6 s | 30.3 s |
| the same, narrowed | 80 | 42,975 | 2,073,313 | 0.41 s | 1.8 s |
| `check.lisp --optimize` (examples `wasm` leg), every declared local | 119,360 | 749,554 | 3,354,877 | 45.0 s | 52.1 s |
| the same, narrowed | 80 | 61,011 | 2,578,987 | 0.54 s | 1.9 s |

`wasmtime run` of the check program went from 59.8 s to 2.4 s, nearly all of it compile time,
which a `--native` build pays too (its shim precompiles with Cranelift). The ci-spec corpus
module went from 7,594,940 to 6,011,558 bytes. A positional rule (keep a local read after the
pad or inside an enclosing loop) does not find the 80: the parser's state loop encloses every
pad. The count first recorded as live, 156, came from a tool that took wasm-tools' `@N` labels
-- nesting depths -- for unique names. An independent Python fixed point over the printed
function agrees with the pass on 80. Tools and how to run them:
`.todo/artefacts/957-landing-pad-refresh-pushes-every-declared-local/`.

## Upstream

The wat above is the report to file against `bytecodealliance/wasmtime`: exceptional-edge
block arguments must be reloaded from the stack-mapped slot in the handler (or never be GC
references). Until that lands, this discipline is what makes the wasm-GC backend correct
on the default collector; `.kb/wasm-gc-heap-pregrow.md`'s size is a performance knob again.
