# `read`/`load`, `read-line`, file streams in all three backends

**The emitted reader has FRONTEND PARITY; anything outside it SIGNALS, never misreads.**
`buildReadExpr` (JVM) / its WASM twin dispatch on the frontend lexer's full set: `(`
(dotted pairs included), `'`, `"`, `)`, atoms (symbol / integer / bignum(JVM) / double /
ratio / nil / t, with a leading `+` consumed before a digit like the frontend), and the
`#` dispatch mirror -- `#'`, `#\` character literals (the frontend's 9-name table,
case-insensitive), `#(...)` and `#nA(...)` arrays, `#*` bit vectors (a general vector,
like the frontend), `#f(`/`#d(` packed float arrays (`--simd` builds the VBLOCK layout),
`#S(...)` structure literals, `#P"..."` pathname literals (the instance over the fixed
PATHNAME layout, todo-304 -- with the instance gate off the arm signals, since no
pathname value can exist in that artifact), `#x`/`#o`/`#b` radix integers, and nesting
`#|...|#` block comments in the whitespace skipper. A token no dispatch claims falls through to the atom
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

**The `%probe-file` PRIMITIVE stays string-in/string-out, not an `open` wrapped in `handler-case`** (all four backends; the public `probe-file` is prelude Lisp over it since todo-304, coercing a pathname argument and wrapping the answer in the pathname VALUE -- `.kb/pathnames.md`; `uiop:file-exists-p` lowers onto `probe-file`, see `.kb/asdf.md`). It predates the catchable-`open` change and stays a primitive: answering nil is cheaper than building and catching a condition, it works outside EH mode (where `%ERROR` is still a trap), and `--no-gc` rejects catching entirely. Contract of the primitive: the namestring when the file exists (no backend resolves symlinks or absolutizes -- the "truename" carries the argument namestring), nil otherwise; a directory counts as existing; the path is interpreted exactly as `open` interprets it (working-directory-relative on interpreter/JVM, resolved against the preopen table on WASM). Per backend: interpreter = a global function in `LispEvaluator` going through the installed **`SourceLoader.exists`**, never `Files` directly, so the browser playground's in-memory loader answers too -- `SourceLoader` grew that as a `default` method deriving the answer from `load` (the `AsdfSystems.locate` "attempt to read" pattern), which `fileSystem()` OVERRIDES with `Files.exists` because a read is both wasteful and WRONG for a file that exists but is not decodable text. JVM = `_probeFile` in `JvmIoRuntimeBuilder` (`new java.io.File(p).exists()`, quotes stripped like `_open`, returning the original quoted path value) + `JvmProbeFileCompiler`. WASM = `_probe_file` (`WasmIoRuntimeBuilder.buildProbeFileBody`, `FUNC_PROBE_FILE` appended after `FUNC_T_SYM` as the new `FX_FUNC_LAST` -- the mod/rem pattern, so no import index shifts and the component blobs are unaffected): `buildOpenBody`'s staging and read-mode `path_open`, with errno != 0 returning `ref.null eq` instead of trapping and a success closing the fd again via `fd_close` (a probe must leak no descriptor -- pinned by `probeFileLeaksNoDescriptor`, 300 probes then an `open`). `#'probe-file` resolves to the prelude defun (the old `BuiltinFunctionWrappers` entry is gone). Pinned by `LispEvaluatorTest#probeFile*`, `JvmLispCompilerTest#probeFile*`, `WasmLispCompilerIntegrationTest#probeFile*`/`componentProbeFile` and the ci-spec case `probe-file-existing-and-missing`.

**A path is resolved against the PREOPEN TABLE on both WASM backends, not against fd 3**
(todo-432, 2026-08-17). One shared front end, `_path_dirfd`
(`WasmIoRuntimeBuilder.buildPathDirFdBody`, called through `emitDirFdAndPath`), answers
the descriptor a staged path must be opened relative to and leaves the number of leading
bytes that descriptor already accounts for in the `PATH_SKIP_ADDR` (248) cell; every
`path_open` call on this backend goes through it -- `_open`, `_probe_file`,
`_list_directory` and `_load` -- so the rule has ONE definition rather than four.

- A **relative** path answers fd 3 with skip 0: the first preopened directory, exactly
  what the four sites hard-coded before, so nothing that worked moves.
- An **absolute** path (a leading `/`) is matched against the preopen NAMES, walked with
  the two new preview1 imports `fd_prestat_get` (name length; its EBADF at the first
  non-preopened fd is what ENDS the walk) and `fd_prestat_dir_name` (the name). The match
  is a path-COMPONENT prefix and the LONGEST wins: with `--dir /` and `--dir /tmp` both
  preopened, `/tmp/x` resolves against `/tmp`; `/tmpfoo` matches neither, because the byte
  after the prefix must be a separator. A trailing slash on a preopen name is stripped
  first, and a preopen whose own name is relative (`--dir .` spells it `.`) can cover no
  absolute path.
- The path naming the preopened directory ITSELF would leave an EMPTY remainder, which
  WASI takes no more than a host does; it becomes `"."`, written over the staged path's
  last byte (the staging is scratch the caller pops right after).
