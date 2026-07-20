# Reader case: the upcase premise + the canonical lowercase fold

The reader upcases unescaped symbol characters like Common Lisp's `:upcase` readtable
case, then folds names whose canonical rontolisp spelling is lowercase back down
(`am.ik.rontolisp.UpcaseSymbols.canonicalize`), so the token stream for lowercase
source is identical to before the premise landed and NOTHING downstream (resolver,
splice scanners, evaluator, all backends) is case-aware. User symbols stay upcased —
`foo` and `FOO` both read as `FOO` — which is what makes case-folding-reliant CL code
work (assoc-utils README's `alist-get` mixed-case keywords and `with-keys`
`(intern (string-upcase ...))` name synthesis were the driving cases, todo-155 gap A).
Decision record: gap A started as an opt-in `--upcase` flag per the todo's design
note; the user redirected mid-session (2026-07-19) to "premise, not option" — always
on, no opt-out, flag removed.

**The fold set** (`UpcaseSymbols.canonicalize`): bare names where lowercase(name) is
`PackageRegistry.isClSymbol` (CL_SYMBOLS + car/cdr compositions) or in
`EXTRA_CANONICAL` (t/nil/pi/fixnum constants, standard type names not in CL_TYPES
like `hash-table`/`symbol`, the ClosRegistry seeded condition types — keep both lists
in sync); any `&`-initial name; qualified names whose package part lowercases to a
built-in package/nickname (`PackageRegistry.isBuiltinPackageName`, a static set kept
in sync with the registry constructor by hand) — the member folds too EXCEPT for
`cl-user`, whose members are user symbols; keyword/`#:` designators of built-in
packages only (`:CL-USER` folds, `:ELEMENTS` stays — data keywords must stay
upcased). Escape handling is per-character in `LispLexer.readSymbol` (the ONLY place
escape info exists): escaped chars are never upcased, but the fold applies to the
finished name, so `|CAR|` ≡ `car` (CL-faithful) and `|car|` ≡ `car` (the documented
deviation — canonical spellings are lowercase).

**`Features.INTERNAL` is the exception, not a mode.** `Features` carries a
`preserveCase` boolean; `INTERPRETER`/`JVM`/`WASM` fold (the premise),
`Features.INTERNAL` (= `INTERPRETER.preservingCase()`) is passed by every read of
rontolisp's OWN lowercase-authored Lisp sources — the library splices
(json/url/linalg/vec/usocket/wit/gray/http/sockets/stdin/wait/prelude/shims) and
`.asd` system definitions (`AsdfSystems` parses them as data against lowercase
spellings; both the LoadInliner and LispEvaluator call sites pass
`preservingCase()`). A NEW internal `readAllFromString` of lowercase-authored source
MUST pass `Features.INTERNAL` or its defuns upcase and every lowercase Java-side
matcher (splice scanners, pruner roots, wrapper defs) misses them — the failure shows
up immediately as `Undefined function` in that library's tests. User-facing reads
(CLI interpret/compile, REPL `evalBuffer`, playground, runtime `load`, `read`/
`read-from-string`) just use the default feature sets.

**Runtime name entry points fold too**: the interpreter's `intern`/`find-symbol`
(`LispEvaluator.foldRuntimeSymbolName` -> `UpcaseSymbols.canonicalize`) so
`(intern "TIME")` names the standard `time` — CL's upcase-world answer, required by
with-keys-style synthesis; ASDF/quicklisp SYMBOL system designators downcase like
ASDF `coerce-name` (`AsdfSystems.symbolName`; string designators stay verbatim).
(The compiled backends' `intern` built-in does NOT fold a CL-name string — a known,
narrow interp-vs-compiled deviation for `(intern "<cl-name>")`, unrelated to `read`.)

**Runtime `read`/`read-from-string` fold on ALL FOUR backends** (the CL-faithful
answer, so `(read-from-string "foo")` is `FOO`, `(eq (read-from-string "car") 'car)`
is `t`). The interpreter's `read`/`read-from-string` built-ins read with
`Features.INTERPRETER` (the full lexer upcase + `UpcaseSymbols.canonicalize`). The
compiled backends replicate `canonicalize(upcase(token))` in their embedded reader
runtimes from a single baked source of truth: `UpcaseSymbols.foldNamesBlob()` (the
567 foldable bare names) and `foldPackageNamesBlob()` (the built-in package/nickname
designators), both sorted + `\n`-delimited (a newline can never appear in a
whitespace-terminated token). The JVM emits a `_canon` helper
(`JvmReadRuntimeBuilder.buildCanon`, called at the top of `_classify`) that tests
membership with `String.contains` over the baked blob; the WASM reader folds the
token bytes IN PLACE before `_intern` (`WasmReadRuntimeBuilder.emitCanon`, inlined in
`_read_expr` — the fold preserves length, so the intern/nil/t path is unchanged) by
lowercasing, scanning the blob (appended to the module's data segment via
`stringTable.appendBlob`, only when the program uses `read`), and uppercasing back
when the name is not foldable. Because the fold set is baked whole, a standard name
folds even when the program does not otherwise reference it (`(read-from-string
"reverse")` is `reverse` on every backend), so the fold is program-independent.

