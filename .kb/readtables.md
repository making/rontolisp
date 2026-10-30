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
- **A reader macro in a backquote template reads the template's commas** (CLHS 2.4.6, h71):
  `LispLexer.followTemplateDepth` counts the backquotes and commas open at the macro
  character, the `Invoker` hands that depth to the call, `LispEvaluator.callReaderMacro`
  holds it (`readerMacroTemplateDepth`, dynamic like CL's) for every `read` /
  `read-delimited-list` the function makes (`readWithSyntax` -> `Forms.withinTemplates`), and
  that read takes a comma outside its own templates as the CLtL2 raw marker
  (`LispReader.readOuterComma`: `(%bq-comma x)` / `(%bq-comma-at x)`, identity sentinels), its
  operand one level further out. The datum comes back as a `Token.Datum`; `readRawTemplate`
  finds the marker (`holdsComma`) and the template takes the CLtL2 path
  (`bqCompletelyProcess`), which consumes it, `,,x` across a nested template included. rutils'
  `` #`(member % var :test ,test) `` (iter.lisp:3162) reads. Deviations: the function sees
  the marker as a two-element LIST where SBCL's comma is an atom (a `listp`/tree walk over
  it differs); `read-from-string` inside the function (`Environment.readOneWithSyntax`) and a
  non-string stream (the prelude scanner) start at depth 0.
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
- Run time: one-argument `read-from-string` under a view reads ONE datum
  (`readOneWithSyntax`; the stop index is cached for `%read-from-string-end`, a second read
  would run the reader macros twice). `read` / `read-delimited-list` on a STRING stream under
  a view are served natively by `evalReadCall` (the `evalSequenceScan` shape: only while the
  name resolves to the prelude's defun); other streams, and the standard readtable, run the
  prelude scanner. **Symbols such a read answers are homed in the current package** as each
  token is read (`LispReader.Forms.homingSymbols` over `PackageResolver.readSpelling`, the
  same hook every interpreter runtime read uses -- `.kb/packages.md`): cl-annot looks an
  annotation's arity up on the symbol it read, and a bare spelling names cl-user's.
- Not honored: escape syntax under another character (a constituent there), `read` from a
  file stream or standard input, `with-standard-io-syntax` (does not rebind `*readtable*`),
  the REPL / playground (whole-buffer reads), `*read-suppress*` around a reader macro
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
  than `#\# #\@` / `#\# #\L`) opens the image, which catches up on all of `out`; before it
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
  in its own `connection.lisp`: `TRIVIAL-GRAY-STREAMS:STREAM-CLEAR-INPUT` is not external
  (`.todo/h77`), and past it the same file names `sb-sys:fd-stream` with no feature guard
  (cl-redis 20250622 loads on SBCL only).
- Compile paths (h57, 2026-10-31): named-readtables runs on all four (its `defpackage` sits
  under `(handler-bind () ...)` -- the SBCL binding is `#+sbcl` -- in an `eval-when`, which
  `flattenTopLevel` now splices like `progn`). Through `ql:quickload`, output = the
  interpreter's: cl-interpol (`#?"..."`) and cl-csv (`read-csv`/`write-csv`) on all four,
  jonathan on the JVM, and (re-measured 2026-10-31, `to-json`/`parse`) on WASM -- verified by
  hand, no test (the Quicklisp cache).
  Still stopping, none of it in the reader (`.todo/h77`): cl-annot's `@export` (the expansion's
  `(progn (export 'x) (defun x ...))` nests the export the compilers' resolver needs at top
  level: "GREET is not external"), parenscript (`property-bindings`: `Cannot compile symbol
  reference: PARENSCRIPT:VAR`), rutils -- past `(block :+ ...)` since h74
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
