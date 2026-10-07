# Gray streams (rontolisp's own protocol, all backends)

rontolisp OWNS the Gray-stream protocol; third-party layers adapt onto it. Plain CLOS-subset
Lisp in `src/main/resources/am/ik/rontolisp/eval/gray.lisp`, served by
`eval/GrayStreamsLibrary`; backend-free expansion output, no codegen.

## Protocol
- Classes: `rontolisp:fundamental-stream`; `-input-stream`/`-output-stream`; leaves
  `-character-input/-output-stream`, `-binary-input/-output-stream`.
- Generics: `stream-write-char`, `stream-write-string (stream string &optional start end)` (ALWAYS
  called with integer start/end, see "Bounds on `write-line` / `write-string`"),
  `stream-write-byte`; `stream-line-column`, `stream-start-line-p`, `stream-terpri`,
  `stream-fresh-line`, `stream-advance-to-column`; `stream-force-output`/`-finish-output`/
  `-clear-output`; `stream-read-byte`, `stream-read-char`, `-read-char-no-hang`,
  `-peek-char`, `-unread-char`, `-read-line`, `stream-listen`;
  `stream-read-sequence`/`-write-sequence (stream sequence start end)` (end always an integer
  by method time); `stream-file-position` + its `(setf ...)` writer.
- **ONE of `stream-write-char`/`stream-write-string` required** (wider than full Gray);
  `%gray-default-write-string`/`-write-char` express each via the other. TRAP: defining
  NEITHER is mutual recursion, not "no applicable method".
- **ONE required read method: `stream-read-char`** (`stream-read-byte` binary);
  `-read-line`/`-read-sequence` loop it, `-read-char-no-hang` IS it, `-peek-char` = read +
  `stream-unread-char`.
- `stream-unread-char` default cell: ON THE INSTANCE, the one cell every
  `fundamental-input-stream` descendant reserves past its declared slots
  (`ClosRegistry.registerClass`, capacity + 1), addressed `(%obj-ref s -1)` -- a negative index
  counts back from the end of the storage (`LispLayout.TAIL_CELL`), so it is one literal across
  class widths and stays past every slot of a `change-class` target
  (`applyChangeClassCapacities` reserves the target's CAPACITY; the interpreter's
  `becomeLayout` carries the last cell when it grows). SBCL's sb-gray has no default method.
  `%gray-read-char-1` is the ONE cell-draining entry; its `typep` guard is load-bearing: a
  dispatch helper hands it ANY instance, whose last cell may be a declared slot. TRAP:
  overriding `stream-read-line`/`-read-sequence` OUTRIGHT reads past a pushed-back char.
- Until 2026-10-06 the cell was ONE program-wide pair (`*gray-unread-stream*` /
  `*gray-unread-char*`) that overwrote unchecked: two instances each unread once lost the
  first character (`(#\b #\x #\c)` for SBCL-per-instance `(#\a #\x #\b)`), all four.
  Pinned by `GrayStreamCallFixture.PER_INSTANCE_PUSHBACK_PROGRAM` in the three backend suites.
- `stream-start-line-p` comes from `stream-line-column` (nil = no column, so `fresh-line`
  breaks unconditionally); flush trio, `stream-listen`, `stream-file-position` answer nil.
  Read generics answer `:eof`; dispatch maps it to `eof-error-p`/`eof-value`,
  `(error 'end-of-file)`. `stream-read-line` returns a partial last line, PRIMARY values only.

## Dispatch helpers
`rontolisp::%gray-*-dispatch` defuns at the bottom of gray.lisp: ONE copy of "instance ->
generic, else -> built-in" plus the `:eof` translation, shared by both seams. Covers every stream-taking built-in (read-line's eof-error-p defaults NIL, read-byte/char
default T; read/write-sequence's missing end -> `(length sequence)`).
- Resolve through `%stream-target` FIRST (synonym and OPEN streams are instances too,
  `.kb/read-load-streams.md`), so **splicing gray.lisp requires running
  `LispPreludeLibrary.process`** (`referencedBySurfaceForm`, `LibraryDefunPruner` roots).
- `%gray-close-dispatch` does NOT resolve a synonym (CLHS 21.1.3), tests `%STREAM` by tag
  ahead of `%obj-p`; predicate dispatchers pass the ORIGINAL designator to the built-in.
- `%gray-fresh-line-dispatch` answers **nil** like the handle-based `fresh-line`, not
  `stream-fresh-line`'s t/nil: an operator's value must not depend on stream kind.

## Stream-less calls on a Gray `*standard-output*` / `*standard-input*`
A nil (or omitted) stream designates the CURRENT standard stream, which may hold a Gray instance:
`(let ((*standard-input* gray)) (read-char))` is SBCL's `stream-read-char` on it. Until
2026-10-06 the dispatch decided on the argument as written -- interpreter "READ-CHAR expects an
input stream" / "not an output stream", JVM read the process stdin, P1/component trapped, and
the print family wrote PAST the instance to standard output on the compile paths.
- Helpers: every output helper resolves `(or stream *standard-output*)`, every character-read
  helper (read-char, -no-hang, peek-char, unread-char, read-line, listen) `(or stream
  *standard-input*)`, before `%stream-target` and the instance test -- so a stream argument
  that is nil at run time, and the function-value wrappers (`(funcall #'read-line)`), resolve
  too. The read helpers' fallback still gets the stream as given. An unbound read is the
  constant `t` on the compile paths, so a program that binds nothing is unaffected.
