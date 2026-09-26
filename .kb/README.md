# Knowledge Base

Implementation notes on the invariants `CLAUDE.md` refers to: one file per topic, holding
the invariant, the mechanics, the per-backend differences and the tests that pin them.

**Editing a file here: replace a passage you can SEE, never a computed range.** A
marker-to-marker edit silently deletes whatever was added between the markers since you
last read the file, and nothing fails -- these files have no tests.

**These files are already compacted; do not run another size pass.** The 2026-09-04/05
passes took the tree from 3.66 MB to 1.54 MB (42%) and that is the RESTING size: what is
left is identifier catalogues, one-line traps and pinning-test names, and the next thing a
cut reaches is the test names -- the highest-value pointer a file carries. Ranking files by
"% of the pre-compaction size" does NOT find un-compacted ones (measured 2026-09-08): the
top of that ranking is files whose baseline was already terse (`string-index-cost.md` 96%,
`bfloat16.md` 81%, `instance-syntax.md` 74%, all in card shape) plus files that GREW
afterwards with new content (`checkpoint-readers.md`, 103%). An identifier-preservation
audit over all 126 files against the pre-compaction tree (every backticked token, with
package and class qualifiers normalized) found five names worth restoring and no other
load-bearing loss: of the identifiers that left `.kb` entirely, 20 were
`Jvm`/`Wasm<Name>Compiler` spellings the naming convention regenerates, the rest were
narrative, private helper methods, per-test method names and benchmark row labels. Two
deliberate boundaries the audit must not undo: user-facing operator catalogues live in
`doc/**`, not here, and an enumeration of the emitters sharing one helper was replaced by
"grep that name", which is the better pointer.

## Core language and evaluation