- **No preopen covers it** -> fd 3 with skip 0, i.e. the call the site would have made
  anyway, so the failure is the ordinary "cannot open" ERRNO each caller already turns
  into nil. An errno, never a trap -- the `.kb/wasi-component.md` policy.

**A LITERAL absolute path is not a test of any of this.** `(with-open-file (s
"/tmp/x.txt") ...)` appears to work on WASM with no `--dir` at all, because
`CompileTimePathnameFolder` bundles the file's COMPILE-TIME contents into the artifact as
a `with-input-from-string` (`.kb/asdf.md`) and nothing is opened -- editing the file after
compiling does not change the output. Every pin here therefore BUILDS the path at run
time (a special variable plus `concatenate`).

Before this, `_open` handed every path to fd 3 and never asked what that fd was CALLED, so
`/tmp/x.txt` went to `path_open` whole and WASI rejected it even when the host had mapped
exactly the directory meant. The sharp half was `probe-file`, which cannot signal by
contract: it answered nil for a file that exists. The shape is the common one --
`asdf:system-relative-pathname`, a runtime `uiop:merge-pathnames*`, `(merge-pathnames name
*load-truename*)`, a path out of a config file or an environment variable all produce an
absolute namestring -- and it was dead on both WASM backends.

Cost and mechanics: `IMPORT_FUNC_COUNT` went 9 -> 11 and `FUNC_START` with it, so every
emitted function index shifted (inherent to extending the import surface, the `fd_readdir`
precedent in `.kb/directory-listing.md`). Neither import needed a new type entry -- `fd_prestat_get` reuses
`TYPE_INTERN` `(i32,i32)->i32` and `fd_prestat_dir_name` `TYPE_RD_MEMEQ`
`(i32,i32,i32)->i32`. Three things stay in step: `--no-wasi` defines two more trap stubs
(both EBADF, which ends the walk at the first fd, so a reactor answers "nothing covers
it"); `adapter.wat` exports both over `wasi:filesystem@0.3.0`; and
`adapter-http-server-p1.wat` exports them as EBADF (the serve world has no filesystem).

**The `--component` adapter had to widen twice.** `$ensure_preopen` cached ONE descriptor
and `$path_open` IGNORED `dirfd`, so even a correct core-side resolution would have opened
against the first preopen. It now caches the whole table -- `$ensure_preopens`, 16 slots of
`{descriptor, name-len, name}` at `0x50500` -- and `dirfd` MEANS `3 + preopen index`. The
table is a COPY on purpose: `get-directories` and its name strings are lifted through
`cabi_realloc`, which allocates at the CORE's `HEAP_PTR`, and the core pops that cell back
after every resolution, so anything still pointing into the lifted list would be handed out
again as heap. A preopen name longer than 256 bytes is recorded with length 0 rather than
truncated -- a truncated name would compare equal to a prefix that is not the directory it
names, and a preopen the core cannot see is merely unreachable.

**What this does NOT reach**: `file-write-date` and `file-length` still answer nil on both
WASM backends and `%delete-file` / `%rename-file` / `%make-directories` still signal --
they resolve no path at all, because the imports they need (`fd_filestat_get`,
`path_unlink_file`, `path_rename`, `path_create_directory`) do not exist here. That is
`.todo/257`, unchanged by this work. Pinned by
`WasmLispCompilerIntegrationTest#absoluteRuntimePathResolvesAgainstThePreopenThatCoversIt`
and its `component` twin (both staging the file one level BELOW the preopened directory, so
the prefix match is what is under test, and both keeping a `-sibling` tree that a match
without the component-boundary rule would find) plus the ci-spec case
`runtime-absolute-path-open-probe-and-load`.

**The load-context variables `*load-pathname*` / `*load-truename*` hold the file being loaded on EVERY backend** (todo-375): the interpreter's `loadFile` binds them dynamically around each file it reads (a COMPONENT by its resolved path, a plain `load` by the spelling it was called with -- and `truename` here means the same "resolved against the loading file's directory" as the probe paragraph above, since nothing absolutizes), and the compile paths ASSIGN them per SPLICED file from `LoadInliner`'s `%begin-file` brackets, so the value agrees byte for byte across the four and equals `asdf:component-pathname` for a component. **The context is established at READ time as well** (todo-428): the interpreter's `loadFile` binds the pair BEFORE its marker read, and on the compile paths `UserMacroExpander` pushes the same two strings around the spliced file's forms -- without that, a `#.` datum reading `*load-truename*` answered nil there, because the bracket lowers to `setq` statements that run long after the datum resolves. Mechanics, the byte-identity gate and the tests: `.kb/load-inliner.md`; the variable family (including the permanently-nil compile-file pair -- nil at read time too, deliberately -- and `*readtable*`) is `.kb/asdf.md`.

**WASM runtime intern table and heap base are computed, not fixed**: `_intern` appends 8-byte `(offset,len)` records for symbols first seen at runtime to a table whose base it loads from the `RT_INTERN_BASE_ADDR` (152) cell; the heap bump pointer lives at `HEAP_PTR_ADDR` (84). Both cells are seeded by active data segments at instantiation (never in `_start` -- hosts can call exports without running it) with values computed from the final static-data size in `WasmLispCompiler.compile`: `rtInternBase = max(RT_INTERN_MIN_BASE=8192, 16-aligned end of the string segment)`, `heapBase = rtInternBase + RT_INTERN_REGION_SIZE (8192)`, and the Preview 1 memory page count grows with `heapBase` (minimum 4 pages). The bases used to be the fixed constants 8192/16384; once a large program's interned-string segment (which also holds the eval function registry, appended last) outgrew 8192, runtime interning silently overwrote static strings and registry records -- symptoms ranged from `eval` returning nil for a defined function to garbled prints, and shifted with any layout change. First hit by the concatenated `CiSpecE2eTest` program; pinned by `WasmLispCompilerIntegrationTest#runtimeInternTableSurvivesLargeStaticData`.

**String streams (`with-output-to-string` / `with-input-from-string`) + print-family stream args**: the two macros are `LispMacroExpander` expansions over three `%`-internal builtins (`%make-string-output-stream`, `%make-string-input-stream`, `%string-stream-contents`; classified in `PackageRegistry.CL_INTERNALS`), following the with-open-file let/close shape (`expandWithOutputToString` fetches the contents, then closes; `expandWithInputFromString` mirrors `__wof_result` with `__wifs_result`). String streams live in the same handle space as file streams: interpreter = a `StringWriter` / `BufferedReader(StringReader)` entry in the `streams` table (so `read`/`read-line`/`write-line` work unchanged; `write-line`'s writer dispatch was widened from `BufferedWriter` to `Writer`); JVM = the same in the `_streams` table (`_makeStringOutputStream`/`_makeStringInputStream`/`_stringStreamContents` in `JvmIoRuntimeBuilder`); WASM = a **negative i31 handle** whose absolute value is a 12-byte record in linear memory (a WASI fd is never negative) -- an output record is `[kind=1][slot][len]` over a per-stream `$str_bytes` GC byte buffer reached through a module-global table (see the paragraph below), input records hold a `[cursor][end]` range over a persistent linear copy of the source string, consumed by a branch at the top of `_read_line` (which makes `_read` work for free); `_write_line` grew an append branch, `_close` skips `fd_close` for negative handles and hands an output record's table slot back (`WasmStringStreamRuntimeBuilder` + branches in `WasmIoRuntimeBuilder`/`WasmRuntimeBuilder.buildReadLineBody`). On top of this, `print`/`prin1`/`princ`/`terpri` accept an optional stream argument on all three backends (interpreter routes through a shared `emitTo`; JVM renders then calls the new `_writeStr(String, Object)` -- non-`Long` handles, i.e. nil/t, go to `System.out` and update the `_col` fresh-line tracking; WASM renders via `FUNC_PRINC_TO_STR`/`FUNC_PRIN1_TO_STR` then calls `_write_stream_str`, whose stdout path delegates to `_write_str` keeping `LINE_START_ADDR` tracking). `write-string` (function; write-line minus the newline, optional stream) and `write-to-string` (a prin1-to-string alias; both wrappers in `BuiltinFunctionWrappers`) round out the set, and `expandFormat` accepts a non-literal destination expression by building the string exactly like `format nil` and emitting one `(write-string <string> __format_stream)` (destination bound first, so it evaluates before the args). Compiled `print`-family return values keep the existing convention (nil on the compile backends, todo-063). `read` on a string stream inherits the line-oriented one-datum-per-line semantics of the stream `read` on every backend. Runtime `_eval` interpreters and `--no-gc` do not know string streams.

**A string OUTPUT stream costs linear memory NOTHING per write (WASM)**: the record
`[kind=1][slot][len]` names, through the module-global buffer table, one `$str_bytes` GC
array holding what a string holds -- the frame quote `"` at index 0, the content at
`[1, 1+len)`. Every append (`_write_stream_str`, `_write_line`, `fresh-line`'s newline)
asks `_ostream_room(rec, n)` (`FUNC_OSTREAM_ROOM`, reusing the `(i32,i32)->(ref null eq)`
signature `TYPE_RAT_NEW`, appended after the last fixed helper) for room for `n` more
bytes and `array.copy`s into it; the buffer DOUBLES when it runs out, so writing k bytes
one at a time copies O(k) in total, and `_str_stream_contents` is one `array.copy` out
into the fresh `TYPE_STRING`. It used to be a `[off][len][next]` chunk list: because a
GC string has no stable linear address, each append COPIED its content into a persistent
linear buffer and linked a 12-byte chunk record at it -- **15 bytes of linear memory per
CHARACTER** on a `write-char` loop (the shape `%http-utf8-decode-octets` and
`%http-percent-decode` take), reclaimed only at the enclosing `__ronto_alloc_reset`, i.e.
a whole request on a reactor and never in a program without one. Three consequences the
layout is chosen for, and each is a trap if you move it:
- **The table is the GC ROOT** a linear-memory record cannot be. It is a
  `TYPE_HASH_BUCKETS` in the module's LAST global (after the cached `t` and the raw-local
  sentinel), created by the first `_make_str_ostream` and doubled from there, so a program
  that opens no string stream carries a null and nothing else.
- **`_close` recycles the slot, not the record.** A freed slot goes on a free list
  threaded through the table's own entries (a free slot holds the next as an i31; the head
  is the `OSTREAM_FREE_ADDR` cell, a slot index + 1 so zero is the empty list), which puts
  the list on the GC heap where the arena reset a host performs between calls cannot reach
  it. The record's 12 bytes are NOT recycled for exactly that reason -- a free list in the
  arena would hand out records the reset had already given back, and the two allocators
  would alias. So a stream still costs 12 bytes of arena, and only that.
- **A closed stream is closed**: `_close` sets kind 2 and slot -1, so a double close is a
  no-op and a write after one traps at the table read rather than landing in whichever
  stream inherited the slot. CL leaves operating on a closed stream undefined, and
  `with-output-to-string` fetches the contents BEFORE closing, so nothing portable notices.
Pinned by `WasmStringStreamArenaE2eTest` (node, a JS host reading `__ronto_alloc_mark`
across calls: 65536 characters written one `write-char` at a time and the same 65536
written as 64 `write-string`s of 1 KiB each cost the arena the same nothing -- they used
to differ by 15x) on top of the whole `withOutputToString*` / `stringOutputStream*` /
`freshLine*` family in `WasmLispCompilerIntegrationTest`.

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
and `len = 0` on the output record (`WasmStringStreamRuntimeBuilder.buildContentsBody`; the
BUFFER stays -- the stream is live and the writes after the clear reuse it). Pinned by `stringOutputStreamNamesClearOnRead` in the JVM/WASM suites,
`evalStringOutputStreamNamesClearOnRead`, and the `postmodern-language-incidentals` ci-spec case.
`make-string-input-stream` is the CL spelling of `%make-string-input-stream`, wired the same way
(`LispMacroExpander.expandMakeStringInputStream`, dispatched in `Jvm/WasmExprCompiler`; a real
`LispFunction` in the interpreter). It USED to be withheld, on two grounds that both expired:
"CL's takes `&optional start end`, which this machinery would silently ignore" -- the expansion
now routes a bounded call through `(subseq string start end)`, so the code-point bounds rule is
subseq's on every backend instead of a second implementation of it, and the interpreter's
`LispFunction` applies the same rule directly -- and "`with-input-from-string` covers every known
consumer", which stopped being true the moment a real library needed the stream to OUTLIVE the
form that made it: yason's `parse` takes a string by making one, so `lack/request` answered
`400 Bad Request` to every JSON request body, which is every `ningle` application (a route's
controller reads `request-parameters`, and that parses the body). Pinned by
`stringInputStreamReadsWithoutWithInputFromString` + `stringInputStreamHonoursStartAndEnd` in the
JVM/WASM suites, `evalStringInputStream*` in the interpreter suite, and end to end by
`examples/cloudflare-workers/httpbin-ningle` (a JSON `POST` echoed back as `form`).
**The re-evaluation trigger for the next visitor**: nothing about the shape of this argues for a
`%`-internal-only name -- if another CL stream constructor is being withheld for "the internal
one covers every consumer", check whether a library now needs it as a VALUE rather than in a
scoped macro, because that is the shape the argument does not survive.

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

**A synonym stream is a distinct VALUE that forwards EVERY operation to the current value
of the symbol it names -- for any symbol** (todo-377, 2026-08-15; before it, a synonym over
`*standard-output*`/`*standard-input*` was the `nil` designator and any other symbol was
resolved ONCE, at construction). The value is an instance of the fixed
`LispLayout.SYNONYM_STREAM` layout (tag `%SYNONYM-STREAM`, seeded into
`ClosRegistry.layoutsByTag` as a LAYOUT ONLY, the pathname precedent of
`.kb/pathnames.md`): ONE declared slot holding the symbol, plus ONE RESERVED cell
(`capacity` 2) holding the per-operation READER.

**The reader is a zero-argument closure over a read of that variable, and that is the whole
mechanism.** `(make-synonym-stream '*out*)` expands to
`(%obj-new '%SYNONYM-STREAM '*out* (lambda () *out*))`
(`LispMacroExpander.expandMakeSynonymStream`; a COMPUTED symbol falls back to
`(lambda () (symbol-value sym))` over a let-bound temporary). Calling the closure answers
the variable's value as of that call -- the innermost dynamic binding on the interpreter,
the dynamic-first read a `let` of a special compiles to on the compile paths. So nothing
needs the symbol's NAME at run time, which is why `symbol-value` (global-only on the
compile paths, and a force of the whole eval runtime, `.kb/symbol-runtime-api.md`) is not
in the lowering. The reader is deliberately OUTSIDE `slotNames`, so it reaches neither the
printers -- `#<SYNONYM-STREAM :SYMBOL *STANDARD-OUTPUT*>`, identical on all four backends,
while a closure prints `#<lambda>` on the interpreter and `#<function>` on the other three
-- nor `equal`.

