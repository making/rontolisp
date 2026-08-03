# Gray streams (rontolisp's own protocol, all backends)

rontolisp OWNS the Gray-stream protocol; third-party portability layers adapt
onto it and the core knows no third-party name.

**The protocol** (`src/main/resources/am/ik/rontolisp/eval/gray.lisp`, served by
`eval/GrayStreamsLibrary`): a CL-shaped base-class hierarchy —
`rontolisp:fundamental-stream` at the root, `-input-stream`/`-output-stream`
below it, `-character-input/-output-stream` and `-binary-input/-output-stream`
as leaves — plus the generics `stream-write-char`, `stream-write-string
(stream string &optional start end)`, `stream-write-byte`, `stream-read-byte`,
`stream-read-char`, `stream-unread-char` (protocol-only, no built-in
dispatches), `stream-read-line`, `stream-listen`, `stream-read-sequence` /
`stream-write-sequence` `(stream sequence start end)` (end always an integer by
method time), `stream-file-position` and its `(setf ...)` writer generic
(todo-232 setf methods) — all plain CLOS-subset definitions, so the whole
protocol is backend-free expansion output, no codegen. **Read-side EOF
convention: the read generics answer the keyword `:eof`**; the dispatch layer
translates it into the `eof-error-p`/`eof-value` contract, signalling
`(error 'end-of-file)` (same class + "end of file" message as the built-ins).
`stream-read-line` answers a partial last line as that line and returns
PRIMARY values only (no missing-newline-p — .todo/212, secondary values do not
cross function boundaries on the compile paths). Default methods on the base
classes: `stream-read-line` / `stream-read-sequence` / `stream-write-sequence`
loop the element generics (shared `%gray-default-*` defuns), `stream-listen`
answers nil, `stream-file-position` and its setf answer nil (CL's "not
supported").

**The dispatch helpers** (`rontolisp::%gray-*-dispatch` defuns at the bottom of
gray.lisp) hold the ONE copy of "instance -> generic, anything else -> the
built-in" plus the `:eof` translation, shared by both dispatch seams:
write-string/char, write-byte, read-byte/char/line (read-line's eof-error-p
defaults NIL — the built-in's lite convention; read-byte/char default T),
listen, read-sequence/write-sequence (normalize a missing end to
`(length sequence)`), file-position get/set.

**Interpreter dispatch**: `LispEvaluator` wraps the built-ins — when the
stream argument is an INSTANCE (`%obj-p`, `.kb/instance-syntax.md`) the wrap
lazy-loads `gray.lisp` (`ensureGrayStreamsLoaded`) and applies the matching
`%gray-*-dispatch` defun (`applyGrayDispatch`); non-instances go straight to
the base built-in (the helpers' fallbacks re-enter the wraps, one extra hop, no
recursion). `read-sequence`/`write-sequence` are macro expansions, not
functions, so they are intercepted at `evalCons`
(`evalSequenceWithGrayDispatch`): seq and stream evaluate once, an instance
routes to the sequence dispatch helper, anything else re-enters the shared
expansion with the two evaluated values QUOTED in place (no double
evaluation). A `defclass` naming a Gray base class as a superclass eager-loads
`gray.lisp` (`referencesGrayBaseClass`/`GRAY_BASE_CLASSES` — all seven class
names) — without that, a bare-protocol user class died on "unknown
superclass".

**Compile path** (`GrayStreamsLibrary.process`, the usocket `process()`
pattern): runs after `UserMacroExpander` in `RontoLispCli`, the playground
frontend and `AsdfLibraryE2eSupport`. Triggered by any protocol name in the
program (`splitQualified` member match, so `trivial-gray-streams:` spellings
count; the match set is ALL-UPPERCASE — the reader upcases every symbol, and
the pre-widening set carried two dead lowercase class names). It (1) splices
the gray.lisp PROTOCOL forms unless a load already did (guard: a `defclass` of
`rontolisp:fundamental-character-output-stream` is present), (2) rewrites
every stream-taking call with an explicit non-literal stream (not
`t`/`nil`/string literal) onto the dispatch helpers —
`write-string`/`write-char`, `write-byte`, `read-byte`/`read-char`/`read-line`
(absent eof args become their literal defaults), `listen`, `file-position`
(1-arg -> get helper, 2-arg -> set helper), `read-sequence`/`write-sequence`
(literal `:start`/`:end` keywords parsed, missing end stays nil) — and (3)
**splices ONLY the dispatch defuns the rewrites referenced**
(`SPLICE_ON_USE`, collected via `dispatchSymbol`; `WRITE_CHAR_DISPATCH`
transitively pulls `WRITE_STRING_DISPATCH`). Selective splicing is
load-bearing, not just size: `LibraryDefunPruner` does NOT cover this splice
(nor the shim systems), and `%gray-listen-dispatch`'s fallback names the
`listen` built-in, which Preview 1 WASM rejects at COMPILE time — an
unconditional splice broke every Gray program on that backend. Same reason the
shim load path (`ShimLibraries.forms`) combines `protocolForms()` (no dispatch
defuns), and `process` adds the used ones on top. The rewrite walker skips
quoted data and the gray.lisp defuns' own bodies (`DISPATCH_DEFUNS` — their
fallback built-in calls must not rewrite into themselves). The compiled
runtimes themselves know nothing: the dispatch is ordinary Lisp.

**Shim** (`trivial-gray-streams.lisp` via `ShimLibraries`/`BuiltinSystems`):
mirrors the full hierarchy — each `trivial-gray-streams:` class subclasses its
trivial-gray-streams parent AND its rontolisp twin, so ONE delegating method
per generic, specialized on the input/output/root class, covers every adapter
subclass — plus `trivial-gray-stream-mixin` (empty; upstream's own class) and
the portable generics (`stream-read/write-sequence` spelled `(stream sequence
start end &key)` like upstream). Delegations route rontolisp's protocol into
the portable generics; portable-side DEFAULT methods reuse the
`%gray-default-*` loops (without them the delegations would shadow the
rontolisp base-class defaults for a class defining only element generics);
`stream-file-position` delegates in both directions (reader and setf writer).
Package seeding: `PackageRegistry` (both the rontolisp externals and the
trivial-gray-streams package symbols), name constants in `LispNames`
(`GRAY_*`).

**format rewrite (todo-146, jzon's `(format %stream "~D" value)`)**: the
pre-pass also rewrites `(format STREAM ctrl args...)` with a possibly-instance
destination into `(let ((__gray_fmt_stream STREAM))
(let ((__gray_fmt_result (format nil ctrl args...)))
(if __gray_fmt_stream (progn (%gray-write-string-dispatch __gray_fmt_result
__gray_fmt_stream) nil) __gray_fmt_result)))` — render to a string, then test the
destination at RUN time: a non-nil one routes through the write-string dispatch
(whose fallback handles stream handles and the `t` designator) and the form yields
nil like format-to-stream, a nil one returns the string. Destination bound first,
keeping CL's evaluation order. **The run-time test is not optional and this pass is
where it is easiest to get wrong**: nil is not a stream but format's "return the
string" destination ([standard-output-redirect.md](standard-output-redirect.md)),
and this rewrite fires over the WHOLE program as soon as ANY part of it uses the
Gray protocol — so writing unconditionally turned an unrelated
`(format stream ...)` in a completely different function into the wrong answer
(quri's `(render-uri uri &optional stream)`, caught only by the concatenated
ci-spec program, where a Gray case sits 18 cases upstream of the quri one). TWO walker rules exist because the
walk has no position awareness: (1) a lambda-list keyword is never a stream arg
(`streamArgMayBeInstance` rejects `&`-prefixed symbols — jzon's `(defun %raise
(type pos format &rest args) ...)` parameter tail read as a format call), and
(2) the generic recursion rewrites list ELEMENTS, never re-checking a cdr TAIL
as a call (`rewriteTail` — `(error 'ty :format-control format
:format-arguments args)` has a tail whose car is `format`).

**A BINDING form's structural position is not a call.** The rewrite walker skips
the lambda list of `lambda`/`defun`/`defmacro`/`defmethod`/`destructuring-bind`
and the variable half of a `let`/`let*`/`flet`/`labels`/`macrolet` binding
(`rewriteBindingForm`). Without that, cl-postgres' `messages.lisp:218`
`(flet ((set-param (format size value) ...)))` had its PARAMETER LIST rewritten
as a `format` call -- the rewrite runs over the WHOLE program whenever any of it
uses the protocol, so the failure was "Parameter must be a symbol" in an
unrelated library. Latent until todo-249 made a mito program trigger the splice
across cl-postgres.

**Limits**: `stream-unread-char` has no dispatching built-in and `peek-char`
does not dispatch. `listen` on a Gray instance works interpreter/JVM; Preview 1
WASM rejects ANY `listen` at compile time (pre-existing platform limit, Gray
or not — the rewrite keeps that error, it neither adds nor removes it). Both
seams key on "is this an instance", so they agree; a plain cons handed as a
stream falls through to the built-in either way. A bounded
`(write-string s instance :start ...)` is NOT rewritten (interpreter ignores
the bounds for instances; both are edge behavior). First-class
`(funcall #'read-byte instance)` does not dispatch on the compile paths (no
call site to rewrite). `stream-read-sequence`/`-write-sequence` methods are
honored on BOTH seams (the rewrite and `evalSequenceWithGrayDispatch` route to
the same sequence dispatch helper). A runtime-nil `format` destination used to
write like a designator instead of returning the string; that divergence is
RETIRED (see the run-time test in the rewrite above).

## `make-broadcast-stream` is a Gray stream (todo-249)

CL's broadcast stream -- writes fanning out to several components -- is not a
new stream KIND in any runtime: it is prelude Lisp
(`LispPreludeLibrary.MAKE_BROADCAST_STREAM`) defining a
`rontolisp:fundamental-character-output-stream` subclass whose
`stream-write-char` / `stream-write-string` methods loop the components. The
dispatch above carries it, so ONE definition serves all four backends and the
Java built-in the interpreter used to have (a StringWriter, zero-argument shape
only) is gone.

The consequence is worth stating plainly, because it is the price of the design:
**a broadcast stream with components is a Gray stream, so exactly the operators
that dispatch on one work with it** -- `format`, `princ`, `write-string`,
`write-char`. `terpri` / `fresh-line` / `write-line` / `force-output` /
`finish-output` / `print` / `close` are not part of the protocol here and signal
on ANY Gray stream, broadcast or not (see Limits above); that is a pre-existing
protocol boundary this feature inherits rather than one it introduces.
Widening the protocol to those five is the re-evaluation trigger: do it and
broadcast streams gain them for free.

A component-LESS `(make-broadcast-stream)` is unchanged -- the same discarding
`%make-string-output-stream` handle it always returned -- but note it now rides
the same prelude entry, so a program that uses only the sink also carries
gray.lisp. That was the deliberate trade against a second, arity-selected
lowering; the driver was mito's `generate-migrations`, which echoes its DDL to
`*standard-output*` and the migration file at once.

Pinning tests: `LispEvaluatorTest#grayStreamInstanceReceivesWriteCharAndWriteString`
(shim), `#grayBaseClassSuperclassLoadsGrayStreamsEagerly` (bare protocol),
`#grayBinaryStreamReadWriteBytesAndFilePosition`,
`#grayInputStreamReadCharReadLineAndSequenceDefaults`,
`#grayShimBinaryInputStreamWithMixinAndSetfFilePosition` (the circular-streams
class shape), `JvmLispCompilerTest#compileAndRunGrayStreamInstanceDispatch`,
`#compileAndRunGrayBinaryStreamDispatchAndFilePosition`,
`#compileAndRunGrayInputStreamReadLineAndSequenceDefaults`,
`WasmLispCompilerIntegrationTest#grayStreamInstanceDispatch`,
`#grayBinaryStreamDispatchAndFilePosition`, ci-spec
`gray-stream-instance-dispatch` and
`gray-stream-binary-round-trip-and-file-position`.
