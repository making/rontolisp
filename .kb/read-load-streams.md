# `read`/`load`, `read-line`, file streams in all three backends

**The emitted reader has FRONTEND PARITY; anything outside it SIGNALS, never misreads.**
`buildReadExpr` (JVM) / its WASM twin dispatch on the frontend lexer's full set: `(`
(dotted pairs included), `'`, `"`, `)`, atoms (symbol / integer / bignum(JVM) / double /
ratio / nil / t, with a leading `+` consumed before a digit like the frontend), and the
`#` dispatch mirror -- `#'`, `#\` character literals (the frontend's 9-name table,
case-insensitive), `#(...)` and `#nA(...)` arrays, `#*` bit vectors (a general vector,
like the frontend), `#f(`/`#d(` packed float arrays (`--simd` builds the VBLOCK layout),
`#S(...)` structure literals, `#x`/`#o`/`#b` radix integers, and nesting `#|...|#` block
comments in the whitespace skipper. A token no dispatch claims falls through to the atom
path exactly like the frontend's `readSymbol` (`#foo` is the symbol `#FOO`, `#:g` is
`#:G`, `#16r1f` is a symbol -- `#nR` is not frontend syntax either). Three forms are
PERMANENT limits because they need an evaluator / the feature set at run time: `#.`,
`#+`/`#-` and `#n=`/`#n#` reader labels signal a catchable error (the interpreter's
runtime read still resolves the first two and reads labels -- the one documented
interpreter/compiled divergence; `#.` there EVALUATES via the marker resolver
`LispEvaluator` installs into `Environment.setReadTimeEvalResolver`, gated on
`*read-eval*` -- mechanics in `.kb/reader-features.md`). Reader errors are `RuntimeException`s on the JVM
(handler-case catches them as `simple-error` with the frontend's EXACT messages;
`LispEvaluator.foldStructLiteralsOf` converts the interpreter's `LispReadException` the
same way), and on WASM an `unreachable` trap -- or, in EH mode, a catchable
`$lisp-cond` throw whose message is STATIC (no name interpolation; see
`WasmReadRuntimeBuilder.emitErr`). `#S` mechanics: the JVM bakes an `Object[][]`
`_rdStructs` directory (every registered layout + per-slot initform actions, filled in
`<clinit>` via `JvmReadRuntimeBuilder.structTableClinit`, gated on `usesRead &&
mayUseInstances`); WASM appends a directory blob after the `WasmInstanceLayouts` records
(`buildReadCtx`). An omitted slot takes a `nil` initform, re-reads a baked
`EmittedReaderInitforms` constant text in place (cursor save/restore), or signals --
never a silently wrong value. The reader forces the JVM array machinery
(`usesFloatArray |= usesRead`) since a read datum can be any value kind. Pinned by
`Jvm/WasmLispCompilerTest#compileReadFromString{CharLiterals,RatiosAndRadix,VectorsAndArrays,StructLiterals,SymbolParityAndBlockComments,ReaderErrors*}`
and the ci-spec cases `runtime-read-*` (all four backends).

A runtime reader/parser is emitted into the compiled output (like `eval`). Interpreter uses `LispReader`/`Files`; JVM emits a recursive-descent reader (`JvmReadRuntimeBuilder`) with full JDK parity; WASM (`WasmReadRuntimeBuilder`) walks linear memory, interns symbols to shared string offsets; its integers are `i31` (ratio/radix tokens included) and it parses decimal floats (`emitTryFloat`, no exponent) into `TYPE_FLOAT`. All three readers parse dotted pairs `(a . b)`: `LispReader.readList` consumes a `Token.Dot`, and the runtime list parsers (`JvmReadRuntimeBuilder.buildReadList`, `WasmReadRuntimeBuilder.buildReadListBody`) treat a `.` as a dot token only when the following byte is a delimiter (whitespace, `(`, `)`, `'`, `"`, `;`) or end of input, so symbols/floats containing `.` are untouched. `with-open-file` is a plain macro (`LispMacroExpander.expandWithOpenFile`) over `open`/`close`, so no backend needed a new special form. A stream is an opaque integer handle, backend-local: interpreter/JVM index a stream table, WASM uses the WASI fd directly.