**The resolution is one shared prelude defun, `%SYNONYM-TARGET`** (`LispPreludeLibrary`):
"a synonym stream answers `(funcall (%obj-ref s 1))`, recursively; anything else answers
itself", so a synonym over a synonym resolves and a cycle is the only thing that cannot.
Reached from five places:

- **Both compile-path seams**: `StreamDesignators.throughSynonym` wraps the designator
  expression `JvmStringStreamCompiler.streamArg`/`inputStreamArg` and `WasmEmitHelper`'s
  twins already produce -- so every print/read/byte/sequence operator inherits it from the
  ONE gate per backend. A literal that cannot BE a synonym (an omitted argument, `t`, a
  handle) is left untouched.
- **The interpreter**: `Environment.synonymTarget` (the same walk in Java, over the native
  `LispFunction` reader the interpreter's `make-synonym-stream` builds), applied by
  `resolveOutputDest`/`resolveInputSrc` and -- BEFORE the `instanceof LispInstance` test --
  by every Gray-dispatching built-in wrap in `LispEvaluator` (`resolveSynonymArg`).
- **`gray.lisp`'s `%gray-*-dispatch` helpers**, which resolve their stream FIRST: a synonym
  stream is an instance too, so without that they would take the CLOS arm and die on "no
  applicable method", and the target -- which may itself be a Gray instance -- would never
  be reached. That is rove's exact composition (an indent stream wrapping
  `(make-synonym-stream '*standard-output*)`), and it works in both directions.
