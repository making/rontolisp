# wasm-GC served requests collapse under concurrency: every `ref.cast` pays a host libcall

Difficulty: High

A wasm-GC serve component is as fast as the JVM backend for ONE request at a time
and then falls apart as soon as requests overlap: throughput drops ~14x between
c=1 and c=16 while the host burns 15 cores. It is not a scheduling limit (the WASM
backends being single-threaded would cap throughput, not reduce it) -- the profile
puts ~90% of the samples in ONE wasmtime function, and the emitted module is what
calls it.

## The measurement (2026-08-07, wasmtime 46.0.1, M4 Max 16 cores, macOS 26.3.1)

`wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y`,
`ab` (HTTP/1.0, no keep-alive) over loopback, 6000 requests per cell, warmed up.
CPU seconds are wasmtime's own, sampled as a `ps -o cputime` delta across the run.

`examples/net/httpbin.lisp` (`GET /get?a=1&b=two`):

| concurrency | rps | wasmtime CPU | cores busy | CPU per request |
| --- | ---: | ---: | ---: | ---: |
| 1 | 8,897 | 0.53 s | 0.8 | 88 us |
| 4 | 6,085 | 3.32 s | 3.4 | 553 us |
| 8 | 1,945 | 22.81 s | 7.4 | 3,802 us |
| 16 | 648 | 143.60 s | 15.5 | 23,933 us |

CPU per request grows ~quadratically in the number of in-flight requests
(88 us x 16^2 = 22.5 ms, measured 23.9 ms).

`examples/net/http-handler.lisp` -- the same server path but a handler that barely
allocates -- shows the same wall, later:

| concurrency | rps | CPU per request |
| --- | ---: | ---: |
| 1 | 12,419 | 58 us |
| 4 | 30,599 | 65 us |
| 8 | 28,412 | 153 us |
| 16 | 4,576 | 3,115 us |

So it scales properly until the work per request crosses a threshold; the more
casts a request executes, the earlier the collapse. Both JDK backends are immune
(the same program on the JVM does ~33,500 rps at c=8 and keeps scaling).

## The profile says it is one function

`sample <wasmtime pid> 4` during the c=16 run, "sort by top of stack":

```
TypeRegistry::is_subtype_slow   40058      <- ~90% of all samples
__psynch_cvwait                  2791
mach_msg2_trap                   2709
semaphore_wait_trap              2709
```

and the call chain under it is JIT frames (`??? in <unknown binary>`) ->
`wasmtime::runtime::vm::libcalls::raw::is_subtype` -> `is_subtype_slow`. The same
sample at **c=1** puts `is_subtype_slow` at 138 samples, with the threads otherwise
idle. The per-request libcall COUNT does not change with concurrency -- only its
cost does, which is the signature of contention on the engine-global type registry
(one `TypeRegistry` is shared by every instance in the engine), not of extra work.

## What the compiler emits

`wasm-tools print` of the `--component` output of `examples/net/httpbin.lisp`:

```
ref.cast       26522      ref.cast (ref i31)  7346
ref.test       15039      ref.cast (ref 3)    7096   ; the cons struct
call_ref           0      ref.cast (ref 47)   2356
call_indirect      0      ref.cast (ref 35)   1723
```

There are no funcref casts and no indirect calls at all: every one of these is a
value-model cast -- unbox an `eqref` into `i31`, into the cons struct, into a
boxed i64, into an eqref array. The struct types are plain (implicitly final)
members of one rec group.

## What it is NOT (all measured, all no-ops)

- `-W threads=y`, `-W shared-everything-threads=y`, `-W component-model-threading=y`
  -- these are GUEST proposals; the backend emits no atomics, no shared memory,
  no thread ops, so they only relax validation. c16 stayed 578-659 rps in all three.
- `-O pooling-allocator=y|n`, `-O pooling-memory-keep-resident`,
  `-O pooling-table-keep-resident` -- within noise.
- Instantiation cost: the top level (and its output) runs 146 times across 18,000
  requests, so instances are recycled, not per-request.
- The application: the collapse is identical for a handler that only formats a
  string, and identical across two unrelated echo programs.
- A GC-heap sizing problem: `-O gc-heap-reservation` cannot even be lowered,
  because the module declares a 4 GiB maximum memory and wasmtime's pooling
  allocator requires `memory_reservation == gc_heap_reservation`. Worth revisiting
  separately -- a serve component reserving 4 GiB of GC heap PER concurrent
  instance is its own smell.

## Where to start

The question the fix has to answer is **why a `ref.cast` to a final concrete
struct type takes a host libcall at all** instead of the inline type-id check
Cranelift can emit. Two independent attacks, both worth measuring before choosing:

1. **Make the checks inlinable.** Determine which of our cast shapes miss
   wasmtime's fast path (an `i31` test is a tag check and cannot be the problem;
   the concrete-struct casts are the suspects). Emitting the struct types
   explicitly `final`, or outside a shared rec group, or with a declared
   supertype chain shallow enough for the inline supertype-vector check, are all
   cheap experiments -- build one 20-line module per shape and count the libcall
   with `sample`. If wasmtime simply has no inline lowering for this shape, say so
   in the `.kb` file and take attack 2.
2. **Emit far fewer casts.** 26,522 casts for this program means the value model
   round-trips through `eqref` constantly. Keeping a typed local across a
   sequence of operations on the same object (cast once, reuse) and typing the
   internal calling convention more precisely would cut the count directly, and
   that win is not conditional on any wasmtime behaviour -- it also shows up in
   the c=1 numbers.

Both are wasm-GC codegen changes, so `--no-gc` and the JDK backends must come out
byte-identical, and the four-backend E2E has to stay green.

Related: `.kb/no-gc-scalar-wasm.md` (the `--no-gc` value model, which pays none of
this), `.kb/concurrent-served-requests.md` (states the WASM backends are
single-threaded by construction -- that is true and is NOT what this is).

## Reproducing

```bash
rontolisp examples/net/httpbin.lisp -o httpbin.wasm --component
wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y \
  --addr 127.0.0.1:8080 httpbin.wasm &
ab -q -n 6000 -c 1  'http://127.0.0.1:8080/get?a=1&b=two' | grep 'Requests per second'
ab -q -n 6000 -c 16 'http://127.0.0.1:8080/get?a=1&b=two' | grep 'Requests per second'
sample $(pgrep -f 'wasmtime serve') 4 -f /tmp/s.txt   # during the c=16 run
awk '/Sort by top of stack/,0' /tmp/s.txt | head
```