Both compiled backends use the same SIMPLIFIED rule — fold the whole name to
lowercase when foldable, else keep it upcased — which equals
`canonicalize(upcase(token))` for every bare name, keyword/`#:` designator,
`&`/`%` prefix and built-in-package-qualified name, and deviates from the
interpreter only on two pathological package-qualified *runtime-read* shapes:
`cl-user::X` (whole folds instead of just the package part) and a non-built-in
package's `%`-member (kept upcased instead of folding just the member). These are
untested and semantically moot (the compiled reader does not resolve packages at
runtime), and the JVM and WASM are byte-identical to each other. Non-ASCII bytes are
left as-is on WASM (a documented runtime-read limitation, consistent with the other
WASM non-ASCII gaps); the JVM/interpreter fold full Unicode.

**Keyword-argument matching is case-insensitive** at every builtin matcher:
`LispNames.keywordMatches(symName, canonicalLower)` (equalsIgnoreCase) and
`LispNames.foldKeyword` for `switch` scrutinees (labels stay lowercase). Swept
through: the 5 helper choke points (LispEvaluator `optionalKeywordArg`/
`requireTestKeyKeywords`, Environment `plistGet`, LispMacroExpander `keywordValue`/
`requireKeywords`), the 3 `findKeywordValue` copies (JvmArrayCompiler,
WasmArrayCompiler, NoGcWasmCompiler), the inline `*_KEYWORD.equals` sites, and the
keyword `switch`es (defstruct/defclass/define-condition options, defpackage clauses,
open/write-string/parse-integer/replace, wasm-export/wasm-import/wit-import
directive options, AsdfSystems clauses). `loop` already lowercases its keywords.
`LambdaLists.unknownKeyCheck` dual-spells `:allow-other-keys`/`:ALLOW-OTHER-KEYS` in
the generated member list + getf override (the generated `&key` machinery is
otherwise self-consistent: keywords derive from the already-folded parameter names).
Keyword DATA is never re-cased — only comparisons against known parameter names.

**symbol-name/print consequences**: user symbols report/print upcased (`'foo` →
`FOO`, `(symbol-name 'foo)` = `"FOO"` — the CL answer; supersedes the todo-085
verbatim-lowercase note in `symbol-runtime-api.md` for USER symbols); standard
symbols keep canonical lowercase (`(symbol-name 'car)` = `"car"`, the documented
CL deviation).

**Synthesized names case-match their base** (`LispMacroExpander.affixFor`): defstruct
PT gets MAKE-PT/PT-P/COPY-PT (accessors ride the conc-name, already consistent),
defclass/`make-instance` use `%MAKE-`+base for upcased bases — while lowercase
internal sources keep `make-`/`-p`/`%make-`. Any NEW Java-side name synthesis from a
user symbol must go through `affixFor` (or match case some equivalent way).

**Case-flip retries bridge the mixed-case seams** (each is a one-shot flip, never a
loop): `PackageResolver.resolveUnqualified`/`resolveQualified` retry the lowercase
spelling against packages whose canonical members are lowercase (`(in-package
:rontolisp)` + bare `version`; `GL:CREATE-SHADER` reaching a wit-import package's
lowercase defuns); `ClosRegistry.slotPosition` flips both ways (Java asks
"format-control", upcase-read conditions register FORMAT-CONTROL); the JVM eval
runtime (`JvmEvalRuntimeBuilder`) flips in the function lookup, the variable lookup
(self-eval still returns the ORIGINAL spelling) and the apply operator (ARITY's sign
is the one-shot guard — CH is clobbered by carCdrComposition, which runs BEFORE the
flip so compositions see the original spelling). On the JVM these bridge
compiled-upcased references with a runtime-`load`ed/`read` symbol in both directions
(the embedded reader now folds — see the runtime-`read` paragraph above — but the
flip still handles a runtime-defined symbol whose spelling the fold cannot reach).
The WASM backends have NO such bridge: their eval runtime compares interned
string-table OFFSETS, so bridging a runtime-DEFINED symbol whose bytes were not
compile-time-interned needs runtime re-interning (the `_intern` rail) — deferred.
Documented limitation: on WASM, a runtime-`load`ed definition (a NEW symbol absent
from the compile-time intern table) is reachable from compiled code only under an
offset the program already interned; the integration tests for that path spell such
runtime-loaded definitions to match a compiled reference. (This is distinct from the
runtime-`read` case fold above, which IS live on WASM — a read token that folds to a
compile-time-interned canonical name resolves by offset.) Follow-up tracked in
`.todo/155` (item 1).

