# CLAUDE.md

```bash
./mvnw clean spring-javaformat:apply compile   # compile
./mvnw clean spring-javaformat:apply package   # executable JAR (-exec classifier)
./mvnw spring-javaformat:apply test            # all tests
```

Two modules live OUTSIDE the root reactor, each with its own `pom.xml`, because each needs
a dependency the core libraries may not have. `./mvnw test` does not build either:

```bash
./mvnw -f docs-tool/pom.xml test               # the documentation site generator
./mvnw -f rontolisp-maven-plugin/pom.xml test  # the compile-src/main/lisp plugin
```

`rontolisp-maven-plugin` depends on the rontolisp artifact by coordinates, so a SNAPSHOT
build needs `./mvnw install -DskipTests` first, and its own `install` is what
`MavenBuildE2eTest` (`-Drontolisp.plugin.e2e=true`) needs.

A third, `rontolisp-native/`, is a Cargo workspace (rustc >= 1.96): the wasmtime
precompile shim and the runner stub a native executable is made of (`.kb/native-output.md`).

```bash
rontolisp-native/build.sh --test   # host shim + stub into its target/resources, then the Rust tests
```

Three documentation layers, no duplication between them:

- `doc/en/**` + `doc/ja/**` -- user-facing behavior and examples (rendered by `docs-tool/`,
  verified by `DocExamplesTest`).
- **This file** -- architecture, package rules, workflows.
- `.kb/*.md` -- one file per topic: the invariant plus its full mechanics. Index:
  `.kb/README.md`. **Before changing behavior in any area, grep `.kb/` for the topic and
  read the matching file** -- this file does not list the constraints.

A premise recorded in `.kb/` is a measurement, not a law: it was true when it was written
and the code has moved since. When a number you measure contradicts one, **the finding is
the deliverable** -- write the new numbers and the date into that file rather than working
around it or forcing the planned change through. A change that measurement says is not
worth its blast radius is a result; land the measurement, not the change.

## Architecture

```
Source string
  -> SourceLanguage (eval pkg): read + lower -> List<LispVal> (core forms, the IR)
    -> [LispMacroExpander] -> expanded AST               # cond/and/or/setf -> if/let/progn/setq/rplaca/rplacd
    -> LispEvaluator (eval pkg)                          # interpret
    -> JvmLispCompiler (codegen.jvm) -> byte[] (.class)
    -> WasmLispCompiler (codegen.wasm) -> byte[] (.wasm)
```

A language is something that PRODUCES core forms and then joins the existing pipeline
(`CompileFrontend.expand`, `LispEvaluator`), never something beside it. `SourceLanguage`
is the one seam from user source to forms (`.kb/source-language.md`): it owns the read
-- including the `#.` decision -- and picks the language per file from its extension,
with a `--source-language` CLI override for the entry source, so one program may mix
languages file by file. No class outside the seam reads user source through `LispReader`
(`SourceLanguageSeamTest` pins this); library source shipped in the jar stays Common
Lisp and keeps its direct reads, as do the runtime data reads and the `.asd` scans.
No new package: the seam lives in `eval`, reachable from `cli`, `web` and the
interpreter's `load` under the existing graph. The second language is `scheme` -- an
EXPERIMENTAL subset of R7RS-small for `.scm`, lowered to the same core forms so that no
backend learns a Scheme name; its run-time helpers are Common Lisp source spliced like any
other library (`.kb/scheme-frontend.md`).

`am.ik.jvm`, `am.ik.wasm`, `am.ik.wit`, `am.ik.gpu` and `am.ik.objc` are **language-independent**
libraries; none may import rontolisp packages or external dependencies. `am.ik.gpu` is the
device half of `--gpu` -- CUDA and Metal behind one sealed `GpuDevice` seam -- and imports
nothing at all (`.kb/gpu.md`); the interpreter reaches it
through `eval/LinalgGpu` -> `eval/LinalgGpuKernels`, and the JVM backend EMBEDS its class
files in the compiled output (`codegen/jvm/JvmGpuRuntimeBuilder`) -- so a class added to
that package must be added to the list that travels. `am.ik.objc` is the Objective-C runtime and
AppKit through FFM (`.kb/objc.md`), reached from `eval/ObjcInterop` -> `eval/ObjcBridge` only,
so `-Pweb` substitutes the one entry class; the JVM backend EMBEDS it the same way as `am.ik.gpu`
(`codegen/jvm/JvmObjcRuntimeBuilder`, whose class list must follow the package, in an order the
verifier accepts); the `appkit` widget layer is `appkit.lisp`, the `metal` drawing surface
`metal.lisp` and the `scene` 3D viewer `scene.lisp`, all shipped like `linalg.lisp` and spliced
on the compile path by `AppKitLibrary` / `MetalLibrary` / `SceneLibrary`'s `process` (in dependency
order, `.kb/geom.md`). None of the four compiles to WASM -- `AppKitLibrary.firstObjcReference`
answers for all of them and `CompileFrontend` refuses by the reference.

