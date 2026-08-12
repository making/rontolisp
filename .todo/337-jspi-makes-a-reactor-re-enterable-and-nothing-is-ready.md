# JSPI makes a reactor re-enterable, and nothing in it is ready

Difficulty: High

A `--no-wasi` reactor's exports have always been called one at a time: the host
enters `handle-request`, the module runs to completion, the host gets its
`[ptr, len]`. A JSPI import breaks that. When the wasm stack parks on
`WebAssembly.Suspending`, control returns to the host's event loop, and the host
may call the export AGAIN before the first call has resumed --
`examples/cloudflare-workers/dog-fetcher` is a live instance of exactly this
shape, and it avoids the problem only by serialising calls in JavaScript.

Two module-global stores are wrong under interleaving, and one of them was
already written down as the trigger for this todo.

## 1. The allocator arena is a single LIFO mark

`__ronto_alloc` is a bump pointer; `__ronto_alloc_mark` / `__ronto_alloc_reset`
is the host's way to free a request's scratch (`.kb/wasm-export-no-wasi.md`, the
bracket every `examples/cloudflare-workers/*/src/index.js` writes). With two calls
in flight, B's mark is taken above A's allocations, and whichever finishes first
resets over the other's live scratch -- the string a still-running handler is
holding, or the `[ptr, len]` it is about to return. Silent: no trap, wrong bytes.

A second mark does not fix it; the marks are not nested, they are interleaved.
What is needed is an allocation SCOPE per call -- a region the export opens and
closes, with the current region in a cell the string helpers
(`_str_from_mem`, `WasmExportCompiler.emitStringResult`) read -- or a real
free-list, which is a much bigger change than this bug justifies.

## 2. Special variables are a module global, and the documented trigger has fired

`.kb/dynamic-special-variables.md` states the WASM divergence and its reason
verbatim: shallow binding over the module global is safe because "a served
component's concurrent tasks interleave on ONE instance's single stack, never
preempting inside a synchronous handler body", and it names the condition that
would end that -- "a host that suspends a handler MID-extent (an `await` inside a
special `let` in a served handler)". A suspending import inside a `let` of a
special IS that host. Two interleaved requests then lose each other's binding,
which is the bug the JVM backend had before its `_d$` ThreadLocal hybrid.

Same question for every other module-global cell reached from a handler: the
gensym counter, `*standard-output*` redirection, the random state.

## Proposal, in the order it should land

1. **Fail loudly instead of corrupting.** A re-entry guard: a module cell set on
   export entry and cleared on return, with a second entry signalling rather than
   proceeding. Cheap, and it turns a silent wrong answer into a message that
   names the cause. A host that serialises (as the example does) never sees it;
   a host that does not, learns immediately instead of at 3 a.m.
2. **Say it where the host reads.** The build already names host obligations for
   the clock and entropy (`compiler/NoWasiLoadPathRefusals`); "this module can
   suspend, so it may be re-entered -- serialise or use one instance per call" is
   the same kind of line, and it belongs with the `:async t` import declaration
   of `.todo/336`.
3. **Then, and only if a host really needs overlap**, make the two stores
   per-call: an arena scope keyed by the call, and a per-task dynamic-binding
   store on WASM (the JVM's hybrid is the shape -- byte-identical when nothing is
   let-bound). Note the cost honestly: this is the concurrency the serialising
   host gave up, and it is worth paying only when the workload is I/O-bound
   enough to want it.

`.todo/190` is a different concurrency trap on the served-component path; the two
should be read together before touching either.

## Verification

- A node/workerd host that deliberately does NOT serialise: two overlapping calls
  with a slow import, one holding a string across the suspend, one binding a
  special across it. Both must be wrong TODAY (write the reproduction first --
  it is the whole value of this todo) and right, or refused, after.
- The single-call path stays byte-identical: a module whose program cannot
  suspend must not gain a guard, a cell, or an instruction.