**Host-facing names derive lowercased**: wasm-export/wasm-import default
export/import names and `:as` quoted-symbol aliases, wasm-export `:param-names`
symbols, wit-export `:world` symbol designators — all `toLowerCase` (component
labels are lower-kebab; string spellings stay verbatim). wit-export matches a WIT
export name against the defun table by exact-then-upcased lookup and quotes the
ACTUAL defun spelling into the synthesized wasm-export.

**HTTP plist keys are UPPERCASE** (`HttpPlistShape`: keyword = ":" + upcased field
name): all four backends emit/read `:STATUS`/`:HEADERS`/`:BODY`..., the generated
Lisp helpers inherit it, `http.lisp`'s own getf spellings are written uppercase, and
the JVM fetch runtime matches option keys with `equalsIgnoreCase`.

**Component rich-params keywords: UPCASED on the way OUT, dual-compared on the way IN**
(same rule the HTTP plists took; todo-155 item 2). The `--component` canonical-ABI
boundary is the one place a rich WIT type crosses as raw Lisp data a program inspects by
tag, so it must agree with upcased user data. The LIFT (component memory → Lisp value,
what the user sees) spells every case name / record plist key / result-envelope head
UPCASED — `WasmComponentImportCompiler.emitVariantCase`/`emitLiftRecordAt`: `:OK`/`:ERROR`
and `.toUpperCase(ROOT)` for enum/variant/record — so a user's `(eq (car r) :ok)` /
`(case c (:red ...))`, which reads `:OK`/`:RED`, hits. The LOWER (Lisp value → component
memory) DUAL-compares each tag (`:get` OR `:GET` via `I32_OR`), because the internal
lowercase-authored sources (`http.lisp` `%fetch-method-variant`, read `Features.INTERNAL`)
construct the lowercase spelling while user data is upcased. `wit.lisp`'s `%wit-result`
accepts both envelope heads (`:ok`/`:OK`, `:error`/`:ERROR`). On the interpreter and JVM
the boundary is an ordinary Lisp call: the value comes from the provider (user Lisp,
already upcased) and the core ships NO built-in provider, so those two backends are
self-consistent. A Features.INTERNAL consumer of a LIFTED tag must match the upcased
spelling — `sockets.lisp` (`%sock-format-quad`, dual) and `http.lisp`'s
`%serve-method-string` (the served `method` variant, dual) both do; the latter was the
one gap-A miss (every non-GET served method silently collapsed to GET until it matched
`:POST` as well as `:post`). Pinned by
`WasmComponentImportCompilerTest.liftsAVariantEnumAndResultTagUpcased...` (the lift emits
upcased and never a lowercase fallback) and `ServeMethodCaseComponentE2eTest` (a served
component's every HTTP method round-trips through the real wasi:http `method` variant
under `wasmtime serve`).

Pinned by: `LispReaderTest` (fold/escape/designator cases), `LispEvaluatorTest`
(`upcaseReaderMode*`: mixed-case program, upcased keyword args, intern fold,
keyword-data symbol-name, and `upcaseReaderModeFoldsRuntimeRead*`: runtime
`read`/`read-from-string`/`read`-from-stream fold),
`JvmLispCompilerTest.compileAndRunUpcaseReaderMode{,FoldsRuntimeRead}`,
`WasmLispCompilerIntegrationTest.compileAndRunUpcaseReaderMode{,FoldsRuntimeRead}`,
the `read-from-string-upcase-fold` ci-spec case (native `CiSpecE2eTest`, all four
backends), `AssocUtilsUpcaseE2eTest` (the REAL assoc-utils README gap-A examples on
all four backends). Docs: `doc/{en,ja}/guides/reader-case.md` + the
`symbol-name`/`string`/`read-from-string`/`read` reference pages.
