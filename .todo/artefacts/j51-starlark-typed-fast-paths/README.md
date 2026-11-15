# Typed fast paths in the Starlark lowering: the measurements (2026-11-15)

The numbers of `.kb/starlark-frontend.md`, "Typed fast paths". Each candidate of the item
was measured before it was made, as a hand-edited lowering, and again once made.

- `time.sh` -- compiles a program for the JVM, WASM preview 1 and the component and prints
  the best of three wall times of each.
- `*.star` -- one micro benchmark per candidate: `fib` (tested integer operators), `loop`
  (the counting loop, known ints), `calls` (direct calls with defaults and names), `swap`
  (a tuple of targets), `lit` (constant lists and tuples), `truth` (known Booleans and
  ints as tests), `listops` (`pop`, `insert`, `remove`), `hof` (a function called through
  a parameter), `mixed` (Collatz and a sieve).
- `loop1.lisp` .. `loop4.lisp`, `fib1.lisp` .. `fib4.lisp` -- the generic lowering of
  `loop.star` and `fib.star` edited by hand, run through the unchanged compiler:
  `loop1` the counter alone, `loop2` plus inline arithmetic, `loop3` the range's vector
  with inline arithmetic, `loop4` the counter as the loop variable itself; `fib1` a test
  only where one operand is a literal, `fib2` every operator tested, `fib3` plain Common
  Lisp, `fib4` no block.

```bash
export JAR=$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
D=$PWD/.todo/artefacts/j51-starlark-typed-fast-paths
cd "$(mktemp -d)" && $D/time.sh $D/*.star $D/*.lisp
```