- **`streamp` / `input-stream-p` / `output-stream-p` / `close`**: the value answers `t` for
  the three predicates, and `close` closes the SYNONYM -- nothing to do -- and answers `t`.
  The `close` guard has to exist TWICE on the wasm side: at the `close` case and at the
  `%CLOSE-RAW` alias the `--component` socket rewrite falls through to, or a component
  hands the synonym to the handle-typed close and traps.
- **The `--component` spliced dispatchers**, which resolve the designator themselves
  (`sockets.lisp` / `stdin-dispatch.lisp` / `stdin.lisp`): their `(or s *standard-input*)`
  binding is now `(%synonym-target (or s *standard-input*))`. That rewrite REPLACES the
  read built-ins, so the compiler's own seam never sees those call sites and a synonym
  would read as "not a handle" and go to the host stdin cache. Same reason the explicit-nil
  designator is resolved there (`.kb/standard-output-redirect.md`).

**Everything a program-level operator does is gated on `make-synonym-stream` appearing in
the source** (`Ctx.usesSynonymStreams`, and `constructsInstance` for the instance gate): it is the only
constructor and there is no read syntax for one, so a program that never spells it compiles
byte-identically to before -- no `%SYNONYM-TARGET` call at any stream site, no synonym arm
in `streamp`, no guard on `close`. **Two LIBRARY splices pay unconditionally**, and that is
the deliberate price: gray.lisp's dispatch helpers and the `--component` I/O dispatchers
carry the hop whether or not their program can build a synonym stream, so any program using
the Gray protocol or the component socket/stdin splice carries `%SYNONYM-TARGET` (one small
defun) plus one call per dispatch. Making those two conditional would mean editing a
spliced body per program -- a bypass branch in exchange for a couple of hundred bytes --
and the shared resolution is worth more than that. It is also why
`LispPreludeLibrary.referencedBySurfaceForm` splices `%SYNONYM-TARGET` for a program that
merely uses the GRAY protocol (the same predicate roots it in `LibraryDefunPruner`): both
compile-path seams insert the call inside the expression compilers, and
`GrayStreamsLibrary.process` runs AFTER the prelude selection, so the reference the
selection would look for does not exist yet. **A pipeline that splices gray.lisp must run
`LispPreludeLibrary.process` too** (the CLI, the playground and the E2E supports all do;
the backend test harnesses grew a `compileAndRunGray` for it). The component splices have
no such problem -- they run BEFORE the prelude selection, which therefore sees their
reference directly.

