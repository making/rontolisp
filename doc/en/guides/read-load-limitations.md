# Compiled read/load Limitations

The JVM `read`/`load` reuse the JDK at runtime (`Long.parseLong`, `BigInteger`, `Double.parseDouble`, `java.nio.file`), so they parse the same value kinds as the interpreter: integers, big integers, floats, strings, symbols, `nil`/`t`, lists, `'quote` and `#'function`.

In every backend `read` parses one S-expression from a line of stdin: blank and comment-only lines are skipped (it keeps reading until a line contains a datum), EOF returns `nil`, and a form must fit on a single line.

The WASM reader has a hand-written parser and is narrower:

- **Integers are 31-bit.** Integer tokens are parsed to `i31` and wrap on overflow; there is no big-integer parsing.
- **Floats are read.** A decimal token (optional leading `-`, digits, one `.`, e.g. `1.0`, `-2.5`, `.5`, `5.`) parses to an `f64`-backed float, matching how float literals compile. There is no exponent (`1e3`) or grouping-comma support in the reader, and a token with two dots or any non-digit (e.g. `1.2.3`, `foo.bar`) stays a symbol.
- **Symbol interning is runtime-backed.** Symbols that appear in the compiled program resolve to the same offset the compiled `eval` uses; symbols seen only at runtime (e.g. a lambda parameter inside a loaded file) are interned in a runtime table so repeated occurrences stay consistent.
- **`load` requires a preopened directory.** It opens the file via WASI `path_open` relative to the first preopened directory (fd 3), so run with `--dir`.

`require`/`provide` are compile-time directives on the compiled backends, so they are **not** understood by the runtime `load` of compiled output: a file read at runtime via a computed or nested `load` must not contain them (only a literal, top-level `require`/`provide` works, consumed at compile time) — the same limitation as a runtime-loaded file's package directives (see [Packages](../reference/packages.md)).

`read-from-string` reuses the same runtime reader, so on the compiled backends it parses the same value kinds as `read` (and `#\` character literals parsed at runtime are out of scope on both compilers — character literals written directly in source are compiled fully). `parse-integer` is independent of the reader and works on all three backends; its `:start`/`:end` keywords are interpreter-only, and on the compiled backends the keyword names must be literal. Both are also usable as first-class values (`#'parse-integer`, `#'read-from-string`) on all three backends — passed via the single-argument wrapper, so the keyword/optional arguments are not available through the function value.
