# DRAFT -- not filed. Filing needs the user's explicit word (see the open .todo item).

Target: `bytecodealliance/wasmtime`, issue.

---

**Title:** Compile time is quadratic in the number of GC allocations in one function (copying collector, backtracking regalloc)

**Version:** wasmtime 49.0.0 (17830bd3c 2026-09-21), linux x86_64.

**Reproducer.** `min.py` writes one function doing N x `struct.new $s; drop` on an empty struct type:

```wat
(module
  (type $s (struct))
  (func (export "f")
    struct.new $s drop
    struct.new $s drop
    ;; ... N times
  ))
```

```
python3 min.py empty 20000 m.wat
wasm-tools parse m.wat -o m.wasm
time wasmtime compile -W gc=y m.wasm -o m.cwasm
```

**Measured** (`wasmtime compile` wall time, seconds; 64-core host, one function, so
parallel compilation does not help):

| N | default (`copying`) | `-C collector=drc` | `-C collector=null` | copying, `-O regalloc-algorithm=single-pass` | copying, `-O opt-level=0` |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 5,000 | 1.61 | 0.10 | 0.27 | 0.32 | 0.58 |
| 10,000 | 4.79 | 0.15 | 0.61 | 0.71 | 1.94 |
| 20,000 | 15.16 | 0.40 | 1.56 | 1.52 | 4.84 |

A struct with an `i32` or an `eqref` field behaves the same under `copying` (1.36 / 4.36 / 15.99
and 1.24 / 4.14 / 14.76 s). Under `drc`, a two-`eqref`-field struct with an `i31` in one field
(the cons cell of a Lisp list) is also quadratic: 0.95 / 2.84 / 9.26 s.

Cranelift's own pass timings (`WASMTIME_LOG=cranelift_codegen::timing=debug`,
`-C parallel-compilation=n`), N = 10,000, copying: 4,391 ms of compilation passes, of which
**register allocation is 3,824 ms**, egraph 284, lowering 178. The same body under `null`:
286 ms of regalloc.

**What the CLIF shows** (`--emit-clif`, N = 2, copying): the allocation-pointer cell
`v3 = load.i64 readonly can_move vmctx+32` is loaded once in the entry block and used by
every allocation's inline bump (`load.i32 v3`, `store v3`), and every allocation has a cold
block calling the `gc_alloc_raw` builtin. So one value (and `vmctx`) is live across the whole
function and across N calls; we suspect the backtracking allocator's splitting of that one
long range around each clobbering call is the quadratic part. That is a guess from the
shape, not a profile of regalloc2.

**Why it matters to us.** We compile Common Lisp to wasm-GC. A quoted list literal of N
elements is one function with N allocations; a 50,000-symbol list takes 94 s to compile.
Splitting the same 20,000 allocations into helper functions of 64 compiles in 0.2 s, so we
can work around it, but the cost is surprising for straight-line code with no loops.

---

Attach: `min.py` (this directory).