Pinned by `makeSynonymStreamResolvesTheNamedVariable` / `makeSynonymStreamIsAStreamValue` /
`synonymStreamOverStandardOutputFollowsALaterBinding` /
`makeSynonymStreamOverStandardInputFollowsALaterBinding` /
`synonymStreamOverAUserSpecialFollowsALaterBinding` (JVM + WASM), their `eval*` twins in
`LispEvaluatorTest`, and the `synonym-stream-value` ci-spec case.

**Binary stdin/stdout is the standard-stream DESIGNATOR, not a new stream kind** (todo-314,
2026-08-10). `read-byte`/`write-byte` take the same designators every other stream operation
takes -- `t` is the process standard stream, an explicit `nil` resolves through
`*standard-input*` / `*standard-output*` (which hold `t` unless the program binds them) via the
shared `compiler.StreamDesignators` rewrite at the call site, and handle 2 is stderr. That is
what makes `(read-byte *standard-input*)` -- CL's own spelling for a bivalent standard stream --
work with NO handle value handed to those variables, which was the design question the todo left
open: the two candidate spellings turned out to be the same one. `read-sequence`/`write-sequence`
inherit it for free (they lower onto the byte ops), so a byte-oriented filter is
`(read-sequence buf *standard-input*)` + `(write-sequence out *standard-output*)` on every
backend -- `size-report/programs/zlib/zlib.lisp` is exactly that. Per backend:
- **Runtime dispatch is "is it a handle", never "is it nil"**, because `t` is a value: WASM tests
  `ref.test (ref i31)` (a `ref.cast` on the `t` struct would trap) and falls back to fd 0 / fd 1,
  the same shape `_read_char` already had; the JVM tests `instanceof Long` and falls back to
  `System.in` / `System.out`, plus the reserved handles (0 = stdin, 1 = stdout, 2 = stderr through
  the existing `emitStderrBranch` gate) so the handle space means the same thing on all four
  backends; the interpreter routes through `resolveInputSrc` / `resolveOutputDest` to the raw
  process streams.
