# Readtables: the value on every backend, every SOURCE read reads with it

**Invariant**: a readtable is a VALUE on all four backends -- the fixed `LispLayout.READTABLE`
layout (tag `%READTABLE`, OPAQUE: prints `#<READTABLE>`), built and changed by ONE prelude
definition (`LispPreludeLibrary`, the `%rt-*` family). Source is read with one on every
backend: the interpreter's reader with the current `*readtable*`, the compile paths with the
compile-time evaluator's (`ReadTimeImage`, below). The emitted runtime readers ignore
`*readtable*` (`.todo/h77`). Under the STANDARD one, an undefined dispatch sub-character reads
as part of a symbol on all four instead of signalling (CLHS 2.4.8; measured 2026-10-31:
`` "#`(a ,b)" `` answers `|#`|` compiled and `#` on the interpreter, `"#!x"` the symbol `#!X`).

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
  interpreter (`specialVars`). `with-standard-io-syntax` binds it to another fresh one on the
  interpreter and in a compiled program naming the variable (since 2026-11-05).
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
- **A reader macro in a backquote template reads the template's commas** (CLHS 2.4.6, h71):
  `LispLexer.followTemplateDepth` counts the backquotes and commas open at the macro
  character, the `Invoker` hands that depth to the call, `LispEvaluator.callReaderMacro`
  holds it (`readerMacroTemplateDepth`, dynamic like CL's) for every `read` /
  `read-delimited-list` the function makes (`readWithSyntax` -> `Forms.withinTemplates`) and,
  since h74, every `read-from-string` under the readtable (`Environment.readOneWithSyntax`,
  through `setTemplateDepthQuery`), and that read takes a comma outside its own templates as
  the CLtL2 raw marker
  (`LispReader.readOuterComma`: `(%bq-comma x)` / `(%bq-comma-at x)`, identity sentinels), its
  operand one level further out. The datum comes back as a `Token.Datum`; `readRawTemplate`
  finds the marker (`holdsComma`) and the template takes the CLtL2 path
  (`bqCompletelyProcess`), which consumes it, `,,x` across a nested template included. rutils'
  `` #`(member % var :test ,test) `` (iter.lisp:3162) reads. Deviations: the function sees
  the marker as a two-element LIST where SBCL's comma is an atom (a `listp`/tree walk over
  it differs); a read off a non-string stream (the prelude scanner) starts at depth 0.
- **`#L` and `#N@` stay native whatever function the table holds** (`isNativeSub`): the
  compile paths open no image for them (`ReadTimeImage.changesSyntax`), and the interpreter
  reads them as the compiled backends do. A library giving either another meaning is
  misread.
- **Reading is FORM AT A TIME**: `SourceLanguage.readForms` -> `LispReader.Forms` ->
  `LispLexer.tokenizeNextDatum` (stops at depth 0 behind a complete datum; prefixes and `#0A`
  keep it going; `stopAt` for `read-delimited-list`). `LispEvaluator.loadFile` (`loadForms`)
  and the CLI entry (`evalSource`) read each form with `currentReadSyntax()` after the forms
  before it ran; `load` binds `*readtable*`. A read error is signalled after the forms before
  it ran (the CLI entry included, since 2026-10-30).
- **A reader macro function's value in SOURCE is respelled** (`respelledForResolver`,
  `requalifyShadowedClNames`; 2026-11-05): the form goes through the resolver as spelled, so a bare `cl`
  name it answered read as the package's own where the package uses no `cl` (coalton's readtable
  returning `cl:declaim` read by eclector: `T1::PRINT is undefined`). A run-time `read`'s datum is not resolved
  and must keep its symbols (`The function CL:PROGN is undefined` the other way round), so the
  interpreter's source reads take `sourceReadSyntax` and the image's `currentReadSyntax(check)`.
  ci-spec `a-reader-macro-answers-a-cl-symbol-in-a-package-using-no-cl`.
