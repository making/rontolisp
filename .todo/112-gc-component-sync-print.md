# 112. GC component: print inside SYNC exports (fd_write over WASI 0.2)

## STATUS: WILL NOT DO (decided 2026-07-13). Kept as the decision record.

The proposal was: rewire the GC adapters' `fd_write` (fd 1/2) from WASI 0.3's
async `stream.write` to WASI 0.2's synchronous
`output-stream.blocking-write-and-flush`, so `print` works inside a sync-lifted
`wasm-export` (today it traps and the author must write `:async t`, todo-92
Tier 3) and the export becomes jco-callable. Rejected. Do not revive this
without re-reading the two findings below -- they are what settled it.

## Finding 1: the 0.2 import would be a PERMANENT island, not a temporary one

WASI 0.3 has **no synchronous write, by design** -- this is not an upstream gap
that a later 0.3.x fills. `deps/cli/stdio.wit` (vendored, 0.3.0) is:

```wit
interface stdout {
  write-via-stream: func(data: stream<u8>) -> future<result<_, error-code>>;
}
```

There is no `output-stream` resource, no `pollable`, no `blocking-*` anywhere in
0.3: the resource-based streams of 0.2 (`deps/io-0.2/streams.wit`) were
*replaced* by the component-model-native `stream<u8>`/`future<T>`. In 0.3,
blocking is a property of the TASK (an async canonical built-in suspends it),
not of a host function. So on a pure-0.3 import surface, "print inside a sync
export" is impossible **permanently**.

This is the decisive difference from the `wasi:http@0.2` island in `.todo/02`:
that one disappears the day async `wasi:http@0.3` ships upstream. A `wasi:io@0.2`
+ `wasi:cli/stdout@0.2` island imported for fd_write would never disappear.
Since the goal is a uniformly-0.3 component (the `.todo/02` lineage, and the
stepping stone toward language-level async), taking this island contradicts the
goal -- and the eventual cleanup would mean regenerating the three worlds and
churning every component's bytes a SECOND time.

## Finding 2: the jco payoff is not ours to buy -- the gap is stackful-vs-callback

The motivating bonus was "a printing export becomes callable from jco" (jco
1.25.2 cannot call our `:async t` exports). That framing was wrong:

- jco already supports **all of WASI 0.3** (1.20+ passes the 46 upstream
  wasmtime p3 tests, ships a Preview Three Shim). So the gap is NOT 0.2-vs-0.3,
  and "wait for jco to support 0.3" is already satisfied.
- The real gap is **stackful vs stackless(callback) async lift**. rontolisp
  lifts `run` and `:async t` exports as STACKFUL (functype tag 0x43); jco's
  generated `_driverLoop` assumes the callback ABI and misreads the flat result
  ("invalid async return value [13]", todo-92). The Component Model async MVP
  plan explicitly implements stackless first and defers stackful coroutines
  (goroutine/fiber-shaped guests) to post-MVP -- so the ecosystem lags on a
  spec-sanctioned path (the async ABI was designed to host BOTH side by side).
- It is an unimplemented code path, not an impossibility: jco already drives our
  stackful `run` under Node 23 JSPI. The missing piece is the export-call path.

So the honest trade is: pay a permanent 0.2 island to route around one narrow
unimplemented path in one host. Not worth it. The escape hatch already exists for
anyone who needs a jco-callable printing export today: `--no-gc --component`
(zero flags, jco-verified, ~1.9 KB).

## Why stackful is the RIGHT lift for rontolisp (do not "fix" this either)

Recorded because the natural follow-up question is "should we move to the
callback ABI, which everyone else implements?". No:

- The callback ABI requires the guest core function to **return to the host at a
  blocking point** (the wasm stack unwinds; the host re-enters via the callback).
  That only works if the continuation lives somewhere other than the wasm stack.
  Rust/JS get this for free: their SOURCE has `async`/`await`, so the compiler
  already lowered the function to a state machine.
- rontolisp source is synchronous, and `print`/`fetch`/`read` appear at arbitrary
  stack depth. To unwind at those points we would have to CPS-transform (or
  explicit-stack) the whole compiled program -- a different wasm backend, and a
  slowdown on pure-compute code that never does I/O. Binaryen's Asyncify is not
  an out: it spills locals to LINEAR MEMORY, and the GC backend's values are GC
  refs, which cannot be stored there (verify before trusting, but it looks fatal).
- `adapter.wat` is straight-line synchronous WAT precisely BECAUSE the lift is
  stackful ("...so the adapter stays straight-line", see its header comment).
  Callback mode means hand-writing a state machine across every I/O path
  (stdout/stdin/file/fetch/socket).

The one world where this flips: **if rontolisp ever gains language-level async**
(future/stream as rontolisp values -- the `.todo/02` lineage). That is NOT ruled
out. There the source itself carries the suspension points, so only async-marked
functions pay the state-machine cost, and a callback lift becomes cheap and
natural (the Rust shape). If that work is ever picked up, revisit the lift choice
AT THAT TIME -- but drive it from the concurrency requirement (e.g. several
in-flight fetches inside one program), never from host compatibility. Even then
the stackful lift keeps working: a callback lift would be an addition, not a
forced migration.

## The residual pain, and the cheap way to take it

What stays: `:async t` is required on the GC path for an I/O-bearing export, and
rejected on `--no-gc --component` (which prints from sync exports via its own 0.2
micro-adapter, todo-93). Same source, opposite rules.

Treat this as the two backends' theses showing through, not as a bug: GC
`--component` sells full-fidelity Component Model (0.3-pure; an I/O export IS an
`async func` in WIT -- that is the model, not a wart), while `--no-gc
--component` sells tiny/zero-flags/any-host portability (and its 0.2 print shim
is consistent with THAT thesis; `uni-nogc-print.wit` says so). Fixing the
asymmetry by dragging the purity-selling backend down to 0.2 has it backwards.

Two cheap follow-ups worth doing instead (neither touches a blob or a byte of
existing output):

1. Turn "forgot `:async t`" from a runtime trap into a COMPILE ERROR. todo-92
   rejected I/O auto-detection because it over-approximates (funcall/apply reach
   everything through the arity dispatchers, so auto-flipping would make nearly
   every export async). But for a DIAGNOSTIC an under-approximation is fine: walk
   only the static direct call graph, ignore dynamic dispatch, and error when an
   export provably reaches print/fetch/read without `:async t`. No false
   positives, catches the realistic cases.
2. Document `:async t` as the 0.3-native contract (an I/O export is an
   `async func`), not as a workaround.

## Upstream watch (the things that WOULD change this picture)

- jco implementing **stackful async export calls** (the actual blocker; a minimal
  repro is already written up in todo-92 -- worth filing upstream).
- async `wasi:http@0.3` shipping (`.todo/02`) -- removes the fetch 0.2 island.
- wasmtime enabling the sync stream/future built-ins by default (drops
  `-W component-model-more-async-builtins=y`).

## Related

- `.todo/92` (`:async t` stackful lift; the jco gap + minimal repro)
- `.todo/93` (the `--no-gc` print micro-adapter -- the 0.2 half, DONE)
- `.todo/02` (the fetch 0.2 island; temporary, unlike this one)
- `.kb/wasi-component.md`, `.kb/no-gc-scalar-wasm.md`
- `src/wasm-component/adapter-http-server-p1.wat` (the 0.2 sync-write precedent that
  made this look easy -- it is easy; it is just not right)
