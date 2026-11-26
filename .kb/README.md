# Knowledge Base

Implementation notes on the invariants `CLAUDE.md` refers to: one file per topic, holding
the invariant, the mechanics, the per-backend differences and the tests that pin them.

**Editing a file here: replace a passage you can SEE, never a computed range.** A
marker-to-marker edit silently deletes whatever was added between the markers since you
last read the file, and nothing fails -- these files have no tests.

**"Of the artefacts", "the k35 artefacts' `Grid.java`" and the like** name measurement files of
a closed item. They were removed from the tree on 2026-11-26; the numbers they backed are the
ones written here, and the files are in the history, by the commit that removed them.

**Compaction (2026-11-25): 6.6 MB to 4.85 MB** (183 files, measured before
`java-frontend.md`'s own pass, 0.87 MB of it). Cut: history (what an item added, superseded
designs, intermediate measurement logs, abandoned plans) and what the source says (method
walk-throughs, field and argument lists, restated code, user catalogues `doc/**` holds).
Kept: invariants, the why the source cannot show and its numbers, traps, per-backend
differences, deviations from other implementations and why, provenance, and the pinning
tests' names -- the highest-value pointer a file carries. **Compact a file again when history
and source-readable text have piled up in it again, not because of its size**: a large file of
invariants and test names is at its resting size.

## Core language and evaluation

