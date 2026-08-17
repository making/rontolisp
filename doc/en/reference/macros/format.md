# format

`(format destination control-string args...)`

A subset of Common Lisp's `format`, implemented as a macro shared by the
interpreter and both compilers. A literal `control-string` is expanded at compile
time; a computed one is rendered at run time (see [Runtime control
strings](#runtime-control-strings)), with the same directives either way. With
destination `t` the form expands into `princ`/`prin1`/`terpri` calls, writes to
standard output, and returns nil; with destination `nil` it builds and returns
the formatted string (expanding into `princ-to-string`/`prin1-to-string` calls
folded with the internal string concatenation); with any other destination
expression it builds the string the same way and then DISPATCHES on the value at
run time -- a stream is written with one `write-string` call and nil is returned
(a `with-output-to-string` string stream or a file stream), a `t` value writes to
`*standard-output*`, and a nil value returns the string. That test has to happen
at run time because nil does not name a stream: it is the "return the string"
destination, so a function that forwards its own `&optional stream` argument
(`(defun render (x &optional stream) (format stream ...))`, the Common Lisp
convention) answers a string when called without one. All arguments are evaluated left to right before any output.

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
| `~s`, `~S` | Standard: prints the argument like `prin1` (readable; a string is quoted and its embedded `"` / `\` escaped). With `:`, nil prints as `()` |
| `~w`, `~W` | Write: prints the argument like `write` -- `prin1` under the printer control variables. It takes no prefix parameters, and its modifiers bind variables the printer does not honor (`~:W` binds `*print-pretty*`, `~@W` unbinds `*print-level*`/`*print-length*`), so all three spellings print the same text |
| `~d`, `~D` | Decimal integer. With `:`, digits are grouped with commas; with `@`, a `+` sign precedes non-negative values |
| `~x`, `~o`, `~b` | Hexadecimal / octal / binary integer (uppercase digits), with the same parameters and modifiers as `~d` |
| `~R` | Radix: `~NR` prints the integer in radix `N` (2-36). Without the radix parameter the decimal digits are printed (English cardinal/ordinal output is not implemented) |
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
| `~?` | Recursive format: consumes a control string and a list of its arguments, rendered through the runtime renderer. `~@?` takes the inner control's arguments from the remaining arguments instead of a list |
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
(format t "~w and ~a~%" "str" "str")
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
"str" and str
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

Other destinations (strings with fill pointers) are not supported. The loop escape `~^` is
supported at the top level and inside `~{ ... ~}` / `~@{ ... ~}` bodies (the
join idiom `"~{~a~^, ~}"`; inside `~:{ ... ~}` it ends the current sublist's
body), but its `~:^`/`~@^` variants and prefix parameters are not. Further notes:

- A `~f` (and the fixed branch of `~g`) without a digit count falls back to the
  free-format float printing (the shortest round-trip decimal, identical on every
  backend), which may use exponent notation where `~f` with a digit count never
  would; supply a digit count for a fixed decimal layout. `~g` accepts no prefix
  parameters.
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
- `~w` prints as `prin1` and does not read `*print-escape*` / `*print-readably*`, so binding one of them around it does not switch it to `princ` -- the same gap `write-to-string` has (`write` itself honors them).

## Runtime control strings

A control string that is a runtime value -- a computed control expression, a
call through the function value `#'format` (`funcall`/`apply`), the inner
control of `~?`, or a condition's `format-control` slot -- is rendered by a
runtime renderer instead of being expanded statically. It understands the same
directives as the table above, so the same control string and arguments produce
the same text whichever way `format` reaches them (on every backend). Two
differences follow from the control being data rather than source:

- The renderer never signals: a malformed control (`"abc~"`), an unknown
  directive (`~Q`), an unterminated `~{`, and a missing argument render as text
  (the directive verbatim, `NIL` for the missing argument) rather than raising an
  error. A literal control reports the same problems at expansion time, where a
  diagnostic belongs; a runtime control usually arrives with the data being
  reported, and a report must not fail while reporting.
- The column-control directive `~t` (`~n,mT`, `~n@T`), the plural directive
  `~p`, the logical-block / justification directive `~<...~>` and the
  call-a-function directive `~/name/` are available here but not in the literal
  expansion -- a literal control using one falls back to this renderer, so all
  four work either way. `~t` measures the column from the text rendered so far.

`~<...~>` is justification and `~<...~:>` a logical block; the closing directive
decides which. Their SECTION rules are the standard ones: a justification's `~;`
segments consume arguments in turn, while a logical block's first section is the
prefix and, when there are three, the last is the suffix (neither consumes an
argument), and a block without `@` takes one argument -- a list -- as its whole
argument list. What does not happen is the LAYOUT: no padding to a minimum
column, no wrapping at the right margin, and of the conditional newlines only the
mandatory `~:@_` breaks a line (`~_` / `~:_` / `~@_` and `~i` do nothing).
Deciding the others needs the stream's current column, which no rontolisp stream
carries -- the same reason `pprint-newline` only honors `:mandatory`.

`~/name/` calls the named function as `(name stream object colon-p at-p)` and
splices what it writes. The name is looked up as if by `find-symbol`, where a
single and a double colon are equivalent, so `~/mypkg:helper/` reaches an
internal symbol too.

**Compiled output carries `~/name/` only when the compiler can see the
directive.** Resolving a function out of a control string at run time means any
function in the program can be reached by name, which is exactly what stops
`--optimize` from removing unused code -- so the compiler includes that part of
the renderer only when some string literal in the program spells a `~/name/`
directive (anywhere: the control at the call site, a control bound to a variable,
a control inside a spliced library). That covers every ordinary use. A control
string *assembled at run time* out of pieces that never spell the directive
signals instead of rendering it, naming the reason; compile with `--dynamic` to
keep the directive available unconditionally. The interpreter always supports it.

```lisp
(defun brackets (stream x &optional colonp atp)
  (princ (if colonp "[" "<") stream) (princ x stream) (princ (if atp "]" ">") stream))
(list (format nil "~@<a and ~a~:>" 1)
      (format nil "~<~@;~a-~a~:>" (list "x" "y"))
      (format nil "~/brackets/ ~:@/brackets/" 1 2)) ; => ("a and 1" "x-y" "<1> [2]")
```

`~r` without a radix parameter prints the decimal digits; English cardinals and
ordinals are not implemented.

Like the other macros, `format` is not recognized by the embedded `eval` runtime
in compiled output (see [Compiled `eval` limitations](../../guides/eval-limitations.md)).
