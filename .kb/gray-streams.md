# Gray streams (rontolisp's own protocol, all backends)

rontolisp OWNS the Gray-stream protocol; third-party portability layers adapt
onto it and the core knows no third-party name.

**The protocol** (`src/main/resources/am/ik/rontolisp/eval/gray.lisp`, served by
`eval/GrayStreamsLibrary`): a CL-shaped base-class hierarchy —
`rontolisp:fundamental-stream` at the root, `-input-stream`/`-output-stream`
below it, `-character-input/-output-stream` and `-binary-input/-output-stream`
as leaves — plus the generics `stream-write-char`, `stream-write-string
(stream string &optional start end)`, `stream-write-byte`, the line/column
family `stream-line-column`, `stream-start-line-p`, `stream-terpri`,
`stream-fresh-line`, `stream-advance-to-column`, the flush family
`stream-force-output` / `stream-finish-output` / `stream-clear-output`,
`stream-read-byte`, `stream-read-char`, `stream-unread-char` (protocol-only, no
built-in dispatches), `stream-read-line`, `stream-listen`,
`stream-read-sequence` / `stream-write-sequence` `(stream sequence start end)`
(end always an integer by method time), `stream-file-position` and its
`(setf ...)` writer generic (todo-232 setf methods) — all plain CLOS-subset
definitions, so the whole protocol is backend-free expansion output, no codegen.

**Exactly ONE of `stream-write-char` / `stream-write-string` is required**
(todo-252). Each has a default method on
`fundamental-character-output-stream` written in terms of the other
(`%gray-default-write-string` loops the char generic, `%gray-default-write-char`
hands the one-character string to the string generic), so a class defines
whichever it can and inherits the rest of the output protocol. This is
deliberately WIDER than full Gray, which requires `stream-write-char`: before
todo-252 `write-char` LOWERED to `write-string` on every seam, so
`stream-write-char`-only classes — rove's `indent-stream`, the Gray protocol's
one required method — died with "No applicable method: STREAM-WRITE-STRING",
while `stream-write-string`-only classes (rontolisp's own broadcast stream,
jzon's writer, the doc page's example) are what every existing program had
written. Keeping only one side would have broken the other. **Defining NEITHER
is the one degenerate shape: the two defaults then call each other** — a
programming error in any implementation (SBCL signals "no applicable method"),
here a recursion instead of a message.

Everything above the two element generics composes out of them:
`stream-terpri` writes a newline through `stream-write-char`,
`stream-start-line-p` answers from `stream-line-column` (default `nil` = "this
stream tracks no column", which is what makes `fresh-line` break the line
unconditionally, the same rule the handle-based built-in follows for a file
stream), `stream-fresh-line` is
`(unless (stream-start-line-p s) (stream-terpri s) t)`, and the flush trio
answers nil (no backend buffers output in a way a program could discard).

