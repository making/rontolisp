# CLAUDE.md

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw clean spring-javaformat:apply package                    # Build executable JAR (-exec classifier)
./mvnw spring-javaformat:apply test                             # Run all tests
```

User-facing behavior, limitations, and examples live in the documentation site
sources under `doc/en/**` (Markdown rendered to HTML by the standalone
`docs-tool/` generator; the runnable Lisp examples are verified by
`DocExamplesTest`). The README is a slim landing page that links to the site.
This file is the agent-facing companion: invariants you must not break and
pointers to where things live. When a constraint below ends with "(README)", the
user-facing description lives in `doc/` (and its rendered site) -- don't
duplicate it here. The docs nav/table of contents is `doc/en/nav.yaml`.

Implementation-level detail behind each constraint below (internal class names,
per-backend mechanics, edge cases, pinning tests) lives in `.kb/` -- see
`.kb/README.md` for the index. Read the one-line summary here first; open the
linked `.kb/*.md` file only when you need the full mechanics.

## Architecture Overview

Three execution modes share a common frontend (reader) and AST:

```
Source string
  -> LispReader (reader pkg) -> List<LispVal> (AST)
    -> [LispMacroExpander] -> expanded AST               # cond/and/or/setf -> if/let/progn/setq/rplaca/rplacd; defun is a special form
    -> LispEvaluator (eval pkg)                          # interpret
    -> JvmLispCompiler (codegen.jvm) -> byte[] (.class)  # compile to JVM
    -> WasmLispCompiler (codegen.wasm) -> byte[] (.wasm) # compile to WASM
```

`am.ik.jvm` and `am.ik.wasm` are **language-independent** bytecode generation libraries ported from [bfc](https://github.com/making/bfc).

Package dependency direction (no cycles allowed):

```
cli -> eval, codegen.*
codegen.jvm -> compiler, am.ik.jvm
codegen.wasm -> compiler, am.ik.wasm
compiler -> rontolisp (AST types only)
eval, reader -> rontolisp (AST types only)
```

## Key Design Constraints

- **Lisp-2 (separate function/variable namespaces) in all three backends**: a bare symbol is a variable reference only; a symbol in call position resolves in the function namespace only; a function value comes from `(function name)`/`#'name`/`symbol-function`. Details: `.kb/lisp2-namespaces.md`.
- **Lambda list extensions (`&optional`/`&rest`/`&key`/`&aux`/`&allow-other-keys`)**: `LambdaLists` desugars everything to the one native shape "required + `&rest`" (a `let*` prologue wrapped around the body); only `&rest` has per-backend support (variadic flag on the function records, surplus-arg packaging at call sites/dispatchers, negative arity in the eval registry). Not supported: `&whole`/`&environment`, runtime-`eval` lambdas, `--no-gc`. Details: `.kb/lambda-lists.md`.
- **JVM Class Version 50 (Java 6)**: avoids mandatory StackMapTable (required from version 51+); the lenient version-50 verifier is relied on throughout.
- **WASM function types outside rec group**: wasmtime's WASI host requires plain `(func ...)` types for imports; only the cons struct goes inside a rec group.
- **symbolp/stringp**: quoted symbols and string literals share runtime representation, distinguished by a leading `"`.
- **consp in JVM**: both cons cells and function references use `Object[]`, distinguished by `arr[0] instanceof Integer`.
- **Three-pass compilation**: Pass 1 collects defuns; 2a compiles defun bodies, 2b top-level, 2c iteratively compiles lambda bodies (top-level must compile before lambda iteration).
- **`do`/`return` and the `%block` non-local exit boundary**: `do` expands to a `let`/`while` loop; `return` is a non-local exit to the nearest enclosing `%block`, implemented differently per backend; only works where the surrounding operand stack is empty. Details: `.kb/do-return-block.md`.
- **`defmacro` (user macros) + read-time backquote + `destructuring-bind`**: backquote (incl. nested/multi-level backquote via a CLtL2 Appendix C port in `LispReader`, all levels expanded at read time to `list`/`cons`/`append`/`quote`) is expanded by the reader; `defmacro` is fully expanded at compile time by `UserMacroExpander` before the compilers run -- no backend codegen, no Jvm/Wasm macro compiler. `destructuring-bind` is a `LispMacroExpander` lowering to a `let*` of car/cdr chains (nested patterns + `&optional`/`&rest`/`&body`/`&key`/`&aux`, lite no-mismatch-error semantics); `defmacro` lambda lists beyond "required + `&rest`/`&body`" are wrapped in it at definition time, so both expansion consumers destructure identically. Details: `.kb/defmacro-backquote.md`.
- **`gensym` + `macroexpand`/`macroexpand-1`**: implemented in all three backends; the interpreter and the compile-time macro expander use separate counters, so gensym names can diverge across backends. Details: `.kb/gensym-macroexpand.md`.
- **Runtime symbol API (`symbol-name`/`intern`/`find-symbol`/`make-symbol`/`boundp`/`fboundp`/`symbol-value`)**: symbols compare by name (no intern table), so `symbol-name` returns the stored name verbatim (case-preserving, NOT upcased; keywords keep `:`), `intern` is plain symbol construction (package argument = error), and `make-symbol` prepends the gensym `#:` marker. `boundp`/`symbol-value` see GLOBAL variables only (CL dynamic-only semantics) and, with computed-`fboundp`, force `usesEval` on the compile path (JVM `_genv`/`_fenv`, WASM always-present `FUNC_MAKE_SYMBOL`..`FUNC_FBOUNDP` helpers; WASM `intern` canonicalizes offsets through the reader `_intern` via a `usesIntern` gate). `find-symbol` folds at compile time (literal string only). Details: `.kb/symbol-runtime-api.md`.
- **`defstruct`**: expanded by the shared `LispMacroExpander.expandDefstruct` into plain defuns over a tagged-list representation (no per-backend codegen); the compilers splice top-level forms before Pass 1 (top-level only on the compile path), and an accessor-position registry threaded into `expandSetf` makes accessors setf places. Options/`:include`/`#S` and `--no-gc` are unsupported. Details: `.kb/defstruct.md`.
- **`flet`/`labels` (local functions)**: `LispMacroExpander` expansions to let-bound lambdas plus a Lisp-2 body rewrite (call position `(f ...)` -> `(funcall var ...)`, `#'f` -> `var`; bare `f` stays a variable); `labels` binds nil then `setq`s the lambdas (letrec via boxed captures). Definition lambda lists are desugared inside the expansion (`LambdaLists.expand`), variable names are counter-unique. `macrolet`/`symbol-macrolet` unsupported. Details: `.kb/flet-labels.md`.
- **Multiple values (syntactic tier)**: `values` + `multiple-value-bind`/`-list`/`-call`/`nth-value` are `LispMacroExpander` lowerings with NO runtime multiple-value representation -- consumers recognize the producer syntactically (a literal `(values ...)` call, or the two-value built-ins `floor`/`ceiling`/`round`/`truncate` and `gethash` (gensym-sentinel present-p)); any other producer (including a user function ending in `values`) supplies its primary value only, extra vars read nil. `(values ...)` in an ordinary context = `prog1`; `(floor a b)` etc. = `(floor (/ a b))` everywhere; `values` is classified as a function (the first variadic `BuiltinFunctionWrappers` entry), the other four as CL_MACROS. Details: `.kb/multiple-values.md`.
- **Declarations, `eval-when`, `check-type`/`assert`**: `declare`/`declaim`/`proclaim` expand to nil (parsed no-ops), `the` to its value form, `eval-when` to `progn` -- plus `flattenTopLevel` splices top-level `progn`/`eval-when` on the compile path (UserMacroExpander entry + all three compilers) so nested defun/defmacro definitions are collected. `check-type`/`assert` are lite error-signaling expansions over a shared `makeTypeTest` (atomic + `or`/`and`/`not`/`member`/`eql`/`satisfies`/ranged-numeric specifiers, also used by `typecase`). All classified as CL_MACROS. Details: `.kb/declarations-type-checks.md`.
- **`%` prefix convention**: internal helpers not part of the public API use a `%` prefix (e.g. `%remf-tail`).
- **Built-in function wrappers**: `BuiltinFunctionWrappers` synthesizes `(setq name (lambda ...))` defuns so `#'+`/`#'car` etc. work as first-class values; this shape is internal encoding, not a real user function definition (Lisp-2).
- **JVM method name mangling**: `JvmLispCompiler.mangleMethodName()` maps `/`, `<`, `>`, `:` (forbidden in JVM method names) to `$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`.
- **Packages**: a `cl`/`cl-user`/`rontolisp` namespace system resolved by a single `PackageResolver` pass before the evaluator/compilers run; CL colon semantics (`pkg:name` = external symbols only, `pkg::name` = any, canonical form matches externality so it re-resolves to itself). Nicknames (`common-lisp` -> `cl`, `common-lisp-user` -> `cl-user`, `defpackage :nicknames`) resolve through `PackageRegistry.canonicalName`; `defpackage` also supports `:import-from` (textual redirect to the source package's canonical spelling), ignores `:documentation`/`:size`, and rejects `:shadow`/`:shadowing-import-from`; `#:name` designators are accepted everywhere. Details: `.kb/packages.md`.
- **Reader features (`#+`/`#-`, `*features*`, `#|...|#`, `#.`)**: resolved entirely in the frontend lexer/reader against a `reader.Features` set (`:rontolisp` + `:rontolisp-interpreter`/`-jvm`/`-wasm`; the CLI picks by output target, `LoadInliner` threads it into loaded files, so a compiled program's feature set is fixed at compile time). A failing `#+`/`#-` guard skips the next datum at the raw character level (may contain unsupported syntax); `*features*` reads as a quoted keyword list like `pi`; `#.` is a clear read error except in `.asd` files (skip + warning). The compiled runtime readers do NOT know any of this. Details: `.kb/reader-features.md`.
- **`read`/`load`/`read-line`/file streams in all three backends**: a runtime reader/parser is emitted into compiled output, mirroring `eval`; a stream is an opaque, backend-local integer handle. Details: `.kb/read-load-streams.md`.
- **String streams (`with-output-to-string`/`with-input-from-string`) + print-family stream args**: the two macros expand over `%`-internal builtins into the same stream handle space (interpreter/JVM: `StringWriter`/`StringReader` table entries; WASM: negative i31 handles over linear-memory records -- output = a chunk list referencing existing bytes, input = a cursor range consumed via `_read_line`); `print`/`prin1`/`princ`/`terpri` take an optional stream argument on all three backends, `format` accepts a stream destination (built like `format nil`, written with one `write-string`), and `write-string`/`write-to-string` are functions. `read` on a string stream is line-oriented (one datum per line) like the stream `read` everywhere. Details: `.kb/read-load-streams.md`.
- **Compile-time `load` inlining (`LoadInliner`)**: a literal, top-level `(load "file.lisp")` is spliced in at compile time so JVM/WASM see the loaded defuns natively; paths resolve relative to the loading file. The same pass implements `require`/`provide` (idempotent module loading): real runtime functions on the interpreter, literal top-level directives on the compile path (nested/non-literal = compile error). Details: `.kb/load-inliner.md`.
- **`asdf:defsystem`/`asdf:load-system` (limited ASDF subset)**: an API-compatible mini-ASDF, NOT a port -- `.asd` files and `defsystem` forms are parsed as plain data (`eval/AsdfSystems`, never evaluated; only `defsystem` + skipped `in-package` allowed in a `.asd`), components topo-sorted by `:depends-on`/`:serial`, `NAME.asd` located via loading-file dir -> `--system-path` -> `RONTOLISP_SOURCE_REGISTRY`. Compile path splices inside the `LoadInliner` recursion (idempotent like `require`; nested/non-literal = compile error); interpreter has `defsystem` as a special form + `load-system` as a runtime function (computed names OK). Component-level `:if-feature` drops a disabled component's files (graph slot kept); `#.` in a `.asd` is skipped with a warning; unsupported clauses (`:in-order-to`/`:perform`/...) are hard errors; no ci-spec case (compile path needs the `.asd` on disk). Roadmap: `.todo/54-asdf-support.md`. Details: `.kb/asdf.md`.
- **`--dynamic` (late binding)**: opt-in; unresolvable calls/variables fall back to the embedded `eval` at those call sites instead of a compile error. Details: `.kb/dynamic-late-binding.md`.
- **`--component` (WASI 0.3 output)**: opt-in; the core module stays Preview-1-identical (fixed `FUNC_*` indices) and an adapter module implements WASI over async streams/futures. Details: `.kb/wasi-component.md`.
- **`rontolisp:wasm-export` + `--no-wasi` reactor mode**: exports a Lisp `defun` as host-callable WASM (Preview 1 only); `--no-wasi` emits a reactor module with no WASI imports. Details: `.kb/wasm-export-no-wasi.md`.
- **`rontolisp:wasm-import` (host functions) + export `:as` aliases**: declares a host function callable from Lisp like a defun (Preview 1 only; error under `--component`/`--no-gc`); compiled as a synthetic-defun wrapper calling a placeholder index that the `WasmImportInjector` post-pass resolves by prepending the imports and renumbering every function reference. Details: `.kb/wasm-import.md`.
- **`--optimize` (dead-code elimination)**: a post-pass tree-shaker for both WASM (`WasmTreeShaker`) and JVM (`JvmClassShaker`); skipped under `--component`. Details: `.kb/optimize-dead-code-elimination.md`.
- **`--no-gc` (non-GC scalar WASM lowering)**: a separate backend (`ScalarWasmCompiler`) using static type inference (`i64`/`f64`/string pointer) that emits a plain MVP module needing no `-W gc`. String/char primitives: `concatenate`/`length`/`subseq`/`string=`/`char`/`char-code`/`code-char`/`char=`/`princ-to-string` over `[len][bytes]` linear-memory headers; a character IS its code point (no separate type). Details: `.kb/no-gc-scalar-wasm.md`.
- **Time/environment built-ins**: implemented in all three backends; WASM returns floats where magnitudes exceed the `i31` range. Details: `.kb/time-environment-builtins.md`.
- **`rontolisp:fetch` (outgoing HTTP)**: interpreter/JVM use `java.net.http.HttpClient`; WASM support is component-only via a WASI 0.2 http adapter. Details: `.kb/fetch-http.md`.
- **`rontolisp:http-handler` (incoming HTTP / serving)**: `(rontolisp:http-handler 'name [port])` serves requests with a handler taking a request plist (`:method`/`:path`/`:query`/`:headers`/`:body`; `:path` is the path only, `:query` the raw query string without the `?` or nil -- split once in `HttpHandlerSupport.Request.of` for interpreter/JVM and in the inliner's `%http-request` helper on the component path) and returning a response plist (`:status`/`:headers`/`:body`) -- the incoming counterpart of `fetch`, same value model. **Interpreter** (`HttpHandlerSupport`: blocking JDK `HttpServer`, one virtual thread per request; `serve()` blocks, `start()` is the test seam; registered in `LispEvaluator` because serving applies the handler via `apply`) and **WASM component** (`--component`): a `HttpHandlerInliner` cli pre-pass rewrites the directive into a `%http-dispatch` wasm-export wrapper (`"<status>\n<body>"`), `WasmLispCompiler` serve mode (implies component) un-gates wasm-export while importing memory, and `WasmServeComponentBuilder.buildServe` wraps mem + the preview1 bridge (`adapter-serve-p1.wasm`, instantiated before the core: `random`/time/`print` work inside a served handler via `wasi:random`/`wasi:clocks`/`wasi:cli` stdio; `getenv` nil, file streams unavailable) + core + `adapter-serve.wasm` into a `wasi:http/incoming-handler@0.2.0` component that runs under `wasmtime serve -W gc=y` (plain WASI 0.2 -- no `component-model-async` flags needed, unlike `wasmtime run`; any `wasi:http` 0.2 host with wasm-GC works -- verified 2026-07 on jco `jco serve` (Node/V8, GC on by default) and wasmCloud `wash dev`/`wash host` with the `gc` proposal enabled, `dev.wasm_proposals: [gc]` / `--wasm-proposal gc`). **Spin cannot run it** (Spin's wasmtime doesn't enable the wasm-GC proposal every rontolisp component needs and has no flag to do so -- same limitation for fetch/tcp). **JVM backend** (`JvmHttpHandlerRuntimeBuilder` + `JvmHttpHandlerCompiler`): the generated class implements `HttpHandlerSupport.Handler` (the tls-connect `X509TrustManager` mechanism -- shared no-arg ctor, `handle` is an extra `--optimize` shaker root), the directive stores the handler funcref in the `_httpHandlerFn` static field and calls `HttpHandlerSupport.serve(port, new Prog())`, and the injected `handle()` marshals the plists through `_invoke_1`; the compiled class therefore needs the rontolisp jar on the runtime classpath. Preview-1 WASM = compile error "requires --component". Interpreter and JVM marshal headers; the WASM component still drops them (only method/path/body/status). The serve adapter chunks response writes at 4096 bytes (`blocking-write-and-flush` cap) and resets both bump allocators per request (intern-count-guarded; needed on instance-reusing hosts like jco/wasmCloud -- `wasmtime serve` instantiates per request). **fetch inside a served handler** works on all three backends: on the component path a program using both swaps the bridge for `adapter-serve-p1-http.wasm` (fetch-start/fetch-await + the reserved-slot tcp stubs, satisfying the core's `http`/`sock` imports) over `import-block-serve-http.bin` (`WasmServeComponentBuilder.buildHttp`; still the proxy world -- run with `wasmtime serve -W gc=y -S http=y`); serve + `rontolisp:tcp-*` stays a compile error. Details: `.kb/fetch-http.md`; full design in `.todo/51-wasi-http-incoming-handler-spin.md`.
- **`rontolisp:tcp-*` (TCP sockets)**: `tcp-connect`/`tcp-listen`/`tcp-accept`/`tcp-local-port` return bidirectional handles in the file-stream handle space, so `read-line`/`write-line`/`read-byte`/`write-byte`/`close` work on sockets; interpreter/JVM use `java.net.Socket`, WASM is component-only over native WASI 0.3 `wasi:sockets` (fd >= 200 in the sockets adapter; run with `-S tcp=y -S inherit-network=y`; IPv4 literals only; fetch+tcp in one component is a compile error). `tls-connect` (client; fresh SSLContext per call, endpoint identification on, `:insecure value` skips verification -- interpreter uses a trust-all manager, JVM makes the generated class implement `X509TrustManager`) and `tls-listen` (server; PKCS12 keystore + password, accepted via the plain `tcp-accept`, lazy handshake) are the encrypted variants -- SSL(Server)Socket subclassing keeps the stream runtime untouched. `tls-listen-pem` (cert + unencrypted-PKCS#8 key PEM files) parses PEM via the shared `SocketSupport.pemToKeyStore`: the interpreter at run time, the JVM at compile time (the `TlsPemInliner` cli pre-pass embeds a Base64 PKCS12 blob and rewrites to the internal `%tls-listen-p12`, so PEM paths must be literals when compiling). All three interpreter/JVM only, compile error on WASM. Details: `.kb/tcp-sockets.md`.
- **`rontolisp:json-parse`/`json-stringify` (JSON)**: one hand-written Lisp-source library (`json.lisp`), lazily loaded by the interpreter and spliced into compiled programs by `JsonLibrary.process` (a cli/playground pre-pass like `UserMacroExpander` -- compiler unit tests must call it explicitly). WASM `equal`/`_hash` compare string content so runtime-built strings work as hash keys. Details: `.kb/json.md`.
- **`rontolisp:url-decode`/`url-encode`/`query-params`/`query-param`/`url-path`/`url-query` (URL / query strings)**: a third Lisp-source library (`url.lisp` + `UrlLibrary`, linalg pattern: plain fixed-arity defuns, no call-site rewriting; the interpreter lazy-loads on the first resolution of a public name, the cli/playground pre-pass splices). Percent decoding/encoding reassembles multi-byte UTF-8 correctly on both string representations (UTF-16 interpreter/JVM, byte-indexed WASM); `query-params` returns a url-decoded alist; the parsing layer over the request plist's raw `:query`. Details: `.kb/url.md`.
- **`linalg` package (numpy-style vector/matrix ops) + standard array functions**: `linalg.lisp` is a second Lisp-source library (`LinalgLibrary`, json.lisp pattern but with no call-site rewriting; the interpreter lazy-loads on a `linalg:` function-lookup miss). `array-dimensions` and `row-major-aref` (+ `%row-major-aset`) are the backend primitives; `vector`/`svref`/`array-rank`/`array-dimension`/`array-total-size`/`array-row-major-index`/`coerce` are `LispMacroExpander` expansions (the JVM array-helper gate must list the derived names). Arrays are rank-n across all backends (JVM header slot 0 = an `Object[]` of Long dims). Details + a full API quick reference (signatures/semantics -- read it before writing any program that uses `linalg:`): `.kb/linalg.md`.
- **Fill-pointer / `:adjustable` arrays (`copy-array` surface)**: `make-array` `:fill-pointer`/`:adjustable`, `fill-pointer`(+setf)/`array-has-fill-pointer-p`/`adjustable-array-p`/`array-element-type`, `vector-push`/`vector-pop`/`vector-push-extend`. **Interpreter only so far** (`LispArray` gained a mutable `fillPointer`/`adjustable`; the verbatim cl-utilities `copy-array` runs there). JVM/WASM/`--no-gc` and displaced arrays / `adjust-array` are pending follow-ups (`.todo/71`-`73`). Details: `.kb/adjustable-arrays.md`.
- **`java:` interop**: a built-in `java` package with reflection-based interop, supported in the interpreter and JVM compiler (via an embedded bridge class), not WASM. Details: `.kb/java-interop.md`.
- **Template-class embedding is a last resort**: prefer (1) macro expansion, then (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`, only then (3) an embedded Java "template" class (used by `java:` interop). Details: `.kb/template-class-embedding.md`.
- **`eval` in all three backends**: a runtime tree-walking interpreter sharing the compiled value representation, emitted only when a program calls `eval`. Details: `.kb/eval-runtime.md`.
- **Hash tables**: interpreter/JVM use a real `HashMap`; WASM implements a true open-chaining hash table, not an alist. Details: `.kb/hash-tables.md`.

## Development Workflows

### Implementation Order

When adding a new built-in function or special form:

1. **Interpreter** (`LispEvaluator` / `Environment`) -> run `LispEvaluatorTest`
2. **JVM compiler** -> run `JvmLispCompilerTest`
3. **WASM compiler** -> run `WasmLispCompilerIntegrationTest`
4. Add a case to `src/test/resources/ci-spec.yaml` if it should be covered end-to-end (`CiSpecE2eTest`)
5. Update the docs (see "Updating the Documentation Site"). For a new built-in **function / macro / special form**: add a per-operator page (H1 = name, signature, short description, one runnable ```lisp example with a `; => value` annotation -- a static ```console block for forms needing stdin/files or that signal, e.g. `error`/`with-open-file`) under the matching `reference/{functions,macros,special-forms}/` directory and a `_catalog.yaml` entry, then run the `-Drontolisp.doc.fix=true` helper. The table page name links to it automatically.

### Adding a New Built-in Function

1. **LispNames.java / PackageRegistry.java**: Add the name constant, and add it to `PackageRegistry.CL_SYMBOLS` (otherwise it is misclassified as a user symbol).
2. **Environment.java**: Add in `createGlobal()` via `env.define("name", new LispFunction(...))`.
3. **JVM compiler**: Create `Jvm<Name>Compiler.java`, add a case in `JvmExprCompiler.compileCons()`.
4. **WASM compiler**: Create `Wasm<Name>Compiler.java`, add a case in `WasmExprCompiler.compileCons()`. Use `WasmEmitHelper.castI31GetS()` to unbox to `i32` and `ref.i31` to re-box.
5. **BuiltinFunctionWrappers.java**: Add a `WRAPPER_DEFS` entry (arity + body AST) so it works as a first-class value.

### Adding a New Macro

Macros expand into existing primitives (`if`, `let`, `progn`, `rplaca`, `rplacd`) at the AST level. `LispMacroExpander` (in `am.ik.rontolisp`) is shared by the evaluator and both compilers. No per-compiler class is needed.

1. **LispMacroExpander.java**: Add `public static LispVal expand<Name>(LispCons cons)`. Add the name to `LispNames` and `PackageRegistry.CL_SYMBOLS`.
2. **LispEvaluator.java**: `evalCons()` case -> `return eval(LispMacroExpander.expand<Name>(cons), env);`
3. **JvmExprCompiler.java** / **WasmExprCompiler.java**: case -> `compileExpr(LispMacroExpander.expand<Name>(cons), ctx, ...)`.
4. **First-class value support** (if it should be passable to `map`/`reduce`/`funcall`): register as `LispFunction` in `Environment` (interpreter) AND add a `BuiltinFunctionWrappers` entry using the expanded body (compilers). Both are needed; omitting `Environment` causes `Undefined symbol` in interpreter/native-image mode.

### Adding a New Special Form

1. **LispEvaluator.java**: `evalCons()` case (special forms receive unevaluated arguments).
2. **JVM / WASM**: Create `Jvm/Wasm<Form>Compiler.java`, wire into `Jvm/WasmExprCompiler.compileCons()`.

### Updating the Documentation Site

The user-facing manual is Markdown under `doc/en/**`, rendered to a static HTML
site (`web/dist/docs`) by the standalone generator in `docs-tool/` and published
to GitHub Pages by `.github/workflows/pages.yaml`. Every doc change must be
mirrored across `doc/en/**` and `doc/ja/**` (and any future `doc/<lang>/`) in the
same commit -- same file set, same headings, byte-identical code fences; only
prose and titles are translated. Layout, code-fence conventions (`lisp`/`console`/
`bash`), and the full sync/build/preview procedure are in `.kb/documentation-site.md`.

After editing examples, normalize results and catch non-runnable examples:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixDetailResults test  # rewrite ; => / output of detail pages
./mvnw -Dtest=DocExamplesTest test                                            # verify every example runs + matches
```

### Verifying Output Manually (all four backends)

A program is "verified" only when it has been run on **all four** backends:
interpreter, JVM, WASM Preview 1, and WASM component (`--component`). Don't stop
at three — the component path uses a different I/O adapter (and, for `random` /
time, a different entropy/clock source), so it can diverge from Preview 1.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
echo '(print (+ 1 2))' > test.lisp

# 1. Interpreter
java -jar $JAR test.lisp

# 2. JVM (class is named after the output file, so keep it path-free)
java -jar $JAR test.lisp -o Prog.class && java Prog

# 3. WASM Preview 1 (requires wasmtime 14+)
java -jar $JAR test.lisp -o test.wasm && wasmtime run -W gc test.wasm

# 4. WASM component / WASI 0.3 (requires wasmtime 46+)
java -jar $JAR test.lisp -o test-comp.wasm --component && \
  wasmtime run -W gc=y -W component-model-async=y \
    -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
    test-comp.wasm
```

### Verifying the Native Image End-to-End (run locally before every push)

`./mvnw test` (JVM) **skips `CiSpecE2eTest`** because `-Drontolisp.binary` is
unset, so a stale `ci-spec.yaml` expectation passes the JVM run and only the CI
`native-image` job catches it. Reproduce that job locally before pushing —
always after editing `ci-spec.yaml`, and after any change that can shift
cross-backend output (a new built-in/macro/special form, anything touching
introspection, or a runtime behavior change). Requires GraalVM `native-image`
and `wasmtime` on `PATH` (build ~30s):

```bash
# 1. Build the native binary (same as the CI native-image job)
./mvnw -V --no-transfer-progress -Pnative clean package -DskipTests

# 2. Run the cross-backend E2E driver against the native binary
#    (interpreter / JVM / WASM in one run; names the exact failing case)
./mvnw -V --no-transfer-progress \
  -Dtest=CiSpecE2eTest -DfailIfNoTests=false \
  -Drontolisp.binary="$PWD/target/rontolisp" \
  test
```

A failure prints `[case '<name>' on <BACKEND>` with the offending lines; fix the
`ci-spec.yaml` `expected` (or the backend) and re-run step 2 only (the binary
does not need rebuilding unless Java sources changed).

## Development Requirements

- Java 25+
- No external dependencies for core libraries (reader, eval, codegen, am.ik.jvm, am.ik.wasm). The `docs-tool/` documentation generator is a separate Maven project (not part of the reactor) and may use flexmark/snakeyaml.
- Spring Java Format enforced via Maven plugin
- Use modern Java features (Records, Pattern Matching, Sealed Types, Text Blocks, etc.)
- Avoid circular references between classes and packages
- `src/test/resources/ci-spec.yaml` is the single source of truth for the cross-backend E2E test (`CiSpecE2eTest`, package `am.ik.rontolisp.e2e`). Each case is `name` + `source` + `expected` (with an optional `expectedByBackend` override). Cases share global state and run IN ORDER: the driver concatenates them into one program, runs the native binary once per backend (interpreter / JVM / WASM Preview 1 / WASM component via `--component`), and slices the output back per case. The driver only runs when `-Drontolisp.binary=<path>` is set, so `mvn test` on the JVM skips it.
- Documentation lives in `doc/en/**` (Markdown), built by `docs-tool/` and published by `.github/workflows/pages.yaml`; see "Updating the Documentation Site" above and `.kb/documentation-site.md`.
- WASM integration tests skipped if Docker unavailable

### After Task Completion

- Format: `./mvnw spring-javaformat:apply`
- Test: `./mvnw test`
- Web profile compile: `./mvnw -Pweb compile`. Required whenever `src/web/java`
  changed (e.g. the `Target_*` substitutions or `web/` `@JS` classes), or when a
  signature it overrides changed (e.g. `HttpSupport.request`). `src/web/java` is
  added to the sources only under the `web` profile, so the default `./mvnw test`
  does NOT compile it — a break there only surfaces in the web-playground CI job.
- Native E2E: build the native image and run `CiSpecE2eTest` against it (see
  "Verifying the Native Image End-to-End"). Required whenever `ci-spec.yaml` or
  any cross-backend output changed — `./mvnw test` does not cover it.
- Javadoc: `./mvnw javadoc:jar` - confirm 0 warnings/errors
- Notify: `osascript -e 'display notification "<Message Body>" with title "<Message Title>"'`
