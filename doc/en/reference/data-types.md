# Data Types

| Type | Example | Description |
|------|---------|-------------|
| Integer | `42`, `-5`, `1,000`, `#xff`, `#o777`, `#b1010` | 64-bit signed integer that auto-promotes to a big integer on overflow (interpreter and JVM), 31-bit signed integer (WASM). `#x`/`#o`/`#b` read hexadecimal/octal/binary literals |
| Ratio | `1/3`, `-2/5` | Exact rational number (Common Lisp ratio), always normalized; supported by all three backends |
| Double | `3.14`, `-0.5`, `3,000.50`, `1d0`, `6.02e23` | 64-bit floating-point number |
| String | `"hello"` | String literal |
| Character | `#\a`, `#\Space`, `#\Newline` | Character literal (`#\` plus a glyph or a standard name: `Space`, `Newline`, `Tab`, `Return`, `Page`, `Backspace`, `Nul`, `Rubout`). The WASM backend indexes strings by byte, so non-ASCII characters are out of scope there |
| Symbol | `x`, `foo` | Identifier |
| Keyword | `:foo`, `:bar` | Self-evaluating symbol starting with `:` |
| Nil | `nil` | False / empty list |
| T | `t` | True |
| Pi | `pi` | The constant π, read as the double `3.141592653589793` |
| Cons | `(1 2 3)`, `(a . 1)` | Linked list built from cons cells; `(a . b)` is dotted-pair notation for a single cell |
| Function | `#'car`, `(lambda (x) x)` | Function object obtained via `#'`/`function`/`lambda` |
| Array | `#(1 2 3)`, `#2A((1 2) (3 4))` | Fixed-size array of any rank (rank 1 = vector); `#(...)` and `#nA(...)` are self-evaluating array literals |
| Hash table | `(make-hash-table)` | Mutable key/value table with structural (`equal`) keys |

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

## Dotted pairs, association lists and property lists

The reader supports Common Lisp dotted-pair notation: `(a . b)` denotes a
single cons cell whose car is `a` and whose cdr is `b`, and `(a b . c)` is a
list whose final cdr is `c` instead of `nil`. This is how association-list
(alist) literals are written:

```lisp
(cdr (assoc 'b '((a . 1) (b . 2)))) ; => 2
```

Dotted tails also work in backquote templates (`` `(a . ,x) `` expands to a
`cons` chain), and the runtime reader of compiled programs parses the same
notation, so a `read`/`read-from-string` of `"(a . 1)"` behaves identically in
all backends. A standalone `.` outside a list is a read error, as in Common
Lisp, and `,@` cannot be combined with a dotted tail in a backquote template.
A dotted tail in **call position** (e.g. `(+ 1 . 2)`) is an error in all three
backends -- a dotted pair is only meaningful as data.

The alist function family -- `assoc`, `assoc-if`, `rassoc`, `acons`, `pairlis`
and `copy-alist` -- works in all three backends. `assoc` and `rassoc` compare
with `eql` by default and accept optional `:test`/`:key` keywords (`:test` a
function designator, e.g. `#'equal` for string keys; `:key` a selector applied
to each pair's car/cdr before the comparison), like `member`:

```lisp
(assoc "b" '(("a" . 1) ("b" . 2)) :test #'equal) ; => ("b" . 2)
```

Property lists (plists) -- flat lists of alternating indicator/value pairs
like `(:a 1 :b 2)` -- are the keyword-based cousin of alists. `getf` reads the
value for an indicator (two arguments only: no `&optional default`), the
`remf` macro removes an indicator/value pair from a plist held in a variable
or other `setf` place, and `&key` parameters in lambda lists are parsed from
the same shape. `(setf (getf ...))` is not a supported place and there are no
symbol plists (`get`/`symbol-plist`); to add or update an entry, rebuild the
list, e.g. by prepending with `list*`:

```lisp
(let ((p (list :a 1 :b 2)))
  (remf p :a)
  (getf (list* :c 3 p) :c)) ; => 3
```

## Arrays

`make-array`, `aref` and `(setf (aref ...))` work in all three backends. Arrays
of **any rank >= 1** are supported; the dimensions argument is an integer
(rank 1) or a non-empty list of integers, and `:initial-element` sets every
cell (defaulting to nil). Elements are stored row-major with O(1) access
(flat rank-independent access via
[`row-major-aref`](functions/row-major-aref.md) /
[`array-row-major-index`](functions/array-row-major-index.md)), and arrays are
compared by identity (`eq`), so two distinct arrays are never `equal`. `length`
returns the element count of a vector (rank-1 array); a multidimensional array
is not a sequence, so `length` signals an error on it. Unlike the hash-table
operators, the array operators are not exposed as first-class function values,
so `#'aref` and `#'make-array` are not available (call them directly). Vectors
can also be built with [`vector`](functions/vector.md) and read with
[`svref`](functions/svref.md), array shapes are inspected with
[`array-dimensions`](functions/array-dimensions.md) /
[`array-rank`](functions/array-rank.md) /
[`array-total-size`](functions/array-total-size.md), and
[`coerce`](functions/coerce.md) converts between lists, vectors and strings.
For numpy-style vector/matrix math on top of arrays, see the
[`linalg` package](../guides/linear-algebra.md). A
2-D array indexed in nested loops:

```lisp
(let ((m (make-array (list 2 3) :initial-element 0)))
  (setf (aref m 1 2) 9)
  (incf (aref m 1 2))
  (aref m 1 2)) ; => 10
```

The `#(...)` reader syntax denotes a self-evaluating rank-1 vector literal whose
elements are read as data (not evaluated), e.g. `#(1 2 3)` or `#(a "b")`. A
rank-n array is written `#nA((...) ...)` with its contents as nested lists of
depth n (`#2A` for a matrix, `#3A` for a rank-3 array, ...); every list at the
same depth must have the same length, so ragged contents are a read error.
Arrays print in the same readable syntax across all backends, with `prin1`
quoting string elements and `princ` not:

```lisp
(print #(1 2 3))                          ; #(1 2 3)
(princ #(a "b"))                          ; #(a b)
(print #2A((1 2) (3 4)))                  ; #2A((1 2) (3 4))
(aref #3A(((1 2) (3 4)) ((5 6) (7 8))) 1 0 1) ; => 6
(make-array (list 2 2) :initial-element 0) ; #2A((0 0) (0 0))
```

## Hash tables

`make-hash-table`, `gethash`, `(setf (gethash ...))`, `remhash`, `clrhash`,
`hash-table-count`, `hash-table-p` and `maphash` work in all three backends.
Keys are compared structurally (as if by `equal`): a list key like `(list r c)`
matches an equal list, and numbers, symbols, characters and strings match by
value. `:test` is accepted for familiarity but does not change this -- an `eql`
table also matches structurally-equal aggregate keys. Iteration order (`maphash`)
is not guaranteed across backends, so portable code should not depend on it. They
are also usable as first-class function values (`#'gethash`, `#'remhash`,
`#'clrhash`, `#'hash-table-count`, `#'hash-table-p`, `#'maphash`, and
`#'make-hash-table` in its no-argument form) on all three backends -- passed via
fixed-arity wrappers, so `gethash`'s optional default and `make-hash-table`'s
keyword arguments are not available through the function value. A typical use --
counting with `incf` on the place:

```lisp
(let ((counts (make-hash-table :test 'equal)))
  (dolist (w '("a" "b" "a"))
    (incf (gethash w counts 0)))
  (gethash "a" counts)) ; => 2
```
