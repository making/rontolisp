# h74. Readtables: the compiled readers, the interpreter's other reads, the libraries still stopping

Difficulty: High

h71 made a reader macro in a backquote template read the template's commas
(`.kb/readtables.md`, "The interpreter's reader"); rutils loads on the interpreter. What is
left, in the order it unblocks libraries:

1. **The emitted runtime readers** (JVM `JvmReadRuntimeBuilder`, WASM
   `WasmReadRuntimeBuilder`) and the prelude `read` scanner ignore `*readtable*`; the
   frontend-parity invariant says they should signal rather than misread under a
   non-standard one, or honor it. Measured 2026-10-31: under a `#`` dispatch function,
   `(read-from-string "#`(a ,b)")` answers the symbol `|#`|` on the JVM and both WASM
   backends, where the interpreter calls the function.
   `LispEvaluatorTest.theReaderHonorsTheCurrentReadtable`'s run-time half
   (`read-from-string`, `read` on a string stream, the case modes, a template's commas)
   becomes a ci-spec case once they do.
2. **Libraries still stopping** (measured 2026-10-31 by hand through the Quicklisp cache;
   none of it in the reader):
   - cl-redis on the interpreter: its `connection.lisp` names
     `trivial-gray-streams:stream-clear-input`, which rontolisp's trivial-gray-streams does
     not export ("The symbol STREAM-CLEAR-INPUT is not external").
   - rutils on the compile paths: `BLOCK: block name must be a symbol, got :+`.
     `LispMacroExpander.blockName` and `LispEvaluator.blockName` both refuse a keyword,
     which CLHS allows as a block name; where the `(block :+ ...)` comes from (core
     `misc.lisp`'s `(abbr :+ incf)` inside a `handler-bind`) is not traced yet.
   - cl-annot's `@export`: the expansion `(progn (export 'x) (defun x ...))` nests the
     `export` the compilers' resolver needs at top level ("GREET is not external"). CLHS
     processes a top-level `progn`'s members as top-level forms; `UserMacroExpander` keeps a
     macro-expanded `progn` whole.
   - jonathan on WASM: `Cannot compile symbol: *` (the JVM runs it).
   - parenscript: `property-bindings` fails `Cannot compile symbol reference:
     PARENSCRIPT:VAR`.
   - cl-unicode (under cl-interpol) warns `CL-UNICODE::NAME-CHAR is undefined` on the
     compile paths.
3. Interpreter gaps: `read` from a file stream / standard input (the prelude scanner
   delimits by the standard syntax), the REPL and playground (whole-buffer reads),
   `with-standard-io-syntax` (does not rebind `*readtable*`; its expansion runs after the
   instance gate is computed), calling a standard `#` sub-function, escape syntax under
   another character, `*read-suppress*` around a reader macro, and `read-from-string`
   inside a reader macro function starting outside the enclosing template
   (`Environment.readOneWithSyntax` is not handed the depth `readWithSyntax` is).
4. The image is a second macro-time pass over the program (thrown away, because the passes
   between the read and `UserMacroExpander` rewrite the program): the ci-spec corpus compiles
   in 24.1 s with it vs 23.3 s without. Reusing the session when those passes change nothing
   would remove it; only worth it if a readtable-heavy library makes the cost show.
