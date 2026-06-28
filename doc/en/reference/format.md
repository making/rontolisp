# format

A minimal subset of Common Lisp's `format`, implemented as a macro shared by the
interpreter and both compilers. With destination `t` it expands into
`princ`/`prin1`/`terpri` calls and returns nil; with destination `nil` it builds and
returns the formatted string (expanding into `princ-to-string`/`prin1-to-string` calls
folded with the internal string concatenation). The destination must be the literal `t`
or `nil` and the control string must be a string literal. All arguments are evaluated
left to right before any output.

| Directive | Meaning |
|-----------|---------|
| `~a`, `~A` | Aesthetic: prints the argument like `princ` (strings without quotes). With `:`, nil prints as `()` |
| `~s`, `~S` | Standard: prints the argument like `prin1` (readable, strings quoted). With `:`, nil prints as `()` |
| `~d`, `~D` | Decimal integer. With `:`, digits are grouped with commas; with `@`, a `+` sign precedes non-negative values |
| `~f`, `~F` | Fixed-format floating point. `~,Df` prints `D` digits after the decimal point (rounded); with `@`, a leading `+` |
| `~e`, `~E` | Exponential (scientific) floating point: `[-]d.ddde[+/-]xx`. `~,De` prints `D` digits after the decimal point (default 6, rounded); with `@`, a leading `+` |
| `~$` | Monetary: `~D$` prints `D` digits after the decimal point (default 2); with `@`, a leading `+` |
| `~%` | Newline (one, or the count given by a prefix parameter) |
| `~&` | Fresh line: a newline only if not already at the start of a line |
| `~~` | A literal `~` |

Directives accept prefix parameters (written after the `~`, comma-separated) and the
`:`/`@` modifiers. A parameter is a decimal number, a character (`'c`), `v` (consume an
argument and use its value), or `#` (the number of remaining arguments). Field directives
(`~a`/`~s`/`~d`/`~f`/`~e`/`~$`) take a leading minimum-width parameter; text shorter than the
width is padded (with the pad-character parameter, space by default). `~a`/`~s` pad on the
right (left with `@`); numbers pad on the left.

```lisp
(format t "Hello ~a, you are ~d years old.~%" 'world 42)
;; Hello world, you are 42 years old.
(format t "~:d and ~@d~%" 1000000 42)
;; 1,000,000 and +42
(format t "~,2f and ~$~%" 3.14159 3.14159)
;; 3.14 and 3.14
(format t "~e and ~,4e~%" 1234.5 pi)
;; 1.2345e+3 and 3.1416e+0
(format t "~10a|~5,'0d|~%" "foo" 42)
;; foo       |00042|
(format nil "list=~a" (list 1 2 3))
;; => "list=(1 2 3)"
(princ (format nil "Hello ~a!" 'world))
;; Hello world!
```

Limitations: other destinations (streams, strings with fill pointers) are not supported,
the control string cannot be a runtime value, and the remaining directives (`~c`, `~g`,
`~{`, ...) are not implemented. Further notes:

- A `~f` without a digit count (no `~,D`) falls back to each backend's native float
  printing, so its exact form is backend-specific; supply a digit count for portable
  output.
- `~e` builds its mantissa from integer arithmetic (so the output is identical on every
  backend) and the digit count must be a literal, not a runtime `v`. Because the WASM
  backend caps integers at the i31 range, the scaled mantissa limits `~,De` to roughly
  `D` ≤ 8 digits of precision; the default (`~e`, 6 digits) is well within that bound.
- The repeat count of `~%`/`~&`/`~~` must be a literal or `#` (a runtime `v` count there
  is not supported). `~&` decides whether to emit a newline from the actual output column
  for destination `t`, but from the surrounding literal text (a static approximation) for
  destination `nil`.
- On the WASM backend integers are limited to the i31 range, so `~:d` grouping of very
  large (bignum) integers works only in the interpreter and the JVM backend.

Like the other macros, `format` is not recognized by the embedded `eval` runtime in
compiled output (see [Compiled `eval` limitations](../guides/eval-limitations.md)).
