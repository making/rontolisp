# Running a program on every backend: manual verification, native E2E, examples

The invocation recipes `CLAUDE.md` refers to. `.kb/test-execution.md` covers how the
suite SEQUENCES and what it assumes; this file covers how to RUN each leg by hand.

## Verifying Output Manually (all four backends)

A program is "verified" only when it has run on **all four**. The component path uses a
different I/O adapter (and entropy/clock source), so it can diverge from Preview 1.
Assumes wasmtime 47+, which enables wasm-GC and exception-handling by default.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
echo '(print (+ 1 2))' > test.lisp

java -jar $JAR test.lisp                                                    # interpreter
java -jar $JAR test.lisp -o Prog.class && java Prog                         # JVM (path-free name, or --class-name)
java -jar $JAR test.lisp -o test.wasm && wasmtime run test.wasm             # WASM preview 1
java -jar $JAR test.lisp -o test-comp.wasm --component && \
  wasmtime run test-comp.wasm                                               # WASM component (WASI 0.3)
```

`handler-case`/`ignore-errors`/`unwind-protect`/`catch`/`throw`, an async component
(incl. every fetch/serve program), and a cross-lambda `return-from`/`go` all compile in EH
mode. A fetch component also needs `-S http=y`.

## Native Image E2E (run locally before every push)

`./mvnw test` **skips `CiSpecE2eTest`** (`-Drontolisp.binary` unset), so a stale
`ci-spec.yaml` expectation only fails in CI. Reproduce it after editing `ci-spec.yaml` or
changing anything that can shift cross-backend output:

```bash
./mvnw -Pnative clean package -DskipTests
./mvnw -Dtest=CiSpecE2eTest -DfailIfNoTests=false -Drontolisp.binary="$PWD/target/rontolisp" test
```

A failure prints `[case '<name>' on <BACKEND>`; re-run step 2 only unless Java sources changed.
The corpus runs on each backend TWICE -- default and `--simd`, so the leg is `<BACKEND> --simd` --
because `--simd` changes the packed-array REPRESENTATION and a matrix counting only backends misses
half of every accelerated primitive (`.kb/vec.md`, "The E2E `--simd` axis").

### What a leg costs (per-command timeout 300 s)

Measured 2026-10-04, native binary, load average ~5-15, scalar / `--simd` alike: interpreter
27-36 s; JVM compile 8-10 s + run 19-22 s; P1 compile 10 s + run 12-13 s; component compile
11 s + run 14-15 s. Under load ~170 a leg runs ~2.5-3x longer. Before that day P1 ran 158 s
and the component 173 s idle / 426 s loaded (one timeout), and the time was not the corpus:
`runtime-package-api` alone was 79-95% of every compiled leg (a quadratic dedup in the
package walk, `.kb/packages.md` "The enumeration universe"), and
`landing-pads-read-fresh-references-after-a-collection` 45% of the interpreter's (5M
interpreted `cons` iterations; now the same cells by `make-list`, 250 per iteration).
Later that day `_intern` became a hash table: on the jar, the program's own time in the P1
and component legs went ~9.5 s -> ~6.9 s, `runtime-package-api` ~4.9 s -> ~2.5 s of it.
The next day the eval mirror stopped recording `%mv-spill` (`.kb/eval-runtime.md`): P1
6.6 s -> 2.4 s, component 7.1 s -> 2.5 s, JVM 21.2 s -> 19.1 s, `runtime-package-api`
0.8 s on both WASM legs and 2.8 s on the JVM (its first walk runs cold).
**Find the case that owns a leg before touching the timeout**: prepend
`(cl:format cl:*error-output* "~&@@T ~A ~A~%" "<case>" (cl:get-internal-real-time))` to each
case of the concatenated program, run the leg, and diff consecutive stamps. What owns the
interpreter leg now is the constant-stack cases (1M / 100,000-deep tail calls, ~45%).

## Examples Suite

`ExamplesE2eTest` runs every example in `examples/examples.yaml` on every backend it
declares. `./mvnw test` skips it, so run it after touching an example or a surface they
exercise:

```bash
./mvnw clean package -DskipTests
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test
# narrow it while iterating: -Drontolisp.examples.only=cloudflare
# ...but `only=` is a plain SUBSTRING match on the example's path, not a directory name.
# `only=llm` also matches `ml/tiny-llm.lisp` and `examples/llm-from-scratch/` -- 72 legs
# instead of 39. Anchor a directory with a trailing slash: `only=llm/`.
```

It is the longest run in the repo. **Split it with `-Drontolisp.examples.only=` from the
start** -- one slice per surface -- and run each slice to completion in the foreground. A
run detached into the background loses its result if the session ends before it finishes,
which is indistinguishable from never having run it.