- Run time: one-argument `read-from-string` under a view reads ONE datum
  (`readOneWithSyntax`; the stop index is cached for `%read-from-string-end`, a second read
  would run the reader macros twice). `read` / `read-delimited-list` on a STRING stream under
  a view are served natively by `evalReadCall` (the `evalSequenceScan` shape: only while the
  name resolves to the prelude's defun); other streams, and the standard readtable, run the
  prelude scanner. **Symbols such a read answers are homed in the current package** as each
  token is read (`LispReader.Forms.homingSymbols` over `PackageResolver.readSpelling`, the
  same hook every interpreter runtime read uses -- `.kb/packages.md`): cl-annot looks an
  annotation's arity up on the symbol it read, and a bare spelling names cl-user's.
- **`*read-base*` rides the same view** (`ReadSyntax.readBase`, `ReadtableSyntax.of`): a base
  other than 10 makes a view of an empty table, so every path above reads with it, the source
  reads and the image's included (`LispLexer.lexRationalInReadBase`,
  [read-load-streams.md](read-load-streams.md) "`*read-base*`").
- Not honored: escape syntax under another character (a constituent there), `read` from a
  file stream or standard input, the REPL / playground (whole-buffer reads), `*read-suppress*` around a reader macro
  (`.todo/h77`).

## The compile paths' read (`eval/ReadTimeImage`, since h57)
- The compile path read a whole file before `UserMacroExpander` evaluated anything, so no
  compile-time readtable change reached the read. Now `LoadInliner` reads EVERY Common Lisp
  file (the entry included: `inlineSource`, which `CompileFrontend.run` and
  `CompileFrontendAccess.corpus` call) FORM AT A TIME through a `FormSource` -- a whole read
  of the entry went stale when a `ql:quickload` in it opened the image (cl-interpol's
  `#?` after `in-readtable` read as a symbol). Once an image exists, each form reads with
  `ReadTimeImage.syntax(out)`: the image first feeds every form appended to `out` since the
  last call (the spliced program so far, markers included, component brackets skipped) to a
  SHALLOW `UserMacroExpander.Session`, then answers that session's evaluator's
  `currentReadSyntax(check)`. Lazy: the first file whose text can change the syntax
  (`changesSyntax`: `set-macro-character`, `make-dispatch-macro-character`,
  `set-syntax-from-char`, `(setf (readtable-case`, a `set-dispatch-macro-character` other
  than `#\# #\@` / `#\# #\L`, an assignment of `*read-base*`: `setq`, `setf`, `psetq`, `psetf`,
  `set`, a binding ending before the next form) opens the image, which catches up on all of `out`; before it
  every form reads with the standard syntax. ironclad (`#@`) and iterate (`#L`) do not open
  one. The package bracket's push now lands in front of the first `in-package`
  ([packages.md](packages.md)).
- **The image is thrown away** after the read: the passes between the read and
  `UserMacroExpander` in `CompileFrontend.expand` rewrite and splice the program, so the
  real pass reprocesses it from scratch (nothing restates the pipeline). A shallow session
  is the same per-form code (`Session.accept`) with four differences: top-level macro
  expansion only (`expandTopLevel`: a head macro call, and the members of a
  progn/locally/eval-when it becomes -- CLHS 3.2.3.1), nothing emitted, nothing printed
  (its `CompileWarnings` go through `discarding`), and a spliced system's
  `defvar`/`defparameter`/`defconstant` EVALUATED where the real pass parks it lazily
  ([defmacro-backquote.md](defmacro-backquote.md)): cl-syntax's `defsyntax` is
  `(defvar NAME (defreadtable NAME ...))`, and the readtable jonathan's `use-syntax` finds by
  name is that value form's effect. A form the image cannot process (a package a later pass
  synthesizes) is skipped; the real pass reports it. Measured 2026-10-31: the ci-spec corpus
  (opened by `readtable-objects-and-the-reader-macro-api`) compiles to `.class` in 24.1 s vs
  23.3 s without the image, and its output and compile warnings are unchanged on all four
  backends but for the new case.