Package dependency direction (no cycles allowed):

```
cli -> eval, compiler, codegen.*, macro, reader, format, am.ik.wit
codegen.jvm -> compiler, macro, runtime, am.ik.jvm, am.ik.gpu, am.ik.objc
codegen.wasm -> compiler, macro, am.ik.wasm, am.ik.wit
compiler -> macro, runtime, rontolisp (AST types only), am.ik.wit
eval -> macro, compiler, reader, scheme, runtime, rontolisp (AST types only), am.ik.gpu, am.ik.objc
scheme -> reader, rontolisp (AST types only)
macro -> reader, rontolisp (AST types only)
reader -> rontolisp (AST types only)
format -> (nothing)
runtime -> (nothing)
am.ik.gpu -> (nothing)
am.ik.objc -> (nothing)
```

- `runtime` imports nothing at all, project or otherwise — not even the build's
  `@Nullable`, which is `RuntimeVisible` and would follow the class out. Its classes are
  COPIED into a compiled program's output (beside a `.class`, inside a `.jar`, into the
  Maven plugin's `target/classes`), so anything they imported would become that
  artifact's dependency. What travels and when: `.kb/jvm-export.md`, "What travels" — the
  `rontolisp:jvm-export` handle boundary types, the embedded HTTP server a
  `rontolisp:http-handler` program serves through (`.kb/http-server.md`), and — ONE
  stated exception to importing nothing — the `jakarta.servlet` transport pair only a
  `-o app.war` output carries (`provided` scope; the container supplies it by
  definition). **A class added to this package must be added to a travelling list**
  (`JvmRuntimeClassFilesTest` fails otherwise).
- `format` depends on nothing, not even `reader`: it needs the source verbatim and has its
  own lossless CST front end (`.kb/formatter.md`).
- `compiler` holds backend-shared, backend-FREE front-ends and depends on no backend.
- `macro` sits ABOVE `reader` so an expander may build injected AST by reading Lisp source;
  therefore the root `rontolisp` package must never import `macro`.
- **A compile-time AST pass that reads a file belongs in `eval`, not `cli`, and must read
  through `SourceLoader`** -- the browser playground (`src/web/java`) never touches `cli`
  and has no filesystem (`.kb/wit.md`).
- **The compile path's front end is `cli/CompileFrontend`, not a stretch of
  `RontoLispCli`** -- the read, the `(load ...)` inlining, the library splice chain, the
  WIT lowerings and the tree-shaker, in one order-critical place. Every backend and every
  embedder goes through it; a JVM embedder goes through `cli/JvmSourceCompiler`, which is
  the same backend half the CLI's `-o out.class` runs (`.kb/jvm-export.md`).
  **The pass pipeline itself is `CompileFrontend.expand`, and nothing may restate it** --
  `run` is the read plus `(load ...)` inlining in front of it, and a caller that needs its
  own source loader calls `expand` directly rather than copying the order. The two corpus
  guards used to copy it and drifted: eight passes behind when a `tokenizer:` case joined
  `ci-spec.yaml` and went red, ten when that was finally fixed, and -- invisibly to any
  census of pass NAMES -- `VecLibrary` applied in the wrong POSITION, so a `vec:` reference
  introduced by the Gray-streams / usocket / unread-char rewrites was spliced by the CLI
  and missed by both guards. The `asdf:load-system` library E2Es carried the same copy,
  stopped six passes in, and `JvmOsrBackedgeCorpusTest` kept a fifth until 2026-09-19 --
  eleven splices behind, printing ten `TOKENIZER:... is undefined` warnings on every GREEN
  run. Every test now reaches the front end through
  `src/test/.../cli/CompileFrontendAccess` (or, for a JVM target, `JvmSourceCompiler`).
  A list of libraries exported for a caller to fold is the same bug with more steps.

Where behavior must be identical across the interpreter, the JVM and both WASM backends,
the topic's `.kb` file says so and names the pinning test -- change the file and the test
together, never one backend in isolation.

## Development Workflows

Adding a built-in function, a macro or a special form: `.kb/adding-primitives.md` -- the
per-surface checklists, including the two traps that compile clean and then fail silently
at the call site (a `rontolisp:` name in the wrong dispatch chain; a macro registered in
one of the two required places).

### Documentation Site

