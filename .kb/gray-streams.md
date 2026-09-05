# Gray streams (rontolisp's own protocol, all backends)

rontolisp OWNS the Gray-stream protocol; third-party layers adapt onto it. Plain CLOS-subset
Lisp in `src/main/resources/am/ik/rontolisp/eval/gray.lisp`, served by
`eval/GrayStreamsLibrary`; backend-free expansion output, no codegen.

## Protocol
- Classes: `rontolisp:fundamental-stream`; `-input-stream`/`-output-stream`; leaves
  `-character-input/-output-stream`, `-binary-input/-output-stream`.
- Generics: `stream-write-char`, `stream-write-string (stream string &optional start end)`,
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
- `stream-unread-char` default cell: `rontolisp::*gray-unread-stream*` / `*gray-unread-char*`,
  ONE char for ONE stream (WASM fd-pushback shape, `PEEK_FD_ADDR`/`PEEK_CP_ADDR`); full Gray
  keeps it per CLASS. `%gray-read-char-1` is the ONE cell-draining entry. TRAP: overriding
  `stream-read-line`/`-read-sequence` OUTRIGHT reads past a pushed-back char.
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

## Ownable operators
- OWNABLE: `close`, `open-stream-p`, `stream-element-type`
  (`GrayStreamsLibrary.OWNABLE_OPERATORS`, interpreter `wrapGrayOwnableOperator`); both seams
  stand down when the program defines one, so there is deliberately **no
  `rontolisp:stream-close` generic** (`GrayStreamsLibrary.ownsClose` /
  `closRegistry.findGeneric(CLOSE)`). With none, `close`/`open-stream-p` = `t` and
  `stream-element-type` = `character` / `(unsigned-byte 8)` by `typep` on the base classes.
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

## Compile path: `GrayStreamsLibrary.process`
Runs after `UserMacroExpander`; triggered by any protocol name (`splitQualified` member match,
so `trivial-gray-streams:` counts; the match set is ALL-UPPERCASE).
1. Splice gray.lisp PROTOCOL forms unless a load already did (guard: a `defclass` of
   `rontolisp:fundamental-character-output-stream`).
2. Rewrite every stream-taking call with an explicit non-literal stream (not
   `t`/`nil`/string literal) onto the helpers. The stream-LESS spelling is left alone, so a
   program naming no stream stays byte-identical.
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
- Not rewritten: a bounded `(write-string s instance :start ...)`; first-class
  `(funcall #'read-byte instance)` does not dispatch on the compile paths.
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
parks in a one-slot cell the character reads consult.
- Interpreter: Java, in `Environment.createGlobal` — a `pushbackStream`/`pushbackChar` pair the
  read definitions close over (its built-ins are FUNCTION VALUES, not rewritable call sites).
- Both compile paths: ORDINARY LISP — `unread-char.lisp`, spliced by `eval/UnreadCharLibrary`,
  which also rewrites the `read-char`/`read-char-no-hang`/`peek-char`/`read-line`/`unread-char`
  call sites onto its defuns. Trigger: the program names `unread-char`; else byte-identical.
  **Runs LAST, over `GrayStreamsLibrary.process`'s output**, because
  `%gray-unread-char-dispatch`'s non-instance fallback IS the handle arm.

Contract, identical on all four:
- KEY = the stream argument AS GIVEN, an omitted stream and the nil designator folded onto `t`;
  else `eql`.
- `read-char`/`read-char-no-hang` DRAIN it; `%peek-char` LEAVES it; `peek-char`'s skipping
  peek-types drain it exactly when the char is one to skip (`%unread-peek-stops-p` runs
  built-in `peek-char` over a one-character string input stream rather than adding a FOURTH
  whitespace-set copy). `read-line` DRAINS it and prepends it to the line.
- A second `unread-char` with the cell full SIGNALS
  (`LispMacroExpander.UNREAD_CHAR_TWICE_MESSAGE`, shared verbatim with `unread-char.lisp` and
  `Environment`).
- `read-byte`, `read-sequence`, `read` do NOT consult it on any backend: their loops are
  generated inside the expression compilers, after this pass could walk them.
- **A `#'unread-char` FUNCTION VALUE still signals on the compile backends**
  (`LispMacroExpander.UNREAD_CHAR_NOT_A_VALUE_MESSAGE`); the interpreter has no such limit.
  Callers: cl-json's decoder, local-time's parser, chunga's `unread-char*`.

## `make-broadcast-stream` is a Gray stream
Prelude Lisp (`LispPreludeLibrary.MAKE_BROADCAST_STREAM`) defines a
`rontolisp:fundamental-character-output-stream` subclass looping the components. **A broadcast
stream with components is a Gray stream, so exactly the operators that dispatch on one work
with it.** A component-LESS `(make-broadcast-stream)` is still the discarding
`%make-string-output-stream` handle but rides the same prelude entry, so a sink-only program
also carries gray.lisp.

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
- `LispEvaluatorTest#gray*` (14 cases: instance dispatch, eager base-class load, binary
  round trip + file-position, read-line/sequence defaults, peek/unread/no-hang, the
  unread-char method owning the pushback, direction predicates, shim mixin + setf
  file-position, `grayStreamInstanceIsAStream`) and `#unreadChar*`, `#evalFlexiStream*`.
- `JvmLispCompilerTest#compileAndRunGray*` (7) + `#compileAndRunUnreadCharOnAStreamHandleRoundTrips`,
  `#grayRewriteLeavesASlotNamedAfterAStreamBuiltinAlone`.
- `WasmLispCompilerIntegrationTest#gray*` (6) + `#unreadCharOnAStreamHandleRoundTrips`.
- `GrayStreamsLibraryTest#programWithoutAGrayShimKeepsTheProtocolSpliceAtTheFront`;
  `FastIoCircularStreamsE2eTest`, the two `LackEcosystem*E2eTest` classes.
- ci-spec: `gray-stream-instance-dispatch`, `gray-stream-binary-round-trip-and-file-position`,
  `gray-stream-input-protocol-widening`, `gray-stream-is-a-stream`.
