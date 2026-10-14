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
`-Dtest=ClojureNativeLoweringE2eTest` with the same binary checks that it lowers the Clojure
corpus (`clojure-spec.yaml`, the Clojure examples) to the IR this JVM does: the binary reads a
host form's class from class files where the JVM reflects (`.kb/clojure-frontend.md`, "Host
classes"); CI's native-image job runs it.
The corpus runs on each backend TWICE -- default and `--simd`, so the leg is `<BACKEND> --simd` --
because `--simd` changes the packed-array REPRESENTATION and a matrix counting only backends misses
half of every accelerated primitive (`.kb/vec.md`, "The E2E `--simd` axis").

### What a leg costs (per-command timeout 300 s)

A leg's cost is dominated by a few cases, not the corpus as a whole: `runtime-package-api`
once owned most of every compiled leg (a quadratic dedup in the package walk, since fixed --
`.kb/packages.md` "The enumeration universe") and
`landing-pads-read-fresh-references-after-a-collection` much of the interpreter's (it now
builds the same cells by `make-list`). `_intern` later became a hash table and the eval
mirror stopped recording `%mv-spill` (`.kb/eval-runtime.md`), each cutting the legs further.
**Find the case that owns a leg before touching the timeout**: prepend
`(cl:format cl:*error-output* "~&@@T ~A ~A~%" "<case>" (cl:get-internal-real-time))` to each
case of the concatenated program, run the leg, and diff consecutive stamps. What owns the
interpreter leg now is the constant-stack cases (1M / 100,000-deep tail calls).

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

A `deps.edn` project's entry names a file in the project and a `cli` (`[-M:alias]`, `[test]`):
every leg runs in a copy of that directory with its options ahead of the command line (after a
leading `test`); `javaSources` compiles an example's own Java library onto `--java-classpath`
(`ExamplesE2eTest#theLegsOptionsGoAheadOfACommandLineAndAfterAFile`). Both since 2026-10-12.
`javaInterop: true` marks a program calling Java as it runs: under the native binary (CI's
driver) its `interpreter` leg is skipped, since the binary interprets no `java:`; the exec jar
runs it (`ExamplesE2eTest#javaInteropGatesTheInterpreterLegOfTheNativeBinaryOnly`). Added
2026-10-12, when `clojure/http-client.clj` (`java.net.ServerSocket`) and
`clojure/java-library/main.clj` failed that leg in CI.

It is the longest run in the repo. **Split it with `-Drontolisp.examples.only=` from the
start** -- one slice per surface -- and run each slice to completion in the foreground. A
run detached into the background loses its result if the session ends before it finishes,
which is indistinguishable from never having run it.