- A reader macro function runs in the image; its value must have a literal form (`check`:
  numbers, symbols, strings, characters, conses, arrays, packed vectors, instances by slot --
  not a readtable, closure, hash table, stream). Otherwise, and when the function signals,
  `ReadSyntax.Refusal` -> `LispLexer` turns it into a read error at the macro character.
- **The image's ASDF answers from the compile path's registry** (2026-11-06,
  `LispEvaluator.answerSystemsFrom`): what `LoadInliner` registered, and as loaded what it spliced
  so far, as the macro-time pass's baked `%asdf-registry%` does. coalton's parser picks its only
  `make-expression-result` arm by `(eval-when (:compile-toplevel) (if (uiop:version<
  (asdf:component-version (asdf:find-system :eclector)) "0.10.0") (pushnew ...) ...))`; the image
  signalled `Component "eclector" not found`, skipped the form, read the method with neither arm,
  and the method answered nil: `No applicable method: CONCRETE-SYNTAX-TREE:RAW on SYMBOL` at
  `library/types.lisp:20`. Pinned by `AsdfMetaobjectsE2eTest` (`meta-demo`).
- **A form of the program's own that a reader macro function read is read again by the
  macro-time pass**, at its turn, in its own evaluator (`ReaderMacroReads`, 2026-11-06). The read's
  effects on the compiling image are part of compile-file's semantics -- coalton's
  `coalton-toplevel` reader macro typechecks the form and stores what it defined in
  `*global-environment*`, where the next `(coalton ...)` macro looks -- and the image is thrown
  away, so the pass had `Unknown variable COLOR-NAME` for a program read with `in-readtable`.
  `LoadInliner` records the form, the text and offsets and the features it was read with, when the
  image's reader-macro count moved during the read and no system is splicing; `UserMacroExpander`
  keeps the form whole through its flatten (`%read-again` placeholder) and reads the text again with
  its evaluator's readtable. The image's read stands when this one runs no reader macro function or
  ends elsewhere (a readtable this pass holds otherwise, e.g. one a spliced `defvar` made that the
  image evaluated and this pass parks). A spliced system's forms are not read again: a system is
  loaded, and its load-time forms are what the pass replays (coalton's generated code updates the
  environment itself), as loading a fasl never reads source. Cost: the reader macro runs twice per
  such form. ci-spec `a-reader-macro-reads-into-the-compiling-image` (all four and SBCL: the second
  line was NIL on the compile paths).
- **compile-file semantics**: only what the compile evaluates changes the read. A
  source-level `eval-when` whose situations evaluate at compile time (`:compile-toplevel`,
  or `:execute` in compile-time-too mode) has its members recorded by
  `flattenTopLevel(program, compileTime)` and EVALUATED by the session (`evalAtCompileTime`,
  a defvar eagerly -- lazily it captured the readtable AFTER the change); before h57 only
  their definitions registered. The mode carries into a macro-expanded `eval-when`
  (`processExpandedEvalWhen(.., compileTimeToo)`: named-readtables' `defreadtable` is
  `(eval-when (:load-toplevel :execute) ...)`), and inside a spliced system its
  `:load-toplevel`/`:execute` members replay like the system's plain forms (the system is
  LOADED). So a `defreadtable` the same compile selects needs an `eval-when`, as
  named-readtables' own doc says; the interpreter evaluates everything anyway.
- **`load` binds `*readtable*`**: at macro time each `%begin-file` level binds it
  (`pushReadtableBinding`), and at run time `lowerLoadContextMarkers` pushes the current one
  on `rontolisp::%load-readtables` at a level's start and pops it back at its end, in a
  program that mentions `*readtable*` at all (a whole-program gate: a file may change it
  through a function another file defines).
- `#L`/`#N@` stay native here too (`isNativeSub`), and a `#.` still rides the marker read.