**CRLF parity**: `read-line` strips one trailing carriage return on every backend (interpreter/JVM inherit it from `BufferedReader.readLine`; the WASM `_read_line` does an explicit `pos--` when the byte before the newline is `0x0D`, added for the tcp built-ins -- CRLF-terminated socket lines such as HTTP must read as plain lines and a blank CRLF line must compare `string=` to `""`; see `.kb/tcp-sockets.md`). A lone `\r\n` line therefore reads as `""`, not `"\r"`, on all backends.

**Design decision**: `:direction` must be a literal `:input`/`:output` so both compilers resolve the mode at compile time (the shared `OpenModes.staticMode` in `am.ik.rontolisp.compiler`, used by `Jvm/WasmOpenCompiler`); consequently `open` has no `BuiltinFunctionWrappers` entry.

**A failed `open` SIGNALS on every backend** (since the local-time work, 2026-07-31). It used to trap on WASM -- `_open` emitted `unreachable` on a non-zero `path_open` errno, and a wasm trap is catchable in no mode -- which made `(handler-case (open ...) (error () ...))` a whole-program abort on the one backend that has no filesystem. Now `_open` ANSWERS `ref.null eq` on a non-zero errno and the null check plus the signal live at the call site (`WasmOpenCompiler`), where `%ERROR` already compiles to a catchable `$lisp-cond` throw in EH mode and to the same `unreachable` as before outside it. Two consequences worth knowing: a program that opens a missing file without a handler still dies, just with a Lisp error rather than a bare trap; and a program that CATCHES it is in EH mode by construction (`handler-case` forces it), so no new gate was needed. Under `--component` the same call used to trap one level lower and for a different reason -- `adapter.wat`'s `$ensure_preopen` read the first element of the `get-directories` list unconditionally, so with NO preopened directory (no `--dir`) it handed `open-at` descriptor handle 0 and wasmtime trapped with `unknown handle index 0`, before any errno existed to inspect. It now caches `-1` for "no preopen" and `$path_open` turns that into a plain errno. **This affected `probe-file` too**, which is the sharper way to see that it was a platform bug and not a local-time one: the "one primitive that never signals" trapped under a component with no `--dir`.

**`probe-file` is still a PRIMITIVE, not an `open` wrapped in `handler-case`** (all four backends; `uiop:file-exists-p` lowers onto it, see `.kb/asdf.md`). It predates the catchable-`open` change and stays a primitive: answering nil is cheaper than building and catching a condition, it works outside EH mode (where `%ERROR` is still a trap), and `--no-gc` rejects catching entirely. Contract: the pathname when the file exists (a rontolisp pathname IS its namestring, so no backend resolves symlinks or absolutizes -- the "truename" is the argument string itself), nil otherwise; a directory counts as existing; the path is interpreted exactly as `open` interprets it (working-directory-relative on interpreter/JVM, first preopened dir on WASM). Per backend: interpreter = a global function in `LispEvaluator` going through the installed **`SourceLoader.exists`**, never `Files` directly, so the browser playground's in-memory loader answers too -- `SourceLoader` grew that as a `default` method deriving the answer from `load` (the `AsdfSystems.locate` "attempt to read" pattern), which `fileSystem()` OVERRIDES with `Files.exists` because a read is both wasteful and WRONG for a file that exists but is not decodable text. JVM = `_probeFile` in `JvmIoRuntimeBuilder` (`new java.io.File(p).exists()`, quotes stripped like `_open`, returning the original quoted path value) + `JvmProbeFileCompiler`. WASM = `_probe_file` (`WasmIoRuntimeBuilder.buildProbeFileBody`, `FUNC_PROBE_FILE` appended after `FUNC_T_SYM` as the new `FX_FUNC_LAST` -- the mod/rem pattern, so no import index shifts and the component blobs are unaffected): `buildOpenBody`'s staging and read-mode `path_open`, with errno != 0 returning `ref.null eq` instead of trapping and a success closing the fd again via `fd_close` (a probe must leak no descriptor -- pinned by `probeFileLeaksNoDescriptor`, 300 probes then an `open`). Unlike `open` it has a `BuiltinFunctionWrappers` entry (`#'probe-file`): its single argument needs no compile-time literal. Pinned by `LispEvaluatorTest#probeFile*`, `JvmLispCompilerTest#probeFile*`, `WasmLispCompilerIntegrationTest#probeFile*`/`componentProbeFile` and the ci-spec case `probe-file-existing-and-missing`.