- [core-representation.md](core-representation.md) -- core value model, three-pass compilation, `FreeVarAnalyzer` capture rule, non-top-level `defun`, an assignment of an unbound name (function body or top level) as a global, `%` prefix, JVM method mangling, WASM rec-groups
- [lisp2-namespaces.md](lisp2-namespaces.md) -- Lisp-2 function/variable namespace split; a lexical variable named like a function and the one call-site walk (`CallSiteWalk`) every call-head rewrite takes
- [parallel-let.md](parallel-let.md) -- `let` stays parallel on the compile path (`ParallelLetStaging` at the let compilers' entry); `(+)`/`(*)` identities
- [lambda-lists.md](lambda-lists.md) -- `&optional`/`&rest`/`&key`/`&aux` desugared to required + `&rest`; on the compilers an optional travels as a parameter of its own (the UNSUPPLIED marker when absent), so passing one conses nothing
- [argument-evaluation-order.md](argument-evaluation-order.md) -- call arguments and `list` elements evaluate left to right on every backend; an operation applies, and signals, only after its arguments -- on the compiled fast paths too (SBCL's pairwise n-ary arithmetic is the one difference)
- [do-return-block.md](do-return-block.md) -- `do`/`return`, `block`/`return-from` (lexical), `catch`/`throw`, `tagbody`/`go`, `prog`
- [loop-iteration-heads.md](loop-iteration-heads.md) -- `loop` per-clause iteration heads: what is assigned before vs after the termination test; the simple type symbols a variable may carry
- [flet-labels.md](flet-labels.md) -- `flet`/`labels` as let-bound lambdas + Lisp-2 call-site rewrite
- [symbol-macrolet.md](symbol-macrolet.md) -- `symbol-macrolet` via one shared shadow-aware substitution; an expansion that is a user setf place
- [dynamic-special-variables.md](dynamic-special-variables.md) -- special variable binding: `defvar`/`declaim special`, `let`/`progv`, a local `(declare (special ...))` scoped per CLHS 3.3.4 (no dual binding), a parameter named like a special, the by-name dispatch, `boundp` and the unbound marker, `unbound-variable`, `makunbound`, a `defvar`'s "only if unbound" below the top level (`DefvarGuards`)
- [multiple-values.md](multiple-values.md) -- `values`/`multiple-value-bind`/`-list`/`-call`/`nth-value` as a syntactic lowering
- [declarations-type-checks.md](declarations-type-checks.md) -- `declare`/`declaim`/`proclaim`/`the`: no-ops but where a backend trusts one (array kinds and integer ranges on wasm-GC, double-floats and integer ranges on the JVM); `eval-when` by its position; runtime type checks; `single-float` as the binary32 subset of the one float format
- [dynamic-late-binding.md](dynamic-late-binding.md) -- `--dynamic` late-binding fallback
- [eval-runtime.md](eval-runtime.md) -- runtime `eval` interpreter embedded in compiled output: non-forms, unbound variables, undefined functions, the built-in macros (`eval-macros.lisp`), `loop`, restarts, `(setf name)` places, what a form can reach (`EvalReferences`)
- [interpreter-expansion-memo.md](interpreter-expansion-memo.md) -- the interpreter expands a built-in macro once per call site; which arms re-expand; the weak identity memos (`FormMemo`); what keeps a user macro site's expansion valid; the call-site note on a head symbol
- [interpreter-compiled-bodies.md](interpreter-compiled-bodies.md) -- a lambda called twice runs its body as a compiled closure tree; why not the JVM backend's bytecode or a class per body (numbers); guards and epochs, what stays walked, frames and tail stepping, depth numbers

## Macros, reader, printer

- [defmacro-backquote.md](defmacro-backquote.md) -- `defmacro`, read-time backquote, compile-time expansion
- [gensym-macroexpand.md](gensym-macroexpand.md) -- `gensym`, `macroexpand`/`macroexpand-1`, `macroexpand-all` (trivial-macroexpand-all's arm: a symbol macro expanded where it is referenced)
- [compiler-macros.md](compiler-macros.md) -- `define-compiler-macro` at call sites + `load-time-value` once per occurrence, `compiler-macro-function`
- [make-load-form.md](make-load-form.md) -- a literal object is dumped by its own `make-load-form` method
- [reader-case-upcase.md](reader-case-upcase.md) -- uppercase-canonical reader model, verbatim `intern`/`find-symbol`
- [readtables.md](readtables.md) -- a readtable is a value on every backend (`LispLayout.READTABLE`); the interpreter reads form at a time with the current one (`ReadSyntax`); the compile paths read with the compile-time evaluator's (`ReadTimeImage`, `UserMacroExpander.Session`); named-readtables; what is not honored yet
- [reader-features.md](reader-features.md) -- `#+`/`#-`, `*features*` as a runtime variable, `#|...|#`, `#.`, `--feature`
- [read-time-constants.md](read-time-constants.md) -- `pi`/float-range/fixnum/array limits as bound symbols, not reader substitutions; per-backend values; `constantp` of a standard or user constant name
- [source-positions.md](source-positions.md) -- `file:line:column` in reader and frontend errors; the cons-identity rule every AST pass honours; `LocatedCons`, the interpreter's runtime positions; the JVM backend's site-numbered line tables
- [compile-warnings.md](compile-warnings.md) -- `CompileWarnings`: warning vs note, attempt buffering, what `--warnings-as-errors` counts (program units only; macro-built code at the nearest located form)
- [source-language.md](source-language.md) -- the one seam from user source to core forms (`SourceLanguage`): per-file language pick, `--source-language`, the program-wide `SourceStandards`, what is NOT user source
- [scheme-frontend.md](scheme-frontend.md) -- the EXPERIMENTAL Scheme front end: lowering table, library tags, `--scheme-standard`, loops, tail-call groups, identifier escaping, `syntax-rules`, `define-library`/`include`, `cond-expand`, `(scheme char)`'s Unicode tables, ports and `(scheme file)`, measurements
- [clojure-frontend.md](clojure-frontend.md) -- the EXPERIMENTAL Clojure front end: value representation, the `c%` mangle, lowering table, deviations from `clj`, host classes and exceptions across the `java:` boundary, sorted collections, structural keys and `%copy-equal-table`, regular expressions, `hash`, decimals, the IFn dispatcher, `recur`, namespaces and project files, library probes (core.async, spec.alpha, riddley, specter), `deps.edn` (`:local/root`, Maven and git coordinates, `pom.xml`), macros and `&env`, lowering at run time, records, protocols, `print-method` and multimethods, `with-redefs` and metadata, reading (`clojure.edn`, `.cljc`, `#inst`/`#uuid`, `data_readers.clj`), case mapping, `clojure.test`, the Ring adapter, the HTTP client (`rontolisp.http-client`), `rontolisp.wasm`/`rontolisp.wit`, the shipped clojure.jar namespaces (`clojure.pprint` and `cl-format`, `clojure.zip` ...; which of them clojure.jar's own files could replace, measured), byte arrays and the `:octets`/`:through` markers, transients, `PersistentQueue`, `clojure.java.io`, `reduce`/`CollReduce`, the `clojure.lang` interfaces of `reify`/`deftype`, Java faces, the session
- [starlark-frontend.md](starlark-frontend.md) -- the EXPERIMENTAL Starlark front end: the decisions (`None`/`False`, identifier escaping, code-point strings, `print`), lowering table, static name resolution, definite assignment, what the lowering knows of a value, `--starlark-standard`, error positions, call tiers, `load` modules, freezing, `rontolisp:starlark-call`, crossing to Common Lisp, the bench and conformance measurements against CPython, Go and Java Starlark, the reader against Python's `ast`
- [java-frontend.md](java-frontend.md) -- the EXPERIMENTAL front end for a subset of the Java language: where it sits (lexer, parser, class table, `java.lisp` behind the `%java-` gate), the decisions (static types, `null`/boxes, integral wraps, a class as a structure, dispatch, exceptions, the launch rule), several files, libraries read as source (`--java-source-path`, `--java-source-dep`; YAVI; a library's code naming what the subset lacks throws only where it runs), a session, annotations, the library (intrinsic vs Java source, `HashMap` order, `String.format`), `java.io`, files, `Character`, type parameters and inference, nested, local and anonymous classes, enums, records, `switch` and patterns, flow analysis, static initialization, arrays, threads and `java.util.concurrent` (monitors, resumable lowering, `synchronized` regions, `ReentrantLock`), `java.net.http`, `java.net.URI`, `java.time` (`Duration`, tz regions, the formatter, localized forms), `java.util.Date`/`java.sql`, `Calendar` and `TimeZone`, `com.sun.net.httpserver`, sockets, digests and keys, `Base64`, `URLEncoder`, the sorted, enum, sequenced and `Collections` collections, `PriorityQueue`, `java.util.stream`, run-time annotations, WIT imports and exports (`@WitImport`, `@WitExport`), jmustache, `Locale`, the buffers, reflection (the program's classes, the JDK's, reachability metadata, generic types, annotated types and `@Target`), references and `WeakHashMap`, the tests of a program (`rontolisp test`, AssertJ's part vs its sources jar, how it shows values, its recursive comparison and the cost of field names, JUnit), a reactor of public static methods, a reactor on `--no-gc` (the scalar subset), a server as a reactor (Cloudflare Worker) and its waits through the host (`--host-timer`), `java.util.logging` (`wasi:logging`, SLF4J, Commons Logging); measurements throughout
- [format.md](format.md) -- `format`'s two renderings of one directive set, and the shared Schubfach float printer
- [pretty-printer.md](pretty-printer.md) -- `write`/`pprint`, dispatch tables, `pprint-logical-block`, printer-control variables, `print-not-readable`
- [formatter.md](formatter.md) -- `rontolisp format`: the whitespace-only invariant and `IndentRules`

## Data types

- [symbol-runtime-api.md](symbol-runtime-api.md) -- symbol/package runtime API, accessibility second value, string designators, the empty name, `fmakunbound`, a function redefined by name, `eval` or `load` (a constant-time cell per name)
- [packages.md](packages.md) -- the `cl`/`cl-user`/`rontolisp` package system, a standard name in a package not using `cl`, `defpackage` over an existing package, package-local nicknames, `*package*` as dynamic, what the compile image did to packages reaching the compiled program, `make-package`
- [defstruct.md](defstruct.md) -- `defstruct` as plain defuns, setf accessor registry, `:include`, `:type (vector ...)`
- [instance-syntax.md](instance-syntax.md) -- the one instance value model behind defstruct/CLOS/conditions, `%obj-*`, `#S(...)`/`#<...>`, `equal` on a structure or CLOS instance as identity
- [clos.md](clos.md) -- static CLOS subset: registry, dispatcher generation (`DispatchChain`), initialization protocol, runtime `typep`, forward-referenced superclasses, definition order, definitions below the top level, the MOP, contextl, instance updating, precompiled `compile` definitions
- [characters-code-points.md](characters-code-points.md) -- character = Unicode code point on every backend
- [eq-numbers.md](eq-numbers.md) -- `eq` is `eql` on every backend: numbers and characters by type and value, never box identity
- [string-index-cost.md](string-index-cost.md) -- a character index costs the same wherever it lands; the per-string cursor
- [string-search-runtime.md](string-search-runtime.md) -- `search` over two strings allocating nothing on wasm-GC (`%string-search`); its cost on an interpreting host; `%string-order` (the `string<` family, Starlark's sort)
- [string-accumulate-cost.md](string-accumulate-cost.md) -- building one string out of N pieces costs the total length, not the sum of the prefixes
- [hash-tables.md](hash-tables.md) -- the one representation (flat, a structure-sharing trie once copied, insertion-order walk), `equalp` key fold (an instance's behind a marker), depth cap and work budget, the wasm identity-hash slot and `%identity-hash`
- [array-literals.md](array-literals.md) -- an array literal is a constructor, not a constant; rank-0 arrays; when `:element-type` specializes
- [quoted-data.md](quoted-data.md) -- a quoted datum is one shared constant per quote site on all four backends, and a node two literals reach one object (`QuotedSharing`'s units); a long list is built in runs of 16 on wasm-GC (Cranelift time vs. live values across calls)
- [adjustable-arrays.md](adjustable-arrays.md) -- fill pointers, `:adjustable`, displaced arrays, `vector-push*`, `adjust-array`; a wasm element access's cost and the vector cell (`_vec_sync`); the JVM's packed general array, the `Long.MIN_VALUE` it holds, and its boxed copy under threads
- [packed-integer-vectors.md](packed-integer-vectors.md) -- `(unsigned-byte 8|16|32)` rank-1 packs, `#N@(...)`, per-backend raw paths
- [bfloat16.md](bfloat16.md) -- bfloat16 conversion pair, the packed `#bf16` width, and the width's account (why bf16, the name, the prefix, the lattice entry)
- [pathnames.md](pathnames.md) -- a pathname is a distinct value; `#P"..."` in the frontend and both emitted readers; logical hosts as a recorded translation table, `pathname-match-p`
- [random.md](random.md) -- `random` draws from a generator inside the program, never a host call per draw

## Sequences

- [map-family.md](map-family.md) -- `mapcar`/`mapc`/`mapcan`/`maplist`/`mapcon`/`mapl` over any number of lists
- [concatenate-result-families.md](concatenate-result-families.md) -- `concatenate`'s `string`/`list`/`vector` result families; `map`'s result type, a type `coerce` or `map` computes reaching the packed builders
- [sort.md](sort.md) -- one merge sort shared by every backend and by `stable-sort`
- [copy-list-runtime.md](copy-list-runtime.md) -- `copy-list` keeps a dotted tail; `map 'string` / `coerce` to string reject a non-character
- [length-runtime.md](length-runtime.md) -- generic `length` dispatch as a shared callee
- [subseq-runtime.md](subseq-runtime.md) -- `subseq` and general-array element access as shared callees; the bounds check every representation takes, one text
- [seq-conversion-runtime.md](seq-conversion-runtime.md) -- the literal sequence conversions (`coerce` to `'list`/`'string`/`'vector`)
- [seq-coerce-runtime.md](seq-coerce-runtime.md) -- the interpreter converts a sequence in Java, not through an interpreted `map`
- [sequence-op-runtimes.md](sequence-op-runtimes.md) -- `replace`/`fill`/`map-into` as shared callees
- [bulk-primitives.md](bulk-primitives.md) -- `%string-split`, `%string-join`, `%string-count`, `%sort-by-keys`: one wasm-GC function each, hand-assembled bytecode on the JVM (`JvmBulkRuntimeBuilder`, with `%string-search` and `%replace-bulk`), nil on the interpreter; radix vs merge and `array.copy`, measured
- [sequence-bounding-keywords.md](sequence-bounding-keywords.md) -- `:start`/`:end`/`:count`/`:from-end` across the sequence functions; every bound checked once before the walk (`%check-bounds`)
- [sequence-designator-evaluation.md](sequence-designator-evaluation.md) -- a computed `:test`/`:test-not`/`:key` is evaluated once, in argument order
- [cons-set-and-tree-operators.md](cons-set-and-tree-operators.md) -- the `n`-prefixed set/tree spellings are aliases, and the family's one first-class shape
- [string-write-runtime.md](string-write-runtime.md) -- the shared string arm behind every rank-1 element write; the producers whose result is mutable and the internal ones that are not (`%string-concat`, `%substring`)
- [integer-bitwise-fast-paths.md](integer-bitwise-fast-paths.md) -- fixnum fast paths for the logical/shift operators and the byte-specifier fold
- [jzon-cl-additions.md](jzon-cl-additions.md) -- the jzon-driven all-backend CL additions

## I/O and streams

- [read-load-streams.md](read-load-streams.md) -- `read`/`load`/`read-line`/file streams; a compiled run-time read's package (`%read-home`); self-describing stream values; synonym streams; output flushed at the end; element types; the real direction and a string stream's `file-position`; `file-length`, `file-write-date`
- [standard-output-redirect.md](standard-output-redirect.md) -- stream designators resolve through `*standard-output*`/`*standard-input*` at call time; `*error-output*`; a stream's line start, what `fresh-line` and an opening `~&` ask on every stream and what moves it
- [gray-streams.md](gray-streams.md) -- the Gray-stream protocol, the compile-path splice, the flexi-streams wrapper
- [binary-sequence-io.md](binary-sequence-io.md) -- `read-sequence`/`write-sequence` over a packed buffer in one native transfer
- [character-sequence-io.md](character-sequence-io.md) -- `read-sequence` into a string: a block of storage units per host read, never one read per character
- [directory-listing.md](directory-listing.md) -- the one `%list-directory` primitive and the Lisp spellings above it
- [load-inliner.md](load-inliner.md) -- compile-time `load` inlining (a path merged onto `*load-truename*` included), `require`/`provide`, the load-context brackets (the entry file's too; an absolute truename)

## Errors and concurrency

- [error-handling.md](error-handling.md) -- `unwind-protect`, conditions, `handler-case`, restarts, per-backend mechanics; closed and wrong-direction streams; a report rendered where it is read; the uncaught report (`%reporting-uncaught`), `--report-locations` and its cost; `--no-wasi` reports; SIGINT as `rontolisp:interactive-interrupt`
- [async-await.md](async-await.md) -- async-defun/await, futures, asynchronous streams; a body's eager part as a call (its caller's handlers, a handler's exit), an exit crossing a future; withdrawing a component stream read, the component's `%future-race`, `%task-swap` (a frame owned by no task)
- [threads.md](threads.md) -- `rontolisp:make-thread` and friends; what a thread keeps of its own (the handler and restart stacks, whatever started it); a program's end ending its threads in process (`LispEvaluator.stopThreads`)
- [mutexes.md](mutexes.md) -- `rontolisp:make-mutex`/`with-mutex`
- [concurrent-served-requests.md](concurrent-served-requests.md) -- one virtual thread per request over shared interpreter/JVM state

## Networking and web

- [fetch-http.md](fetch-http.md) -- `rontolisp:fetch` and `FetchResponseShape`; the transports (JDK, `wasi:http`, `--host-fetch`, the `--native` runner), the cross-backend corpus and its divergences; the two timeouts
- [http-server.md](http-server.md) -- the server-side HTTP value model (Clack); Clack's streaming writer per transport; a stop called by a handler; the runner's server in steps (`%http-serve-step`, `awaitRequest`, `port`)
- [swank.md](swank.md) -- `rontolisp --swank`: the Common Lisp REPL over SWANK for SLIME, SLY and Lem on one port; framing, threads, the request shapes, a thread-bound current package, output flow control, compile notes, argument lists, SLDB (frames, restarts, interrupts), how the oracle was recorded
- [nrepl.md](nrepl.md) -- `rontolisp --nrepl`: the Clojure session over nREPL (bencode, the ops in nREPL 1.3's shapes), per-session thread bindings, per-message `*out*`/`*err*`, interrupts, completions, a redefinition reaching every caller
- [tcp-sockets.md](tcp-sockets.md) -- `rontolisp:tcp-*` sockets and TLS; the three WASM dispatch providers (component, stdin, the `--native` runner's `rltcp` host, its `wait` over several sockets); read deadlines and the component's `listen` probe
- [dexador.md](dexador.md) -- unpatched dexador: what the usocket/cl+ssl shims and the trivial-mimes derived form owe it (a stream or a signal), the measured legs and run flags, what no test runs
- [url.md](url.md) -- `rontolisp:url-*`/`query-param*`
- [regex-engine.md](regex-engine.md) -- the regex engine every front end shares (`eval/regex.lisp`, gate `%REGEX-`): written from the `Pattern` documentation and a JDK's answers; a tree compiled to closures, repetition kinds, the providers keeping Unicode tables out, what was measured against the oracle, deviations, sizes and times
- [unicode-normalization.md](unicode-normalization.md) -- the four normalization forms (`eval/unicode-normalization.lisp`, gate `%UNORM-`): from UAX #15 as one pass, tables generated from a running JDK's `Normalizer`, the three cost steps, sizes and times
- [servlet-httpserver.md](servlet-httpserver.md) -- `servlet-httpserver/`, a Servlet container on `com.sun.net.httpserver`, a Maven project of its own (`jakarta.servlet-api` 6.1): the course of a request, buffering and commit, dispatch, the staged builder (JSpecify, NullAway), discovery, what it needs of the JDK HTTP server, the room the specification leaves (against Tomcat and Jetty), compiling it with rontolisp (refusals, sizes, the served corpus), sessions (`servlet-httpserver-keyvalue/` on `wasi:keyvalue`), asynchronous requests
- [webmvc-fn-lite.md](webmvc-fn-lite.md) -- `webmvc-fn-lite/`, a part of WebMvc.fn under Spring Framework's names on the Servlet API alone (a Maven project of its own): Spring Framework 7.1 and Jackson 3.1 measured, the light-jar decision; path patterns read off Spring; `ConformanceTest` against Spring Framework; written for the compiler (resumable functions, `-Drontolisp.java.resumable`); sizes (`java.math`, tiny-json); JSpecify and NullAway; provenance
- [jdbc-spin-postgres.md](jdbc-spin-postgres.md) -- `jdbc-spin-postgres/`, a JDBC driver for `jdbc:postgresql:` URLs over Spin's `spin:postgres/postgres` through `@WitImport` (depends on `rontolisp-java-api` alone): the decisions (`connect` answering `null` without the host, parameters as casts, transactions as the statements sent, a result in pieces under a fetch size through `query-async`), what the host was measured to do, sizes, a whole result against one read in pieces, the tests (a stand-in host, the Spin leg of `ClPostgresE2eTest`)
- [jdbc-postgresql.md](jdbc-postgresql.md) -- `jdbc-postgresql/`, a JDBC driver for PostgreSQL, a Maven project of its own written from the protocol documentation and the RFCs (pgjdbc beside it in the tests): the decisions (unnamed prepared statement, text values, timestamps, batches, `statement_timeout`, SASLprep, no TLS), differences from pgjdbc, compiling it with rontolisp (sizes, times), in a served program (`wasmtime serve`, the Java shiori), JSpecify and NullAway
- [java-frontend-provenance.md](java-frontend-provenance.md) -- the licence and naming rules, project-wide (written for the Java front end): no JDK jar, source, Javadoc text or test copied or translated; ports only under a licence that allows it, with a THIRD-PARTY-NOTICES.txt entry; "an experimental subset of the Java language", never "Java compatible"; libraries under another's names (Spring, AssertJ, opentest4j; JUnit from the program's dependency; `clojure.core`); trademark statement and DCO; the evidence tables and what each library was written from
- [json.md](json.md) -- `rontolisp:json-parse`/`json-stringify`; `json-span`/`json-value` over a document's bytes (a string, octets, linear memory in place), the path and value shapes decided with sluice, the wasm-GC scan, the measured edits
- [lack.md](lack.md) -- the lack request/response + middleware ecosystem
- [clack.md](clack.md) -- Clack support, the late-bound `clack-handler-rontolisp` shim, and `http-serve.lisp`, the transport legs it shares with the Clojure Ring adapter
- [mito.md](mito.md) -- Mito DAO + schema migration end to end

## Compile path and optimization

- [size-measurement.md](size-measurement.md) -- "smaller" is five different numbers: raw vs gzip when bytes are moved, a total vs a section, a residue vs what is left
- [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md) -- the `--optimize` flag: levels, what the shaker drops, the duplicate-body fold, dispatch pruning, what a `funcall` brought into a small module
- [pure-builtin-fold.md](pure-builtin-fold.md) -- compile-time fold of pure built-ins over literal arguments, and what is deliberately out
- [toplevel-statement-values.md](toplevel-statement-values.md) -- a top-level form, or a non-final body form, is a statement; nothing may be emitted only to be dropped (docstrings, `declare`)
- [compile-time-boundp.md](compile-time-boundp.md) -- `(boundp 'name)` over a literal symbol decided at compile time; a probe it leaves open reads the variable without the eval mirror
- [library-defun-pruning.md](library-defun-pruning.md) -- AST pruning of spliced library defuns, rontolisp's own and ASDF-spliced; the JVM-lived parse caches the splice reads from
- [emitted-output-determinism.md](emitted-output-determinism.md) -- the same program compiles to the same bytes on every run
- [default-run-path.md](default-run-path.md) -- flagless `rontolisp app.lisp` runs the interpreter by decision
- [ir-dump.md](ir-dump.md) -- `--dump-ir` is a compile stopped after the front end: the IR of the target `-o` and its flags name (none: a `.wasm` flag's, `--native`'s or a `.class`'s), nothing written; why the dump is not the same for every target, measured
- [interpreter-stack.md](interpreter-stack.md) -- the CLI, `JvmSourceCompiler` and a compiled JVM `main` run every program on a stack they chose (`--stack`, `-Drontolisp.stack`); every walk loops down the cdr, so depth is nesting, never a list's length (`LispTrees`)
- [interpreter-tail-calls.md](interpreter-tail-calls.md) -- the interpreter's `eval` is a loop: a form in tail position replaces the frame, so a tail call through a value, `apply`, mutual defuns runs in constant stack; the block-owner identity, the depth and time numbers, `or`'s last form
- [measurement-probes.md](measurement-probes.md) -- whether a performance number answers the question that was asked
- [test-execution.md](test-execution.md) -- how the suite runs (surefire forks, concurrent classes, the per-thread stdio device, the CLI-sized stack); no scratch path or port from a constant; no cost judged by a clock; a ci-spec case leaking into the next
- [running-backends.md](running-backends.md) -- running a program on all four backends by hand, the wasmtime pin (every place it is written, what a bump re-measures), the native-image E2E leg, the examples suite
- [adding-primitives.md](adding-primitives.md) -- the per-surface checklists for a new built-in function, macro or special form, and the two silent-failure traps
- [hot-path-method-size.md](hot-path-method-size.md) -- HotSpot's 8000-bytecode HugeMethodLimit and the splits that stay under it

## JVM backend

- [jvm-run-time-lowering.md](jvm-run-time-lowering.md) -- a Clojure program that lowers as it runs on the JVM: what opens a program, the host class with its image and compiler, a fragment class per verb (`ConstantPool.linkFields`, `$PartN`), names resolved through `_lookup`'s miss, a redefined defn reaching every caller (`notinline`), the first verb's read, the native binary's compiler archive, the limits; measured
- [jvm-export.md](jvm-export.md) -- `rontolisp:jvm-export`, `--no-main`, `-o out.jar`, the `-o` path-to-class-name rule, the Maven plugin (its `wasm` goal: `src/main/java` to a `.wasm` through `cli/WasmSourceCompiler`; its `test` goal: `rontolisp test` at `test`, the default backend measured)
- [jvm-int-fusion.md](jvm-int-fusion.md) -- integer expression-tree fusion into unboxed arithmetic; the order checks between leaves and their probe; a declared local raw in one `long` slot, raw answers, trees over such locals in place (measured on SHA-3); a declared array read and stored in its one representation's arm (measured on SHA-512); a fallback past 35 bytecodes a method of its own, no handler over a fast path that cannot throw, and what C2's `InlineSmallCode` still leaves a call
- [jvm-double-arithmetic.md](jvm-double-arithmetic.md) -- `hasDoubleLiteral` routing and unboxed IEEE operations; the exact prefix a float site folds first (`FloatFold`, `_addd`, `_rat_add_f64`), on every backend; one-operand sites unboxed only on a proven float
- [jvm-complex.md](jvm-complex.md) -- the `RontoComplex` holder, the gated `_c*` group, the `hasComplexOperand` steering, and a complex through a variable or beside a float literal found at run time
- [jvm-typed-loops.md](jvm-typed-loops.md) -- typed numeric loops over packed float arrays
- [jvm-bignum-literal-pool.md](jvm-bignum-literal-pool.md) -- one `BigInteger` instance per distinct literal in a `_bi$N` pool
- [jvm-method-size-limits.md](jvm-method-size-limits.md) -- the 64 KB method limit, 16-bit branch offsets, `$PartN` classes past a 65534-entry constant pool, `java.lang.classfile` (`JvmClassSplitter`, `CodeReplay`), the typed emission layer (`MethodCode`, `Ctx.body`)
- [jvm-osr-backedges.md](jvm-osr-backedges.md) -- no backward branch may target a bci with a non-empty operand stack
- [jvm-self-tail-calls.md](jvm-self-tail-calls.md) -- a self tail call jumps back to the method's first instruction (constant stack for every lambda-list shape); the tail mark, what keeps a call, the Clojure deep self tail call deviation; mutual tail calls as a tail group (`JvmTailGroup`), Graal OSR's reducibility, layouts and costs
- [jvm-tail-bounce.md](jvm-tail-bounce.md) -- a tail call through a value: a real call through `_vtc<n>` while fewer than 64 have run since the nearest ordinary call, a bounce through `_tramp` past it; the count as last argument, `_vtcd<n>`, the copied dispatcher; why not a static count, a `ThreadLocal` or an allocation-free bounce; which defuns may bounce; depth/size/time per JIT, C2's dispatcher-size cliff
- [jvm-aot-cache.md](jvm-aot-cache.md) -- the JDK 25 Leyden AOT cache, measured and deliberately not shipped
- [stack-map-frames.md](stack-map-frames.md) -- class version 61, frames computed as `JvmClassSplitter` writes the class (`java.lang.classfile`'s generator over `StackMapFrames.resolver`); failures, measured cost; `ClassFileInfo` on the same API; the `wide` prefix
- [template-class-embedding.md](template-class-embedding.md) -- when to use a Java template class, shipping it beside the program named after it, and class closures
- [java-interop.md](java-interop.md) -- `java:` interop: one resolution model, direct calls (`--java-static`, native-image), `java:reify`/`java:proxy` as generated classes, the reflection bridge, exceptions both ways (`java:java-exception`), `java:handle`/`java:view`, the `:bytes`/`:octets`/`:through` markers, functional interfaces, a Comparator's refusals

## WASM backends

- [wasi-component.md](wasi-component.md) -- `--component` WASI 0.3 component output
- [wit.md](wit.md) -- `am.ik.wit`, `--emit-wit`, `WitTypeMapper`, `rontolisp:wit-export`/`wit-import` (a file or a package directory with `deps/`), `:reactor t` (the exports a program's entry points), the `:names` naming hook and `describe`, `:octets` (a `list<u8>` result lifted as octets), a `list<T>` argument (a list or a vector, `%wit-coerce`), `with-<resource>`, `:canonical t`, `:blocking t`, `:aliases` and a stream of values (`(rows-read h n)`)
- [wasm-export-no-wasi.md](wasm-export-no-wasi.md) -- `rontolisp:wasm-export` + `--no-wasi` reactor mode (the host opt-ins: `--host-random`, `--host-output`, `--host-timer`, `--host-fetch`), and the reactor that keeps its WASI imports (`wit-export :reactor t`)
- [wasm-import.md](wasm-import.md) -- `rontolisp:wasm-import`, `:async t`, `"rontolisp:memory"` and its `search`, `--emit-js-glue`, `--emit-worker-entry`, `--host-boundary`, a WIT interface beyond the flat set on a `--no-wasi` module (the canonical ABI lifted by the glue; `wasi:keyvalue` over Workers KV, `wasi:logging` on the console)
- [proxy-wasm-sdk.md](proxy-wasm-sdk.md) -- `examples/proxy-wasm`: what an exchange runs, instruction by instruction; `define-filter` over `defmethod`, measured; what state and each choice cost, what was weighed and left
- [no-gc-scalar-wasm.md](no-gc-scalar-wasm.md) -- the `--no-gc` non-GC scalar backend: its value model, strings, boundary and imports; the top level (globals, the start function), a structure nothing reads and `error` as a trap; exact mode for a lowered language's program
- [proxy-wasm-no-gc.md](proxy-wasm-no-gc.md) -- the `--no-gc` Proxy-Wasm SDK in `examples/proxy-wasm/no-gc/`: why macros, what state and each piece cost, measured
- [wasm-gc-strings.md](wasm-gc-strings.md) -- GC-backend strings as `$str_bytes` arrays; a literal as one object (`_lit_init`), a kept hash, concatenation by `array.copy` and a chain as one string (`_str_cat`), strings counted where the builder knows the count
- [wasm-gc-final-types.md](wasm-gc-final-types.md) -- every emitted wasm-GC type must be `sub final`
- [wasm-gc-heap-pregrow.md](wasm-gc-heap-pregrow.md) -- `_start` pre-grows the engine's GC heap with one dropped allocation
- [wasm-gc-object-size.md](wasm-gc-object-size.md) -- what a struct field costs on the heap per engine, measured (a wasmtime object is a 16-byte header plus fields rounded to 16, so the identity-hash slot is free there and +8 bytes per cons on V8), and the capped-heap probe that measures it
- [wasm-landing-pad-refresh.md](wasm-landing-pad-refresh.md) -- a `try_table` landing pad refreshes every local live after it: Cranelift passes a local into the pad as a pre-call exceptional-edge argument, stale after a copying collection; the push is narrowed to the live locals once the body is complete; fixed upstream in 48.0.4 / 49.0.2, kept while the documented floor takes older hosts
- [wasm-bignum.md](wasm-bignum.md) -- exact integers in three tiers (i31, `TYPE_BIGNUM`, `TYPE_BIGINT`), ratios with components in those tiers, and the correctly rounded conversions to double
- [wasm-complex.md](wasm-complex.md) -- the tagged `TYPE_COMPLEX` struct (the `TYPE_FARRAY` twin that forced the tag), the `_c*` runtime group, the `containsComplex` steering, and a complex through a variable or beside a float literal found at run time
- [wasm-int-fusion.md](wasm-int-fusion.md) -- integer expression-tree fusion keeping raw i64; the order checks with an inline probe; declared leaves, counted shifts, the bitwise operations' signed-field ranges; a leaf defun compiled twice; `--optimize=size` keeping a site fused where that is the smaller emission (`WasmSizeChoice`); the guest profiler's trap
- [wasm-ref-type-fold.md](wasm-ref-type-fold.md) -- a closed module's `ref.test`/`ref.cast` are decided by its own constructors: the byte-level type-flow fold before the shaker, the call-forwarding redirect, the exact integer export lane, the raw condition compare
- [wasm-counted-loops.md](wasm-counted-loops.md) -- a loop induction variable as a bare `i64` counter
- [wasm-unboxed-locals.md](wasm-unboxed-locals.md) -- dual-representation `let` locals, the exact flavour, a declared binding or parameter kept raw where it is read mostly raw
- [wasm-callable-arity.md](wasm-callable-arity.md) -- the 10-parameter callable limit as an index origin
- [wasm-tail-calls.md](wasm-tail-calls.md) -- every tail call is a `return_call` (the dispatcher and `_apply` too): constant stack; the consumed-at-entry flag, the post-passes, the inliner's three rules, the numbers, the crash-reads-as-fast trap
- [wasm-function-body-size.md](wasm-function-body-size.md) -- no emitted function body may grow without bound
- [cons-access-runtime.md](cons-access-runtime.md) -- `car`/`cdr` as one shared callee under `--optimize=size`; the temp-free plain-local read; what an external optimizer's residue is made of
- [wasm-shortest-encoding.md](wasm-shortest-encoding.md) -- every emitted byte in its shortest legal encoding
- [wasm-shared-coercion.md](wasm-shared-coercion.md) -- the numeric-to-`f64` coercion is one runtime function, never an inlined ladder
- [wasm-linear-memory-layout.md](wasm-linear-memory-layout.md) -- what lives where in a module's linear memory; every address derived from the module's own static-data end (a fixed env/argv block once landed inside large static data and corrupted fold tables); the block reserved only where `environ_get`/`args_get` is reachable; the two fixed component-only regions

## Numeric, GPU and native

- [transcendentals.md](transcendentals.md) -- one algorithm (fdlibm: `StrictMath` on the interpreter and the JVM, the same fdlibm on WASM) so every backend and CPU answers the same bits; gating, sizes, costs; `%strict-math`; `Math` against `StrictMath` on x86-64; `scale-float`
- [linalg.md](linalg.md) -- the `linalg` package and the standard array functions
- [linalg-simd.md](linalg-simd.md) -- `--simd` interception of the `linalg:` kernels
- [linalg-blas.md](linalg-blas.md) -- `--blas`: the matrix product on a tuned CBLAS from the OS
- [simd-parallel.md](simd-parallel.md) -- `--parallel` over the `--simd` matrix products
- [gpu.md](gpu.md) -- `--gpu` and `am.ik.gpu`, the never-throwing declining device layer
- [native-output.md](native-output.md) -- `--native -o prog`: the wasmtime precompile shim and the runner stub (`rontolisp-native/`), the Java side (`cli/NativeToolchain`, `cli/NativeExecutable`); engine config, fingerprint, payload trailer, resource cache, refusals, the separate-build trap, the CPU baseline and `--native-target`, the network runner (`rlrun-net`), numbers
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
- [objc.md](objc.md) -- `objc:`/`appkit:`/`metal:`: a native macOS window through FFM; one Lisp vocabulary over per-backend primitives, ownership counts, `fli:` foreign objects, class definition, blocks, Objective-C exceptions caught under every call, thread 0, the native binary's closed shape table, `--native`'s runner host; per-send and audio render-block measurements

## Ecosystem and tooling

- [asdf.md](asdf.md) -- real ASDF's whole export list over the limited `asdf:defsystem`/`load-system`/`test-system` subset, a `.asd`
  run as Lisp in an image (the compile paths' build image) with its `defsystem` forms parsed as
  data, the measured decision for that over a data-only parse, and the operation protocol
  (`asdf:perform` around every component load, the compile paths' thunks); building an executable
  (`program-op`, `asdf:make`, `rontolisp build`: the interpreter's image compiled by `uiop:dump-image`;
  deploy's build-time and boot hooks)
- [uiop.md](uiop.md) -- uiop as the 15-sub-package bundle upstream is
- [dists.md](dists.md) -- the download half of `ql:quickload`; `am.ik.artifact`, the fetch-and-cache layer every downloader shares (size + MD5 verification, timeouts, tar.gz/zip, atomic install)
- [git-fetcher.md](git-fetcher.md) -- `am.ik.artifact.GitFetcher`: a repository at a full sha into the cache through the `git` CLI (why not forge archives), the clone/checkout layout and key, tag checks, the resolver's questions (a revision's commit, a tag, the descendant of two commits), the refusals
- [maven-resolver.md](maven-resolver.md) -- `am.ik.maven`: coordinates to descriptors, a collected graph and jars through `~/.m2`; Maven 3.9's model building replicated (profiles, inheritance, interpolation, BOM imports, the super POM, plugin merges, `relativePath`, validation) and measured against Maven; the MXParser-compatible XML reader (why not `java.xml`); deliberate differences; `maven-metadata.xml` (ranges, SNAPSHOT, LATEST/RELEASE); `settings.xml` (mirrors, proxies, servers, encrypted passwords)
- [time-environment-builtins.md](time-environment-builtins.md) -- time/environment built-ins, the universal-time codec and the local time zone per backend, and the environment-enquiry family
- [architecture.md](architecture.md) -- the pipeline, the language-independent libraries, the package dependency graph and the per-package import rules, the `CompileFrontend.expand` no-restatement rule and its drift history, requirements
- [session-workflow.md](session-workflow.md) -- builds outside the root reactor (a worktree's own local repository over the shared one), the CI deploy job (Maven 3.10 credential scoping, the `sonatype-central` server id), the release jobs' snapshot tag (why GITHUB_TOKEN cannot always move it), waiting for a long run and the three false greens, working beside other sessions on `develop` (`.todo` number claims), the after-task checks
- [cli-options.md](cli-options.md) -- `CliOptionTable`, the one table the command-line parser, `--help` (grouped by front end and back end, `--help=GROUP,...`) and the reference page read; unknown options refused
- [documentation-site.md](documentation-site.md) -- doc site layout, fence conventions, the Run cells (in the playground's worker, with Stop), search index, build/preview, checking the built image
- [directory-rename.md](directory-rename.md) -- moving a directory: what breaks outside the rename's own diff (location-scoped ignore rules, a citation vs a member, relative paths inside what moved, re-flowed javadoc lines, fixture lookups turning into skips), and `PathCitationTest` as the machine half
