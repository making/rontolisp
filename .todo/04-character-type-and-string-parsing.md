# Character type + string/number parsing (missing builtins)

**Status:** not implemented. Needed before the deferred text-analysis example
([03-text-analysis-example-blocked](03-text-analysis-example-blocked.md)) can be
written idiomatically.

rontolisp currently has no character data type and no way to turn the text read
from a file/string into numbers, so any tokenization or CSV-style parsing has to
be done with per-character `subseq`+`string=` scanning -- not how Common Lisp
code is written.

## Functions to add

1. **Character data type and accessors**
   - `#\c` reader syntax (at least `#\Space`, `#\Newline`, `#\Tab`, printables).
   - `char` / `schar` (index a string -> character), `char-code` / `code-char`,
     `char=` / `char<` / `char<=` / `char-upcase` / `char-downcase`,
     `characterp`, `alpha-char-p` / `digit-char-p`.
   - Decide the runtime representation across backends. Note the existing
     symbol/string convention (a leading `"` distinguishes strings; see
     CLAUDE.md "symbolp/stringp"); characters need a third, distinguishable
     representation, and the WASM backend is `i31`-limited (a char code fits in
     i31, so a tagged small int is plausible).

2. **String -> number parsing**
   - `parse-integer` (with the common `:radix` / `:junk-allowed` keywords, or at
     minimum the no-keyword base-10 form).
   - `read-from-string` (parse one form from a string) -- reuses the reader.

3. **`read` with a stream argument**
   - `(read stream)` so file contents can be deserialized. Today `read` is
     0-arity only (`read expects 0 arguments`); `read-line` already accepts an
     optional stream, so mirror that. This makes `prin1-to-string` + `write-line`
     + `(read in)` a working serialization round-trip.

## Where to implement (per CLAUDE.md "Adding a New Built-in Function")

- `LispNames.java` / `PackageRegistry.java` (`CL_SYMBOLS`) -- add the names.
- `Environment.java` -- interpreter implementations via `env.define(...)`.
- JVM: `Jvm<Name>Compiler.java` + a case in `JvmExprCompiler.compileCons()`.
- WASM: `Wasm<Name>Compiler.java` + a case in `WasmExprCompiler.compileCons()`
  (mind the `i31` integer limit and the hand-written WASM reader
  `WasmReadRuntimeBuilder`; the JVM reader has full JDK parity).
- `BuiltinFunctionWrappers.java` entries so they work as first-class values.
- README "Built-in Functions" + `ReadmeExamplesTest`; `ci-spec.yaml` for E2E.

The WASM reader's existing integer/symbol-only limitation (README "Compiled
`read`/`load` limitations") applies -- `parse-integer` of floats/ratios stays out
of scope for WASM unless the reader is extended.
