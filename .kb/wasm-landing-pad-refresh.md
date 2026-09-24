# A wasm landing pad refreshes every local before it reads one

**Invariant**: on the wasm-GC backend, no wasm local's value may reach a `try_table`
landing pad through the catch block. Every region whose landing runs user code -- or
continues into it -- pushes the live locals onto the operand stack immediately before
its landing block (`WasmLandingPad.keepLocalsAlive`) and pops them back into the locals
as the pad's first act (`WasmLandingPad.refreshLocals`). A variable the protected body
assigns is boxed (`WasmLandingPad.regionAssignedVars`, folded into every binder's boxed
set beside the closure-capture answer). Regions: `WasmUnwindProtectCompiler`,
`WasmHandlerCaseCompiler.compile` and `compileGuard` (`%hb-guard`),
`WasmNlxCompiler.emitCatch` (`catch`, `%nlx-catch`), and a special `let`'s binding-restore region
(`WasmLetCompiler` through `WasmUnwindProtectCompiler.compileRegion`), whose pad refreshes ONLY its
save slots (`WasmLandingPad.keepSlotsAlive`/`refreshSlots`): it reads nothing else, rethrows, and
no user code runs in or after it, so the invariant is kept at a fraction of the push
(`.kb/dynamic-special-variables.md`). Pinned by
`WasmLispCompilerIntegrationTest.landingPadsReadFreshReferencesAfterACollectionDuringTheUnwind`
(Preview 1 and component) and ci-spec `landing-pads-read-fresh-references-after-a-collection`
(all four backends). Compiler-internal pads whose body is a single call (the async entry
wrapper, the future runtime's resume, the export prologues) are exempt by construction:
one predecessor, no block parameter.

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

A region entry emits one `local.get` per declared local and the pad one `local.set` each;
the values are already SSA values, so the normal path gains no instructions -- only the
liveness (and stack-map entries) of locals that would otherwise be dead across the body.
On the ci-spec corpus module every one of the 19 functions that carried a handler-block
argument lost it and nothing else changed.

## Upstream

The wat above is the report to file against `bytecodealliance/wasmtime`: exceptional-edge
block arguments must be reloaded from the stack-mapped slot in the handler (or never be GC
references). Until that lands, this discipline is what makes the wasm-GC backend correct
on the default collector; `.kb/wasm-gc-heap-pregrow.md`'s size is a performance knob again.
