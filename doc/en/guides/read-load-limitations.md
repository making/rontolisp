# Compiled read/load Limitations

The reader compiled into a JVM class or WASM module parses the same syntax as the interpreter's reader: integers, big integers (JVM), floats, strings, symbols, `nil`/`t`, lists (dotted pairs included), `'quote`, `#'function`, ratios (`1/3`), radix integers (`#x10`/`#o17`/`#b101`), character literals (`#\a`, `#\Space`, ...), vectors (`#(1 2)`), rank-n arrays (`#2A((1 2) (3 4))`), bit vectors (`#*101`, read as a general vector like the frontend), packed float arrays (`#f(...)`/`#d(...)`), structure literals (`#S(NAME :SLOT value ...)`), pathname literals (`#P"dir/file"`, read as the pathname VALUE carrying that namestring), `;` line comments and nesting `#| ... |#` block comments. A token no `#` dispatch claims reads as a symbol (`#foo` is the symbol `#FOO`), exactly as it does in source.

Three `#` forms are permanent exceptions, because they need an evaluator or the feature set at read time: `#.` read-time evaluation, `#+`/`#-` feature conditionals, and `#n=`/`#n#` reader labels. The compiled reader SIGNALS a catchable error on them instead of misreading (the interpreter's runtime `read` still resolves `#.` and `#+`/`#-` like the frontend, and reads labels).

A `#S(...)` datum resolves against the structure types the compiled program defines; a slot the datum omits takes its `nil` initform, a simple constant initform (numbers, strings, characters, symbols, quoted lists, nested literals) is re-read from its baked printed form, and an initform outside that set signals rather than silently substituting a wrong value.

In every backend `read` parses one S-expression from a line of stdin: blank and comment-only lines are skipped (it keeps reading until a line contains a datum), EOF returns `nil`, and a form must fit on a single line.

The WASM reader has a hand-written parser and is narrower in its NUMBERS and its error MESSAGES:

- **Integers parse exactly at any magnitude.** Integer and radix tokens promote through the same boxed-integer tiers the frontend uses (a value past the 31-bit fixnum range becomes a boxed integer, past the signed 64-bit range a limb-based big integer); ratio components stay 31-bit.
- **Floats have no exponent.** A decimal token (optional leading `-` or `+`, digits, one `.`, e.g. `1.0`, `-2.5`, `.5`, `5.`) parses to an `f64`-backed float. There is no exponent (`1e3`) support, and a token with two dots or any non-digit (e.g. `1.2.3`, `foo.bar`) stays a symbol.
- **Error messages are static.** A reader error signals (catchably under `handler-case`), but the message is a fixed text without the offending name interpolated -- the JVM and the interpreter carry the frontend's exact messages.
- **Symbol interning is runtime-backed.** Symbols that appear in the compiled program resolve to the same offset the compiled `eval` uses; symbols seen only at runtime (e.g. a lambda parameter inside a loaded file) are interned in a runtime table so repeated occurrences stay consistent.
- **`load` requires a preopened directory.** It opens the file via WASI `path_open` relative to the first preopened directory (fd 3), so run with `--dir`.

Backquote templates and the `*features*` substitution (see [Data Types](../reference/data-types.md#comments-feature-conditionals-and-features)) are frontend read-time constructs the runtime reader of compiled output does not resolve: a file read at runtime via `read`/`read-from-string`/a computed `load` must not use them. (`#| ... |#` block comments ARE skipped; `#+`/`#-` signal, as above.)

`require`/`provide` are compile-time directives on the compiled backends, so they are **not** understood by the runtime `load` of compiled output: a file read at runtime via a computed or nested `load` must not contain them (only a literal, top-level `require`/`provide` works, consumed at compile time) — the same limitation as a runtime-loaded file's package directives (see [Packages](../reference/packages.md)).

`read-from-string` reuses the same runtime reader, so on the compiled backends it parses the same syntax as `read`, and `(read-from-string (prin1-to-string x))` round-trips for every printable-readable value kind on all backends. `parse-integer` is independent of the reader and works on all three backends; its `:start`/`:end` keywords are interpreter-only, and on the compiled backends the keyword names must be literal. Both are also usable as first-class values (`#'parse-integer`, `#'read-from-string`) on all three backends — passed via the single-argument wrapper, so the keyword/optional arguments are not available through the function value.