- Compile-path gate (`GrayStreamsLibrary.binds`): the program binds the variable
  (`SpecialVarCollector.collectDynamicallyBound`, the backends' activation rule) or names
  `rontolisp:make-thread` (its bindings alist). Then the stream-less / literal-nil calls of
  that family are rewritten with a nil stream, and `(format t ...)` is lowered here by
  `LispMacroExpander.expandFormat` and the princ / terpri / fresh-line it yields rewritten in
  turn (so `~&` asks the Gray stream; building the string first would lose `~&` on the real
  stdout too). A Gray program that binds a standard stream -- the ci-spec corpus does --
  sends EVERY stream-less print through the helpers.
- `--component`: a gray.lisp `%gray-*` helper is a strict call head for the await hoist
  (`WasmAwaitNormalizer.isStrictCallHead`, `LispNames.GRAY_HELPER_PREFIX`). Before, `(princ
  (rontolisp:await f) gray-var)` was refused as a non-spine await, and the stream-less rewrite
  would have refused the corpus's `(print (rontolisp:await ...))`.
- Interpreter: `LispEvaluator.resolveDesignatorArg` puts the current standard stream in the
  argument's place (padding an omitted peek-type) when it is a Gray instance;
  `evalWriteCharWithGrayDispatch` does the same for `(write-char c)`.
- `(read)` follows for free: it is written over the character reads (SBCL `((1 2) FOO :END)`
  for three reads of `"(1 2) foo"`, all four alike).
- Pinned by `GrayStreamCallFixture.STANDARD_STREAM_PROGRAM` (SBCL's answers) in the three
  suites, `GrayStreamsLibraryTest#aProgramBindingNoStandardStreamKeepsItsStreamlessCalls` /
  `#bindingAStandardStreamRoutesOnlyItsOwnFamily`,
  `WasmLispCompilerIntegrationTest#anAwaitInAGrayDispatchArgumentIsHoistedOnTheComponent`.

## Ownable operators
- OWNABLE: `close`, `open-stream-p`, `stream-element-type`
  (`GrayStreamsLibrary.OWNABLE_OPERATORS`, interpreter `wrapGrayOwnableOperator`); both seams
  stand down when the program defines one, so there is deliberately **no
  `rontolisp:stream-close` generic** (`GrayStreamsLibrary.ownsClose` /
  `closRegistry.findGeneric(CLOSE)`). With none, `close`/`open-stream-p` = `t` and
  `stream-element-type` = `character` / `(unsigned-byte 8)` by `typep` on the base classes.
  A Gray base class carries no width, so this stays the octet even though a built-in FILE stream
  now answers its real widened type (`read-load-streams.md`, "Element types wider and narrower
  than one octet"); the built-in answer is what a Gray dispatcher's non-instance arm reaches.
- **A BIVALENT class answers `character`** — the order of the two `typep`s in
  `%gray-stream-element-type-dispatch`. The answer is the buffer to allocate;
  `read-sequence`/`write-sequence` pick bytes vs. chars off the SEQUENCE (`stringp`). The
  binary answer signals on SBCL (`.kb/http-server.md`, `:raw-body`).

## Interpreter dispatch
- `LispEvaluator` wraps the built-ins: `resolveSynonymArg` (Java twin of `%stream-target`),
  then for an INSTANCE lazy-load gray.lisp (`ensureGrayStreamsLoaded`) and apply the helper
  (`applyGrayDispatch`); fallbacks re-enter the wraps, one hop, no recursion.
  `wrapGrayOutputOperator(name, streamIndex, helper)` builds the line/print/flush family.
- `read-sequence`/`write-sequence`/`write-char` are macro expansions, intercepted at
  `evalCons` (`evalSequenceWithGrayDispatch`, `evalWriteCharWithGrayDispatch`): args evaluate
  once, a non-instance re-enters the expansion with values QUOTED in place.
- A `defclass` naming a Gray base class eager-loads gray.lisp
  (`referencesGrayBaseClass`/`GRAY_BASE_CLASSES`) — else "unknown superclass".

## `warn` writes to a Gray `*error-output*`
`warn` has no stream argument for either seam to see: its report is written by `%warn` to the
CURRENT `*error-output*`, which may be a Gray instance -- mito silences a statement with
`(let ((*error-output* (make-broadcast-stream))) ...)`, and every broadcast stream is one. It
signalled "not an output stream" on the interpreter and printed PAST the instance to standard
output on both compile paths (2026-09-24). Now: the interpreter wraps `%warn`
(`LispEvaluator`, the write-line wrappers' twin) and routes an instance to
`%gray-write-line-dispatch`; on the compile paths a `warn` call site marks that dispatch used
(`GrayStreamsLibrary.rewrite`, so it is spliced), and `Jvm`/`WasmWarnCompiler` call it
(`LispNames.GRAY_WRITE_LINE_DISPATCH`) whenever the redirect is active and the program carries
it -- its fallback is the built-in write-line, so a handle or string stream is unchanged. Pins
`warnWritesToAGrayErrorOutput` in `LispEvaluatorTest` / `WasmLispCompilerIntegrationTest`,
`JvmLispCompilerTest#compileWarnWritesToAGrayErrorOutput`.

## Bounds on `write-line` / `write-string`

**A spelled `:start` / `:end` on a Gray instance is checked ONCE, before the method runs, and
the method then sees integers.** SBCL signals a `type-error` for every bad bound whatever the
stream -- a nil, negative or non-integer `:start`, a bound past the length, a start past the
end -- and passes `(stream string start end)` with `end` never nil. Measured on the previous
code, all four backends alike: a bound reached the method unchecked (a nil `:start` read as
omitted, a negative or 9-past-5 one went through), and a bounded `write-string` to an instance
on the compile paths bypassed the dispatch and wrote to standard output.

- Two helpers, apart from the unbounded `%gray-write-line-dispatch (s stream)` /
  `%gray-write-string-dispatch (s stream)`: `%gray-write-line-bounds-dispatch` and
  `%gray-write-string-bounds-dispatch (s stream start end)`. Instance arm:
  `%check-sequence-bounds` (the `write-sequence` dispatch's check, `type-error`, datum the
  bound as given -- SBCL's datum for a range is a pair, so the pins print the class alone),
  then `stream-write-string stream s start (or end (length s))`. Non-instance arm: the
  built-in with the bounds as written.
- Compile paths: `GrayStreamsLibrary.rewrite` maps `(write-line|write-string s stream
  [:start a] [:end b])` with literal keywords to the bounds helper (first spelling wins; an
  unspelled start is `0`, an unspelled end `nil`). Interpreter: the `write-line` /
  `write-string` wrappers hand `grayStreamBounds` to the same helpers, so first-class use
  on the interpreter agrees. A tail the rewrite cannot read (a non-literal keyword,
  `:allow-other-keys`) keeps the lowering.
- A call spelling NO bound passes `0` and `(length s)` too (SBCL does; measured 2026-10-06:
  `write-string`, `write-line`, `princ`, `format` all `("hello" 0 5)` on SBCL 2.2.9, `0 NIL`
  here before): the unbounded helpers (`%gray-write-string-dispatch`,
  `%gray-write-line-dispatch`, the print family, so the `format` rewrite too) and the
  interpreter's `write-string` wrapper. `%gray-default-write-char` passes `0 1`. The
  unbounded helpers keep their own name only to skip the bounds check.
- `write-sequence` of a string: the default `stream-write-sequence` hands the range to
  `stream-write-string` in ONE call (SBCL: `("hello" 1 5)`); it looped `stream-write-char`.
- **The arity break is accepted**: a method spelled `(stream string)` -- the shape the
  guide, three ci cases and a dozen backend tests used -- failed "Function expects 3
  arguments, got 5" at its first write. SBCL refuses that method at `defmethod` time
  ("fewer optional arguments than the generic function"), so no portable program has it;
  rontolisp now refuses it there too (lambda-list congruence, `.kb/clos.md`). A definition
  on a `rontolisp:` protocol generic loads gray.lisp first on the interpreter
  (`ensureGrayProtocolDeclared`), as the compile paths splice it for any program naming one,
  so the method is judged against the declared lambda list, never one it established.
- Cost (2026-10-06, 748 / 740 / 744 JVM / P1 / component artifacts of every ci-spec case,
  size-report, bench-report and the non-GUI examples, compiled by the base and the new jar):
  all byte-identical (bar the build timestamp) except the programs carrying the Gray protocol
  -- 14 examples and three ci cases -- which SHRINK, JVM -0.6..-2.4 KB, P1 -0.1..-0.6 KB (the
  unbounded write-line helper lost its `&optional`/`start-p` shape); the one program
  spelling a bounded Gray write (the pinning case) grows +0.87 KB P1 / +0.86 KB component and
  shrinks 0.5 KB on the JVM.
  Speed (min of 12, 300,000 iterations of an unbounded and a bounded write-line plus an
  unbounded write-string to a counting instance): JVM 15-25 ms before and after (noise), P1
  155 -> 195 ms -- the bounded call pays the bounds check (~130 ns).
- Pinned by `SequenceBoundsFixture.GRAY_BOUNDS_PROGRAM` (sbcl's answers) in
  `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` (P1 and
  component) and ci-spec `gray-stream-bounds-are-checked`. The ci-spec case is part of the
  corpus program, which is what carries the Gray library for every case after it.

## Function values
A function value has no call site for the rewrite. Before: on JVM / P1 / component every
stream operator taken as a value (`#'write-string`, `#'read-char`, `(apply #'write-line
...)`, ...) went PAST a Gray instance (output to standard output, reads at EOF); the
interpreter's Java wrappers already dispatched.
- `BuiltinFunctionWrappers.GRAY_WRAPPERS`: per operator, a twin of the catalog wrapper (same
  lambda list) whose body calls the dispatch helpers. `generate` picks it when EVERY helper
  it names is a defun of the program (the `HelperWrapper` idea: one fact read after the
  splice); `lambdaFor` (the interpreter) never sees it.
- `GrayStreamsLibrary.process` splices those helpers for each operator the program
  designates (`functionDesignatorNames`: `#'op` or `'op`), except an OWNED one. The helper
  names live in the wrapper table only; `dispatchDefun` throws if gray.lisp lacks one.
- Covered: write-string/write-line (the BOUNDS helpers), read/write-sequence, princ/prin1/
  print, terpri, fresh-line, force/finish/clear-output, listen, read-char(-no-hang),
  read-line, peek-char, read-byte, input/output-stream-p, stream-element-type, file-length,
  file-position, format, write-char, write-byte, unread-char.
- Pinned by `GrayStreamCallFixture.FUNCTION_VALUE_PROGRAM` (SBCL's answers) on all four,
  ci-spec `gray-stream-operators-as-function-values`, `GrayStreamsLibraryTest
  #aStreamOperatorTakenAsAValueSplicesItsDispatchHelpers`,
  `BuiltinFunctionWrapperCatalogTest#aStreamOperatorsWrapperCallsTheGrayDispatchOnlyBesideIt`.
- `write-char`, `write-byte`, `unread-char` had NO function value until 2026-10-07 (measured
  that day on all four: `#'write-char` "WRITE-CHAR is a macro or special operator, not a
  function" on the interpreter, "Cannot compile: WRITE-CHAR" on JVM/P1/component;
  `#'write-byte` "Cannot compile" on the three compile paths; `#'unread-char` signalled
  `UNREAD_CHAR_NOT_A_VALUE_MESSAGE` there; SBCL 2.2.9 answers all three). `write-char` sat in
  `PackageRegistry.CL_MACROS`, so `(macro-function 'write-char)` answered a function on all
  four (SBCL: NIL); it is a CL function now, interpreter-lowered (`ShadowedBuiltins
  .EXPANSION_LOWERED`). Catalog wrappers `(c &optional s)` / `(b s)` (write-byte
  reference-gated: the JVM drains raw stdout octets only for a source naming it), Gray
  twins over the existing `%gray-write-char/-write-byte/-unread-char-dispatch`. Pinned by
  `GrayStreamCallFixture.WRITE_AND_UNREAD_VALUE_PROGRAM` /
  `StringStreamPrograms.WRITE_AND_UNREAD_VALUE_PROGRAM` in the three suites, ci-spec
  `write-char-write-byte-and-unread-char-as-function-values`.

## Compile path: `GrayStreamsLibrary.process`
Runs after `UserMacroExpander`; triggered by any protocol name (`splitQualified` member match,
so `trivial-gray-streams:` counts; the match set is ALL-UPPERCASE).
1. Splice gray.lisp PROTOCOL forms unless a load already did (guard: a `defclass` of
   `rontolisp:fundamental-character-output-stream`).
2. Rewrite every stream-taking call with an explicit non-literal stream (not
   `t`/`nil`/string literal) onto the helpers. The stream-LESS and literal-nil spellings join
   only when the program binds that family's standard stream (see "Stream-less calls"), so a
   program naming no stream and binding none stays byte-identical.
3. **Splice ONLY the dispatch defuns the rewrites referenced** (`SPLICE_ON_USE` via
   `dispatchSymbol`; `WRITE_CHAR_DISPATCH` pulls `WRITE_STRING_DISPATCH`). Load-bearing:
   `LibraryDefunPruner` covers neither this splice nor the shim systems, and
   `%gray-listen-dispatch`'s fallback names `listen`, which Preview 1 WASM rejects at COMPILE
   time. Same reason `ShimLibraries.forms` uses `protocolForms()`.

The walker skips quoted data and gray.lisp's own defun bodies (`DISPATCH_DEFUNS`). Compiled
runtimes know nothing of this.
- **`process` OWNS where the protocol sits, even when a load already spliced it**: a form
  SUBCLASSING a Gray base class ahead of the protocol HOISTS the protocol forms to the front
  (`protocolFormsToHoist`) — the case is `HttpServerLibrary.process` prepending
  `http-server.lisp` at index 0. Re-evaluate the hoist if that stops.
- **The "is this gray.lisp's own definition" key uses `print()`, never `display()`**: DISPLAY
  drops the package prefix, colliding `trivial-gray-streams:stream-read-line` with
  `rontolisp:stream-read-line` and collapsing both onto one registry key — "No applicable
  method: TRIVIAL-GRAY-STREAMS:STREAM-READ-SEQUENCE".
- Conditional, so an unaffected program is BYTE-IDENTICAL
  (`GrayStreamsLibraryTest#programWithoutAGrayShimKeepsTheProtocolSpliceAtTheFront`).

## Shim
`trivial-gray-streams.lisp` via `ShimLibraries`/`BuiltinSystems`: each class subclasses its
trivial-gray-streams parent AND its rontolisp twin, so ONE delegating method per generic covers
every adapter subclass; plus `trivial-gray-stream-mixin` (empty) and the portable generics
(`stream-read/write-sequence` spelled `(stream sequence start end &key)`), whose DEFAULT
methods reuse the `%gray-default-*` loops. Package seeding in `PackageRegistry`; `LispNames`
`GRAY_*` constants.

## The `format` rewrite
`(format STREAM ctrl args...)` with a possibly-instance destination becomes:

```lisp
(let ((__gray_fmt_stream STREAM))          ; destination bound FIRST (CL evaluation order)
  (let ((__gray_fmt_result (format nil ctrl args...)))
    (if __gray_fmt_stream (progn (%gray-write-string-dispatch __gray_fmt_result
      __gray_fmt_stream) nil) __gray_fmt_result)))
```

**The run-time test is not optional**: nil is not a stream but format's "return the string"
destination ([standard-output-redirect.md](standard-output-redirect.md)), and the rewrite fires
over the WHOLE program once ANY part uses the protocol. Walker rules (no position awareness):
- A lambda-list keyword is never a stream arg (`streamArgMayBeInstance` rejects `&`-prefixed).
- Rewrite list ELEMENTS, never a cdr TAIL as a call (`rewriteTail`).
- **A BINDING form's structural position is not a call** (`rewriteBindingForm`): skip the
  lambda list of `lambda`/`defun`/`defmacro`/`defmethod`/`destructuring-bind` and the variable
  half of `let`/`let*`/`flet`/`labels`/`macrolet`; else a parameter named `format` fails with
  "Parameter must be a symbol".
- **A slot NAME is not a call operator**: `defclass`/`define-condition`/`defstruct` slot specs
  are headed by the slot name — slot NAME, class name and superclass list verbatim, only
  option VALUES rewritten, plus `defstruct`'s positional initform (`rewriteSlotSpec`'s
  `firstOptionIndex`). Pinned by
  `JvmLispCompilerTest.grayRewriteLeavesASlotNamedAfterAStreamBuiltinAlone`.

## Limits
- `listen` on an instance works interpreter/JVM; Preview 1 WASM rejects ANY `listen` at
  compile time.
- `input-stream-p`/`output-stream-p` = `typep` against the two DIRECTION base classes,
  ownable like `open-stream-p`, deliberately not full Gray's per-base-class generics.

## `streamp` and `(typep x 'stream)`
**`streamp` answers `t` for a Gray instance on all four backends, and the LOWERING says so —
not a dispatch helper**: `(typep x 'stream)` lowers to `(streamp x)` in
`LispMacroExpander.makeTypeTest`, LONG after `GrayStreamsLibrary.process`.
`expandStreamp(cons, synonymStreams, streamValues, closRegistry)` builds
`(let ((__s x)) (if (eq __s t) t (%obj-is __s '<tags>)))`.
- `<tags>` = the OPEN-stream layout tag `%STREAM` and the synonym-stream tag (each only when
  the program can build one), then `closRegistry.descendantTags(rontolisp:fundamental-stream)`.
  No `integerp` arm: every stream is a VALUE (`.kb/read-load-streams.md`).
- **A COMPUTED type specifier needs BOTH halves of the runtime typep machinery**
  (`.kb/clos.md`): `STREAM` in `RUNTIME_TYPEP_BUILTINS` AND a row in `%typep-tag-table%` —
  `%typep-runtime` tests `%obj-p` FIRST, so an instance never reaches the built-in name arms.
- **`ArgumentShapes.Shape.INSTANCE` had to gain `STREAM`**: the compile-path dead-branch pruner
  deletes a `typecase` clause no value of the key's shape can satisfy, so `STREAM` absent from
  that row DELETED cl+ssl's `(etypecase socket (integer ...) (stream ...))` arm.

## Handle-side pushback of `unread-char`
Nothing a runtime holds can be un-read (WASI fd, socket, `BufferedReader`), so the character
parks in a cell the character reads consult. **The cell lives ON the stream value**: the
reserved `LispLayout.STREAM_PUSHBACK_CELL` (capacity 4), as CL keeps the pushback on the stream,
so it dies with the value -- no close hook, no table to prune (the closes `with-input-from-string`
/ `with-open-file` synthesize are invisible to the call-site rewrite), and two streams each hold
one. A key that is not a stream value (`t`) shares ONE cell keyed by `eql`.
- Interpreter: Java, `eval/StreamPushback`, which `Environment.createGlobal`'s read definitions
  close over (its built-ins are FUNCTION VALUES, not rewritable call sites).
- Both compile paths: ORDINARY LISP — `unread-char.lisp`, spliced by `eval/UnreadCharLibrary`,
  which also rewrites the `read-char`/`read-char-no-hang`/`peek-char`/`read-line`/`unread-char`
  call sites onto its defuns. Trigger: the program names `unread-char`; else byte-identical.
  The cell write is `(%obj-set key 3 c)` behind `(%obj-is key '%STREAM)`, which compiles with the
  instance gate off too (`.kb/instance-syntax.md`, "The emit gate").
  **Runs LAST, over `GrayStreamsLibrary.process`'s output**, because
  `%gray-unread-char-dispatch`'s non-instance fallback IS the handle arm.

Contract, identical on all four:
- KEY = the stream the designator DENOTES: an omitted stream and nil -> the current
  `*standard-input*`, a synonym -> its target (recursively, NOT unwrapped to the handle), a nil
  left over -> `t`; else `eql`. Interpreter `StreamPushback.key` (`Environment.defaultInput` +
  `synonymTarget`), compile paths `%unread-key`. Until 2026-10-06 it was the argument AS GIVEN
  (nil folded onto `t`): `(let ((*standard-input* s)) (unread-char (read-char)))` parked under
  `t` and a later `(read-char s)` skipped it on all four (SBCL: `#\a`, all four: `#\b`), and a
  synonym keyed on itself on the compile paths only (the interpreter's Gray wrap resolves it
  first). Pinned by `StringStreamPrograms.DESIGNATOR_PUSHBACK_PROGRAM` in the three suites.
- Naming `*standard-input*` in `%unread-key` does NOT switch the input redirect on: the redirect
  activates on a BINDING (`.kb/standard-output-redirect.md`, "Activation rule"), and an unbound
  read compiles to the constant `t` (checked 2026-10-06 with `javap`: an `unread-char` program
  that binds nothing has no `*STANDARD-INPUT*` field). What it does add is the eval runtime's
  mirror seed of the variable, in an `unread-char` program that also uses `eval`.
- **gray.lisp's read-side helpers hand the fallback built-in the stream AS GIVEN** (`read-char`,
  `-no-hang`, `peek-char`, `unread-char`, `read-line`, `listen`, `file-position` and its set),
  testing `%obj-p` on the `%stream-target` result only. They handed the resolved HANDLE until
  2026-10-06, so in a Gray-using program every open-stream read keyed the pushback on an integer
  -- the shared cell: two string streams each unread once signalled on JVM, P1 and component.
  Pinned by `GrayStreamCallFixture.OPEN_STREAM_PUSHBACK_PROGRAM`.
- `read-char`/`read-char-no-hang` DRAIN it; `%peek-char` LEAVES it; `peek-char`'s skipping
  peek-types drain it exactly when the char is one to skip (`%unread-peek-stops-p` runs
  built-in `peek-char` over a one-character string input stream rather than adding a FOURTH
  whitespace-set copy). `read-line` DRAINS it and prepends it to the line.
- A second `unread-char` with THAT stream's cell full SIGNALS
  (`LispMacroExpander.UNREAD_CHAR_TWICE_MESSAGE`, shared verbatim with `unread-char.lisp` and
  `StreamPushback`). SBCL answers nil there for a string input stream (measured 2026-10-06).
- `file-position` counts a parked character as NOT consumed (sbcl): the query subtracts its
  UTF-8 length, the set drops it. Compile paths: `%unread-file-position` /
  `%unread-file-position-set`, spliced only when the program also names `file-position` --
  their bodies name it, and the backends gate their position runtime on that name, so
  splicing them into every `unread-char` program would grow each by a runtime it never
  calls. Interpreter: the outermost `file-position` wrapper in `Environment`.
- `read-byte`, `read-sequence`, `read` do NOT consult it on any backend: their loops are
  generated inside the expression compilers, after this pass could walk them. The one such
  expansion the pass DOES reach is an indexed `with-input-from-string`: it expands it itself
  (`LispMacroExpander.isIndexedWithInputFromString`), so the `:index` store's `file-position`
  becomes `%unread-file-position` (`.kb/read-load-streams.md`, "String streams").
- **A reserved cell is no part of `equal` or the `equal` hash** on any backend: the JVM
  (`JvmNumericRuntimeBuilder.emitInstanceEqual`, `JvmHashRuntimeBuilder`) and WASM
  (`WasmRuntimeBuilder.pushLayoutSlotCount`) loops are bounded by the LAYOUT's slot count, not
  the storage length. They looped the storage until 2026-10-06, so a stream value (or Gray
  instance) keyed in an `equal` hash table was lost once a character was parked on it -- JVM,
  P1 and component (`:FOUND` on SBCL and the interpreter). Pinned by the last form of
  `StringStreamPrograms.PER_STREAM_PUSHBACK_PROGRAM` and the Gray fixture.
- **Until 2026-10-06 the cell was ONE slot for the whole program**: a stream closed (or dropped)
  with a parked character made every later `unread-char` on ANY stream signal, and two streams
  could not hold one each -- all four (SBCL: fine). Pinned now by
  `StringStreamPrograms.PER_STREAM_PUSHBACK_PROGRAM` in the three backend suites.
- A parked character survives `close` on its value: a read of the closed stream answers it
  where SBCL signals (unmeasured edge, all four alike).
- **Function values: `BuiltinFunctionWrappers.PUSHBACK_WRAPPERS`**, the `GRAY_WRAPPERS`
  idea on this splice: where the program carries the defuns (it names `unread-char`; the
  file-position pair also needs `file-position` named), `#'unread-char`, `#'read-char`,
  `#'read-char-no-hang`, `#'peek-char`, `#'read-line` (the catalog's lite eof shape),
  `#'listen` and `#'file-position` call them, so a value parks and drains like a call.
  Until 2026-10-07 `#'unread-char` signalled and the read family taken as values read PAST a
  parked character (then the next `unread-char` signalled "without an intervening
  READ-CHAR"), JVM/P1/component. A Gray twin wins over a pushback twin: its handle fallback
  is a call site this pass already rewrote. The catalog `#'unread-char` still signals
  (`LispMacroExpander.UNREAD_CHAR_NOT_A_VALUE_MESSAGE`), reachable only through a symbol
  built at run time. Callers: cl-json's decoder, local-time's parser, chunga's
  `unread-char*`.

## `make-broadcast-stream` is a Gray stream
Prelude Lisp (`LispPreludeLibrary.MAKE_BROADCAST_STREAM`) defines a
`rontolisp:fundamental-character-output-stream` subclass looping the components. **Every
broadcast stream is that class, with components or without**: a component-less
`(make-broadcast-stream)` is the same class over an empty list (not the discarding
sink), so the file queries answer for it. The class carries a `stream-fresh-line`
method answering the last component (nil with none).
**So exactly the operators that dispatch on one work
with it.**

## The composite-stream constructors are Gray streams too
`make-two-way-stream` / `make-echo-stream` / `make-concatenated-stream` (`.todo/387`,
`.kb/read-load-streams.md`) are the same pattern on the INPUT half: prelude Lisp
(`LispPreludeLibrary.MAKE_TWO_WAY_STREAM` / `MAKE_ECHO_STREAM` /
`MAKE_CONCATENATED_STREAM`) defining Gray classes over the components. A two-way stream
subclasses both `fundamental-character-input-stream` and `fundamental-character-output-stream`
(slotted `in`/`out`); an echo stream is its OWN binary-input+output class (NOT a subclass of
`%two-way-stream` -- each prelude entry loads standalone on the interpreter, so an entry must
not need another entry's defclass already evaluated, and the echo entry re-declares its own
slots and readers `%echo-input`/`%echo-output`); a concatenated stream subclasses
`fundamental-character-input-stream` (slotted `streams`).

The METHODS call the BUILT-INS (`read-char` / `write-char` / `write-string`), exactly like the
broadcast defun, so a component that is a stream HANDLE works and the `.kb/gray-streams.md`
compile-path rewrite -- which runs AFTER this splice -- redirects each call site onto the
dispatch helpers, so a component that is itself a Gray instance dispatches. The `:eof` answer
comes from the read built-in's eof-value, which the read dispatch translates back into the
eof contract. Selection keys on ANY of the cluster's surface names (constructor or accessors,
`referencedBySurfaceForm`), so a program that only receives the stream from a library still
splices the whole entry.

## flexi-streams
`flexi-streams.lisp` is a lite shim except for these REAL Gray classes:
- `flexi-streams:vector-stream` (`flex:make-in-memory-input-stream`) — a
  `rontolisp:fundamental-binary-input-stream` subclass, slots `vec`/`index`/`end`. CLASS
  external (http-body spells `(typep s 'flex:vector-stream)`), the three accessors INTERNAL
  (`flex::vector-stream-vector`). Its REAL `stream-file-position` pair lets circular-streams
  rewind. `FLEX` nickname in `PackageRegistry.BUILTIN_NICKNAMES`.
- WRITE half (`.kb/cffi.md`): `flexi-streams::vector-output-stream`,
  `make-in-memory-output-stream`, `get-output-stream-sequence` (RESETS the stream, answers a
  PACKED `(unsigned-byte 8)` array). Deliberately NOT a `vector-stream` — that class is
  http-body's no-copy INPUT path.
- `flexi-streams:flexi-stream` (`flex:make-flexi-stream`) — subclasses all four base classes
  (bivalent), slots `stream`/`external-format`/`element-type`/`position`/`bound` with EXTERNAL
  `flexi-stream-*` readers (cl+ssl spells `flexi-streams:flexi-stream-stream` single-colon and
  specializes on the class). **Reads/writes OCTETS on the wrapped stream**, which must be
  binary-capable: `stream-read-char`/`-write-char` are a UTF-8 codec over
  `read-byte`/`write-byte`; UTF-8 is the only external format. `:bound` honoured, `:position`
  seeds the octet counter, `:column` ignored.
- **Deliberately no `close` method**: the stand-down is PROGRAM-wide, so one
  `(defmethod close ...)` in a shim every lack/clack program loads would disable the Gray
  `close` rewrite for every class.
- `BuiltinSystems.DEPENDENCIES` records `flexi-streams -> trivial-gray-streams`; both loaders
  honour it (`LispEvaluator.loadSystem`, `cli.LoadInliner.spliceSystem`) because the protocol
  must be DEFINED before the `vector-stream` defclass runs. Pinned by the two
  `LackEcosystem*E2eTest` classes.

## Tests
- `LispEvaluatorTest#gray*` (15 cases: instance dispatch, eager base-class load, binary
  round trip + file-position, read-line/sequence defaults, peek/unread/no-hang, the
  unread-char method owning the pushback, the default parking on its instance, direction
  predicates, shim mixin + setf file-position, `grayStreamInstanceIsAStream`) and
  `#unreadChar*`, `#evalFlexiStream*`.
- `JvmLispCompilerTest#compileAndRunGray*` (10) + `#compileAndRunUnreadChar*`,
  `#grayRewriteLeavesASlotNamedAfterAStreamBuiltinAlone`.
- `WasmLispCompilerIntegrationTest#gray*` (8) + `#unreadChar*`.
- `GrayStreamsLibraryTest#programWithoutAGrayShimKeepsTheProtocolSpliceAtTheFront`;
  `FastIoCircularStreamsE2eTest`, the two `LackEcosystem*E2eTest` classes.
- ci-spec: `gray-stream-instance-dispatch`, `gray-stream-binary-round-trip-and-file-position`,
  `gray-stream-input-protocol-widening`, `gray-stream-is-a-stream`,
  `gray-stream-unbounded-write-passes-integer-bounds` (`GrayStreamCallFixture
  .UNBOUNDED_WRITE_PROGRAM` in the three backend suites), `gray-stream-operators-as-function-values`.

## The composite stream classes as CL type names, and the zero-component broadcast stream (2026-09-23, `.todo/927`)

**`broadcast-stream`, `two-way-stream`, `echo-stream` and `concatenated-stream` are CL
type names over the prelude Gray classes** (`PackageRegistry.CL_TYPES` +
`LispMacroExpander.makeTypeTest`): the test is the class tag (`%class-%<NAME>`), exact
on all four backends like `synonym-stream`'s. A Lisp source spells the tag
bar-quoted (`'|%class-%BROADCAST-STREAM|`): the tag's `%class-` prefix is lowercase
and the reader would upcase it.

**A broadcast stream answers the file queries for its LAST component, or for an
empty list when it has none** (length 0, position 0, string-length 1,
external-format `:default`): `file-length` rides a new Gray dispatch
(`%gray-broadcast-file-length`, recursive so a nested broadcast works, nil for a
non-broadcast instance the same answer the built-in gives one);
`file-position`'s existing dispatch grew the same arm; `file-string-length` and
`stream-external-format` -- prelude defuns, so one change covers every backend --
test the tag inline.

- A zero-component `(make-broadcast-stream)` program now carries the Gray
  broadcast entry: JVM `.class` 5,896 -> 12,717 B (+6.8 KB); with a write,
  9,816 -> 42,135 B (the protocol's first-use cost, at parity with a
  one-component broadcast at 42,179 B). Anything else is byte-identical.
- `#'make-broadcast-stream` is the same Gray broadcast stream (2026-09-27): the catalog
  wrapper is `(&rest c) (%make-broadcast-stream c)` on every backend (the interpreter's Java
  sink built-in is gone). The prelude selects the entry on any mention of the name, `#'`
  included, and the compile paths inject that wrapper exactly where the entry is in the
  program (`BuiltinFunctionWrappers.HELPER_WRAPPERS`); elsewhere -- reachable only through
  `eval` -- the value is still the discarding sink. `(print (typep (funcall
  #'make-broadcast-stream) 'broadcast-stream))`: JVM 13,344 -> 15,102 B, wasm P1 3,523 -> 3,515.
- **ANSI `streams`** (interpreter, suite `ca06bd9`, names diffed): 13 fixed, 0
  regressed -- `BROADCAST-STREAM-STREAMS.1/.3/.4`, `MAKE-BROADCAST-STREAM.1/.2/.3`
  (whose element-type asserts behind the typep ones pass too), `.5/.7/.8`,
  `MAKE-TWO-WAY-STREAM.1`, `MAKE-CONCATENATED-STREAM.6`, `MAKE-ECHO-STREAM.12`,
  `FILE-LENGTH.ERROR.8` (a broadcast of a file answers the file's length).
  `WRITE-LINE.4-.10` still fail on the `#.` expectation harness gap.
- Pinned by `LispEvaluatorTest#theCompositeStreamTypeNamesResolve`,
  `JvmLispCompilerTest#compileAndRunCompositeStreamTypeNames`,
  `WasmLispCompilerIntegrationTest#compositeStreamClassesAreTypeNames`, ci-spec
  `composite-stream-classes-are-type-names`.
