# format

`(format destination control-string args...)`

A minimal subset of Common Lisp's `format`, implemented as a macro shared by the
interpreter and both compilers. The `control-string` must be a literal: with
destination `t` the form expands into `princ`/`prin1`/`terpri` calls, writes to
standard output, and returns nil; with destination `nil` it builds and returns
the formatted string (expanding into `princ-to-string`/`prin1-to-string` calls
folded with the internal string concatenation); with any other destination
expression it builds the string the same way, writes it to the stream with one
`write-string` call, and returns nil (a `with-output-to-string` string stream or
a file stream). All arguments are evaluated left to right before any output.

```lisp
(format t "Hello ~a, you are ~d!~%" 'world 42)
```

```
Hello WORLD, you are 42!
```

With destination `nil` the result is returned as a string instead of printed:

```lisp
(format nil "~a+~a=~a" 1 2 3) ; => "1+2=3"
```

## Directives

| Directive | Meaning |
|-----------|---------|
| `~a`, `~A` | Aesthetic: prints the argument like `princ` (strings without quotes). With `:`, nil prints as `()` |
| `~s`, `~S` | Standard: prints the argument like `prin1` (readable, strings quoted). With `:`, nil prints as `()` |
| `~d`, `~D` | Decimal integer. With `:`, digits are grouped with commas; with `@`, a `+` sign precedes non-negative values |
| `~x`, `~o`, `~b` | Hexadecimal / octal / binary integer (uppercase digits), with the same parameters and modifiers as `~d` |
| `~R` | Radix: `~NR` prints the integer in radix `N` (2-36). The radix parameter is required (English cardinal/ordinal output is not implemented) |
| `~c`, `~C` | Character: prints the glyph like `write-char`. With `@`, the `#\` reader syntax (like `prin1`); with `:`, non-graphic characters print their name (`Newline`, `Space`, ...) |
| `~f`, `~F` | Fixed-format floating point. `~,Df` prints `D` digits after the decimal point (rounded); with `@`, a leading `+`. Full parameters: `~w,d,k,overflowchar,padchar F` |
| `~e`, `~E` | Exponential (scientific) floating point: `[-]d.ddde[+/-]xx`. `~,De` prints `D` digits after the decimal point (default 6, rounded); with `@`, a leading `+`. Full parameters: `~w,d,e,k,overflowchar,padchar,exponentchar E` (`k` must be 1) |
| `~g`, `~G` | General floating point: the plain float representation for magnitudes in `[0.1, 1e16)` (and zero), the `~e` form otherwise |
| `~$` | Monetary: `~D$` prints `D` digits after the decimal point (default 2); with `@`, a leading `+` |
| `~%` | Newline (one, or the count given by a prefix parameter) |
| `~&` | Fresh line: a newline only if not already at the start of a line |
| `~~` | A literal `~` |
| `~(str~)` | Case conversion of the processed `str`: downcase; `~:(` capitalizes every word, `~@(` capitalizes only the first word, `~:@(` upcases |
| `~[str0~;str1~:;default~]` | Conditional: the argument (an integer) selects a clause; `~:;` introduces the default. `~N[` / `~#[` select by a literal / by the number of remaining arguments; `~:[false~;true~]` tests nil; `~@[str~]` processes `str` (re-using the tested argument) only when it is non-nil |
| `~{str~}` | Iteration: applies `str` repeatedly to the elements of the list argument. `~N{` caps the passes at `N`; `~:{` iterates over a list of sublists; `~@{` iterates over the remaining arguments; `~:@{` treats each remaining argument as a sublist |
| `~?` | Recursive format: consumes a control string and a list of its arguments, rendered through the runtime renderer (its directive subset applies); `~@?` is not supported |
| `~*` | Argument jump: `~N*` skips `N` arguments (default 1), `~N:*` moves back `N`, `~N@*` jumps to argument `N` (default 0) |

Directives accept prefix parameters (written after the `~`, comma-separated) and
the `:`/`@` modifiers. A parameter is a decimal number, a character (`'c`), `v`
(consume an argument and use its value), or `#` (the number of remaining
arguments). Field directives (`~a`/`~s`/`~d`/`~x`/`~o`/`~b`/`~f`/`~e`/`~$`) take
a leading minimum-width parameter; text shorter than the width is padded (with
the pad-character parameter -- a `'c` literal or a runtime `v` -- space by
default). `~a`/`~s` pad on the right (left with `@`); numbers pad on the left.

```lisp
(format t "Hello ~a, you are ~d years old.~%" 'world 42)
(format t "~:d and ~@d~%" 1000000 42)
(format t "~,2f and ~$~%" 3.14159 3.14159)
(format t "~e and ~,4e~%" 1234.5 pi)
(format t "~10a|~5,'0d|~%" "foo" 42)
(princ (format nil "Hello ~a!" 'world))
(terpri)
(format t "~x ~o ~b ~8r~%" 255 64 5 4096)
(format t "~c ~@c ~:c~%" #\a #\b #\Newline)
(format t "~(~a~) ~:(~a~)~%" "FOO BAR" "foo bar")
(format t "~[zero~;one~:;many~] ~:[no~;yes~] ~@[x=~a~]~%" 1 t 42)
(format t "~{<~a>~} ~:{(~a,~a)~}~@{ ~a~}~%" '(1 2) '((x 1) (y 2)) 'a 'b)
(format t "~{~a~^, ~}~%" '(1 2 3))
(format t "~a ~:* ~a~%" 1)
```

```
Hello WORLD, you are 42 years old.
1,000,000 and +42
3.14 and 3.14
1.2345e+3 and 3.1416e+0
foo       |00042|
Hello WORLD!
FF 100 101 10000
a #\b Newline
foo bar Foo Bar
one yes x=42
<1><2> (X,1)(Y,2) A B
1, 2, 3
1  1
```

## Limitations

Other destinations (strings with fill pointers) are not supported, and the
column-control directives (`~t`, `~<...~>`) and `~r`
without a radix parameter are not implemented. The loop escape `~^` is
supported at the top level and inside `~{ ... ~}` / `~@{ ... ~}` bodies (the
join idiom `"~{~a~^, ~}"`; inside `~:{ ... ~}` it ends the current sublist's
body), but its `~:^`/`~@^` variants and prefix parameters are not. A control string that is a
runtime value -- a computed control expression, or a call through the function
value `#'format` (`funcall`/`apply`) -- renders through a runtime fallback that
supports only the basic directives `~a ~s ~d ~x ~c ~%` and `~~` (an unknown
directive is emitted verbatim); with a nil destination it returns the string,
any other destination is written with one `write-string` call. Further notes:

- A `~f` (and the fixed branch of `~g`) without a digit count falls back to each
  backend's native float printing, so its exact form is backend-specific; supply
  a digit count for portable output. `~g` accepts no prefix parameters.
- `~e` builds its mantissa from integer arithmetic (so the output is identical on
  every backend) and the digit count must be a literal, not a runtime `v`. The scaled mantissa is computed in 64-bit arithmetic, which limits `~,De` to roughly `D` <= 17 digits of precision (identically on every backend); the default (`~e`, 6 digits) is well within that bound. The scale factor of `~e` must be 1 (the default), and
  the overflow character of `~f`/`~e` requires a literal width.
- The repeat count of `~%`/`~&`/`~~` must be a literal or `#` (a runtime `v` count
  there is not supported). `~&` decides whether to emit a newline from the actual
  output column for destination `t`, but from the surrounding literal text (a
  static approximation) for destination `nil` and inside composite
  (`~(`/`~[`/`~{`) bodies.
- Composite directives nest freely: a `~[` conditional may hold another `~[` (or
  a `~{ ... ~}` iteration) in any of its clauses. When a runtime-selected `~[`
  has clauses that consume DIFFERENT numbers of arguments, the rest of the
  control string is expanded once per clause so each branch continues from its
  own argument position, exactly as Common Lisp's argument pointer would. A
  branch that would need more arguments than were supplied signals only if it is
  actually selected.
- Because `format` expands statically, a `~@[` clause must consume exactly the
  tested argument, `#` and `~@{` are not available inside a `~{ ... ~}` body, and
  an argument-divergent `~[` nested inside another composite directive
  (`~(`/`~{`) is not supported.
- `~:d` grouping and the radix directives `~x`/`~o`/`~b`/`~r` are exact for integers of any magnitude on every backend.

Like the other macros, `format` is not recognized by the embedded `eval` runtime
in compiled output (see [Compiled `eval` limitations](../../guides/eval-limitations.md)).
