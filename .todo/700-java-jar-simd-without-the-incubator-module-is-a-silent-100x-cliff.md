# 700. `java -jar prog.lisp --simd` without the incubator module is a 100x cliff that reads as a hang

Difficulty: Low

Found 2026-09-05 while running `examples/llm/llm.lisp` over `Qwen3.5-0.8B-BF16.gguf`
from the exec jar (`.todo/672`'s llama.cpp comparison):

```
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/llm/llm.lisp --simd --parallel -- model.gguf -t 0 -n 68 -i "Once upon a time"
```

prints one line to stderr -- `rontolisp: warning: --simd: jdk.incubator.vector is
unavailable, running the scalar vec:/linalg: kernels; re-run with `java --add-modules
jdk.incubator.vector -jar ...`, or use the native binary.` -- and then runs the 0.8B
forward pass on the scalar `vec.lisp` defuns at ~0.01 tok/s. With the module it is 0.2-3
tok/s. The warning scrolls off under the model's own load line and the run looks hung;
it cost an hour before the cause was read off the first line of a log file.

The mechanics are all correct and documented (`.kb/vec.md`, "Module-absence degrade": the
flag degrades to the scalar reference instead of failing, the same shape `--blas`/`--gpu`
give with no library). What is wrong is the SIZE of the degrade for a `--simd` program
that is an LLM decode loop: a 100x slowdown is not a degrade, it is a hang with a
different name, and a one-line warning is the wrong severity for it.

## Decide

- A hard error for `--parallel` at least: that flag is only asked for by someone about to
  run something large, and "requires `--simd`" is already a hard error on the same path
  (`RontoLispCli.requireSimdForParallel`).
- Or make the jar's manifest carry `Add-Opens`/`Launcher-Agent`-style `--add-modules`:
  `Add-Modules` is NOT a manifest attribute the launcher honours for `-jar`, so this may
  not be available; check before proposing it.
- Or keep the degrade but make the warning unmissable: repeat it at exit with the elapsed
  time, or refuse when the program's first `vec:matvec` is above some size.

Whatever lands, the interpreter twin (`LispEvaluator.setSimd` with `VecSimd.available()`
false) and the compiled `.class` (`_simdInit`'s `LinkageError` catch) should agree.
