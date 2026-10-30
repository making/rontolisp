# h57. Readtables on the compile paths and in the compiled readers

Difficulty: High

h53 made a readtable a value on every backend and the INTERPRETER's reader read with it
(`.kb/readtables.md`). What is left, in the order it unblocks libraries:

1. **The compile paths read source with the standard syntax.** `CompileFrontend.run` reads
   every file whole (`SourceLanguage.read`, `LoadInliner`) before `UserMacroExpander`'s
   macro-time evaluator runs, so an `in-readtable` / `set-macro-character` in an `eval-when`
   cannot reach the read of the forms after it, and no reader macro is called. Needed: read
   form at a time against the macro-time evaluator (`SourceLanguage.readForms` +
   `ReadSyntax` over ITS `*readtable*`), i.e. the read interleaved with `UserMacroExpander`'s
   per-form loop and `LoadInliner`'s splicing -- design that split; the passes between them in
   `CompileFrontend.expand` must not restate the pipeline. A reader macro's datum must be
   compilable (a value without a literal form signals). Then turn
   `LispEvaluatorTest.theReaderHonorsTheCurrentReadtable` into a ci-spec case.
2. **A reader macro called inside a backquote template cannot read the template's commas**:
   the callout's recursive `read` is a fresh read (iterate's `#L(member !1 var :test ,test)`
   failed "Comma is illegal outside of backquote"; cl-redis stops here too, in rutils
   `core/iter.lisp:3162`'s user-defined `` #`(member % var :test ,test) ``). Needed: the backquote depth at the call
   (the lexer knows the open `Backquote` tokens) handed to the recursive read, which then
   reads `,x` as the CLtL2 raw markers, and `LispReader.readBackquote` taking the raw path for a
   `Token.Datum` holding them. Until then `#L`/`#N@` stay native (`ReadtableSyntax.isNativeSub`).
3. **The emitted runtime readers** (JVM `JvmReadRuntimeBuilder`, WASM `WasmReadRuntimeBuilder`)
   and the prelude `read` scanner ignore `*readtable*`; the frontend-parity invariant says
   they should signal rather than misread under a non-standard one, or honor it.
4. Interpreter gaps: `read` from a file stream / standard input (the prelude scanner
   delimits by the standard syntax), the REPL and playground (whole-buffer reads),
   `with-standard-io-syntax` (does not rebind `*readtable*`; its expansion runs after the
   instance gate is computed), calling a standard `#` sub-function, escape syntax under
   another character.
5. named-readtables on the compile paths: its `defpackage` sits under
   `locally`/`handler-bind` inside an `eval-when` (`src/package.lisp`), which the compile-time
   resolver does not see ("No such package: EDITOR-HINTS.NAMED-READTABLES");
   `swank:*readtable-alist*` exists only on the interpreter.
