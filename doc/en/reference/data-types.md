# Data Types

| Type | Example | Description |
|------|---------|-------------|
| Integer | `42`, `-5`, `1,000` | 64-bit signed integer that auto-promotes to a big integer on overflow (interpreter and JVM), 31-bit signed integer (WASM) |
| Ratio | `1/3`, `-2/5` | Exact rational number (Common Lisp ratio), always normalized; supported by all three backends |
| Double | `3.14`, `-0.5`, `3,000.50`, `1d0`, `6.02e23` | 64-bit floating-point number |
| String | `"hello"` | String literal |
| Character | `#\a`, `#\Space`, `#\Newline` | Character literal (`#\` plus a glyph or a standard name: `Space`, `Newline`, `Tab`, `Return`, `Page`, `Backspace`, `Nul`, `Rubout`). The WASM backend indexes strings by byte, so non-ASCII characters are out of scope there |
| Symbol | `x`, `foo` | Identifier |
| Keyword | `:foo`, `:bar` | Self-evaluating symbol starting with `:` |
| Nil | `nil` | False / empty list |
| T | `t` | True |
| Pi | `pi` | The constant π, read as the double `3.141592653589793` |
| Cons | `(1 2 3)` | Linked list built from cons cells |
| Function | `#'car`, `(lambda (x) x)` | Function object obtained via `#'`/`function`/`lambda` |

Numeric literals may use `,` as a grouping separator between digits in the
integer part, so `1,000` reads as `1000` and `(+ 1,000 100)` evaluates to
`1100`. The comma is only treated as a separator when it sits between two
digits; it is stripped before parsing and applies to all three backends. This
differs from Common Lisp, where `,` is the unquote character (not supported
here).

Float literals may carry a Common Lisp exponent marker -- a mantissa followed
by one of `e`, `s`, `f`, `d`, `l` (case-insensitive), an optional sign, and an
exponent, e.g. `1d0`, `1e0`, `1.5d3` (`1500.0`), `-2e-3`, `6.02e23`. This works
in all three backends (it is a reader-level feature). **Unlike Common Lisp,
rontolisp has a single floating-point type, so every marker reads as the same
64-bit double** -- the single/short/long-float distinction (`1d0` vs `1e0` vs
`1f0`) is not preserved, and there is no `*read-default-float-format*`. A marker
that is not followed by exponent digits is not a float: `1d` and `1d0x` read as
symbols (like `1+`), not numbers.

In the **interpreter and the JVM compiler**, integer arithmetic never silently
wraps: when a `long` operation (`+`, `-`, `*`, `/`, `1+`, `1-`, `abs`, ...)
would overflow, the result is automatically promoted to an arbitrary-precision
big integer, and integer literals larger than a `long` are read as big integers.
A big-integer result that fits back in a `long` is demoted again, so values keep
a single canonical representation. For example, with
`(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))`, `(fact 32)` returns the
exact `263130836933693530167218012160000000`. The **WASM compiler** does not
support this: its integers are limited to 31-bit (`i31ref`) and overflow wraps.

**All three backends** support Common Lisp ratios (exact rational numbers).
`1/3` reads as a ratio literal, and integer division that does not divide
evenly returns a ratio instead of truncating:

```console
> 1/3
1/3
> (/ 1 2)
1/2
> (+ 1/2 1/3)
5/6
> (/ 1 2.0)
0.5
> (float 1/2)
0.5
```

Ratio results are always normalized -- reduced by the gcd with the sign on the
numerator (`2/4` reads as `1/2`), and demoted to an integer when the
denominator reduces to one (`(/ 10 2)` is `5`, `(+ 1/2 1/2)` is `1`).
Arithmetic, comparisons (`= < > <= >=`), `eq`/`eql`, `abs`/`min`/`max`/`1+`/`1-`/
`signum`, the predicates (`numberp`, `rationalp`, `zerop`, `plusp`, `minusp`),
`truncate`/`floor`/`ceiling`/`round`, `expt` with an integer exponent
(`(expt 2 -1)` is `1/2`), and `numerator`/`denominator` all handle ratios;
mixing in a float switches to float contagion. Unary `(/ x)` is the reciprocal
(`(/ 2)` is `1/2`).

Per backend, the components follow the integer representation: the
**interpreter and the JVM compiler** use big integers (a ratio of huge
numerators/denominators stays exact), while the **WASM compiler** keeps them
in the 31-bit `i31` range with no overflow promotion, like all of its integer
arithmetic. The runtime reader emitted for compiled `read`/`load` does not
parse ratio literals (a `1/3` token read at runtime is a symbol), and `mod`,
`evenp`/`oddp`, `gcd`/`lcm` and `isqrt` remain integer-only.
