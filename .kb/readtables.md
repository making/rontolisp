# Readtables: the value on every backend, the interpreter's reader reads with it

**Invariant**: a readtable is a VALUE on all four backends -- the fixed `LispLayout.READTABLE`
layout (tag `%READTABLE`, OPAQUE: prints `#<READTABLE>`), built and changed by ONE prelude
definition (`LispPreludeLibrary`, the `%rt-*` family). Only the INTERPRETER's reader reads
with one; the compile paths read source with the standard syntax and the emitted runtime
readers ignore `*readtable*` (`.todo/h57`).

## The value
- Slot 0 `CASE` (`:upcase`/`:downcase`/`:preserve`/`:invert`), slot 1 `CHARS`: an eql table
  holding ONLY the characters whose syntax differs from the standard one -- the standard
  readtable is an empty table, a copy costs nothing. Entry = `(KIND FN NON-TERMINATING-P SUBS
  STD)`: KIND `:whitespace`/`:constituent`/`:macro`/`:single-escape`/`:multiple-escape`; SUBS a
  dispatch character's table `sub-char -> (FN . STD-SUB)` (upcased keys, ALL entries explicit
  once materialized, `%rt-own-entry`); STD the standard character whose standard function FN is.
- **Standard functions are memoized objects, EQ in every readtable** (`%rt-std-fn`,
  `%rt-std-subs`): named-readtables' merge compares with `eq` and signals
  `reader-macro-conflict` otherwise. **Every dispatch character, `#` included, has the ONE
  `%rt-dispatcher`** -- the standard `#` and one `make-dispatch-macro-character` made must
  answer the same function (measured: jonathan's `use-syntax` merge failed on it).
  `set-macro-character` derives STD by reverse lookup (`%rt-std-of`), so `(set-macro-character
  #\] (get-macro-character #\)))` is a `)` under `]`.
- Calling a standard function: `%rt-call-standard` (`;` reads the line, `)` signals, others
  unread + `read`). A standard `#` sub-function cannot be called (signals); it is compared and
  copied only.
- `*readtable*` seeds a fresh standard readtable: interpreter `Environment.newStandardReadtable`,
  compile paths `injectMvSpillGlobal` (`(%obj-new '%READTABLE :upcase (make-hash-table))`, the
  instance gate answered by the variable's name in `mayCreateInstance`). Special on the
  interpreter (`specialVars`).
- `typep`/specializer `readtable` = `%obj-is` (`makeTypeTest`, `isSupportedTypeSpecializer`);
  `type-of` a prelude clause. `get-macro-character` is in `passesMultipleValues`.
- Gone: the call-position stubs (`expandCopyReadtable` & co.), their wrappers,
  `ShadowedBuiltins` entries, and the pruner's dead-hook exception -- a hook `#'name` is a
  reference now (ironclad's `array-reader` stays alive, with `read`, in a compiled ironclad).

## The interpreter's reader (`reader/ReadSyntax`, `eval/ReadtableSyntax`)
- `ReadtableSyntax.of` turns the table into a `ReadSyntax` view holding only what reads
  differently: `Whitespace`/`Constituent`, `Standard(as)` (a standard function under another
  character: `( ) ' ` , ; "` natively, `#` refused), `Call(fn)` (a user function, or the
  dispatcher of a non-`#` dispatch character), `Sharp(subs)` (`#` with its table). A readtable
  reading as the standard one -- an empty table, or named-readtables' full standard refill --
  has NO view (`null`), the reader's own fast path.
- `LispLexer.lexSyntaxEntry` consults the view first in every lexical unit; `isSymbolChar` asks
  it (`continuesToken`); `readSymbol`/`numberFallbackSymbol` convert the case (escape mask,
  `:invert` over the whole token). A call is `ReadSyntax.Invoker` ->
  `LispEvaluator.callReaderMacro`: a `RontoStringInputStream` over the SAME text at the offset
  (`StringStreamTable`, the stream table's view), `(fn stream char)` / `(fn stream sub arg)`,
  zero values = a comment (`consumeValues`), the stream's position (pushback counted back) is
  where lexing resumes; one value becomes `Token.Datum`. In a backquote template a datum is
  quoted DATA (`readTemplateElement`).
- **`#L` and `#N@` stay native whatever function the table holds** (`isNativeSub`): a callout
  inside a backquote template cannot read the outer template's commas (iterate's
  `#L(member !1 var :test ,test)` failed "Comma is illegal outside of backquote"), and the
  compile paths read them natively anyway. The real fix is a backquote-depth-aware recursive
  read (`.todo/h57`).
- **Reading is FORM AT A TIME**: `SourceLanguage.readForms` -> `LispReader.Forms` ->
  `LispLexer.tokenizeNextDatum` (stops at depth 0 behind a complete datum; prefixes and `#0A`
  keep it going; `stopAt` for `read-delimited-list`). `LispEvaluator.loadFile` (`loadForms`)
  and the CLI entry (`evalSource`) read each form with `currentReadSyntax()` after the forms
  before it ran; `load` binds `*readtable*`. A read error is signalled after the forms before
  it ran (the CLI entry included, since 2026-10-30).
- Run time: one-argument `read-from-string` under a view reads ONE datum
  (`readOneWithSyntax`; the stop index is cached for `%read-from-string-end`, a second read
  would run the reader macros twice). `read` / `read-delimited-list` on a STRING stream under
  a view are served natively by `evalReadCall` (the `evalSequenceScan` shape: only while the
  name resolves to the prelude's defun); other streams, and the standard readtable, run the
  prelude scanner. **Symbols such a read answers are homed in the current package**
  (`homeReadSymbols`, the quoted-data resolution): cl-annot looks an annotation's arity up on
  the symbol it read, and a bare spelling names cl-user's.
- Not honored: escape syntax under another character (a constituent there), `read` from a
  file stream or standard input, `with-standard-io-syntax` (does not rebind `*readtable*`),
  the REPL / playground (whole-buffer reads), `*read-suppress*` around a reader macro.

## named-readtables and its dependents (measured 2026-10-30, interpreter)
- Derived form `eval/NamedReadtablesForms` (tier 4, `.kb/asdf.md`): `src/cruft.lisp`'s
  portable `%make-readtable-iterator` grovels `char-code-limit` (1,114,112) code points, twice
  per dispatch character: 26.5 s to load named-readtables. The port walks
  `%readtable-macro-entries` (the table plus the standard macro characters): 0.43 s.
- `swank:*readtable-alist*` exists (nil) on the interpreter: the swank package always does,
  so `in-readtable`'s `%frob-swank-readtable-alist` reaches it (`(boundp nil)` is true).
- Load on the interpreter: named-readtables, cl-interpol (`#?"..."`, 9.7 s with cl-unicode),
  cl-annot (`enable-annot-syntax`, `@export`), cl-syntax, jonathan (loads; `jonathan:parse`
  escapes a `GoSignal`). Not: parenscript (a `shadowing-import`/`export` conflict in its DOM
  symbol packages, after `:invert` reads fine), cl-csv (`.asd` holds a `defpackage` under
  `unless`), cl-redis (not reached) -- `.todo/h58`. Compile paths: named-readtables' `defpackage` sits under
  `locally`/`handler-bind` in an `eval-when`, which the compile-time resolver does not see.

## Tests
ci-spec `readtable-objects-and-the-reader-macro-api` (the API, all four backends);
`LispEvaluatorTest.theReaderHonorsTheCurrentReadtable` (interpreter reading: dispatch and macro
characters, comments, remaps, `read-delimited-list`, case, form-at-a-time `load`, homed
symbols); `NamedReadtablesE2eTest` (vendored named-readtables 0.9: the derived form's markers,
`defreadtable`, `in-readtable` in a loaded file).
