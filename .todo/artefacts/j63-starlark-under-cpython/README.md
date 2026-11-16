# The Starlark programs still over CPython on wasm: the tools and the measurements (2026-11-16)

The numbers of `.kb/starlark-frontend.md`, "Where the time is: a sampler of the machine
code", and of the sections it points to. The eleven programs and `time.sh` are
`.todo/artefacts/j54-starlark-faster-than-cpython/`.

## The sampler

`wasmtime run --profile=guest` samples at function entries and loop back edges, so it
charges a function's time to whatever it calls next (`.kb/wasm-int-fusion.md`,
"Profiling trap"); `perf` needs `kernel.perf_event_paranoid` below what this machine
has. `sample.c` is a ptrace sampler that needs neither: it runs the command, stops
every thread that is on a CPU each interval and records its program counter, the
return addresses up its frame-pointer chain (wasmtime's code keeps frame pointers) and
the top of its stack. `wasmtime run --profile=perfmap` writes `/tmp/perf-PID.map`,
which names each function's address range.

```bash
export JAR=$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
A=$PWD/.todo/artefacts/j63-starlark-under-cpython
cd "$(mktemp -d)"
$A/profile.sh $A/../j54-starlark-faster-than-cpython/sorting.star 20 --callers
$A/profile.sh $A/parts/o3.star 8 --offsets 'FUNC_STR_ORDER#136'   # then read 136.annot
```

- `report.py` -- self and inclusive samples per function (named by the compile's
  `-Drontolisp.wasm.debug-func-sizes=true` listing), `--callers` the leaf and its
  caller, `--offsets NAME#N` the samples per instruction offset of one function. A
  sample in host code (an allocation, `array.copy`, a collection) is charged to
  `[host] under F`, F the first wasm function on its stack.
- `annot.py` -- one function's machine code with the samples that hit each
  instruction. The code is binutils' `objdump -d` of the `.cwasm`, which is an ELF
  file: `wasmtime objdump` stops at the jump table of the first `br_table`.
- `ab.sh N a.cwasm b.cwasm ..` -- alternating runs, the least user seconds of each.
- The sampler costs the run about as much again in system time; the shares are what
  to read, not the seconds.

What it showed that the guest profiler and hand-edited modules had not: 33% of
`_str_order`'s samples under `sorted(words)` are the four instructions that load an
object's header first (two string structs, two byte arrays: cache misses, 50,000
strings do not fit the second-level cache); `(find d "eEfFgG")` at the head of
`%starlark-conversion` was 15% of `strings.star`; a list copied by `replace` was two
thirds `_idx_ref`, `_arr_check_rank` and `_arr_set`; `str(i)` a quarter of
`strings.star` through the capture mode and `_str_fresh`.

## The parts

`parts/*.star` are the five programs cut into what each does, one feature a file, so
that a feature's time can be set against CPython's (`python3 parts/o3.star`):
`m1a` append through a `def`, `m1b` pop, `m1c` `list(xs)` and `extend`, `m2` a dict
through a `def`, `m3` `split`, `m4` `upper().lower().strip()`, `m5` `startswith`,
`endswith`, `find`, `count`; `s1` `+` and `+=` with `str`, `s1a` `str` alone, `s2`
`join` and `split`, `s2a` `join`, `s3` `format` and `%`; `o1` `sorted` of ints both
ways, `o2` by a key, `o3` of strings, `o4` `min`, `max`, `reversed`.

- `sortcheck.star` -- `sorted` over 10 to 5,000 ints of spans from 1 to past 2^32,
  negative ones, by a key both ways with the stability checked, floats, strings,
  Booleans, tuples: the same output from Go and Java Starlark and from the four
  backends (CPython prints a string's quotes otherwise).
- `ms.lisp`, `ms2.lisp` -- a merge sort over two `(unsigned-byte 32)` vectors in plain
  Common Lisp, as written and with every index masked into a known range
  (`.kb/wasm-unboxed-locals.md`, "The exact flavour"): 25 and 21 ms a sort of 50,000 on
  wasm, where the merge over conses the library had was 20. Not the way: the radix
  sort made instead is 8.
- `general-move.lisp` -- the radix pass carrying an item beside each key in two general
  vectors: 66 ns a move on wasm with the vectors declared `(simple-array t (*))`, 21
  without the declaration (118 and 85 on the JVM). The library carries the item's place
  in a packed vector instead.
- The JVM and the radix sort: `java -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation`
  over `sorting.star` shows C2 at the pass for 0.7 s a compilation (109 KB of code from
  495 bytes) and at it again after each branch it had not seen; `/usr/bin/time` 13 s of
  user time against 5 before for the same 3.1-3.6 s of wall time.
