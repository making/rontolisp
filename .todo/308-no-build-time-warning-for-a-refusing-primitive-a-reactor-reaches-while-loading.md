# `--no-wasi`: nothing warns at BUILD time about a primitive the load path refuses

Difficulty: Medium

Split out of the clock work (`.kb/wasm-export-no-wasi.md`), which closed the
clock itself but not this. A `--no-wasi` module answers `random`, `getenv`, the
filesystem lookups and — since a host can hand one over — the clock. What is
left refusing still refuses at RUN time, and a refusal reached from a TOP-LEVEL
form kills the instance during `_initialize` (or, on a reactor component, during
instantiation) with nothing in `RuntimeError: unreachable` naming the culprit.

## The cost, measured

Finding the four blockers in the `lack-request -> http-body -> fast-http ->
smart-buffer` chain (`random`, `uiop:getenv`, `path_open`, the clock) took four
separate node runs plus reading wasm function indices out of a backtrace, one at
a time. The clock work added a fifth round of exactly the same procedure. Each
one is a build-time-decidable fact about the program that the build does not
mention.

## What is still refusable, after the clock

| what | when it refuses |
| --- | --- |
| `read`, `read-line`, `read-char` (stdin) | always — a bare trap, the one uncatchable case |
| `rontolisp:random-bytes` | unless `--host-random` |
| `with-open-file` / `open` / `load` at run time | always |
| `sleep` | always |
| the three clock built-ins | only if the HOST never calls `__ronto_set_time` |

The last row is the interesting one and the reason this is not simply "list the
refusals": the clock is not a build-time refusal any more, it is a build-time
**host obligation**. A program that reads the clock from a top-level form is
perfectly loadable — on a host that sets it first. That is exactly what a build
line should say, and exactly what no one can currently discover without running
it.

## Two shapes, and why the original sketch no longer fits

1. **A wasm call graph rooted at `FUNC_START`** (the shape this was first
   written as): `am.ik.wasm.WasmTreeShaker` already computes reachability and
   would need only a "which functions are reachable from root R" query. It no
   longer maps onto the refusals, though: since they became Lisp-level
   `(error "...")` calls rather than distinct stub functions, the call graph can
   see `fd_read` and the `clock_time_get` backstop and nothing else. It would
   answer for stdin and miss everything the compiler lowered.
2. **An AST reachability pass over the program** (top-level forms, plus the
   defuns they call, transitively) looking for the refusing operator set. This
   is where the refusals actually are, it is entry-shape independent (a reactor
   component has no `_initialize` to fail in), and it can distinguish
   "reachable from a TOP-LEVEL form" — fatal — from "reachable only from an
   export", which is the caller's problem at call time. It belongs in
   `compiler`, beside `NoWasiFilesystemStubs`, which already walks the program
   for the same flag.

Shape 2 looks right; the reason for recording shape 1 is that its dead end is
not obvious from the outside.

## Done when

- Compiling with `--no-wasi` prints one line per refusing primitive reachable
  from a top-level form, naming the operator and (where there is one) the way
  out: `__ronto_set_time` for the clock, `--host-random` for entropy.
- It stays quiet for a primitive only an EXPORT can reach — that one is a
  call-time condition the caller can catch, not a load-time death.
- Verified against the `lack-middleware-session` program in
  `.kb/wasm-export-no-wasi.md`: the build names the clock BEFORE the first node
  run, not after it.

## Related

`.kb/wasm-export-no-wasi.md` (the stub rule, both host hooks, what each
primitive does), `compiler/NoWasiFilesystemStubs`, `am.ik.wasm.WasmTreeShaker`,
`examples/cloudflare-workers/`.