**Read-side EOF convention: the read generics answer the keyword `:eof`**; the dispatch layer
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
built-in" plus the `:eof` translation, shared by both dispatch seams. **Each
resolves its stream through `%synonym-target` FIRST**: a synonym stream
(`.kb/read-load-streams.md`) is an instance too, so without that it would take
the CLOS arm and die on "no applicable method", and its target -- which may
itself be a Gray instance -- would never be reached. That is why the prelude
splices `%SYNONYM-TARGET` for any program using this protocol
(`LispPreludeLibrary.referencedBySurfaceForm`, `LibraryDefunPruner`'s root list):
this pass runs AFTER the prelude selection, so the reference it would look for
does not exist yet. A pipeline that splices gray.lisp must therefore run
`LispPreludeLibrary.process` too. The helpers:
write-string/char, write-byte, read-byte/char/line (read-line's eof-error-p
defaults NIL — the built-in's lite convention; read-byte/char default T),
listen, read-sequence/write-sequence (normalize a missing end to
`(length sequence)`), file-position get/set, and (todo-252) terpri, fresh-line,
write-line, princ/prin1/print, force-output/finish-output/clear-output, close.

**The print-family helpers RENDER and then write**: `%gray-princ-dispatch` /
`-prin1-dispatch` / `-print-dispatch` call `princ-to-string` / `prin1-to-string`
and hand the text to `stream-write-string`, so a `print-object` method still
decides the text (the print-object rewrite hooks exactly those two conversions,
`LispMacroExpander.expandPrintObjectHook`, and it runs over gray.lisp's bodies
like any other code) and the instance receives the same bytes the handle-based
built-in would have written — `print`'s TRAILING newline included, which is
where rontolisp's `print` puts it (`.todo/215`).

`%gray-fresh-line-dispatch` answers **nil**, like the handle-based `fresh-line`,
rather than `stream-fresh-line`'s CL-shaped t/nil: the value of the operator
must not depend on which kind of stream it was handed. The generic's own answer
is still what a direct caller and the shim's delegation see.

**`close` is the one operator a program can OWN, and the rewrite stands down for
it.** CL spells a stream's close as a method on `close` ITSELF, and a
`defmethod` on a built-in name already dispatches on every backend
(`.kb/clos.md`, todo-237) — fast-io's `(defmethod close ((stream
fast-output-stream) &key abort) ...)` is exactly that, pinned by
`FastIoCircularStreamsE2eTest`. So there is deliberately **no
`rontolisp:stream-close` generic** competing with it (the name is taken by the
async-stream API anyway), `%gray-close-dispatch` simply answers `t` for an
instance, and both seams decline the dispatch when the program defines a `close`
method: `GrayStreamsLibrary.ownsClose` scans the program for a `defmethod`/
`defgeneric` named `close`, the interpreter's wrap asks
`closRegistry.findGeneric(CLOSE)`. Same condition, so the two agree. Two
consequences worth knowing: a Gray program with no `close` method now answers
`t` for `(close <any instance>)` rather than signalling "CLOSE expects a
stream", and `%gray-close-dispatch` is the ONE helper that does not resolve a
synonym stream first — closing a synonym closes the synonym (CLHS 21.1.3), which
its instance arm already answers.

**Interpreter dispatch**: `LispEvaluator` wraps the built-ins — the wrap
resolves a synonym stream first (`resolveSynonymArg`, the Java twin of the
helpers' `%synonym-target` hop) and then, when the stream argument is an INSTANCE
(`%obj-p`, `.kb/instance-syntax.md`), the wrap
lazy-loads `gray.lisp` (`ensureGrayStreamsLoaded`) and applies the matching
`%gray-*-dispatch` defun (`applyGrayDispatch`); non-instances go straight to
the base built-in (the helpers' fallbacks re-enter the wraps, one extra hop, no
recursion). The whole line/print/flush family rides ONE shared wrap builder,
`wrapGrayOutputOperator(name, streamIndex, helper)` — the stream sits at
argument 0 for terpri/fresh-line/force-output/finish-output/clear-output and at
argument 1 for write-line/princ/prin1/print — so a new operator is one line and
cannot drift from the helper it names. `read-sequence`/`write-sequence` and
`write-char` are macro expansions, not
functions, so they are intercepted at `evalCons`
(`evalSequenceWithGrayDispatch`, `evalWriteCharWithGrayDispatch`): the
arguments evaluate once, an instance
routes to the dispatch helper, anything else re-enters the shared
expansion with the evaluated values QUOTED in place (no double
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
(literal `:start`/`:end` keywords parsed, missing end stays nil), the unary
`(op STREAM)` family `terpri`/`fresh-line`/`force-output`/`finish-output`/
`clear-output`/`close` and the `(op VALUE STREAM)` family
`write-line`/`princ`/`prin1`/`print` — the stream-LESS spelling of each writes
to `*standard-output*`, can never be an instance, and is left alone, which is
also what keeps a program that names no stream byte-identical — and (3)
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

**`GrayStreamsLibrary.process` OWNS where the protocol sits — including when a
load already spliced it.** The guard in (1) is not "decline and leave it";
when the protocol is present but a form that SUBCLASSES a Gray base class
precedes it, the protocol forms are HOISTED to the front
(`protocolFormsToHoist`). One owner for placement, and it runs last, so no
library's splice index has to depend on another's. The real collision:
`HttpServerLibrary.process` prepends `http-server.lisp` at index 0 and its
buffered `:raw-body` half defines `http-request-body-stream`, a
`rontolisp:fundamental-binary-input-stream` subclass — while the protocol
arrives at the trivial-gray-streams shim's splice site, i.e. wherever the
`ql:quickload` sat. A program quickloading BOTH a Clack server and
`lack-request` therefore had its subclass at the top and its base class in the
middle, and every compile path rejected the `defclass` ("unknown superclass").

Two rules the hoist has to keep, both learned the hard way:

- **Conditional, so an unaffected program is BYTE-IDENTICAL.** A program whose
  protocol already precedes every subclass — every program that loads a Gray
  shim and nothing else — moves nothing. Pinned by
  `GrayStreamsLibraryTest#programWithoutAGrayShimKeepsTheProtocolSpliceAtTheFront`.
- **The "is this one of gray.lisp's own definitions" key is built from
  `print()`, never `display()`.** A symbol's DISPLAY text drops its package
  prefix, so `trivial-gray-streams:stream-read-line` and
  `rontolisp:stream-read-line` collide — which hoisted the SHIM's delegating
  methods above the shim's own classes, and their specializers then resolved
  to the rontolisp base class (`ClosRegistry.findClass`'s unique-member
  fallback). The two methods collapsed onto one registry key, so the
  dispatcher tested the rontolisp descendant set and called the shim's body:
  `read-sequence` on a plain `rontolisp:` Gray stream ended in "No applicable
  method: TRIVIAL-GRAY-STREAMS:STREAM-READ-SEQUENCE". Package-qualified
  identity is load-bearing in this file.

