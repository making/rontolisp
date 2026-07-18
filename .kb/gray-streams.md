# Gray streams (rontolisp's own protocol, all backends)

rontolisp OWNS the Gray-stream protocol; third-party portability layers adapt
onto it and the core knows no third-party name.

**The protocol** (`src/main/resources/am/ik/rontolisp/eval/gray.lisp`, served by
`eval/GrayStreamsLibrary`): two base classes
(`rontolisp:fundamental-character-output-stream` / `-input-stream`) and two
generics (`rontolisp:stream-write-char (stream character)`,
`rontolisp:stream-write-string (stream string &optional start end)`), all
plain CLOS-subset definitions — so the whole protocol is backend-free
expansion output, no codegen.

**Interpreter dispatch**: `LispEvaluator` wraps the `write-string` builtin —
when the stream argument is a CLOS instance (`%class-` tagged cons) it lazy-loads
`gray.lisp` (`ensureGrayStreamsLoaded`) and applies
`rontolisp:stream-write-string` with `(stream string)`. `write-char` lowers to
`write-string` (shared `expandWriteChar`), so it dispatches too. A `defclass`
naming a Gray base class as a superclass ALSO eager-loads `gray.lisp`
(`referencesGrayBaseClass` in `evalDefclass`) — without that, a bare-protocol
user class (no `trivial-gray-streams` load) died on "unknown superclass"
while the compile path worked.

**Compile path** (`GrayStreamsLibrary.process`, the usocket `process()`
pattern): runs after `UserMacroExpander` in `RontoLispCli`, the playground
frontend and `AsdfLibraryE2eSupport`. Triggered by any protocol name in the
program (`splitQualified` member match, so `trivial-gray-streams:` spellings
count). It (1) splices `gray.lisp` unless a load already did (guard: a
`defclass` of `rontolisp:fundamental-character-output-stream` is present), and
(2) rewrites every `(write-string s STREAM)` / `(write-char c STREAM)` call
with an explicit non-literal stream (not `t`/`nil`/string literal) onto
`rontolisp::%gray-write-string-dispatch` / `-char-dispatch` — defuns at the
bottom of `gray.lisp` that test `(consp stream)` and route instances to the
generic, everything else back to the builtin. The rewrite walker skips quoted
data and the dispatch defuns' own bodies (their fallback `write-string` call
must not rewrite into itself). The compiled runtimes themselves know nothing:
the dispatch is ordinary Lisp riding the generic dispatcher defuns.

**Shim** (`trivial-gray-streams.lisp` via `ShimLibraries`/`BuiltinSystems`):
subclasses the rontolisp base classes and delegates the rontolisp generics to
`trivial-gray-streams:stream-write-char`/`-string`, so libraries written
against the portable API (jzon) run unchanged.

**format rewrite (todo-146, jzon's `(format %stream "~D" value)`)**: the
pre-pass also rewrites `(format STREAM ctrl args...)` with a possibly-instance
destination into `(let ((__gray_fmt_stream STREAM))
(%gray-write-string-dispatch (format nil ctrl args...) __gray_fmt_stream) nil)`
— render to a string, route through the write-string dispatch (whose fallback
handles handles and the `t` designator), destination bound first to keep CL's
evaluation order, nil like format-to-stream. TWO walker rules exist because the
walk has no position awareness: (1) a lambda-list keyword is never a stream arg
(`streamArgMayBeInstance` rejects `&`-prefixed symbols — jzon's `(defun %raise
(type pos format &rest args) ...)` parameter tail read as a format call), and
(2) the generic recursion rewrites list ELEMENTS, never re-checking a cdr TAIL
as a call (`rewriteTail` — `(error 'ty :format-control format
:format-arguments args)` has a tail whose car is `format`).

**Limits**: output side only (`stream-write-char`/`-string`); the input
generics of full Gray (stream-read-char, ...) do not exist. The interpreter
dispatch keys on the `%class-` tag, the compiled dispatch on `consp` — a plain
cons handed as a stream errors either way, just differently. A bounded
`(write-string s instance :start ...)` is NOT rewritten (interpreter ignores
the bounds for instances; both are edge behavior). A runtime-nil `format`
destination reached through the rewrite writes like a designator instead of
returning the string (jzon never does; scope is Gray-activated programs only).

Pinning tests: `LispEvaluatorTest#grayStreamInstanceReceivesWriteCharAndWriteString`
(shim), `#grayBaseClassSuperclassLoadsGrayStreamsEagerly` (bare protocol),
`JvmLispCompilerTest#compileAndRunGrayStreamInstanceDispatch`,
`WasmLispCompilerIntegrationTest#grayStreamInstanceDispatch`, ci-spec
`gray-stream-instance-dispatch`.
