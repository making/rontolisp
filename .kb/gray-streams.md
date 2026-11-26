# Gray streams (rontolisp's own protocol, all backends)

rontolisp OWNS the Gray-stream protocol; third-party layers adapt onto it. Plain CLOS-subset
Lisp in `src/main/resources/am/ik/rontolisp/eval/gray.lisp`, served by
`eval/GrayStreamsLibrary`; backend-free expansion output, no codegen.

## Protocol
- Classes as sb-gray: `rontolisp:fundamental-stream`, the direction and element classes, and the
  four leaves (one direction + one element class each). The trivial-gray-streams shim mirrors all
  nine. Pin: `GrayStreamCallFixture.FUNCTION_VALUE_PROGRAM` (`gfv-bits`, `subtypep`) in the three
  suites.
- `stream-write-string (stream string &optional start end)` is ALWAYS called with integer
  start/end (see "Bounds on `write-line` / `write-string`"); `stream-read-sequence`/
  `-write-sequence` get an integer end by method time.
- **ONE of `stream-write-char`/`stream-write-string` required** (wider than full Gray); the
  `%gray-default-*` methods express each via the other. TRAP: defining NEITHER is mutual
  recursion, not "no applicable method".
- **ONE required read method: `stream-read-char`** (`stream-read-byte` binary);
  `-read-line`/`-read-sequence` loop it, `-read-char-no-hang` IS it, `-peek-char` = read +
  `stream-unread-char`.
