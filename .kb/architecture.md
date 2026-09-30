# Architecture: pipeline, package rules, requirements

## Pipeline

```
Source string
  -> SourceLanguage (eval pkg): read + lower -> List<LispVal> (core forms, the IR)
    -> [LispMacroExpander] -> expanded AST               # cond/and/or/setf -> if/let/progn/setq/rplaca/rplacd
    -> LispEvaluator (eval pkg)                          # interpret
    -> JvmLispCompiler (codegen.jvm) -> byte[] (.class)
    -> WasmLispCompiler (codegen.wasm) -> byte[] (.wasm)
```

A language PRODUCES core forms and joins the existing pipeline (`CompileFrontend.expand`,
`LispEvaluator`), never something beside it. `SourceLanguage` is the one seam from user source
to forms (`.kb/source-language.md`): it owns the read -- including the `#.` decision -- and
picks the language per file from its extension, with a `--source-language` CLI override for
the entry source, so one program may mix languages file by file. No class outside the seam
reads user source through `LispReader` (`SourceLanguageSeamTest`); library source shipped in
the jar stays Common Lisp and keeps its direct reads, as do the runtime data reads and the
`.asd` scans. The seam lives in `eval`, reachable from `cli`, `web` and the interpreter's
`load` under the existing graph. The second language is `scheme` -- an EXPERIMENTAL R7RS-small
subset for `.scm`, lowered to the same core forms so no backend learns a Scheme name; its
run-time helpers are Common Lisp source spliced like any other library
(`.kb/scheme-frontend.md`). The third language is `clojure` -- an EXPERIMENTAL small subset
for `.clj`, lowered to the same core forms so no backend learns a Clojure name
(`.kb/clojure-frontend.md`).

## Language-independent libraries

`am.ik.jvm`, `am.ik.wasm`, `am.ik.wit`, `am.ik.gpu` and `am.ik.objc` may import no rontolisp
package and no external dependency.

- `am.ik.gpu` -- the device half of `--gpu`, CUDA and Metal behind one sealed `GpuDevice`
  seam; imports nothing at all (`.kb/gpu.md`). The interpreter reaches it through
  `eval/LinalgGpu` -> `eval/LinalgGpuKernels`; the JVM backend EMBEDS its class files in the
  compiled output (`codegen/jvm/JvmGpuRuntimeBuilder`), so a class added to the package must
  be added to the list that travels.
- `am.ik.objc` -- the Objective-C runtime and AppKit through FFM (`.kb/objc.md`), reached from
  `eval/ObjcInterop` -> `eval/ObjcPrimitives` only, so `-Pweb` substitutes the one entry
  class. Embedded like `am.ik.gpu` (`codegen/jvm/JvmObjcRuntimeBuilder`, whose class list
  follows the package in an order the verifier accepts). `appkit.lisp`, `metal.lisp` and
  `scene.lisp` ship like `linalg.lisp` and are spliced on the compile path by `AppKitLibrary` /
  `MetalLibrary` / `SceneLibrary`'s `process`, in dependency order (`.kb/geom.md`). None
  compiles to a `.wasm` -- `AppKitLibrary.firstObjcReference` answers for all of them and
  `CompileFrontend` refuses by the reference -- but `--native` for `macos-aarch64` takes them:
  `ObjcNativeLibrary` splices the primitive layer over the `rlobjc` imports the runner stub
  answers, and the module runs on thread 0.

## Package dependency direction

No cycles (`PackageCycleTest`).

```
cli -> eval, compiler, codegen.*, macro, reader, format, am.ik.wit
codegen.jvm -> compiler, macro, runtime, am.ik.jvm, am.ik.gpu, am.ik.objc
codegen.wasm -> compiler, macro, am.ik.wasm, am.ik.wit
compiler -> macro, runtime, rontolisp (AST types only), am.ik.wit
eval -> macro, compiler, reader, scheme, clojure, runtime, rontolisp (AST types only), am.ik.gpu, am.ik.objc
scheme -> reader, rontolisp (AST types only)
clojure -> reader, rontolisp (AST types only)
macro -> reader, rontolisp (AST types only)
reader -> rontolisp (AST types only)
format -> (nothing)
runtime -> (nothing)
am.ik.gpu -> (nothing)
am.ik.objc -> (nothing)
```

## Package rules

- `runtime` imports nothing, project or otherwise -- not even the build's `@Nullable`, which
  is `RuntimeVisible` and would follow the class out. Its classes are COPIED into a compiled
  program's output (beside a `.class`, inside a `.jar`, into the Maven plugin's
  `target/classes`), so anything they imported becomes that artifact's dependency. What
  travels and when: `.kb/jvm-export.md`, "What travels". The one exception is the
  `jakarta.servlet` transport pair only a `-o app.war` output carries (`provided` scope).
  **A class added to this package must be added to a travelling list**
  (`JvmRuntimeClassFilesTest`).
- `format` depends on nothing, not even `reader`: it needs the source verbatim and has its own
  lossless CST front end (`.kb/formatter.md`).
- `compiler` holds backend-shared, backend-FREE front ends and depends on no backend.
- `macro` sits ABOVE `reader` so an expander may build AST by reading Lisp source; the root
  `rontolisp` package must never import `macro`.
- **A compile-time AST pass that reads a file belongs in `eval`, not `cli`, and reads through
  `SourceLoader`** -- the browser playground (`src/web/java`) never touches `cli` and has no
  filesystem (`.kb/wit.md`).
- **The compile path's front end is `cli/CompileFrontend`** -- the read, `(load ...)`
  inlining, the library splice chain, the WIT lowerings and the tree-shaker, in one
  order-critical place. Every backend and embedder goes through it; a JVM embedder goes
  through `cli/JvmSourceCompiler`, the same backend half `-o out.class` runs
  (`.kb/jvm-export.md`). **The pass pipeline is `CompileFrontend.expand`, and nothing may
  restate it**: `run` is the read plus `(load ...)` inlining in front of it, and a caller
  needing its own source loader calls `expand` directly. Every test reaches the front end
  through `src/test/.../cli/CompileFrontendAccess` (or, for a JVM target,
  `JvmSourceCompiler`). A list of libraries exported for a caller to fold is the same bug.
  History: the two corpus guards copied the order and drifted -- eight passes behind when a
  `tokenizer:` case joined `ci-spec.yaml`, ten when fixed, and (invisible to any census of
  pass NAMES) `VecLibrary` in the wrong POSITION, so a `vec:` reference introduced by the
  Gray-streams / usocket / unread-char rewrites was spliced by the CLI and missed by both.
  The `asdf:load-system` E2Es stopped six passes in, and `JvmOsrBackedgeCorpusTest` kept a
  copy until 2026-09-19, eleven splices behind, printing ten `TOKENIZER:... is undefined`
  warnings on every GREEN run.

## Requirements

- Java 25+.
- No external dependencies in the core libraries (reader, eval, codegen, `am.ik.*`).
  `docs-tool/` is a separate Maven project and may use flexmark/snakeyaml.
- Modern Java (records, pattern matching, sealed types, text blocks). The package graph is a
  DAG; a class-level reference cycle is allowed only inside the designed mutual-recursion
  clusters (sealed hierarchies, dispatch/re-entrancy hubs) -- `PackageCycleTest` pins both
  halves and names each allowed cluster with its reason.
- `src/test/resources/ci-spec.yaml` is the single source of truth for `CiSpecE2eTest`. Cases
  share global state and run IN ORDER: the driver concatenates them into one program, runs
  the binary once per backend, and slices the output back per case.