- **The JVM reads `System.in` directly, not the `_stdinReader` the character reads share** (and
  the interpreter likewise reads its `in`): mixing byte and character reads on one stream is out
  of contract everywhere, and a shared `BufferedReader` would swallow bytes the next `read-byte`
  owes the caller.
- **The JVM needs an explicit flush; the other three do not.** `System.out` auto-flushes on a
  newline and on every `byte[]` write -- which is why the print family never needed one, its
  characters go out through the writer's `byte[]` path -- but a single-byte `write(int)` only
  flushes on `'\n'`, and a filter's output need not end in one. `JvmLispCompiler` emits
  `System.out.flush()` before `main`'s `RETURN`, gated on the source naming `write-byte` or
  `write-sequence`; **any new path to `_writeByte`'s standard-output branch must join that gate**
  or its output truncates silently. The interpreter's twin is the `out.flush()` at the end of
  `RontoLispCli.interpret`; both wasm backends call `fd_write` per byte and have nothing to drain.
- **A raw octet moves the standard-output COLUMN like a character does**, on all four backends:
  `_write_byte` sets `LINE_START_ADDR` when the descriptor is fd 1, `_writeByte` sets `_col` to
  `b ^ 10` in its stdout branch (the field is only ever tested against zero), and the interpreter
  sets `atLineStart`. Without it `(write-byte 10 t)` followed by `fresh-line` emitted a second
  newline on three backends and none on the interpreter. Pinned by the `binary-standard-output`
  ci-spec case, which asserts exactly that.
- **`--component`**: nothing new. A NON-async component reads fd 0 / writes fd 1 through the
  preview1 adapter (`componentBinaryStandardStreamsAreByteTransparent`). In an ASYNC one
  `stdin.lisp`'s `%stdin-read-byte-or-raw-f` is now a raw passthrough -- it used to signal
  "read-byte expects an input stream" for a nil designator, on the reasoning that read-byte HAD no
  stdin designator, which this change retires -- so the octets come from the adapter's stdin
  rather than from stdin.lisp's chunk buffer. That was decided when the buffer was a STRING whose
  cursor walked decoded characters; since todo-370 the `stream<u8>` chunk lifts as an octet vector
  and stdin.lisp's cursor walks BYTES (`%stdin-read-byte-f`, with `%stdin-read-char-f` assembling
  a character from them the way sockets.lisp does), so the passthrough is no longer forced by the
  buffer -- it stays because nothing has asked for a suspending byte read. The consequence is the
  documented mixing limit, unchanged: an async program reading stdin BOTH as bytes and as
  lines/characters holds two host stdin streams with implementation-specific interleaving.
  **Re-evaluation trigger**: if a program needs a pending BYTE read to suspend the task (the
  reason the line/char reads went through stdin.lisp at all), route `%stdin-read-byte-or-raw-f`'s
  nil designator to `%stdin-read-byte-f` -- the byte-accurate buffer it needs exists now -- and
  the mixing limit goes with it.
