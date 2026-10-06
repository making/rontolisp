# d58. WASM `read-from-string`'s stop index counts UTF-8 bytes, not characters

Difficulty: Low

The one-argument call's second value (`%read-from-string-end`) is the reader cursor's delta,
and the WASM reader walks the string's UTF-8 bytes. Measured 2026-10-06:

| call | SBCL 2.2.9 | interpreter / JVM | P1 / component |
|---|---|---|---|
| `(multiple-value-list (read-from-string "λ x"))` | `(Λ 2)` | `(Λ 2)` | `(λ 3)` |
| `(multiple-value-list (read-from-string "\"日本\" x"))` | `("日本" 5)` | `("日本" 5)` | `("日本" 9)` |

(`λ` staying lowercase is the ASCII-only case folding, a separate matter.) A call passing more
than the string goes through the prelude `%read-from-string-full`, which counts characters
with `read-char` and is already right on WASM (`.kb/read-load-streams.md`).

Count the code points between the start and the cursor in `WasmReadFromStringCompiler.compileEnd`
(non-continuation bytes), and pin it beside ci-spec `read-from-string-stop-index`.
