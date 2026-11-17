# A Starlark program a user wrote, against CPython: the program and the tools (2026-11-17)

The numbers of `.kb/starlark-frontend.md`, "A program a user wrote". The eleven programs
and `time.sh` are `.todo/artefacts/j54-starlark-faster-than-cpython/`, the wasm sampler
`.todo/artefacts/j63-starlark-under-cpython/`, `jfrtop.py`
`.todo/artefacts/j66-starlark-jvm-bulk-arms/`.

- `graph.star` -- `examples/starlark/targets.star` at size: 2,000 targets made by a loop
  (dicts of three string keys, lists of up to three names, `"t%d" % i`), their build
  order, what a change rebuilds, a line of text a target, 40 times. CPython, Go and Java
  Starlark and the four backends print the same checksum.
- `parts/g1_make.star` .. `g5_tail.star` -- the program cut by function: the targets
  made, `build_order`, `rebuilt_by`, `describe`, and `max` / `format(**d)` / `sorted`.
  Parts 2 to 5 make the targets once.
- `abw.sh N prog.star a.jar b.jar ..` -- wasm: alternating runs of each build's module,
  the least user seconds of each. `runsj.sh N prog.star a.jar ..` -- the JVM: N wall
  times of each build's class, in turn (a JVM run of a second varies by a tenth).
- `jfrcomp.py file.jfr [min-ms]` -- the compilations and deoptimizations of a recording
  (`java -XX:StartFlightRecording=filename=c.jfr,settings=profile Prog`): start,
  duration, level, OSR, method, code size.

```bash
export JAR=$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
A=$PWD/.todo/artefacts
cd "$(mktemp -d)"
OTHERS=1 $A/j54-starlark-faster-than-cpython/time.sh $A/j67-starlark-a-program-a-user-wrote/graph.star
$A/j63-starlark-under-cpython/profile.sh $A/j67-starlark-a-program-a-user-wrote/graph.star 40 --callers
```

What a whole-program time hides on wasm: the copying collector's share moves with where
its collections fall. `graph.star` at 1,500 / 2,000 / 2,500 / 3,000 targets ran in 0.74 /
1.17 / 1.17 / 1.60 s where CPython's times are in line (0.39 / 0.53 / 0.66 / 0.76), and
one build was faster than another at three of the sizes and slower at the fourth.
`wasmtime compile -C collector=null` and `run -C collector=null` give the program with no
collection (0.92 s against 1.16 at 2,000; 345 MB). Compare builds on the parts, or at
several sizes.