Pinned by `LispEvaluatorTest#evalBinaryStandardStream{sAreByteTransparent,Designators}`, their
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` twins (the wasm ones render stdout
through `od` inside the runner, since `ExecResult` decodes it as text), and the ci-spec case.

**Binary streams (`:element-type '(unsigned-byte 8)`)**: `open` takes an optional third literal argument -- `'character` (default, text) or `'(unsigned-byte 8)` (binary) -- and `with-open-file` accepts a literal `:element-type` option that `expandWithOpenFile` rewrites into that positional form. The UNPARAMETERIZED spelling `'unsigned-byte` (= `(unsigned-byte *)`) is accepted as the same binary type: every CL opens such a stream as a byte stream, and rontolisp has exactly one byte width -- local-time's TZif reader spells it that way. The mode encoding is `0`=text-in, `1`=text-out, `2`=bin-in, `3`=bin-out (`OpenModes.OUTPUT_BIT`/`BINARY_BIT`). Interpreter: binary entries in the same `Map<Long, Closeable>` are `BufferedInputStream`/`BufferedOutputStream`; `read-byte`/`write-byte` are real `LispFunction`s (so `#'read-byte` works interpreted) with no `BuiltinFunctionWrappers` entries, matching `open`/`write-line`. JVM: `_open` grows a 4-way mode branch, `_closeStream` closes `InputStream`/`OutputStream` entries too, and new `_readByte(handle, eofErrorP, eofValue)`/`_writeByte(byte, handle)` helpers live in `JvmIoRuntimeBuilder`; a byte is a boxed `Long`. WASM: a WASI fd is element-type-agnostic, so `WasmOpenCompiler` masks the mode with `& OUTPUT_BIT` (passing raw 2/3 to `_open` would mis-select the write oflags/rights) and the `_open` body is untouched; `_read_byte`/`_write_byte` (`WasmIoRuntimeBuilder.buildReadByteBody`/`buildWriteByteBody`) move one raw byte through the `BYTE_SCRATCH_ADDR` (148) scratch cell via `fd_read`/`fd_write` -- no quote framing, no newline scan -- and a byte is an i31 fixnum. Their indices `FUNC_READ_BYTE`/`FUNC_WRITE_BYTE` are appended between `FUNC_P1_FUTURE_AWAIT` and `FUNC_USER_BASE` (the mod/rem/gensym pattern), so no import/`FUNC_START` index shifts and the `--component` adapter blobs are unaffected (the adapter's `fd_read`/`fd_write` are already byte-clean). Both helpers grew the standard-stream designator dispatch described above; that is the only thing between them and a bare `fd_read`/`fd_write`. Consequence of fds being untyped on WASM: `read-byte` on a text-opened stream "works" there while the interpreter/JVM signal a type error -- documented as out of contract. `read-byte`'s CL EOF semantics (`eof-error-p` default t = trap/throw, nil = return `eof-value`) are runtime arguments to the helpers. `read-sequence`/`write-sequence` are shared macro expansions (`LispMacroExpander.expandReadSequence`/`expandWriteSequence`) into a `while` loop over `aref`/`%aset`/`length` with fixed `__rseq_`/`__wseq_` temp names and literal-only `:start`/`:end` keywords, so no per-backend codegen exists; the sequence must be a rank-1 array. **The BUFFER, not the stream, picks the element read/written**: both dispatch on `(stringp seq)`, so a character vector -- what `(make-array n :element-type 'character)` and `make-string` build, and the one rank-1 array that answers `stringp` on every backend (`.kb/adjustable-arrays.md`) -- moves CHARACTERS (`read-char` / one `write-string` of the slice) and anything else moves bytes. The test is a RUNTIME one because the buffer arrives in a variable: `alexandria:read-stream-content-into-string` allocates it as `(make-array size :element-type (stream-element-type stream))`, which is also why `make-array`'s `:element-type` accepts a computed designator (`LispMacroExpander.lowerRuntimeElementTypeMakeArray`, wired into `Jvm/WasmArrayCompiler` -- the interpreter reads the designator at run time already). That character half is `.todo/219`; before it, a text stream read through a character buffer fell to `read-byte` and died on the stream cast. Like the other stream ops, none of this is known to the runtime `_eval` interpreters, and `--no-gc` has no stream support at all. The `CiSpecE2eTest` driver passes `--dir . --dir /tmp` to both wasmtime invocations so file-stream ci-spec cases can open files in the shared work dir and the absolute-path case has a preopen whose name can cover one. WASM `open`/`load`/`probe-file`/`%list-directory` resolve paths through the PREOPEN TABLE (the paragraph near the top of this file), so they need `--dir` (in `--component` mode the same `path_open`/`fd_*` imports are satisfied by the adapter over `wasi:filesystem@0.3.0`). The runtime `_eval` interpreters do not know these forms (README). The runtime reader/`_eval` also do not know `require`/`provide` (compile-time directives consumed by `LoadInliner`; a file read by the runtime `load` of compiled output must not contain them — see load-inliner.md).

