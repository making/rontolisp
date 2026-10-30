# h71. Readtables: templates, the compiled readers, the libraries still stopping

Difficulty: High

h57 made the compile paths read source with the compile-time readtable (`.kb/readtables.md`,
"The compile paths' read"): `LoadInliner` reads every Common Lisp file a form at a time and
`ReadTimeImage` runs each form through a shallow `UserMacroExpander.Session`;
named-readtables, cl-interpol and cl-csv run on all four backends. What is left, in the order
it unblocks libraries:

1. **A reader macro called inside a backquote template cannot read the template's commas**:
   the callout's recursive `read` is a fresh read (iterate's `#L(member !1 var :test ,test)`
   failed "Comma is illegal outside of backquote"; cl-redis stops here too, in rutils
   `core/iter.lisp:3162`'s `` #`(member % var :test ,test) ``). Needed: the backquote depth
   at the call (the lexer knows the open `Backquote` tokens) handed to the recursive read,
   which then reads `,x` as the CLtL2 raw markers, and `LispReader.readBackquote` taking the
   raw path for a `Token.Datum` holding them. Until then `#L`/`#N@` stay native
   (`ReadtableSyntax.isNativeSub`) on both read paths.
2. **The emitted runtime readers** (JVM `JvmReadRuntimeBuilder`, WASM
   `WasmReadRuntimeBuilder`) and the prelude `read` scanner ignore `*readtable*`; the
   frontend-parity invariant says they should signal rather than misread under a
   non-standard one, or honor it. `LispEvaluatorTest.theReaderHonorsTheCurrentReadtable`'s
   run-time half (`read-from-string`, `read` on a string stream, the case modes) becomes a
   ci-spec case once they do.
3. **Libraries still stopping on the compile paths** (measured 2026-10-31 by hand through the
   Quicklisp cache; none of it in the reader):
   - cl-annot's `@export`: the expansion `(progn (export 'x) (defun x ...))` nests the
     `export` the compilers' resolver needs at top level ("GREET is not external"). CLHS
     processes a top-level `progn`'s members as top-level forms; `UserMacroExpander` keeps a
     macro-expanded `progn` whole.
   - jonathan on WASM: `Cannot compile symbol: *` (the JVM runs it).
   - parenscript: `property-bindings` fails `Cannot compile symbol reference:
     PARENSCRIPT:VAR`.
   - cl-unicode (under cl-interpol) warns `CL-UNICODE::NAME-CHAR is undefined` on the
     compile paths.
4. Interpreter gaps: `read` from a file stream / standard input (the prelude scanner
   delimits by the standard syntax), the REPL and playground (whole-buffer reads),
   `with-standard-io-syntax` (does not rebind `*readtable*`; its expansion runs after the
   instance gate is computed), calling a standard `#` sub-function, escape syntax under
   another character.
5. The image is a second macro-time pass over the program (thrown away, because the passes
   between the read and `UserMacroExpander` rewrite the program): the ci-spec corpus compiles
   in 24.1 s with it vs 23.3 s without. Reusing the session when those passes change nothing
   would remove it; only worth it if a readtable-heavy library makes the cost show.