## named-readtables and its dependents (measured 2026-10-30, interpreter)
- Derived form `eval/NamedReadtablesForms` (tier 4, `.kb/asdf.md`): `src/cruft.lisp`'s
  portable `%make-readtable-iterator` grovels `char-code-limit` (1,114,112) code points, twice
  per dispatch character: 26.5 s to load named-readtables. The port walks
  `%readtable-macro-entries` (the table plus the standard macro characters): 0.43 s.
- `swank:*readtable-alist*` exists (nil) on the interpreter: the swank package always does,
  so `in-readtable`'s `%frob-swank-readtable-alist` reaches it (`(boundp nil)` is true).
- Load on the interpreter: named-readtables, cl-interpol (`#?"..."`, 9.7 s with cl-unicode),
  cl-annot (`enable-annot-syntax`, `@export`), cl-syntax, cl-csv (since h35's `.asd` as Lisp),
  and (h58, 2026-10-31, output = SBCL 2.2.9) jonathan (`parse`/`to-json`) and parenscript (`ps`).
  What h58 fixed, none of it reader work: a `shadowing-import` of a symbol its home uninterned
  (`.kb/packages.md`, member table), a library's own `while` (`.kb/packages.md`), a `go` in a
  `return-from`'s value form (`.kb/interpreter-tail-calls.md`), the `ratio` type test
  (`.kb/declarations-type-checks.md`). rutils loads since h71 (its `iter` `:unioning`
  reads `` #`(member % var :test ,test) `` in a template, 2026-10-31); cl-redis then stops
  in its own `connection.lisp`, which names `sb-sys:fd-stream` with no feature guard
  (`No such package: SB-SYS`, measured 2026-11-04; cl-redis 20250622 loads on SBCL only).
- Compile paths (h57, 2026-10-31): named-readtables runs on all four (its `defpackage` sits
  under `(handler-bind () ...)` -- the SBCL binding is `#+sbcl` -- in an `eval-when`, which
  `flattenTopLevel` now splices like `progn`). Through `ql:quickload`, output = the
  interpreter's: cl-interpol (`#?"..."`) and cl-csv (`read-csv`/`write-csv`) on all four,
  jonathan on the JVM, and (re-measured 2026-10-31, `to-json`/`parse`) on WASM, and cl-annot's
  `@export` on the JVM and WASM since h74 (its `(progn (export 'x) ...)` is top level member by
  member, [packages.md](packages.md)), and parenscript's `ps` (h74: its `loop` pattern
  `(var p)`, [flet-labels.md](flet-labels.md)) -- verified by hand, no test (the Quicklisp
  cache).
  Still stopping, none of it in the reader (`.todo/h77`): rutils -- past `(block :+ ...)` since h74
  ([do-return-block.md](do-return-block.md)) -- at `merge-hash-tables-with`, which calls
  `dotable` before `hash-table.lisp` defines it: the call's argument reads a free `v`, which
  both compilers refuse (`Cannot compile symbol reference`) where SBCL warns; and
  `rutils:flatten` is not external there (`rutils.lisp` re-exports by a computed `dolist`).

## Tests
ci-spec `readtable-objects-and-the-reader-macro-api` (the API, all four backends) and
`source-reads-with-the-readtable-an-eval-when-installs` (source reading on all four: macro and
dispatch characters, a no-value comment, a remapped `)`, `read-delimited-list`, the case, the
restore, a template's commas read by a reader macro, `,,` across a nested template);
`LispEvaluatorTest.theReaderHonorsTheCurrentReadtable` (the interpreter's run-time
reads -- a template's commas, one outside every template signalling -- and form-at-a-time
`load`, homed symbols);
`LoadInlinerTest.aLoadedFileReadsWithTheReadtableItsEvalWhenInstallsAtCompileTime` (a loaded
file on the compile path, the readtable restored at compile and run time, a refused datum);
`NamedReadtablesE2eTest` (vendored named-readtables 0.9 on all four: `defreadtable`,
`in-readtable` in a loaded file).