Every doc change is mirrored across `doc/en/**` and `doc/ja/**` in the same commit -- same
file set, same headings, byte-identical code fences; only prose and titles are translated.
Layout and preview: `.kb/documentation-site.md`. `docs-tool/` is not in the root reactor, so
run `./mvnw -f docs-tool/pom.xml test` after touching `doc/` layout.

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixShownResults test   # rewrite shown results
./mvnw -Dtest=DocExamplesTest test                                            # verify
```

Running a program on all four backends by hand, the native-image E2E leg and the examples
suite: `.kb/running-backends.md`. A program is "verified" only when it has run on all four.

### Waiting for a Long Run

**Never detach a test run and end the turn to wait for it.** The turn ends, the run keeps
going, and nothing wakes up to read the result -- a stall indistinguishable from a crash,
and the usual reflex (poll again) reproduces it. What works is a blocking foreground wait
on the process:

```bash
./mvnw ... test > run.log 2>&1 &          # or an already-running build
tail -f --pid=$! /dev/null                 # blocks until it exits; raise the tool timeout
echo "exit=$?"; grep -E 'BUILD (SUCCESS|FAILURE)' run.log
```

Three ways a run reports green when it is not:

- **Two maven runs in one worktree** corrupt `target/` and void BOTH results. One run per
  tree, always.
- **Editing `src/` while a run is in flight** means the compile phase saw a mixed tree.
  Freeze the sources until it finishes.
- **A truncated run looks clean**: an orphaned build from an earlier interrupted turn can
  kill it early, leaving zero failures and a short report set. Count
  `target/surefire-reports/*.txt` -- a full `./mvnw test` writes ~280 -- and check the exit
  code, not just the failure counts.

When a session driving subagents hits this, the reliable division of labour is for the
PARENT to run the suite (capturing the exit code) and hand the summary back, with the
worker told not to invoke maven itself.

## Working Alongside Other Sessions

Several sessions push to `develop` at once, so what you tested is not what you push.

- **Take upstream in once, immediately before the final test run** (`git fetch origin`,
  `git merge origin/develop`), so the suite runs over the merged tree.
- **A merge you do AFTER that run does not require re-running the suite.** Merge the
  conflict, push, and let CI cover the combination; a second full pass per push costs more
  than it finds.
- In a worktree, `develop` is held by the main tree: `git checkout develop` and
  `git pull --rebase` are unavailable. The sequence is `git fetch origin` ->
  `git merge origin/develop` -> `git push origin HEAD:develop`, retried from the fetch if
  the push is rejected.
- A semantic conflict passes `git merge` cleanly. When both sides touched one mechanism,
  read the other side's diff before pushing -- two changes to the same representation can
  each be correct alone and emit nonsense together.
- **Claim a `.todo/NNN` number, never pick one**: run
  `.todo/claim-number.sh "<why>" [count]` and use what it prints. Reading `.todo/` for
  the highest number cannot work however fresh the fetch is -- two differently-named
  `NNN-*.md` files MERGE cleanly, so a duplicate survives to be found days later (633
  and 634 both happened on 2026-09-02). The counter is one file, `NEXT`, on the orphan
  branch `todo-seq`: claiming is a push to it, so racers fight over the same file and
  git rejects the loser, which retries. It also reads develop's live files and
  `.todo/history/` rows on every claim and skips anything already taken, so a number
  filed without the script heals itself -- do NOT cross-check by hand afterwards.
  `todo-seq` shares no history with `develop` and must never be merged into it.
  Row format and the duplicate-resolution rule: `.todo/.history.md`.

## Requirements

- Java 25+
- No external dependencies in the core libraries (reader, eval, codegen, `am.ik.*`).
  `docs-tool/` is a separate Maven project and may use flexmark/snakeyaml.
- Modern Java (records, pattern matching, sealed types, text blocks). The package graph
  is a DAG, and a class-level reference cycle is allowed only inside the designed
  mutual-recursion clusters (sealed hierarchies, dispatch/re-entrancy hubs) --
  `PackageCycleTest` pins both halves and names each allowed cluster with its reason.
- `src/test/resources/ci-spec.yaml` is the single source of truth for `CiSpecE2eTest`. Cases
  share global state and run IN ORDER: the driver concatenates them into one program, runs
  the binary once per backend, and slices the output back per case.

## After Task Completion

- Format Lisp: `java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar format examples/ src/main/resources/ size-report/programs/ bench-report/programs/`
- A GUI change (`objc:`/`appkit:`, `RontoLispCli.main`'s thread hand-over, the embedded JVM blob)
  is verified by hand on `java -jar`, the native binary AND the compiled outputs
  (`-o Counter.class --class-name Counter` under `java Counter`, `-o counter.jar` under
  `java -jar`) with `examples/macos/counter.lisp` -- no test opens a window.
- Web profile: `./mvnw -Pweb compile` whenever `src/web/java` or a signature it overrides
  changed -- `./mvnw test` does not compile it. Run it AFTER the test suite (or `clean` in
  between): it leaves the web source set in `target/classes`, and a later `./mvnw test`
  without `clean` then fails with `NoClassDefFoundError` on excluded classes, which looks
  like a regression and is not one.
- Native profile: `./mvnw -Pnative clean package -DskipTests` whenever `src/native/java` or a
  member it aliases or substitutes changed -- `./mvnw test` does not compile it
  (`NativeSubstitutionsTest` pins the member NAMES from the JVM lane, not the bodies), and a
  `--blas` change is verified on the binary it produces (`.kb/native-downcalls.md`).
- Native E2E (above) whenever `ci-spec.yaml` or cross-backend output changed.
