# h77. Readtables: the compiled readers, the interpreter's other reads, the libraries still stopping

Difficulty: High

h74 made a keyword a block name on every backend, the cross-lambda lowering included
(`.kb/do-return-block.md`), so rutils gets past `(block :+ ...)` on the compile paths; and a
`progn` a macro expands into is top level member by member when it holds a package directive
(`.kb/packages.md`), so cl-annot's `@export` runs on the JVM and WASM; and a `loop` pattern
whose head names a macro stays a pattern (`.kb/flet-labels.md`), so parenscript's `ps` does;
and `name-char` exists, so cl-unicode no longer warns it undefined (`.kb/pretty-printer.md`).
What is left, in the order it unblocks libraries (measured 2026-10-31 by hand through the
Quicklisp cache unless noted):

1. **The emitted runtime readers** (JVM `JvmReadRuntimeBuilder`, WASM
   `WasmReadRuntimeBuilder`) and the prelude `read` scanner (it delimits a datum, then calls
   `read-from-string`) ignore `*readtable*`; the frontend-parity invariant says they should
   signal rather than misread under a non-standard one, or honor it. Under a `#`` dispatch
   function `(read-from-string "#`(a ,b)")` answers the symbol `|#`|` on the JVM and both WASM
   backends, where the interpreter calls the function.
   - The cheap half, for dispatch characters: an UNDEFINED sub-character does not signal even
     under the standard readtable (CLHS 2.4.8): `"#`(a ,b)"` answers `|#`|` compiled and `#`
     on the interpreter, `"#!x"` the symbol `#!X` on all four. `LispLexer` falls through to
     `readSymbol`; the runtime readers do the same. A `reader-error` there on all four turns
     the measured misread into a signal. Blast radius to measure first: every source the
     corpus reads with such a `#x` (ci-spec, examples, the vendored libraries).
   - Honoring it needs a reader that calls Lisp functions mid-datum (a list holding a user
     macro character); a non-dispatch macro character still reads as a constituent.
   - `LispEvaluatorTest.theReaderHonorsTheCurrentReadtable`'s run-time half becomes a ci-spec
     case once they do.
2. **Libraries still stopping** (none of it in the reader):
   - rutils on the compile paths: `core/hash-table.lisp`'s `merge-hash-tables-with` calls
     `dotable` before the file defines it, so `(k v ht2)` is a function call reading a free
     `v`; both compilers refuse a free reference at compile time (`JvmExprCompiler`'s
     `Cannot compile symbol reference: RUTILS.HASH-TABLE::V`, WASM's `Cannot compile symbol:`)
     where SBCL warns and signals `unbound-variable` only if it runs.
     `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` pin the refusal
     (`Cannot compile symbol reference: CAR`). Past it, `rutils:flatten` is "not external":
     `core/rutils.lisp` re-exports by `(dolist (p *all-packages*) (re-export-symbols p ...))`
     in `eval-always`, which neither resolver sees (a COMPUTED export, `.kb/packages.md`).
   - cl-redis on the interpreter: the trivial-gray-streams shim lacks `stream-clear-input`,
     and also `stream-peek-char`, `stream-read-char-no-hang`, `fundamental-character-stream`
     and `fundamental-binary-stream`, all upstream exports (the shim's comment saying the two
     generics are not is wrong). A real `stream-clear-input` needs a Gray `clear-input`
     dispatch (`clear-input` is a prelude defun answering nil); chunga defines a method on it
     too. It does not load cl-redis 20250622 by itself: its `connection.lisp` names
     `sb-sys:fd-stream` and `sb-impl::clear-input` with no feature guard (SBCL only).
   - jonathan on WASM no longer stops (`to-json`/`parse` on preview 1).
3. Interpreter gaps: `read` from a file stream / standard input (the prelude scanner
   delimits by the standard syntax), the REPL and playground (whole-buffer reads),
   `with-standard-io-syntax` (does not rebind `*readtable*`; its expansion runs after the
   instance gate is computed), calling a standard `#` sub-function, escape syntax under
   another character, and `*read-suppress*` around a reader macro.
4. The image is a second macro-time pass over the program (thrown away, because the passes
   between the read and `UserMacroExpander` rewrite the program): the ci-spec corpus compiles
   in 24.1 s with it vs 23.3 s without. Reusing the session when those passes change nothing
   would remove it; only worth it if a readtable-heavy library makes the cost show.
