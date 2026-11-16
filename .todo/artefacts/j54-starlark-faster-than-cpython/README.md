# Starlark against CPython: the programs and the measurements (2026-11-16)

The numbers of `.kb/starlark-frontend.md`, "Against CPython".

- `time.sh` -- compiles a program for the JVM, WASM preview 1 and the component and prints
  the best of three wall times of the whole process for each, for `python3` and, with
  `OTHERS=1`, for Go Starlark (`~/.local/bin/starlark -recursion`) and Java Starlark
  (`~/.local/bin/starlark-java`, recursion and sets allowed); then the output sizes and
  whether every runner printed what `python3` printed.
- `bench.star`, `fib.star`, `loop.star`, `calls.star`, `mixed.star` -- the five programs
  the item started from (integers, calls, `range`), copied from the two artefact
  directories that made them.
- The six this item added, each about a second of CPython and each printing a checksum:
  `listidx.star` (`a[i]`, `a[i] = x`, `a[i] += x`), `dicts.star` (get, set, `in`, the three
  iterations, int and string keys), `strings.star` (`+`, `+=`, `str`, `join`, `split`,
  `format`, `%`), `comps.star` (list and dict comprehensions, nested, with a condition),
  `sorting.star` (`sorted` of ints and strings, by a key, reversed; `min`, `max`,
  `reversed`), `methods.star` (methods on parameters and list elements).

```bash
export JAR=$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
D=$PWD/.todo/artefacts/j54-starlark-faster-than-cpython
cd "$(mktemp -d)" && OTHERS=1 $D/time.sh $D/*.star
```

Where the time goes was read off two sample profiles: the JVM's with JFR
(`java -XX:StartFlightRecording=..` with `jdk.ExecutionSample` every 1 ms, then
`jfr print --events jdk.ExecutionSample`), wasm's with `wasmtime run
--profile=guest,prof.json,1ms`, whose `<wasm function N>` is the module's function index
less its imports -- `-Drontolisp.wasm.debug-func-sizes=true` names each index at compile
time.