**WASM runtime intern table and heap base are computed, not fixed**: `_intern` appends 8-byte `(offset,len)` records for symbols first seen at runtime to a table whose base it loads from the `RT_INTERN_BASE_ADDR` (152) cell; the heap bump pointer lives at `HEAP_PTR_ADDR` (84). Both cells are seeded by active data segments at instantiation (never in `_start` -- hosts can call exports without running it) with values computed from the final static-data size in `WasmLispCompiler.compile`: `rtInternBase = max(RT_INTERN_MIN_BASE=8192, 16-aligned end of the string segment)`, `heapBase = rtInternBase + RT_INTERN_REGION_SIZE (8192)`, and the Preview 1 memory page count grows with `heapBase` (minimum 4 pages). The bases used to be the fixed constants 8192/16384; once a large program's interned-string segment (which also holds the eval function registry, appended last) outgrew 8192, runtime interning silently overwrote static strings and registry records -- symptoms ranged from `eval` returning nil for a defined function to garbled prints, and shifted with any layout change. First hit by the concatenated `CiSpecE2eTest` program; pinned by `WasmLispCompilerIntegrationTest#runtimeInternTableSurvivesLargeStaticData`.

**String streams (`with-output-to-string` / `with-input-from-string`) + print-family stream args**: the two macros are `LispMacroExpander` expansions over three `%`-internal builtins (`%make-string-output-stream`, `%make-string-input-stream`, `%string-stream-contents`; classified in `PackageRegistry.CL_INTERNALS`), following the with-open-file let/close shape (`expandWithOutputToString` fetches the contents, then closes; `expandWithInputFromString` mirrors `__wof_result` with `__wifs_result`). String streams live in the same handle space as file streams: interpreter = a `StringWriter` / `BufferedReader(StringReader)` entry in the `streams` table (so `read`/`read-line`/`write-line` work unchanged; `write-line`'s writer dispatch was widened from `BufferedWriter` to `Writer`); JVM = the same in the `_streams` table (`_makeStringOutputStream`/`_makeStringInputStream`/`_stringStreamContents` in `JvmIoRuntimeBuilder`); WASM = a **negative i31 handle** whose absolute value is a 12-byte record in linear memory (a WASI fd is never negative) -- output records head a `[off][len][next]` chunk list referencing the original string bytes (the bump allocator never moves them, so appends copy nothing; `_str_stream_contents` concatenates), input records hold a `[cursor][end]` range consumed by a branch at the top of `_read_line` (which makes `_read` work for free); `_write_line` grew a chunk-append branch, `_close` skips `fd_close` for negative handles (`WasmStringStreamRuntimeBuilder` + branches in `WasmIoRuntimeBuilder`/`WasmRuntimeBuilder.buildReadLineBody`). On top of this, `print`/`prin1`/`princ`/`terpri` accept an optional stream argument on all three backends (interpreter routes through a shared `emitTo`; JVM renders then calls the new `_writeStr(String, Object)` -- non-`Long` handles, i.e. nil/t, go to `System.out` and update the `_col` fresh-line tracking; WASM renders via `FUNC_PRINC_TO_STR`/`FUNC_PRIN1_TO_STR` then calls `_write_stream_str`, whose stdout path delegates to `_write_str` keeping `LINE_START_ADDR` tracking). `write-string` (function; write-line minus the newline, optional stream) and `write-to-string` (a prin1-to-string alias; both wrappers in `BuiltinFunctionWrappers`) round out the set, and `expandFormat` accepts a non-literal destination expression by building the string exactly like `format nil` and emitting one `(write-string <string> __format_stream)` (destination bound first, so it evaluates before the args). Compiled `print`-family return values keep the existing convention (nil on the compile backends, todo-063). `read` on a string stream inherits the line-oriented one-datum-per-line semantics of the stream `read` on every backend. Runtime `_eval` interpreters and `--no-gc` do not know string streams.

**The public string-output-stream names + the clear-on-read contract**:
`make-string-output-stream` and `get-output-stream-string` are the CL spellings of
`%make-string-output-stream` / `%string-stream-contents`
(`LispMacroExpander.expandMakeStringOutputStream` / `expandGetOutputStreamString`, dispatched
in `Jvm/WasmExprCompiler`; the interpreter registers both as real `LispFunction`s so `#'` and
native-image mode work). `%string-stream-contents` itself CLEARS the stream as it answers --
CL's contract, so a second call sees only what was written after the first -- rather than a
non-clearing peek plus a separate clearing alias: `with-output-to-string` fetches ONCE and then
closes, so it cannot tell the difference, and one primitive with one semantics is auditable
where a "which of the two did this call site want" pair is not. Per backend the clear is
`StringWriter.getBuffer().setLength(0)` (interpreter and, in bytecode, `_stringStreamContents`)
and `head = tail = 0` on the output record (`WasmStringStreamRuntimeBuilder.buildContentsBody`;
the chunk bytes stay where the bump allocator put them -- nothing references them once the
chain head is gone). Pinned by `stringOutputStreamNamesClearOnRead` in the JVM/WASM suites,
`evalStringOutputStreamNamesClearOnRead`, and the `postmodern-language-incidentals` ci-spec case.
`make-string-input-stream` is deliberately NOT exposed: CL's takes `&optional start end`, which
this machinery would silently ignore, and `with-input-from-string` covers every known consumer.

**`peek-char` (all four backends)**:
`(peek-char [peek-type [stream [eof-error-p [eof-value]]]])`. Only "the next character, left in
place" is a primitive (`%peek-char`, `PEEK_CHAR_INTERNAL`); the peek-type SKIPPING forms are one
shared `LispMacroExpander.expandPeekChar` lowering -- `t` skips whitespace, a character skips up
to that character, both leaving what they stopped on in the stream -- so no backend has its own
copy of the loop, and a non-literal peek-type keeps the same shape behind a runtime `(null ...)`
test (that is what makes `#'peek-char` work). The `characterp` guard in the loop's end test is
load-bearing: with a nil `eof-error-p` it is what ends the loop on the eof-value instead of
skipping forever. Interpreter/JVM peek by `mark(2)` + `read` + `reset()` on the `BufferedReader`
(a budget of 2 covers a surrogate pair). WASM has no reset: a string input stream decodes at its
record cursor WITHOUT advancing it (exact and unlimited), while a WASI fd goes through
`_read_char` and parks the code point in a ONE-SLOT pushback keyed on the fd
(`PEEK_FD_ADDR` = fd+1, 0 = drained; `PEEK_CP_ADDR` = the code point) that `_read_char` drains
first. `FUNC_PEEK_CHAR` is appended after `FUNC_FRESH_LINE_STREAM` as the new `FX_FUNC_LAST` --
the mod/rem pattern, so no import index shifts and the component blobs are unaffected.
**WASM-only limit, documented not fixed**: only `read-char` drains that pushback, so mixing
`peek-char` with `read-line`/`read` on the same FILE OR STDIN stream loses the peeked character
there (a string input stream has no such limit, and neither do the interpreter and the JVM).
Pinned by `peekCharLeavesTheCharacterInTheStream` / `peekCharSkipsWhitespaceAndUpToACharacter`
in all three suites plus the ci-spec case.

**`make-synonym-stream` over `*standard-output*` / `*standard-input*` forwards per
operation; over any other symbol it is resolved ONCE, at construction** (`expandMakeSynonymStream`, and the
`LispEvaluator` registration next to `symbol-value` so the interpreter's lookup is
dynamic-binding-aware). `(make-synonym-stream '*standard-output*)` and its `*standard-input*` twin answer the `nil`
DESIGNATOR, which every output (resp. input) operation resolves through the current
`*standard-output*` / `*standard-input*` at operation time
(`.kb/standard-output-redirect.md`) -- so those synonyms have CL's semantics with no new
stream kind. Any other symbol compiles to a READ of that variable (a runtime symbol
goes through `symbol-value`), i.e. a snapshot where CL would forward every operation.
**Reason for the divergence**: a per-operation synonym over an ARBITRARY symbol needs a
stream-designator KIND carrying that symbol which the write helpers of all four backends
resolve at run time; `nil` is the only designator the helpers already have, and it names
exactly the standard streams. No known consumer needs more (postmodern's `config.lisp:224`
`*json-output*` defvar constructs once and never rebinds). **RE-EVALUATION TRIGGER**: if a
consumer ever rebinds the symbol behind a NON-standard synonym stream, the runtime needs
that designator kind and the whole expansion has to go. One parity fix fell out of the
original pass: the interpreter's `write-line` takes nil/t as the standard-output designator
like the other three backends already did (its stdout test is "not a handle"). Pinned by
`makeSynonymStreamResolvesTheNamedVariable` / `synonymStreamOverStandardOutputFollowsALaterBinding`
(JVM + WASM), their `eval*` twins, and the ci-spec case.

**Binary streams (`:element-type '(unsigned-byte 8)`)**: `open` takes an optional third literal argument -- `'character` (default, text) or `'(unsigned-byte 8)` (binary) -- and `with-open-file` accepts a literal `:element-type` option that `expandWithOpenFile` rewrites into that positional form. The UNPARAMETERIZED spelling `'unsigned-byte` (= `(unsigned-byte *)`) is accepted as the same binary type: every CL opens such a stream as a byte stream, and rontolisp has exactly one byte width -- local-time's TZif reader spells it that way. The mode encoding is `0`=text-in, `1`=text-out, `2`=bin-in, `3`=bin-out (`OpenModes.OUTPUT_BIT`/`BINARY_BIT`). Interpreter: binary entries in the same `Map<Long, Closeable>` are `BufferedInputStream`/`BufferedOutputStream`; `read-byte`/`write-byte` are real `LispFunction`s (so `#'read-byte` works interpreted) with no `BuiltinFunctionWrappers` entries, matching `open`/`write-line`. JVM: `_open` grows a 4-way mode branch, `_closeStream` closes `InputStream`/`OutputStream` entries too, and new `_readByte(handle, eofErrorP, eofValue)`/`_writeByte(byte, handle)` helpers live in `JvmIoRuntimeBuilder`; a byte is a boxed `Long`. WASM: a WASI fd is element-type-agnostic, so `WasmOpenCompiler` masks the mode with `& OUTPUT_BIT` (passing raw 2/3 to `_open` would mis-select the write oflags/rights) and the `_open` body is untouched; `_read_byte`/`_write_byte` (`WasmIoRuntimeBuilder.buildReadByteBody`/`buildWriteByteBody`) move one raw byte through the `BYTE_SCRATCH_ADDR` (148) scratch cell via `fd_read`/`fd_write` -- no quote framing, no newline scan -- and a byte is an i31 fixnum. Their indices `FUNC_READ_BYTE`/`FUNC_WRITE_BYTE` are appended between `FUNC_P1_FUTURE_AWAIT` and `FUNC_USER_BASE` (the mod/rem/gensym pattern), so no import/`FUNC_START` index shifts and the `--component` adapter blobs are unaffected (the adapter's `fd_read`/`fd_write` are already byte-clean). Consequence of fds being untyped on WASM: `read-byte` on a text-opened stream "works" there while the interpreter/JVM signal a type error -- documented as out of contract. `read-byte`'s CL EOF semantics (`eof-error-p` default t = trap/throw, nil = return `eof-value`) are runtime arguments to the helpers. `read-sequence`/`write-sequence` are shared macro expansions (`LispMacroExpander.expandReadSequence`/`expandWriteSequence`) into a `while` loop over `aref`/`%aset`/`length` with fixed `__rseq_`/`__wseq_` temp names and literal-only `:start`/`:end` keywords, so no per-backend codegen exists; the sequence must be a rank-1 array. **The BUFFER, not the stream, picks the element read/written**: both dispatch on `(stringp seq)`, so a character vector -- what `(make-array n :element-type 'character)` and `make-string` build, and the one rank-1 array that answers `stringp` on every backend (`.kb/adjustable-arrays.md`) -- moves CHARACTERS (`read-char` / one `write-string` of the slice) and anything else moves bytes. The test is a RUNTIME one because the buffer arrives in a variable: `alexandria:read-stream-content-into-string` allocates it as `(make-array size :element-type (stream-element-type stream))`, which is also why `make-array`'s `:element-type` accepts a computed designator (`LispMacroExpander.lowerRuntimeElementTypeMakeArray`, wired into `Jvm/WasmArrayCompiler` -- the interpreter reads the designator at run time already). That character half is `.todo/219`; before it, a text stream read through a character buffer fell to `read-byte` and died on the stream cast. Like the other stream ops, none of this is known to the runtime `_eval` interpreters, and `--no-gc` has no stream support at all. The `CiSpecE2eTest` driver passes `--dir .` to both wasmtime invocations so file-stream ci-spec cases can open files in the shared work dir. WASM `open`/`load` resolve paths against the first preopened dir (fd 3), so they need `--dir` (in `--component` mode the same `path_open`/`fd_*` imports are satisfied by the adapter over `wasi:filesystem@0.3.0`). The runtime `_eval` interpreters do not know these forms (README). The runtime reader/`_eval` also do not know `require`/`provide` (compile-time directives consumed by `LoadInliner`; a file read by the runtime `load` of compiled output must not contain them — see load-inliner.md).

**Component stdin (stdin.lisp over wit-imported `wasi:cli/stdin@0.3.0`)**: on the `--component` path an ASYNC program that reads stdin (`read-line`/`read-char`/`read-byte` referenced + an async form referenced + not serve mode) gets `stdin.lisp` + `stdin-dispatch.lisp` spliced by `eval/StdinLibrary` (runs right after `SocketsLibrary` in the CLI; test helpers mirror it). The interface is bound FROM the fixed import block (`WasmComponentBuilder.FIXED_BLOCK_IFACES`, instance `INST_STDIN` -- the wait.lisp model; `validateFixedMembers` admits async type-alias built-ins, whose component-level stream/future types alias nothing out of the block instance, while drops/task-returns stay rejected), so the program's emitted WIT world is unchanged and no new `-S` flag exists. Mechanics = the preview1 adapter's stdin cache in Lisp: ONE `read-via-stream` stream cached in a defvar (its result future dropped immediately; EOF is the stream status), a chunk buffer + cursor + eof defvars, `%stdin-read-line-f`/`-read-char-f` async-defuns so the compiler rewrite's promotion (`WasmSocketsRewrite`, gated on the spliced `%io-read-line`) makes a pending stdin read SUSPEND the task -- a concurrent `wait-for` timer fires while the read waits (`componentAsyncStdinReadDoesNotStallTheInstance`). The `%stdin-*-or-raw-f` helpers dispatch nil designator -> stdin, else the `%...-raw` native built-ins; EOF parity: `read-line` -> nil, the 0/1-arg `read-char` signals "read-char: end of file" (the interpreter's message), `read-byte` on nil errors (a stream argument is mandatory everywhere). **A NON-async stdin program is deliberately NOT migrated**: it keeps the preview1 adapter's `fd_read` stdin branch, so its component is byte-identical and still runs without `-W exceptions=y` (`componentNonAsyncStdinKeepsTheAdapterPathAndItsFlags`); an async program already needed that flag, so migration changes no flags either. When sockets.lisp is spliced, `StdinLibrary` supplies only the or-raw helpers' backing (real `stdin.lisp`, or `stdin-stub.lisp` raw passthroughs under serve -- the wasi:http service world has no stdin and the bridge's `fd_read` is EOF by construction) and sockets.lisp's own dispatchers keep the `%io-*` names. Known limits, documented not fixed: reads buffer one host chunk (the sockets.lisp divergence from byte-at-a-time), and a migrated program that ALSO consumes stdin through forms the rewrite leaves native (`read`, the 2/3-arg eof-parameter `read-char`/`read-byte` forms) would hold TWO host stdin streams (the adapter's cache + stdin.lisp's) with implementation-specific interleaving -- don't mix them on stdin in one async program.

**`read-char` (one character, all three backends)**: `(read-char [stream [eof-error-p [eof-value]]])` over the same handle space as `read-line` (default stream nil = standard input; file streams and string input streams). EOF semantics mirror `read-byte` (`eof-error-p` default t = throw/trap, nil = return `eof-value`). Interpreter: an `Environment` registration reading one UTF-16 code unit from the `stdinReader` or a `BufferedReader` table entry. JVM: `_readChar(handle, eofErrorP, eofValue)` in `JvmIoRuntimeBuilder` (lazily initializes the shared `_stdinReader` field on a null handle; `BufferedReader.read()`, boxed `Character`), called by `JvmReadCharCompiler`. WASM: `_read_char` (`WasmIoRuntimeBuilder.buildReadCharBody`, `FUNC_READ_CHAR` appended after `FUNC_FBOUNDP` before `FUNC_USER_BASE` — the mod/rem pattern, so no import index shifts and the component blobs are unaffected) reads ONE BYTE — WASM strings are byte-indexed like `char`/`schar`, so a character read is a byte read — from fd 0 / a WASI fd via the `BYTE_SCRATCH_ADDR` cell / a negative string-stream handle's `[cursor,end)` range (advancing the cursor), boxed as a `TYPE_CHAR` struct. 0-arg `#'read-char` wrapper like `#'read-line`. The compilers evaluate multi-argument call forms right-to-left (.todo/014), so sequence consecutive `(read-char s)` calls through `let*`, not `(list ...)`. Needed by cl-utilities' `read-delimited`.

**The stream table is CONCURRENT on the interpreter and the JVM -- one allocator, and it
is atomic** (.todo/193): `http-handler`/`serve` put one virtual thread per request on both
backends (`.kb/mutexes.md`), so several requests allocate handles at the same instant, and
the table is process-wide, not per-request. The old shape -- reserve `count`, then store
the entry -- handed two concurrent requests the SAME handle: one stream was dropped
(leaked, never closed) and both Lisp handles denoted the survivor, so the two
conversations interleaved on one connection. Against PostgreSQL that read as random
connection loss inside the trust-auth handshake ("read-byte: end of file",
"Unexpected message received: 0" -- 10 of 12 connects surviving a burst), which looked
like server-side churn and is not. Per backend:
- **Interpreter** (`Environment.createGlobal`): the table is a `ConcurrentHashMap` and the
  counter an `AtomicLong`. Never put a `null` value in it -- the map forbids it (every
  entry is a live `Closeable`; `close` REMOVES instead of nulling).
- **JVM**: every producer -- `_open`, `_makeStringOutputStream`, `_makeStringInputStream`
  and every socket constructor in `JvmSocketRuntimeBuilder` -- appends through the ONE
  allocator `_addStream(Object) -> Long` (`JvmIoRuntimeBuilder`, emitted
  `ACC_SYNCHRONIZED`), so a new stream-producing built-in MUST call it rather than grow
  its own reserve/store pair. `_closeStream` is synchronized with it (its null-out must
  not land in an array a concurrent growth is replacing) and `_streams` is `ACC_VOLATILE`:
  `_addStream` writes the field back on EVERY call, and that store is what publishes the
  new element to the reader threads. The producers build their stream BEFORE calling the
  allocator, so the blocking part (a connect, a file open) stays outside the lock.
- **WASM**: both backends are single-threaded by construction (the component's
  `rontolisp::*sock-table*` included), so nothing to do -- the same reason mutexes are a
  no-op there.

The wider rule this is one instance of (what else a served request may reach, and the
other two bugs in the family) is `.kb/concurrent-served-requests.md`.

Pinned by `HttpHandlerTest#concurrentRequestsGetTheirOwnSocketHandle` and
`HttpHandlerJvmTest#concurrentRequestsGetTheirOwnSocketHandle` (shared fixture
`StreamHandleConcurrencySupport`: 24 simultaneous requests x 3 rounds, each opening its
own socket to an echo server; the assertion is both "no handle handed out twice" and "the
echo that came back is my own").

**`file-length` is REAL on the interpreter and the JVM, and `nil` everywhere else -- which
is in contract, not a stub** (todo-225, the clack milestone). A `Reader`/`Writer` does not
remember the path it came from, so both backends keep a side table alongside the stream
table and `file-length` stats what it finds there: interpreter = a `Map<Long, String>
streamPaths` in `Environment` filled by `open` and cleared by `close`; JVM = an
`Object[] _streamPaths` field indexed by handle exactly like `_streams`, written by
`_setStreamPath` (which `_open` wraps its `_addStream` call in) and nulled by
`_closeStream`. `_fileLength` runs the handle through `_forceOutput` first, so an output
stream's answer counts what was WRITTEN rather than what happened to reach the disk (the
interpreter flushes a `Flushable` entry for the same reason). Only `open` fills the table,
so every other stream kind -- string streams, sockets, the standard streams, a handle
already closed -- answers nil. **Both WASM backends always answer nil, file streams
included**, and so does `file-write-date`: CL says both answer nil when the value "cannot
be determined", and no WASI `filestat` call is imported here. **Reason for the
divergence**: the fix is a tenth preview1 import (`fd_filestat_get` covers both -- length
from a live fd, mtime via the `probe-file` open/stat/close shape), which shifts every
defined function index and needs `adapter.wat` + `adapter-http-server-p1.wat` + the
`--no-wasi` trap stub in step, the way `fd_readdir` did (`.kb/directory-listing.md`).
**Re-evaluation trigger**: if any consumer needs a real length or timestamp on WASM, that
import is the change to make -- nothing else is missing. The JVM side is GATED per
operator (`JvmIoRuntimeBuilder.FileMeta`), so a program that asks for neither keeps its
exact bytes; `#'file-length` / `#'file-write-date` are in `REFERENCE_GATED_FUNCTIONS`
because their wrapper bodies call those helpers and the gate scans the SOURCE program, not
the injected wrappers. Pinned by `LispEvaluatorTest#evalFileWriteDateAndFileLength`,
`JvmLispCompilerTest#compileAndRunFileWriteDateAndFileLength` and
`WasmLispCompilerIntegrationTest#fileMetadataAnswersNilAndDirectoryCreationSignals`.

**`ensure-directories-exist` is Lisp source over ONE creating primitive** (`%make-directories`,
the write-side sibling of `%list-directory`): `LispPreludeLibrary` holds the defun, so the
"the directory component is everything up to and including the last slash" rule has one
definition for every backend, and only the primitive is per-backend (interpreter/JVM
`Files.createDirectories` / `File.mkdirs`; both WASM backends a call-time
`LispMacroExpander.makeDirectoriesStub()` error). It signals on WASM where `file-length`
answers nil because its contract has no "cannot be determined" answer -- the directory
either exists afterwards or it does not. Lite: CL's second value (`created`) is not
returned, since a prelude defun's secondary value does not cross the function boundary on
the compile paths (`.todo/212`).