- [core-representation.md](core-representation.md) -- core value model, three-pass compilation, `FreeVarAnalyzer` capture rule, non-top-level `defun`, `%` prefix, JVM method mangling, WASM rec-groups
- [lisp2-namespaces.md](lisp2-namespaces.md) -- Lisp-2 function/variable namespace split
- [parallel-let.md](parallel-let.md) -- `let` stays parallel on the compile path (`ParallelLetStaging` at the let compilers' entry); `(+)`/`(*)` identities
- [lambda-lists.md](lambda-lists.md) -- `&optional`/`&rest`/`&key`/`&aux` desugared to required + `&rest`
- [argument-evaluation-order.md](argument-evaluation-order.md) -- call arguments and `list` elements evaluate left to right on every backend
- [do-return-block.md](do-return-block.md) -- `do`/`return`, `block`/`return-from` (lexical), `catch`/`throw`, `tagbody`/`go`, `prog`
- [loop-iteration-heads.md](loop-iteration-heads.md) -- `loop` per-clause iteration heads: what is assigned before vs after the termination test
- [flet-labels.md](flet-labels.md) -- `flet`/`labels` as let-bound lambdas + Lisp-2 call-site rewrite
- [symbol-macrolet.md](symbol-macrolet.md) -- `symbol-macrolet` via one shared shadow-aware substitution
- [dynamic-special-variables.md](dynamic-special-variables.md) -- special variable binding: `defvar`/`declaim special`, `let`/`progv`
- [multiple-values.md](multiple-values.md) -- `values`/`multiple-value-bind`/`-list`/`-call`/`nth-value` as a syntactic lowering
- [declarations-type-checks.md](declarations-type-checks.md) -- `declare`/`declaim`/`proclaim`/`the` no-ops, `eval-when`, runtime type checks
- [dynamic-late-binding.md](dynamic-late-binding.md) -- `--dynamic` late-binding fallback
- [eval-runtime.md](eval-runtime.md) -- runtime `eval` interpreter embedded in compiled output
- [interpreter-expansion-memo.md](interpreter-expansion-memo.md) -- the interpreter expands a built-in macro once per call site; which arms must re-expand

## Macros, reader, printer

- [defmacro-backquote.md](defmacro-backquote.md) -- `defmacro`, read-time backquote, compile-time expansion
- [gensym-macroexpand.md](gensym-macroexpand.md) -- `gensym`, `macroexpand`/`macroexpand-1`
- [compiler-macros.md](compiler-macros.md) -- `define-compiler-macro` at call sites + `load-time-value` once per occurrence
- [make-load-form.md](make-load-form.md) -- a literal object is dumped by its own `make-load-form` method
- [reader-case-upcase.md](reader-case-upcase.md) -- uppercase-canonical reader model, verbatim `intern`/`find-symbol`
- [reader-features.md](reader-features.md) -- `#+`/`#-`, `*features*` as a runtime variable, `#|...|#`, `#.`, `--feature`
- [read-time-constants.md](read-time-constants.md) -- `pi`/float-range/fixnum/array limits as bound symbols, not reader substitutions; per-backend values
- [source-positions.md](source-positions.md) -- `file:line:column` in reader and frontend errors; the cons-identity rule every AST pass honours; `LocatedCons`, the interpreter's runtime positions; the JVM backend's site-numbered line tables
- [source-language.md](source-language.md) -- the one seam from user source to core forms (`SourceLanguage`): per-file language pick, `--source-language`, the program-wide `SourceStandards`, what is NOT user source
- [scheme-frontend.md](scheme-frontend.md) -- the EXPERIMENTAL Scheme front end: lowering table, library tags, `--scheme-standard`, destination-driven loops, tail-call groups of top-level procedures, identifier escaping, hygienic `syntax-rules` macros, `define-library` / `include`, `cond-expand` and its feature list, `(scheme char)`'s generated Unicode tables, ports and `(scheme file)` file ports, the `#f` / loop-shape / tail-depth / size measurements
- [format.md](format.md) -- `format`'s two renderings of one directive set, and the shared Schubfach float printer
- [pretty-printer.md](pretty-printer.md) -- `write`/`pprint`, dispatch tables, `pprint-logical-block`, printer-control variables
- [formatter.md](formatter.md) -- `rontolisp format`: the whitespace-only invariant and `IndentRules`

## Data types

- [symbol-runtime-api.md](symbol-runtime-api.md) -- symbol/package runtime API, accessibility second value, the string-designator contract
- [packages.md](packages.md) -- the `cl`/`cl-user`/`rontolisp` package system, `defpackage` over an existing package, `*package*` as dynamic
- [defstruct.md](defstruct.md) -- `defstruct` as plain defuns, setf accessor registry, `:include`, `:type (vector ...)`
- [instance-syntax.md](instance-syntax.md) -- the one instance value model behind defstruct/CLOS/conditions, `%obj-*`, `#S(...)`/`#<...>`
- [clos.md](clos.md) -- static CLOS subset: registry, dispatcher generation, initialization protocol, runtime `typep`
- [characters-code-points.md](characters-code-points.md) -- character = Unicode code point on every backend
- [eq-numbers.md](eq-numbers.md) -- `eq` is `eql` on every backend: numbers and characters by type and value, never box identity
- [string-index-cost.md](string-index-cost.md) -- a character index costs the same wherever it lands; the per-string cursor
- [string-accumulate-cost.md](string-accumulate-cost.md) -- building one string out of N pieces costs the total length, not the sum of the prefixes
- [hash-tables.md](hash-tables.md) -- per-backend representation, `equalp` key fold, depth cap and work budget
- [array-literals.md](array-literals.md) -- an array literal is a constructor, not a constant; rank-0 arrays; when `:element-type` specializes
- [quoted-data.md](quoted-data.md) -- a quoted datum is one shared constant per quote site on all four backends; a long list is built in runs of 16 on wasm-GC (Cranelift time vs. live values across calls)
- [adjustable-arrays.md](adjustable-arrays.md) -- fill pointers, `:adjustable`, displaced arrays, `vector-push*`, `adjust-array`
- [packed-integer-vectors.md](packed-integer-vectors.md) -- `(unsigned-byte 8|16|32)` rank-1 packs, `#N@(...)`, per-backend raw paths
- [bfloat16.md](bfloat16.md) -- bfloat16 conversion pair, the packed `#bf16` width, and the width's account (why bf16, the name, the prefix, the lattice entry)
- [pathnames.md](pathnames.md) -- a pathname is a distinct value; `#P"..."` in the frontend and both emitted readers
- [random.md](random.md) -- `random` draws from a generator inside the program, never a host call per draw

## Sequences

- [map-family.md](map-family.md) -- `mapcar`/`mapc`/`mapcan`/`maplist`/`mapcon`/`mapl` over any number of lists
- [concatenate-result-families.md](concatenate-result-families.md) -- `concatenate`'s `string`/`list`/`vector` result families
- [sort.md](sort.md) -- one merge sort shared by every backend and by `stable-sort`
- [copy-list-runtime.md](copy-list-runtime.md) -- `copy-list` keeps a dotted tail; `map 'string` / `coerce` to string reject a non-character
- [length-runtime.md](length-runtime.md) -- generic `length` dispatch as a shared callee
- [subseq-runtime.md](subseq-runtime.md) -- `subseq` and general-array element access as shared callees
- [seq-conversion-runtime.md](seq-conversion-runtime.md) -- the literal sequence conversions (`coerce` to `'list`/`'string`/`'vector`)
- [seq-coerce-runtime.md](seq-coerce-runtime.md) -- the interpreter converts a sequence in Java, not through an interpreted `map`
- [sequence-op-runtimes.md](sequence-op-runtimes.md) -- `replace`/`fill`/`map-into` as shared callees
- [sequence-bounding-keywords.md](sequence-bounding-keywords.md) -- `:start`/`:end`/`:count`/`:from-end` across the count/remove/substitute family
- [sequence-designator-evaluation.md](sequence-designator-evaluation.md) -- a computed `:test`/`:test-not`/`:key` is evaluated once, in argument order
- [cons-set-and-tree-operators.md](cons-set-and-tree-operators.md) -- the `n`-prefixed set/tree spellings are aliases, and the family's one first-class shape
- [string-write-runtime.md](string-write-runtime.md) -- the shared string arm behind every rank-1 element write
- [integer-bitwise-fast-paths.md](integer-bitwise-fast-paths.md) -- fixnum fast paths for the logical/shift operators and the byte-specifier fold
- [jzon-cl-additions.md](jzon-cl-additions.md) -- the jzon-driven all-backend CL additions

## I/O and streams

- [read-load-streams.md](read-load-streams.md) -- `read`/`load`/`read-line`/file streams; a stream is a self-describing value; synonym streams; output left open is flushed at the end; element types wider and narrower than one octet; the real stream direction and a string stream's `file-position`
- [standard-output-redirect.md](standard-output-redirect.md) -- stream designators resolve through `*standard-output*`/`*standard-input*` at call time; `*error-output*`
- [gray-streams.md](gray-streams.md) -- the Gray-stream protocol, the compile-path splice, the flexi-streams wrapper
- [binary-sequence-io.md](binary-sequence-io.md) -- `read-sequence`/`write-sequence` over a packed buffer in one native transfer
- [character-sequence-io.md](character-sequence-io.md) -- `read-sequence` into a string: a block of storage units per host read, never one read per character
- [directory-listing.md](directory-listing.md) -- the one `%list-directory` primitive and the Lisp spellings above it
- [load-inliner.md](load-inliner.md) -- compile-time `load` inlining, `require`/`provide`

## Errors and concurrency

- [error-handling.md](error-handling.md) -- `unwind-protect`, condition objects, `handler-case`, restarts, per-backend mechanics; the uncaught report and wasm-GC's opt-in `--report-locations` frames with their measured cost
- [async-await.md](async-await.md) -- async-defun/await, futures, asynchronous streams
- [threads.md](threads.md) -- `rontolisp:make-thread` and friends
- [mutexes.md](mutexes.md) -- `rontolisp:make-mutex`/`with-mutex`
- [concurrent-served-requests.md](concurrent-served-requests.md) -- one virtual thread per request over shared interpreter/JVM state

## Networking and web

- [fetch-http.md](fetch-http.md) -- `rontolisp:fetch` outgoing HTTP and the `FetchResponseShape` plist; its transports (JDK, `wasi:http`, `--host-fetch`, the `--native` runner) and the cross-backend corpus with its known divergences
- [http-server.md](http-server.md) -- the server-side HTTP value model since the Clack cutover
- [tcp-sockets.md](tcp-sockets.md) -- `rontolisp:tcp-*` sockets and TLS
- [url.md](url.md) -- `rontolisp:url-*`/`query-param*`
- [json.md](json.md) -- `rontolisp:json-parse`/`json-stringify`
- [lack.md](lack.md) -- the lack request/response + middleware ecosystem
- [clack.md](clack.md) -- Clack support and the late-bound `clack-handler-rontolisp` shim
- [mito.md](mito.md) -- Mito DAO + schema migration end to end

## Compile path and optimization

- [size-measurement.md](size-measurement.md) -- "smaller" is five different numbers: raw vs gzip when bytes are moved, a total vs a section, a residue vs what is left
- [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md) -- the `--optimize` flag: levels, what the shaker drops, the duplicate-body fold, dispatch pruning
- [pure-builtin-fold.md](pure-builtin-fold.md) -- compile-time fold of pure built-ins over literal arguments, and what is deliberately out
- [toplevel-statement-values.md](toplevel-statement-values.md) -- a top-level form is a statement; nothing may be emitted only to be dropped
- [compile-time-boundp.md](compile-time-boundp.md) -- `(boundp 'name)` over a literal symbol decided at compile time
- [library-defun-pruning.md](library-defun-pruning.md) -- AST pruning of spliced library defuns, rontolisp's own and ASDF-spliced; the JVM-lived parse caches the splice reads from
- [emitted-output-determinism.md](emitted-output-determinism.md) -- the same program compiles to the same bytes on every run
- [default-run-path.md](default-run-path.md) -- flagless `rontolisp app.lisp` runs the interpreter by decision
- [interpreter-stack.md](interpreter-stack.md) -- the CLI, an embedder's `JvmSourceCompiler` and a compiled JVM `main` run every program on a stack they chose; `--stack`, `-Drontolisp.stack`; every walk loops down the cdr so depth is nesting, never a list's length (`LispTrees`, circular tails refused)
- [interpreter-tail-calls.md](interpreter-tail-calls.md) -- the interpreter's `eval` is a loop: a form in tail position replaces the frame, so a tail call through a value, `apply`, mutual defuns runs in constant stack; the block-owner identity, the depth and time numbers, `or`'s last form
- [measurement-probes.md](measurement-probes.md) -- whether a performance number answers the question that was asked
- [test-execution.md](test-execution.md) -- how the test suite actually runs (surefire forks, which classes run methods concurrently, the per-thread stdio device that lets an in-process program run that way, and the CLI-sized stack in-process program work runs on), and the rule that no test may name a scratch path or a port from a constant: two builds share one machine
- [running-backends.md](running-backends.md) -- running a program on all four backends by hand, the native-image E2E leg, the examples suite
- [adding-primitives.md](adding-primitives.md) -- the per-surface checklists for a new built-in function, macro or special form, and the two silent-failure traps
- [hot-path-method-size.md](hot-path-method-size.md) -- HotSpot's 8000-bytecode HugeMethodLimit and the splits that stay under it

## JVM backend

- [jvm-export.md](jvm-export.md) -- `rontolisp:jvm-export`, `--no-main`, `-o out.jar`, the `-o` path-to-class-name rule, the Maven plugin
- [jvm-int-fusion.md](jvm-int-fusion.md) -- integer expression-tree fusion into unboxed arithmetic
- [jvm-double-arithmetic.md](jvm-double-arithmetic.md) -- `hasDoubleLiteral` routing and unboxed IEEE operations
- [jvm-complex.md](jvm-complex.md) -- the `RontoComplex` holder, the gated `_c*` group, and the `hasComplexOperand` steering
- [jvm-typed-loops.md](jvm-typed-loops.md) -- typed numeric loops over packed float arrays
- [jvm-bignum-literal-pool.md](jvm-bignum-literal-pool.md) -- one `BigInteger` instance per distinct literal in a `_bi$N` pool
- [jvm-method-size-limits.md](jvm-method-size-limits.md) -- the 64 KB method code limit, the signed-16-bit branch offset, and the split of a program past one class's 65534-entry constant pool into `$PartN` classes (`JvmClassSplitter`)
- [jvm-osr-backedges.md](jvm-osr-backedges.md) -- no backward branch may target a bci with a non-empty operand stack
- [jvm-aot-cache.md](jvm-aot-cache.md) -- the JDK 25 Leyden AOT cache, measured and deliberately not shipped
- [stackmap-augmenter.md](stackmap-augmenter.md) -- class version 61 via `am.ik.jvm.StackMapAugmenter`
- [template-class-embedding.md](template-class-embedding.md) -- when to use a Java template class, shipping it beside the program named after it, and class closures
- [java-interop.md](java-interop.md) -- the `java:` reflection interop bridge

## WASM backends

- [wasi-component.md](wasi-component.md) -- `--component` WASI 0.3 component output
- [wit.md](wit.md) -- `am.ik.wit`, `--emit-wit`, `WitTypeMapper`, `rontolisp:wit-export`/`wit-import`
- [wasm-export-no-wasi.md](wasm-export-no-wasi.md) -- `rontolisp:wasm-export` + `--no-wasi` reactor mode
- [wasm-import.md](wasm-import.md) -- `rontolisp:wasm-import`, `:async t`, `--emit-js-glue`, `--host-boundary`
- [no-gc-scalar-wasm.md](no-gc-scalar-wasm.md) -- the `--no-gc` non-GC scalar backend
- [wasm-gc-strings.md](wasm-gc-strings.md) -- GC-backend strings as `$str_bytes` arrays
- [wasm-gc-final-types.md](wasm-gc-final-types.md) -- every emitted wasm-GC type must be `sub final`
- [wasm-gc-heap-pregrow.md](wasm-gc-heap-pregrow.md) -- `_start` pre-grows the engine's GC heap with one dropped allocation
- [wasm-gc-object-size.md](wasm-gc-object-size.md) -- what a struct field costs on the heap per engine, measured (a wasmtime object is a 16-byte header plus fields rounded to 16, so the identity-hash slot is free there and +8 bytes per cons on V8), and the capped-heap probe that measures it
- [wasm-landing-pad-refresh.md](wasm-landing-pad-refresh.md) -- a `try_table` landing pad refreshes every local live after it: Cranelift passes a local into the pad as a pre-call exceptional-edge argument, stale after a copying collection; the push is narrowed to the live locals once the body is complete
- [wasm-bignum.md](wasm-bignum.md) -- exact integers in three tiers (i31, `TYPE_BIGNUM`, `TYPE_BIGINT`)
- [wasm-complex.md](wasm-complex.md) -- the tagged `TYPE_COMPLEX` struct (the `TYPE_FARRAY` twin that forced the tag), the `_c*` runtime group, and the `containsComplex` steering
- [wasm-int-fusion.md](wasm-int-fusion.md) -- integer expression-tree fusion keeping raw i64
- [wasm-ref-type-fold.md](wasm-ref-type-fold.md) -- a closed module's `ref.test`/`ref.cast` are decided by its own constructors: the byte-level type-flow fold before the shaker, the call-forwarding redirect, the exact integer export lane, the raw condition compare
- [wasm-counted-loops.md](wasm-counted-loops.md) -- a loop induction variable as a bare `i64` counter
- [wasm-unboxed-locals.md](wasm-unboxed-locals.md) -- dual-representation `let` locals
- [wasm-callable-arity.md](wasm-callable-arity.md) -- the 10-parameter callable limit as an index origin
- [wasm-tail-calls.md](wasm-tail-calls.md) -- every call in tail position is a `return_call`, the dispatcher and `_apply` tail-call their target: constant stack through a function value, a direct call, `labels`, `apply`; the one consumed-at-entry flag, the post-passes that learned the opcode, the inliner's three rules, the depth/byte/time numbers and the crash-reads-as-fast trap
- [wasm-function-body-size.md](wasm-function-body-size.md) -- no emitted function body may grow without bound
- [cons-access-runtime.md](cons-access-runtime.md) -- `car`/`cdr` as one shared callee under `--optimize=size`; the temp-free plain-local read; what an external optimizer's residue is made of
- [wasm-shortest-encoding.md](wasm-shortest-encoding.md) -- every emitted byte in its shortest legal encoding
- [wasm-shared-coercion.md](wasm-shared-coercion.md) -- the numeric-to-`f64` coercion is one runtime function, never an inlined ladder
- [wasm-linear-memory-layout.md](wasm-linear-memory-layout.md) -- what lives where in an emitted module's linear memory, and the rule that every address a module writes is derived from its own static-data end: the env/argv scratch block that used to be FIXED in page 3 and landed inside the static data of any program with more than ~192 KB of interned strings (silently -- on the ci-spec corpus it ate the `char-downcase` fold table, so `format` printed every directive verbatim and 335 of 474 cases failed on the WASM leg with `char-upcase` still perfect), why the block is reserved only for a program that can reach `environ_get`/`args_get`, and the two fixed component-only regions that remain

## Numeric, GPU and native

- [transcendentals.md](transcendentals.md) -- one algorithm (fdlibm: `StrictMath` on the interpreter and the JVM, the same fdlibm as WASM runtime functions) so every transcendental answers the same bits on every backend and CPU; where each lives, the gating, the measured sizes and costs
- [linalg.md](linalg.md) -- the `linalg` package and the standard array functions
- [linalg-simd.md](linalg-simd.md) -- `--simd` interception of the `linalg:` kernels
- [linalg-blas.md](linalg-blas.md) -- `--blas`: the matrix product on a tuned CBLAS from the OS
- [simd-parallel.md](simd-parallel.md) -- `--parallel` over the `--simd` matrix products
- [gpu.md](gpu.md) -- `--gpu` and `am.ik.gpu`, the never-throwing declining device layer
- [native-output.md](native-output.md) -- `--native -o prog`: the wasmtime precompile shim and the runner stub (`rontolisp-native/`) and the Java side that loads, checks and assembles them (`cli/NativeToolchain`, `cli/NativeExecutable`); the one shared engine config, the fingerprint, the payload trailer, the resource layout and cache, the refusals, the separate-build trap, the CPU baseline and `--native-target` (and why SSE2 / Armv8.0 cost nothing), the network runner (`rlrun-net`) a fetching program starts with, the numbers
- [native-downcalls.md](native-downcalls.md) -- `src/native/java`: the binary's downcalls through SubstrateVM's AOT route instead of the interpreted FFM handle; what took it (`--blas`) and what was measured not to (`--gpu`, objc)
- [vec.md](vec.md) -- the `vec` package, the packed float-array type, and its `--simd`/`--no-gc` acceleration layers
- [quantized-matrix.md](quantized-matrix.md) -- `rontolisp:quantized-matrix`: ggml's Q8_0 held verbatim, and the integer-dot GEMV that is the defun bit for bit
- [torch.md](torch.md) -- the `torch` package: tensors + reverse-mode autograd over the linalg kernels
- [geom.md](geom.md) -- the `geom` package: solid modeling over the linalg kernels
- [checkpoint-readers.md](checkpoint-readers.md) -- the `checkpoint` staging package and the `safetensors` reader
- [gguf.md](gguf.md) -- the `gguf` package and the ggml dims-reversed rule
- [tokenizers.md](tokenizers.md) -- the `tokenizer` package and its no-I/O invariant
- [ffi.md](ffi.md) -- `ffi:`, the foreign primitives CFFI's `cffi-sys` backend is written over
- [cffi.md](cffi.md) -- upstream CFFI running from its own source
- [objc.md](objc.md) -- `objc:`/`appkit:`: a native macOS window through FFM

## Ecosystem and tooling

- [asdf.md](asdf.md) -- the limited `asdf:defsystem`/`load-system`/`test-system` subset
- [uiop.md](uiop.md) -- uiop as the 15-sub-package bundle upstream is
- [dists.md](dists.md) -- the download half of `ql:quickload`
- [time-environment-builtins.md](time-environment-builtins.md) -- time/environment built-ins and the environment-enquiry family
- [documentation-site.md](documentation-site.md) -- doc site layout, fence conventions, search index, build/preview
- [directory-rename.md](directory-rename.md) -- moving a directory: the things that break outside the rename's own diff (a location-scoped ignore rule, a citation vs. a member, the relative paths INSIDE what moved when the depth changes, a re-flowed javadoc line, a fixture lookup that turns into a skip), the walk-defined corpus behind them, and `PathCitationTest` as the machine half
