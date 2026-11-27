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

## The wasmtime pin

One version everywhere, moved by one commit: `ci.yaml` (every `wasmtime/setup` step, the
`native-image-verify` job's included -- it was `latest` until 2026-11-22, so a release could change
`wasmtime run` under `NativeOutputE2eTest` with no commit), `bench-report.yaml`,
`size-report.yaml`, `wasmtime-image.yaml` with `.github/docker/wasmtime/Dockerfile`, and
`rontolisp-native` (`Cargo.toml`'s two `=` pins, `precomp/Cargo.toml`'s Cranelift,
`abi`'s `wasmtime_version!`: `.kb/native-output.md`). The documented floor (`47+`) is a
separate statement. What a bump re-measures, with what l24 found on 51.0.0 (2026-11-22):
the TLS write tail and `serve`'s wasi:tls and the draft's WIT ([tcp-sockets.md](tcp-sockets.md),
"WASM TLS"), the landing-pad wat ([wasm-landing-pad-refresh.md](wasm-landing-pad-refresh.md)), the
semi-space band ([wasm-gc-heap-pregrow.md](wasm-gc-heap-pregrow.md)), stack switching and the
component model's threads ([java-frontend.md](java-frontend.md)), object sizes
([wasm-gc-object-size.md](wasm-gc-object-size.md)), the `--native` sizes and start-up
([native-output.md](native-output.md), "Numbers"), the stubs on the oldest CPU when the C
compiler defaults above it (`CFLAGS=-march=x86-64-v3 rontolisp-native/build.sh --test`, and
each mnemonic's count in the stubs equal to a plain build's, which a C build outside cc-rs
would break; a compiler defaulting to `x86-64` hid 51.0.0's AVX from the local run,
[native-output.md](native-output.md), "Traps"), and the bench report's wasm column
(`bench-report/measure.sh rontolisp-wasm`, each version first on `PATH` in turn). On 51.0.0
against 49.0.0 (the same `.wasm`, best of five, two rounds each, ms): startup 41 / 39, fib
146 / 141, mandelbrot 750 / 793, matmul 1109 / 1134, sieve 281 / 309, sort 384 / 383, hash
508 / 459, string 671 / 663, clos 220 / 206, bignum 925 / 975, list 176 / 169 -- every row
within the report's own 10%.

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
classes"); CI's native-image-verify job runs it.
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
A compile leg can grow with no case to blame: the computed `change-class` arms, quadratic in the
corpus's classes, took `compile-jvm` past the timeout on macOS CI on 2026-11-05 ([clos.md](clos.md),
"`change-class`"). `-Drontolisp.wasm.debug-func-sizes` names what the bytes went to, a JFR
recording of a compile what the time went to.

## Examples Suite

`ExamplesE2eTest` runs every example in `examples/examples.yaml` on every backend it
declares. `./mvnw test` skips it, so run it after touching an example or a surface they
exercise:

```bash
./mvnw clean package -DskipTests
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test
# narrow it while iterating: -Drontolisp.examples.only=cloudflare
# ...but `only=` is a plain SUBSTRING match on the example's path, not a directory name.
# `only=llm` also matches `ml/tiny-llm.lisp`. Anchor a directory with a trailing slash: `only=llm/`.
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
`javaSourceDep` (2026-11-20, `java/yavi/Member.java`) passes Maven coordinates to every leg as
`--java-source-dep`. Before the legs the suite resolves each sources jar as the option does
(Maven's settings and `~/.m2`, then Central), so CI's examples job fetches it from Central and
the legs read it from `~/.m2`; offline without it the legs are skipped with the resolver's reason
(`ExamplesE2eTest#aSourceDependencyNowhereToBeHadSkipsTheLegs`). Not opt-in on its own: the
suite already is, and a skip that needs the network would leave CI without the leg.
`wasmtime` (2026-11-27, `wit/sockets/Echo.java`): flags `wasmtime run` takes ahead of the
module, before `env`'s `--env`, on the `wasm` and `wasm-component-run` legs -- what the host
grants (`[-S, inherit-network=y]`), so a component calling a host interface itself RUNS in the
suite (`ExamplesE2eTest#theLegsOptionsGoAheadOfACommandLineAndAfterAFile`).
`javaSourcePath` (2026-11-21, `java/guestbook/Guestbook.java`) passes directories of the
repository as one `--java-source-path`, resolved and joined as `systemPath`'s
(`ExamplesE2eTest#thePathsAndJavaSourceDepTakeOneOrAList`).
**A Maven project's example names neither** (2026-11-23): an entry whose file is under
`src/main/java` of a directory with a `pom.xml` (`examples/java/shiori/` and the other six
applications that use a library) takes both from the pom when the manifest is read
(`ExamplesE2eTest#withItsProject`, `e2e/ExampleProject`): the project's `src/main/resources`
first, a dependency that is a module of this repository (a root directory named by its
artifact id, of the same coordinates; another version fails) as its `src/main/java` and
`src/main/resources` with its own compile dependencies after it, every other `compile`,
`provided` or `system` one as a coordinate. So a version lives in the example's own
`pom.xml` alone (no parent, l51), and the legs read develop's modules with no network. `FetchSpecE2eTest`'s served
examples and `ClPostgresE2eTest`'s shiori read the pom the same way
(`ExamplesE2eTest#everyPathDirectoryInTheManifestExists` pins the shiori's). A module's own
`provided` dependencies (servlet-httpserver's jspecify, l53) are on no source path: they are
the jars of a JDK leg's javac over the module's sources alone (`ExampleProject.provided`;
for a `sourcePath` directory of the HTTP server spec, `ExampleProject.provided(directory)`).

It is the longest run in the repo. **Split it with `-Drontolisp.examples.only=` from the
start** -- one slice per surface -- and run each slice to completion in the foreground. A
run detached into the background loses its result if the session ends before it finishes,
which is indistinguishable from never having run it.