**Component stdin (stdin.lisp over wit-imported `wasi:cli/stdin@0.3.0`)**: on the `--component` path an ASYNC program that reads stdin (`read-line`/`read-char`/`read-byte` referenced + an async form referenced + not serve mode) gets `stdin.lisp` + `stdin-dispatch.lisp` spliced by `eval/StdinLibrary` (runs right after `SocketsLibrary` in the CLI; test helpers mirror it). The interface is bound FROM the fixed import block (`WasmComponentBuilder.FIXED_BLOCK_IFACES`, whose instance index the builder reads off the block -- the wait.lisp model; `validateFixedMembers` admits async type-alias built-ins, whose component-level stream/future types alias nothing out of the block instance, while drops/task-returns stay rejected), so the program's emitted WIT world is unchanged and no new `-S` flag exists. Mechanics = the preview1 adapter's stdin cache in Lisp: ONE `read-via-stream` stream cached in a defvar (its result future dropped immediately; EOF is the stream status), a chunk buffer + cursor + eof defvars, `%stdin-read-line-f`/`-read-char-f` async-defuns so the compiler rewrite's promotion (`WasmSocketsRewrite`, gated on the spliced `%io-read-line`) makes a pending stdin read SUSPEND the task -- a concurrent `wait-for` timer fires while the read waits (`componentAsyncStdinReadDoesNotStallTheInstance`). The `%stdin-*-or-raw-f` helpers dispatch nil designator -> stdin, else the `%...-raw` native built-ins; EOF parity: `read-line` -> nil, the 0/1-arg `read-char` signals `(error 'end-of-file)` -- the CLASS the native lowering signals (`LispMacroExpander.endOfFileSignal`), not a look-alike message, because a `(handler-case (read-char) (end-of-file () ...))` must catch it here exactly as on the interpreter and the JVM (`componentAsyncStdinBareReadCharSignalsTheEndOfFileClass`; it used to raise a plain `"read-char: end of file"` string, uncatchable by that handler, until 2026-08-06) -- `read-byte` on nil errors (a stream argument is mandatory everywhere). **A NON-async stdin program is deliberately NOT migrated**: it keeps the preview1 adapter's `fd_read` stdin branch, so its component is byte-identical and still runs without `-W exceptions=y` (`componentNonAsyncStdinKeepsTheAdapterPathAndItsFlags`); an async program already needed that flag, so migration changes no flags either. When sockets.lisp is spliced, `StdinLibrary` supplies only the or-raw helpers' backing (real `stdin.lisp`, or `stdin-stub.lisp` raw passthroughs under serve -- the wasi:http service world has no stdin and the bridge's `fd_read` is EOF by construction) and sockets.lisp's own dispatchers keep the `%io-*` names. Known limits, documented not fixed: reads buffer one host chunk (the sockets.lisp divergence from byte-at-a-time), and a migrated program that ALSO consumes stdin through forms the rewrite leaves native (`read`, the 2/3-arg eof-parameter `read-char`/`read-byte` forms) would hold TWO host stdin streams (the adapter's cache + stdin.lisp's) with implementation-specific interleaving -- don't mix them on stdin in one async program.

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

**`delete-file` is the same shape** (todo-249): prelude Lisp over `%delete-file`, the other
write-side sibling. The primitive answers nil rather than signalling when the file is not
there or the host refused, so the "a missing file is a `file-error`" decision lives once, in
the Lisp above it. Per backend: interpreter `Files.deleteIfExists`, JVM `_deleteFile`
(`JvmIoRuntimeBuilder`, gated through `FileMeta` like the metadata trio, so a program that
never deletes keeps its bytes), both WASM backends a call-time
`LispMacroExpander.deleteFileStub()` error -- same reason as `%make-directories`, and the
same tenth-import re-evaluation trigger (`path_unlink_file`) -- which has now FIRED (smart-buffer's disk spill), so the work is tracked as `.todo/257`. mito's `generate-migrations`
is the caller: it deletes superseded migration files, which is therefore an
interpreter/JVM-only branch of an operation that otherwise runs everywhere.

**`rename-file` is the THIRD of that shape** (`.todo/036`, 2026-08-15): prelude Lisp over
`%rename-file`, which answers nil rather than signalling when the source is not there or
the host refused. Per backend: interpreter `Files.move` (`REPLACE_EXISTING`), JVM
`_renameFile` (`JvmIoRuntimeBuilder`, `File.renameTo`, gated through the same `FileMeta`
record, which grew a fifth flag), both WASM backends a call-time
`LispMacroExpander.renameFileStub()` error. It is the same "no honest non-answer" reason
and therefore the same `.todo/257` work item, one preview1 import wider
(`path_rename`). CL's second and third values (the two truenames) are not returned, the
`ensure-directories-exist` rule; the new name is MERGED with the old one, so a bare file
name keeps the directory. Pinned by
`LispEvaluatorTest#renameFileMovesTheFileAndSignalsWhenItIsNotThere`,
`JvmLispCompilerTest#renameFileMovesTheFileOnDisk` and the rename arm of
`WasmLispCompilerIntegrationTest#pathnameAlgebraOverTheFlatNamestring`.

**`uiop:read-file-string` must NOT size its buffer from `file-length`** (todo-249). It is
prelude Lisp over `with-open-file` + a CHUNKED `read-sequence` loop, and both properties of
that loop are load-bearing rather than stylistic:

- `file-length` answers nil on both WASM backends (see the paragraph above), so
  `(make-string (file-length s))` traps there -- an uncatchable `unreachable`, not an error
  a caller could handle.
- The loop stops on the first SHORT read, so end of file is read at most ONCE. A SECOND read
  past EOF traps on the `--component` backend alone: `adapter.wat`'s `$fd_read` calls
  `stream.read` after the writable end has dropped, which wasmtime rejects
  (`cannot read after being notified that the writable end dropped`). That is a
  pre-existing component divergence -- any slurp loop hits it -- and the
  **re-evaluation trigger** is the adapter learning to answer 0 bytes/EOF idempotently;
  until then, read-loops here must not read past EOF twice.

**`:if-exists :append` (todo-231, smart-buffer's disk spill)**: the ONE non-default
value of the three otherwise-ignorable `open`/`with-open-file` options that is
implemented rather than rejected. `:direction :output :if-exists :append`
normalizes into the `:append` PSEUDO-DIRECTION -- not a CL direction, produced only
by `compiler.OpenModes.normalizeKeywordForm` and
`LispMacroExpander.expandWithOpenFile` (`LispMacroExpander.isAppendIfExists` is the
shared predicate) -- so every backend reads ONE literal token where the source
wrote an option pair. `OpenModes.APPEND_BIT` (4) extends the mode encoding to
`5` = text output appending and `7` = binary output appending. Per backend:
interpreter `Files.newBufferedWriter`/`newOutputStream` with
`CREATE + WRITE + APPEND`; JVM two extra `_open` arms over
`FileWriter(String,boolean)` / `FileOutputStream(String,boolean)` (the mode
constant no longer fits `ICONST_0..3`, hence `emitIntConst`); WASM
`WasmOpenCompiler.wasmMode` collapses the mode to 0 read / 1 write / 2 append and
`_open` answers `oflags = O_CREAT` alone (O_TRUNC would discard exactly what the
append is there to keep) plus `fdflags = FDFLAGS_APPEND` -- the `i32.eq` result IS
the flag value. An `:if-exists :append` alongside `:direction :input` is ignored,
as in CL. Pinned by `LispEvaluatorTest#evalOpenAppendKeepsTheExistingContent`,
`JvmLispCompilerTest#compileAndRunOpenAppend` and the ci-spec case
`open-if-exists-append-keeps-the-existing-content` (all four backends).