Re-evaluation trigger: the hoist exists because two pre-passes both prepend at
index 0. If `HttpServerLibrary` ever stops prepending (or the shim stops
splicing the protocol at its load site), the hoist becomes dead code — check
`protocolFormsToHoist` then, do not leave it as a permanent unexplained pass.

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
RETIRED (see the run-time test in the rewrite above). The OUTPUT protocol
stopping at the two write generics — `terpri`/`fresh-line`/`write-line`/
`force-output`/`finish-output`/`print` signalling "not an output stream" on any
Gray instance, and `princ`/`print` writing PAST the instance on the compile
paths — is retired too (todo-252); what is left of that boundary is
`stream-advance-to-column`, which has no dispatching built-in (`format`'s
`~T` does not consult it).

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
that dispatch on one work with it**. Since todo-252 that is the whole output
protocol -- `format`, `princ`/`prin1`/`print`, `write-string`/`write-char`,
`terpri`/`fresh-line`/`write-line`, `force-output`/`finish-output`/
`clear-output`, `close` -- and it costs the broadcast stream nothing, because
the widening happened in the protocol rather than per stream kind. `fresh-line`
on one always breaks the line: a broadcast stream tracks no column, and
`stream-line-column`'s nil default is what makes that unconditional.

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

## flexi-streams' in-memory octet streams are REAL Gray streams (todo-231)

`flexi-streams.lisp` is otherwise a lite shim (a flexi WRAPPER is the underlying
stream), but `flex:make-in-memory-input-stream` is not: an octet vector is not a
stream on any backend, and smart-buffer's `finalize-buffer` hands one to the
multipart parser for every request body that stayed under the memory limit. The
shim therefore defines

- `flexi-streams:vector-stream` -- a `rontolisp:fundamental-binary-input-stream`
  subclass with `vec`/`index`/`end` slots. It extends rontolisp's OWN protocol
  rather than trivial-gray-streams: the two shims are independent ASDF systems,
  and a program naming only flexi-streams must not drag the portability adapter
  in. The CLASS is external (http-body spells `(typep s 'flex:vector-stream)`
  with a single colon to take its no-copy fast path); the three accessors stay
  INTERNAL, as upstream, which is why the same file reaches for
  `flex::vector-stream-vector` with a double one.
- `rontolisp:stream-read-byte` (`:eof` at the end, the read-side convention),
  `stream-listen`, and a REAL `stream-file-position` pair -- an index into a
  vector -- which is what lets circular-streams rewind a body it already read.
- the `FLEX` nickname (`PackageRegistry.BUILTIN_NICKNAMES`), the spelling
  smart-buffer and http-body use.

Ordering consequence: `BuiltinSystems.DEPENDENCIES` records
`flexi-streams -> trivial-gray-streams`, the same edge real flexi-streams'
`.asd` declares. Both loaders honour it (`LispEvaluator.loadSystem`,
`cli.LoadInliner.spliceSystem`), because the Gray protocol must be DEFINED
before the `vector-stream` defclass runs -- the eagerly compiling backends
otherwise fail with "unknown superclass
RONTOLISP:FUNDAMENTAL-BINARY-INPUT-STREAM". Pinned by
`LispEvaluatorTest#evalFlexiStreamsInMemoryInputStreamIsARealBinaryStream` and
the two `LackEcosystem*E2eTest` classes (all four backends).

## A slot NAME is not a call operator

The compile-path pre-pass (`GrayStreamsLibrary.process`) rewrites every
stream-taking built-in call with a non-literal stream onto the
`%gray-*-dispatch` helpers, and it walks the WHOLE program to find them. A
`defclass` / `define-condition` SLOT SPEC is a list whose head is the slot name,
so a slot named after one of those built-ins used to be rewritten as if it were a
call: chipz's

```lisp
(define-condition invalid-format-error (chipz-error)
  ((format :initarg :format :reader invalid-format))
  (:report (lambda (condition stream) (format stream "Invalid format ~S" ...))))
```

came back as the format rewrite's `let`, and `defclass` rejected it with
`expects a keyword slot option, got ((__gray_fmt_stream :INITARG))`.

`rewriteBindingForm` now handles the three definers whose bodies carry slot
specs. The slot NAME and the class name / superclass list are left verbatim;
only the option VALUES are rewritten (`:initform` holds a real expression), and
`defstruct`'s positional initform is rewritten too (`rewriteSlotSpec`'s
`firstOptionIndex` is what distinguishes the two shapes). The class options AFTER
the slot list stay ordinary code -- `:report`'s lambda prints through this very
protocol and must keep rewriting. Pinned by
`JvmLispCompilerTest.grayRewriteLeavesASlotNamedAfterAStreamBuiltinAlone`.
