# Core representation

The low-level encoding and pipeline invariants of the value/AST representation
shared across the interpreter and the two compilers. Most of these facts had no
other `.kb` home (they lived inline in CLAUDE.md's "Core representation" bullet
list); the two that do are cross-referenced below rather than duplicated.

## JVM Class Version 61 (Java 17)

Emitters still write frame-free version-50-style code; `am.ik.jvm.StackMapAugmenter`
(an offline post-pass at the end of `JvmLispCompiler.compile()`, after the optional
shake) computes the mandatory StackMapTable frames and stamps the version. Compiled
classes need a Java 17+ JRE.

See [stackmap-augmenter.md](stackmap-augmenter.md) for the full detail.

## WASM function types outside rec group

wasmtime's WASI host requires plain `(func ...)` types for imports; only the cons
struct goes inside a rec group.

## symbolp/stringp

Quoted symbols and string literals share a runtime representation, distinguished by
a leading `"`.

### The quote framing is STORAGE; the escaping belongs to the printer

Because the frame quotes are part of the stored value on both compile backends, the
`*print-escape*` escaping can only be applied at PRINT time, on the content between
them. The split is explicit in the API:

- `LispString.literal()` -- the raw `"` + content + `"` spelling. This is what every
  compile-path STORAGE site emits (`Jvm/WasmExprCompiler`'s `LispString` case,
  `Jvm/WasmQuoteCompiler`, the keyword-name path of `Jvm/WasmSymbolApiCompiler`). A
  storage site that used `print()` would bake the escapes into the value itself, so
  `length`/`char` would see them.
- `LispString.print()` -- the readable form: the frame quotes plus every embedded `"`
  and `\` of the content preceded by a `\` (`LispString.escape`). CLHS 22.1.3.4
  escapes exactly the string terminator and the single-escape character; a newline is
  printed LITERALLY. Our reader also accepts `\n` / `\t` on input, so the writer
  deliberately covers less than the reader -- writing `\n` back would be a different
  string than the one printed.

Per backend the same rule is emitted, and the same discriminator decides string vs
symbol (a symbol never escapes):

| backend | where |
| --- | --- |
| interpreter | `LispString.print()` -- everything above it (`Environment.printString`, `prin1-to-string`, `write-to-string`, `format ~s`, `LispCons`/`LispArray`/`LispInstance` element rendering, the `%print-object-str` seam) inherits it |
| JVM | `_strEsc` (`JvmRuntimeBuilder.buildStrEscBody`), called from the `String` branch of `_lispToString` and from the character-vector prin1 branch of `emitArrayBranch`. `_lispToString` is also the hash-table key function, hence `_strEsc`'s no-op fast path |
| WASM GC / component | `_write_str_gc(str, from, to, esc)` with `esc = 1` (`WasmStringRuntimeBuilder`), from the string branch of `_print_val`; the branch now makes the same leading-`"` test `_princ_val` does, and passes the CONTENT range so the frame quotes it writes are not themselves escaped. [wasm-gc-strings.md](wasm-gc-strings.md) |
| `--no-gc` | `NoGcWasmCompiler.emitWriteStringEscaped`, a run-based writer at the `(print <string>)` site (no allocation: `print` must not move the bump heap). [no-gc-scalar-wasm.md](no-gc-scalar-wasm.md) |

`princ` / `~A` / `princ-to-string` / `write-line` are the no-escape half BY DEFINITION
and must stay untouched. The reader's un-escaping (`\"` / `\\`, plus `\n` / `\t`) is
the mirror: `WasmReadRuntimeBuilder`'s string scanner and `LispReader` on the
interpreter. Pinned by `prin1EscapesQuotesAndBackslashesInStrings` +
`prin1OutputReadsBackAsTheSameString` in `LispEvaluatorTest`, `JvmLispCompilerTest`
and `WasmLispCompilerIntegrationTest`, and the `prin1-escapes-quotes-and-backslashes`
case in `ci-spec.yaml`.

## consp in JVM

Cons cells and function references are both `Object[]`, distinguished by
`arr[0] instanceof Integer`.

## Three-pass compilation

Pass 1 collects defuns; 2a compiles defun bodies, 2b top-level, 2c iteratively
compiles lambda bodies (top-level must compile before lambda iteration).

## `%` prefix convention

Internal helpers outside the public API are `%`-prefixed (e.g. `%remf-tail`).

## Built-in function wrappers

`BuiltinFunctionWrappers` synthesizes `(setq name (lambda ...))` defuns so
`#'+`/`#'car` work as first-class values -- internal encoding, not a real user
definition (Lisp-2).

**The invariant a wrapper must not break: a wrapper's arity is the OPERATOR's
arity, not the shape that was convenient to write.** The body puts the operator
in call position, where the backends inline it -- so anything the operator
decides STATICALLY (an argument count, a result type, a file mode) is fixed in
the wrapper while the caller's real arguments are runtime values. A wrapper
narrower than its operator does not signal: the surplus arguments simply go
nowhere, which is a wrong answer on the compile backends and a correct one on
the interpreter (the same call reaches a real variadic built-in there). Three
worked answers to that, in ascending cost: a `&rest` fold
(`variadicIdentity`/`variadicNonEmpty` for `+`/`min`), a `&rest` dispatch onto
the literal shapes the operator needs (`openWrapper`'s direction/element-type
plist, `concatenateWrapper`'s result-type family --
[concatenate-result-families.md](concatenate-result-families.md)), and the
wrapper implementing the general case itself (`mapFamilyWrapper` walks the
list-of-lists in a `do` loop for all six of `mapcar`/`mapc`/`mapcan`/`maplist`/
`mapcon`/`mapl`, because their list COUNT is static in call position but a
runtime property here; `alexandria:mappend` is `(apply #'mapcar f lists)` and
used to get every list but the first dropped --
[map-family.md](map-family.md)). When adding a wrapper, check the operator's CL
lambda list first: a wrapper narrower than the operator is a silent wrong answer
waiting for the first caller that needs the wide form.

## JVM method name mangling

`JvmLispCompiler.mangleMethodName()` maps `/ < > : .` to
`$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`/`$dot`, plus `%` -> `$pct`. `%` is legal in
a JVM method name, but OpenJDK's JVMCI uses the method name as a *format string*, so
a hot `%`-prefixed defun aborts its JIT compilation and prints a warning into the
program's stdout.

## Template-class embedding is a last resort

Prefer (1) macro expansion, (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`,
only then (3) an embedded Java template class (used by `java:` interop).

See [template-class-embedding.md](template-class-embedding.md) for the full detail.