- `stream-unread-char` default cell: ON THE INSTANCE, the one cell every
  `fundamental-input-stream` descendant reserves past its declared slots
  (`ClosRegistry.registerClass`, capacity + 1), addressed `(%obj-ref s -1)` -- a negative index
  counts back from the end (`LispLayout.TAIL_CELL`), so it is one literal across class widths and
  survives `change-class` (the target's CAPACITY is reserved). SBCL's sb-gray has no default method.
  `%gray-read-char-1` is the ONE cell-draining entry; its `typep` guard is load-bearing: a dispatch
  helper hands it ANY instance, whose last cell may be a declared slot. TRAP: overriding
  `stream-read-line`/`-read-sequence` OUTRIGHT reads past a pushed-back char. Per-instance (two
  instances each unread once keep both): `GrayStreamCallFixture.PER_INSTANCE_PUSHBACK_PROGRAM`.
- `stream-start-line-p` comes from `stream-line-column` (nil = no column, so `fresh-line`
  breaks unconditionally); flush trio, `stream-listen`, `stream-file-position` answer nil.
  Read generics answer `:eof`; dispatch maps it to `eof-error-p`/`eof-value`. A program's own
  `stream-read-line` answering `("" t)` is end of file too ([[multiple-values]], "read-line's
  missing-newline-p").

## Dispatch helpers
`rontolisp::%gray-*-dispatch` defuns at the bottom of gray.lisp: ONE copy of "instance ->
generic, else -> built-in" plus the `:eof` translation, shared by both seams.
- Resolve through `%stream-target` FIRST (synonym and OPEN streams are instances too,
  `.kb/read-load-streams.md`), so **splicing gray.lisp requires running
  `LispPreludeLibrary.process`**. A prelude ENTRY that is a Gray stream (the composite streams,
  `%broadcast-stream`) brings gray.lisp too, so the prelude's fixpoint must select
  `%stream-target` (and `%check-sequence-bounds`) for a PULLED entry, not only for the user
  program -- else `(make-two-way-stream *standard-input* *standard-output*)` alone compiles
  `%STREAM-TARGET is undefined`. Pinned by `CompositeStreampFixture.STANDARD_STREAMS_PROGRAM` on
  all four backends.
- `%gray-close-dispatch` does NOT resolve a synonym (CLHS 21.1.3) and tests `%STREAM` by tag ahead
  of `%obj-p`; predicate dispatchers pass the ORIGINAL designator to the built-in. An instance that
  is no stream is `close`'s and `open-stream-p`'s `type-error`. ci-spec
  `close-of-an-instance-that-is-no-stream`.
- `%gray-fresh-line-dispatch` answers what `stream-fresh-line` answers (SBCL): t when it wrote.

## `clear-input`
The one operator that reaches the protocol through its DEFINITION: a prelude defun on every
backend, so a call and a function value reach one function and there is no call site or
`GRAY_WRAPPERS` entry to route. gray.lisp defines a whole `clear-input`;
`GrayStreamsLibrary.takeOverClearInput` swaps the prelude's form for it, recognising that form by
the lambda list both spell (`(&optional %ci-s)`), so a program's own `defun` stays. It cannot ask
`LispPreludeLibrary` (a class cycle, `PackageCycleTest`). Keep the two lambda lists equal
(`GrayStreamsLibraryTest#aProtocolProgramReplacesThePreludesClearInputOnly`). The prelude's copy
stays protocol-free, so a program naming no Gray stream carries none.
- Answers nil. Default method on `fundamental-character-input-stream` only, as sb-gray (a binary or
  output-only Gray stream has no applicable method; SBCL errors too). `%broadcast-stream` answers
  nil; two-way, echo and concatenated forward to their input side, as SBCL's do.
- Pinned by ci-spec `gray-stream-input-protocol-widening`; `FastIoCircularStreamsE2eTest`.

## Stream-less calls on a Gray `*standard-output*` / `*standard-input*`
A nil (or omitted) stream designates the CURRENT standard stream, which may hold a Gray instance:
`(let ((*standard-input* gray)) (read-char))` is SBCL's `stream-read-char` on it.
- Every output helper resolves `(or stream *standard-output*)`, every character-read helper
  `(or stream *standard-input*)`, before `%stream-target` -- so a stream argument nil at run time
  and the function-value wrappers resolve too. Every helper's fallback gets the stream AS GIVEN (a
  built-in refuses a bare integer, `.kb/error-handling.md`, "A stream operator's argument is a
  stream"). An unbound read is the constant `t` on the compile paths.
- Compile-path gate (`GrayStreamsLibrary.binds`): the program binds the variable
  (`SpecialVarCollector.collectDynamicallyBound`) or names `rontolisp:make-thread`. Then that
  family's stream-less / literal-nil calls are rewritten, and `(format t ...)` is lowered here by
  `LispMacroExpander.expandFormat` with the princ/terpri/fresh-line it yields rewritten in turn (so
  `~&` asks the Gray stream).
- That lowering runs BEFORE `PackageResolver`, inside the caller's package: every name the
  expansion emits must be `cl`-owned (`PackageRegistry.CL_INTERNALS`), or it resolves into the user
  package (clack's `clackup` got `CLACK::%PRINC-PIECE`). Pinned by
  `GrayStreamCallFixture.STANDARD_STREAM_IN_A_PACKAGE_PROGRAM` (all four) and
  `LispMacroExpanderTest.aFormatExpansionNamesOnlyOperatorsClOwns`.
- `--component`: a `%gray-*` helper is a strict call head for the await hoist
  (`WasmAwaitNormalizer.isStrictCallHead`, `LispNames.GRAY_HELPER_PREFIX`), else `(princ
  (rontolisp:await f) gray-var)` is a non-spine await.
- Interpreter: `LispEvaluator.resolveDesignatorArg` substitutes the current standard stream when it
  is a Gray instance; `evalWriteCharWithGrayDispatch` for `(write-char c)`. `(read)` follows for
  free.
- Pinned by `GrayStreamCallFixture.STANDARD_STREAM_PROGRAM` (SBCL's answers) in the three suites,
  `GrayStreamsLibraryTest#aProgramBindingNoStandardStreamKeepsItsStreamlessCalls` /
  `#bindingAStandardStreamRoutesOnlyItsOwnFamily`,
  `WasmLispCompilerIntegrationTest#anAwaitInAGrayDispatchArgumentIsHoistedOnTheComponent`.

## Ownable operators
- OWNABLE: `close`, `open-stream-p`, `stream-element-type` (`GrayStreamsLibrary.OWNABLE_OPERATORS`);
  both seams stand down when the program defines one, so there is deliberately **no
  `rontolisp:stream-close` generic**. With none, `close`/`open-stream-p` = `t` and
  `stream-element-type` by `typep` on the base classes.
- **An OWNED operator keeps the Gray default as a method on `rontolisp:fundamental-stream`**
  (SBCL's shape; `GrayStreamsLibrary.OWNABLE_DEFAULT_METHODS`) -- else owning `close` for ONE class
  sends every other Gray instance to the built-in (chunga's `close` broke drakma's
  `flex:with-input-from-sequence`). Compile path: spliced behind the protocol's last form (a
  defmethod ahead of its specializer's defclass is a compile error). Interpreter:
  `LispEvaluator.installGrayOwnableDefault`, from both `defineDispatcher` and
  `ensureGrayStreamsLoaded`, since either can come first. Pins:
  `LispEvaluatorTest#grayCloseStandsDownForAProgramThatDefinesACloseMethod`,
  `JvmLispCompilerTest#compileAndRunGrayCloseStandsDownForAProgramThatMethodsClose`, ci-spec
  `defmethod-on-a-builtin-name-keeps-the-builtin`, `FastIoCircularStreamsE2eTest`.
  A Gray base class carries no width, so the binary answer stays the octet even though a built-in
  FILE stream answers its real type (`read-load-streams.md`, "Element types wider and narrower than
  one octet").
- **A BIVALENT class answers `character`** (the `typep` order on the ELEMENT classes). The answer is
  the buffer to allocate; `read-sequence`/`write-sequence` pick bytes vs. chars off the SEQUENCE.
  The binary answer signals on SBCL (`.kb/http-server.md`, `:raw-body`).

## Interpreter dispatch
- An instance that is no stream never reaches a helper: a stream operator refuses it first, on
  every backend (`.kb/error-handling.md`, "A stream operator's argument is a stream").
- `LispEvaluator` wraps the built-ins: `resolveSynonymArg` (Java twin of `%stream-target`), then
  for an INSTANCE lazy-load gray.lisp and apply the helper; fallbacks re-enter the wraps, one hop.
  `read-sequence`/`write-sequence`/`write-char` are macro expansions, intercepted at `evalCons`:
  args evaluate once, a non-instance re-enters the expansion with values QUOTED in place.
- **A class lookup of a Gray base class name loads gray.lisp**: `ClosRegistry.classMissLoader`,
  consulted by `findClass` on a miss (`GrayStreamsLibrary.namesBaseClass`, by MEMBER, so an
  unqualified `fundamental-stream` resolves as on the compile paths). One seam for every use of the
  name: `find-class`, `subtypep`/`typep` specifiers, `make-instance`, a `defclass` superclass, a
  method specializer -- a name used as DATA first otherwise answers NIL / "unknown class". A stream
  test is no trigger: `streamInstanceTags` reads `descendantTags`. Pin:
  `GrayBaseClassNamedFirstFixture` (one program per first use).

## `warn` writes to a Gray `*error-output*`
`warn` has no stream argument for either seam to see: `%warn` writes to the CURRENT
`*error-output*`, which may be a Gray instance (mito silences with `(make-broadcast-stream)`, and
every broadcast stream is one). Interpreter: wraps `%warn` and routes an instance to
`%gray-write-line-dispatch`; compile paths: a `warn` call site marks that dispatch used and
`Jvm`/`WasmWarnCompiler` call it (`LispNames.GRAY_WRITE_LINE_DISPATCH`) whenever the redirect is
active and the program carries it. Pins `warnWritesToAGrayErrorOutput` in `LispEvaluatorTest` /
`WasmLispCompilerIntegrationTest`, `JvmLispCompilerTest#compileWarnWritesToAGrayErrorOutput`.

## Bounds on `write-line` / `write-string`

**A spelled `:start` / `:end` on a Gray instance is checked ONCE, before the method runs, and
the method then sees integers.** SBCL signals a `type-error` for every bad bound whatever the
stream and passes `(stream string start end)` with `end` never nil.

- `%gray-write-line-bounds-dispatch` / `%gray-write-string-bounds-dispatch (s stream start end)`:
  instance arm `%check-sequence-bounds` (datum the bound as given -- SBCL's datum for a range is a
  pair, so the pins print the class alone), then `stream-write-string stream s start (or end
  (length s))`; non-instance arm the built-in with the bounds as written.
- Compile paths: `GrayStreamsLibrary.rewrite` maps literal-keyword calls to the bounds helper
  (first spelling wins); a tail it cannot read (non-literal keyword, `:allow-other-keys`) keeps the
  lowering. Interpreter: the wrappers hand `grayStreamBounds` to the same helpers.
- A call spelling NO bound passes `0` and `(length s)` too (SBCL 2.2.9: `write-string`,
  `write-line`, `princ`, `format` all `("hello" 0 5)`); `%gray-default-write-char` passes `0 1`.
  The unbounded helpers keep their own name only to skip the check.
- `write-sequence` of a string: the default `stream-write-sequence` hands the range to
  `stream-write-string` in ONE call (SBCL: `("hello" 1 5)`).
- **The arity break is accepted**: a method spelled `(stream string)` fails at its first write.
  SBCL refuses it at `defmethod` time, so no portable program has it; rontolisp refuses it there
  too (lambda-list congruence, `.kb/clos.md`). A definition on a `rontolisp:` protocol generic
  loads gray.lisp first on the interpreter (`ensureGrayProtocolDeclared`), so the method is judged
  against the declared lambda list.
- Pinned by `SequenceBoundsFixture.GRAY_BOUNDS_PROGRAM` (SBCL's answers) in `LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` (P1 and component) and ci-spec
  `gray-stream-bounds-are-checked`.

## Function values
A function value has no call site for the rewrite, so without this every stream operator taken
as a value went PAST a Gray instance on the compile paths.
- `BuiltinFunctionWrappers.GRAY_WRAPPERS`: per operator, a twin of the catalog wrapper whose body
  calls the dispatch helpers. `generate` picks it when EVERY helper it names is a defun of the
  program (one fact read after the splice); `lambdaFor` (the interpreter) never sees it.
- `GrayStreamsLibrary.process` splices those helpers for each operator the program designates
  (`#'op` or `'op`), except an OWNED one. Helper names live in the wrapper table only;
  `dispatchDefun` throws if gray.lisp lacks one.
- `write-char`, `write-byte`, `unread-char` are CL functions with function values (SBCL answers
  all three; `(macro-function 'write-char)` is NIL). `write-char` is interpreter-lowered
  (`ShadowedBuiltins.EXPANSION_LOWERED`); `write-byte`'s wrapper is reference-gated (the JVM drains
  raw stdout octets only for a source naming it).
- Pinned by `GrayStreamCallFixture.FUNCTION_VALUE_PROGRAM` on all four, ci-spec
  `gray-stream-operators-as-function-values`,
  `GrayStreamsLibraryTest#aStreamOperatorTakenAsAValueSplicesItsDispatchHelpers`,
  `BuiltinFunctionWrapperCatalogTest#aStreamOperatorsWrapperCallsTheGrayDispatchOnlyBesideIt`,
  `GrayStreamCallFixture.WRITE_AND_UNREAD_VALUE_PROGRAM` /
  `StringStreamPrograms.WRITE_AND_UNREAD_VALUE_PROGRAM`, ci-spec
  `write-char-write-byte-and-unread-char-as-function-values`.

## Compile path: `GrayStreamsLibrary.process`
Runs after `UserMacroExpander`; triggered by any protocol name (`splitQualified` member match, so
`trivial-gray-streams:` counts; the match set is ALL-UPPERCASE).
1. Splice gray.lisp PROTOCOL forms unless a load already did.
2. Rewrite every stream-taking call with an explicit non-literal stream onto the helpers;
   stream-less / literal-nil spellings only under the standard-stream gate above, so a program
   naming no stream and binding none stays byte-identical.
3. **Splice ONLY the dispatch defuns the rewrites referenced** (`SPLICE_ON_USE`). Load-bearing:
   `LibraryDefunPruner` covers neither this splice nor the shim systems, and
   `%gray-listen-dispatch`'s fallback names `listen`, which Preview 1 WASM rejects at COMPILE time.
   Same reason `ShimLibraries.forms` uses `protocolForms()`.

The walker skips quoted data and gray.lisp's own defun bodies.
- **`process` OWNS where the protocol sits, even when a load already spliced it**: a form
  SUBCLASSING a Gray base class ahead of the protocol HOISTS the protocol forms to the front
  (`protocolFormsToHoist`) -- the case is `HttpServerLibrary.process` prepending `http-server.lisp`
  at index 0. Re-evaluate the hoist if that stops.
- **The "is this gray.lisp's own definition" key uses `print()`, never `display()`**: DISPLAY drops
  the package prefix, collapsing `trivial-gray-streams:stream-read-line` and
  `rontolisp:stream-read-line` onto one key.
- Unaffected programs are BYTE-IDENTICAL
  (`GrayStreamsLibraryTest#programWithoutAGrayShimKeepsTheProtocolSpliceAtTheFront`).

## Shim
`trivial-gray-streams.lisp` via `ShimLibraries`/`BuiltinSystems`: each class subclasses its
trivial-gray-streams parent AND its rontolisp twin, so ONE delegating method per generic covers
every adapter subclass; plus `trivial-gray-stream-mixin` and the portable generics
(`stream-read/write-sequence` spelled `(stream sequence start end &key)`). The export list equals
the real package's (trivial-gray-streams 20241012;
`PackageResolverTest#shimPackagesExportEveryExternalOfTheRealLibrary`).

## The `format` rewrite
`(format STREAM ctrl args...)` with a possibly-instance destination becomes:

```lisp
(let ((__gray_fmt_stream STREAM))          ; destination bound FIRST (CL evaluation order)
  (let ((__gray_fmt_result (format nil ctrl args...)))
    (if __gray_fmt_stream (progn (%gray-write-string-dispatch __gray_fmt_result
      __gray_fmt_stream) nil) __gray_fmt_result)))
```

**The run-time test is not optional**: nil is format's "return the string" destination
([standard-output-redirect.md](standard-output-redirect.md)), and the rewrite fires over the WHOLE
program once ANY part uses the protocol. The walk is `compiler.CallSiteWalk`, shared with
`ShadowedBuiltins` and `WasmSocketsRewrite` ([lisp2-namespaces.md](lisp2-namespaces.md), "A lexical
variable named like a function"):
- A lambda-list keyword is never a stream arg; rewrite list ELEMENTS, never a cdr TAIL as a call.
- **A BINDING form's structural position is not a call** (lambda list, binding variable, `case`
  key): else a parameter named `format` fails and `(dolist (read-line l) ...)` breaks.
- **A slot NAME is not a call operator**: slot specs keep name, class name and superclass list
  verbatim; only option VALUES (and `defstruct`'s positional initform) are rewritten.
  `JvmLispCompilerTest.grayRewriteLeavesASlotNamedAfterAStreamBuiltinAlone`.
- **The operators the rewrite writes are the reader's** (`ReaderSymbols`: `let`, `let*`, `if`,
  `progn`, `format`): the backends' resolver reads the output again in the form's package, where
  fset's `let` is new-let's. ci-spec
  `format-to-a-string-with-a-fill-pointer-beside-the-gray-dispatch`.

## Limits
- `listen` on an instance works interpreter/JVM; Preview 1 WASM rejects ANY `listen` at compile
  time.
- `input-stream-p`/`output-stream-p` = `typep` against the two DIRECTION base classes, ownable,
  deliberately not full Gray's per-base-class generics.

## `streamp` and `(typep x 'stream)`
**`streamp` answers `t` for a Gray instance on all four backends, and the LOWERING says so -- not a
dispatch helper**: `(typep x 'stream)` lowers to `(streamp x)` in `LispMacroExpander.makeTypeTest`,
long after `GrayStreamsLibrary.process`; `expandStreamp` tests `%obj-is` against the `%STREAM` tag,
the synonym tag (each only when the program can build one) and
`closRegistry.descendantTags(rontolisp:fundamental-stream)`. No `integerp` arm: every stream is a
VALUE (`.kb/read-load-streams.md`).
- **The interpreter does NOT lower the call form**: a lowering bakes the tags at expansion, before
  the argument runs, and the argument may be what loads the class (the composite prelude classes
  load on the constructor's first CALL). Pin: `CompositeStreampFixture`.
- **A COMPUTED type specifier needs BOTH halves of the runtime typep machinery** (`.kb/clos.md`):
  `STREAM` in `RUNTIME_TYPEP_BUILTINS` AND a row in `%typep-tag-table%` -- `%typep-runtime` tests
  `%obj-p` FIRST.
- **The stream SUBTYPE names answer computed as they do literally** (`RUNTIME_TYPEP_STREAM_NAMES`,
  kept out of `RUNTIME_TYPEP_BUILTINS`: a non-instance arm would be dead code, and that list seeds
  the `subtypep` universe). `file-stream`/`string-stream` share the `%STREAM` tag, so their `KIND`
  test is the table scan's RESULT form (`streamKindFallback`). Gated on the program SPELLING the
  name; `get-output-stream-string`'s expansion spells `STRING-STREAM`. Pin:
  `ComputedStreamTypepFixture`.
- **`type-of`/`class-of` name a built-in stream by its STANDARD class, and `subtypep` places the
  subtypes below `stream`**. The ONE dispatch is `%class-designator` (interpreter
  `ClosRegistry.streamClassName`, compile paths `streamClassDesignator`), each arm gated on the
  program building that stream. The names are in `BUILTIN_CLASS_NAMES` (`class-of` of a stream is
  `eq` to `find-class` of the name -- SBCL answers an implementation subclass).
  `SUBTYPEP_PARENTS` follows the ANSI CPLs: echo is NOT below two-way, unlike SBCL. Pin:
  `StreamTypeLatticeFixture`.
- **`ArgumentShapes.Shape.INSTANCE` must include `STREAM`**: the dead-branch pruner deletes a
  `typecase` clause no value of the key's shape can satisfy (it deleted cl+ssl's `(stream ...)`
  arm).

## Handle-side pushback of `unread-char`
Nothing a runtime holds can be un-read (WASI fd, socket, `BufferedReader`), so the character
parks in a cell the character reads consult. **The cell lives ON the stream value**: the reserved
`LispLayout.STREAM_PUSHBACK_CELL`, as CL keeps the pushback on the stream, so it dies with the value
-- no close hook, no table to prune (the closes `with-input-from-string` / `with-open-file`
synthesize are invisible to the rewrite), and two streams each hold one. A key that is not a
stream value (`t`) shares ONE cell.
- Interpreter: `eval/StreamPushback`, which `Environment`'s read definitions close over (its
  built-ins are FUNCTION VALUES, not rewritable call sites).
- Both compile paths: ordinary Lisp, `unread-char.lisp` spliced by `eval/UnreadCharLibrary`, which
  rewrites the character-read call sites onto its defuns when the program names `unread-char`
  (else byte-identical). The cell write compiles with the instance gate off
  (`.kb/instance-syntax.md`, "The emit gate"). **Runs LAST, over `GrayStreamsLibrary.process`'s
  output**, because `%gray-unread-char-dispatch`'s non-instance fallback IS the handle arm.

Contract, identical on all four:
- KEY = the stream the designator DENOTES: omitted/nil -> the current `*standard-input*`, a synonym
  -> its target (NOT unwrapped to the handle), a leftover nil -> `t`. Pinned by
  `StringStreamPrograms.DESIGNATOR_PUSHBACK_PROGRAM`.
- Naming `*standard-input*` in `%unread-key` does NOT switch the input redirect on: it activates on
  a BINDING (`.kb/standard-output-redirect.md`, "Activation rule").
- **gray.lisp's helpers hand the fallback built-in the stream AS GIVEN**, testing `%obj-p` on the
  `%stream-target` result only -- handing the resolved HANDLE keys the pushback on the shared
  cell. `GrayStreamCallFixture.OPEN_STREAM_PUSHBACK_PROGRAM`.
- `read-char`/`read-char-no-hang` DRAIN it; `%peek-char` LEAVES it; `peek-char`'s skipping
  peek-types drain it exactly when the char is one to skip (`%unread-peek-stops-p` runs built-in
  `peek-char` over a one-character string stream rather than a FOURTH whitespace-set copy).
  `read-line` drains it and prepends it.
- A second `unread-char` with the cell full SIGNALS (`LispMacroExpander.UNREAD_CHAR_TWICE_MESSAGE`,
  shared verbatim with `unread-char.lisp` and `StreamPushback`); SBCL answers nil for a string
  input stream.
- `file-position` counts a parked character as NOT consumed (SBCL). `%unread-file-position` /
  `-set` are spliced only when the program also names `file-position`, since the backends gate
  their position runtime on that name.
- `read-byte`, `read-sequence`, `read` do NOT consult it on any backend (their loops are generated
  inside the expression compilers). An indexed `with-input-from-string` is expanded by this pass
  itself (`LispMacroExpander.isIndexedWithInputFromString`, `.kb/read-load-streams.md`, "String
  streams").
- **A reserved cell is no part of `equal` or the `equal` hash** on any backend: the loops are
  bounded by the LAYOUT's slot count, not the storage length, else a stream keyed in an `equal`
  hash table is lost once a character parks. `StringStreamPrograms.PER_STREAM_PUSHBACK_PROGRAM`
  (also pins two streams each holding one, and a closed stream with a parked char) and the Gray
  fixture.
- A parked character survives `close`: a read of the closed stream answers it where SBCL signals
  (all four alike).
- **Function values: `BuiltinFunctionWrappers.PUSHBACK_WRAPPERS`**, the `GRAY_WRAPPERS` idea on this
  splice: where the program carries the defuns, the read family's values park and drain like a
  call. A Gray twin wins over a pushback twin (its fallback is a call site this pass already
  rewrote). The catalog `#'unread-char` still signals (`UNREAD_CHAR_NOT_A_VALUE_MESSAGE`),
  reachable only through a symbol built at run time.

## `make-broadcast-stream` is a Gray stream
Prelude Lisp (`LispPreludeLibrary.MAKE_BROADCAST_STREAM`): a
`rontolisp:fundamental-character-output-stream` subclass looping the components. **Every broadcast
stream is that class, with components or without** (a component-less one is not a discarding
sink). Its `stream-fresh-line` asks the first component and breaks the rest's line after it, as
SBCL's `charpos` ([standard-output-redirect.md](standard-output-redirect.md), "A stream's line
start"). **So exactly the operators that dispatch on one work with it.**

## The composite-stream constructors are Gray streams too
`make-two-way-stream` / `make-echo-stream` / `make-concatenated-stream` (`.kb/read-load-streams.md`):
prelude Gray classes over the components. The echo stream is its OWN class, NOT a subclass of
`%two-way-stream`: each prelude entry loads standalone on the interpreter, so an entry must not need
another entry's defclass. The METHODS call the BUILT-INS, so a handle component works and the
compile-path rewrite (which runs AFTER this splice) makes a Gray component dispatch. Selection keys
on ANY of the cluster's surface names, so a program that only receives the stream still splices the
whole entry.

## The text pull stream is a Gray stream too
`rontolisp::%make-text-pull-stream (pull release)`: a `fundamental-character-input-stream` asking
`pull` for the next piece only when a read needs it -- the Clojure front end's reader over a fetched
reply (`.kb/clojure-frontend.md`, "Readers over a fetched reply"). It has its OWN
`stream-unread-char` (stepping back over the character still in hand), so its `stream-read-line`
override never reads past a parked character (the trap above). Deliberately no
`close`/`open-stream-p` method (the stand-down is program-wide): `%text-pull-stream-close` /
`-closed-p` instead. Pin:
`LispPreludeLibraryTest#aTextPullStreamPullsThePieceAReadNeedsAndReadsLinesAcrossPieces`; the four
backends through the clojure-http corpus.

## flexi-streams
`flexi-streams.lisp` is a lite shim except for these REAL Gray classes:
- `flexi-streams:vector-stream` (`flex:make-in-memory-input-stream`): CLASS external (http-body
  spells `(typep s 'flex:vector-stream)`), accessors INTERNAL; a real `stream-file-position` pair
  lets circular-streams rewind.
- WRITE half (`.kb/cffi.md`): `vector-output-stream`; `get-output-stream-sequence` RESETS the stream
  and answers a PACKED `(unsigned-byte 8)` array. Deliberately NOT a `vector-stream` -- that class
  is http-body's no-copy INPUT path.
- `flexi-streams:flexi-stream` (bivalent; EXTERNAL `flexi-stream-*` readers -- cl+ssl spells
  `flexi-streams:flexi-stream-stream` single-colon). **Reads/writes OCTETS on the wrapped stream**,
  which must be binary-capable, through the external format's codec. `:column` ignored.
- **External formats**: names by `string-equal` onto `:utf-8`, `:iso-8859-1` and `:us-ascii`; any
  other SIGNALS (drakma's `determine-body-format` falls back to binary on it). `:crlf` reads one
  octet ahead past a CR (parked in the `ahead` slot, not counted in the position). Malformed UTF-8
  never signals. **Divergence**: the string functions and `make-flexi-stream` default to `:utf-8`
  (upstream `:latin1`). drakma `http-request` works on the interpreter, JVM and the component
  (`-S inherit-network=y -S tls=y`, `-S allow-ip-name-lookup=y` for a host name; https under
  `:verify :required` only -- drakma's default `:verify nil` is refused there, [tcp-sockets.md](tcp-sockets.md),
  "WASM TLS"). Preview 1: no sockets.
- Not the real library: 48,418 of its 52,557 lines are the GBK table, the rest mostly code pages
  and per-(encoding, eol) class macrology.
- **Deliberately no `close` method**: the stand-down is PROGRAM-wide, so one `(defmethod close ...)`
  in a shim every lack/clack program loads would disable the Gray `close` rewrite for every class.
- `BuiltinSystems.DEPENDENCIES` records `flexi-streams -> trivial-gray-streams`; both loaders
  honour it, because the protocol must be DEFINED before the `vector-stream` defclass. Pinned by
  the two `LackEcosystem*E2eTest` classes.

## Tests
- `LispEvaluatorTest#gray*`, `#unreadChar*`, `#evalFlexiStream*`, `#aGrayBaseClassNamedFirstIsKnown`
  (twins in the two compiler suites).
- `JvmLispCompilerTest#compileAndRunGray*` + `#compileAndRunUnreadChar*`,
  `#grayRewriteLeavesASlotNamedAfterAStreamBuiltinAlone`.
- `WasmLispCompilerIntegrationTest#gray*` + `#unreadChar*`.
- `GrayStreamsLibraryTest`; `FastIoCircularStreamsE2eTest`, the two `LackEcosystem*E2eTest` classes.
- ci-spec: `gray-stream-instance-dispatch`, `gray-stream-binary-round-trip-and-file-position`,
  `gray-stream-input-protocol-widening`, `gray-stream-is-a-stream`,
  `gray-stream-unbounded-write-passes-integer-bounds` (`GrayStreamCallFixture
  .UNBOUNDED_WRITE_PROGRAM`), `gray-stream-operators-as-function-values`,
  `flexi-streams-external-formats`.

## The composite stream classes as CL type names, and the zero-component broadcast stream

**`broadcast-stream`, `two-way-stream`, `echo-stream` and `concatenated-stream` are CL type names
over the prelude Gray classes** (`PackageRegistry.CL_TYPES` + `LispMacroExpander.makeTypeTest`): the
test is the class tag, exact on all four backends. A Lisp source spells the tag bar-quoted
(`'|%class-%BROADCAST-STREAM|`): the `%class-` prefix is lowercase and the reader would upcase it.

**A broadcast stream answers the file queries for its LAST component, or for an empty list when it
has none** (length 0, position 0, string-length 1, external-format `:default`):
`%gray-broadcast-file-length` is recursive (nested broadcasts); `file-string-length` and
`stream-external-format` are prelude defuns testing the tag inline.

- `#'make-broadcast-stream` is the same Gray stream: the catalog wrapper is `(&rest c)
  (%make-broadcast-stream c)` on every backend, injected where the prelude entry is in the program
  (`BuiltinFunctionWrappers.HELPER_WRAPPERS`); reachable only through `eval` otherwise, the value is
  still the discarding sink.
- ANSI `streams`: `WRITE-LINE.4-.10` still fail on the `#.` expectation harness gap.
- Pinned by `LispEvaluatorTest#theCompositeStreamTypeNamesResolve`,
  `JvmLispCompilerTest#compileAndRunCompositeStreamTypeNames`,
  `WasmLispCompilerIntegrationTest#compositeStreamClassesAreTypeNames`, ci-spec
  `composite-stream-classes-are-type-names`.
