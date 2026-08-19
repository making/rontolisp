# Data Types

| Type | Example | Description |
|------|---------|-------------|
| Integer | `42`, `-5`, `1,000`, `#xff`, `#o777`, `#b1010` | 64-bit signed integer that auto-promotes to a big integer on overflow, exact at any magnitude on every backend. `#x`/`#o`/`#b` read hexadecimal/octal/binary literals |
| Ratio | `1/3`, `-2/5` | Exact rational number (Common Lisp ratio), always normalized; supported by all three backends |
| Double | `3.14`, `-0.5`, `3,000.50`, `1d0`, `6.02e23` | 64-bit floating-point number |
| String | `"hello"` | String literal |
| Character | `#\a`, `#\Space`, `#\Newline` | Character literal (`#\` plus a glyph or a standard name: `Space`, `Newline`, `Tab`, `Return`, `Page`, `Backspace`, `Nul`, `Rubout`). The WASM backend indexes strings by byte, so non-ASCII characters are out of scope there |
| Symbol | `x`, `foo` | Identifier |
| Keyword | `:foo`, `:bar` | Self-evaluating symbol starting with `:` |
| Nil | `nil` | False / empty list |
| T | `t` | True |
| Pi | `pi` | The constant π, read as the double `3.141592653589793` |
| Fixnum range | `most-positive-fixnum`, `most-negative-fixnum` | Read as self-evaluating integers like `pi`; the value is backend-dependent (a WASM fixnum is an unboxed 31-bit reference, the interpreter and the JVM backend use 64-bit longs) |
| Other limits | `char-code-limit`, `array-total-size-limit`, `array-dimension-limit` | Read as self-evaluating integers like the fixnum range; `char-code-limit` is `1114112` (full Unicode code points) on every backend, the array limits are backend-dependent |
| Cons | `(1 2 3)`, `(a . 1)` | Linked list built from cons cells; `(a . b)` is dotted-pair notation for a single cell |
| Function | `#'car`, `(lambda (x) x)` | Function object obtained via `#'`/`function`/`lambda` |
| Array | `#(1 2 3)`, `#2A((1 2) (3 4))` | Fixed-size array of any rank (rank 1 = vector); `#(...)` and `#nA(...)` are self-evaluating array literals |
| Hash table | `(make-hash-table)` | Mutable key/value table with structural (`equal`) keys |
| Structure | `#S(POINT :X 1 :Y 2)` | An instance of a [`defstruct`](special-forms/defstruct.md) type. `#S(...)` is both how an instance prints and a self-evaluating literal that reads back into one; the `defstruct` must appear in an earlier top-level form |

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

On **every backend**, integer arithmetic never silently wraps: when an
operation (`+`, `-`, `*`, `/`, `1+`, `1-`, `abs`, ...) overflows the fixed-width
representation, the result is automatically promoted to an arbitrary-precision
big integer, and integer literals of any magnitude are read exactly. A
big-integer result that fits back in the narrower representation is demoted
again, so values keep a single canonical representation. For example, with
`(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))`, `(fact 32)` returns the
exact `263130836933693530167218012160000000` everywhere. (The WASM compiler
promotes in two steps -- its unboxed 31-bit fixnums first box into a signed
64-bit value, then into a limb-based big integer -- but that is invisible to
programs.)

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
numerators/denominators stays exact), while the **WASM compiler** keeps ratio
components in the 31-bit fixnum range with no overflow promotion (plain
integers promote without bound, and `truncate`/`floor`/`ceiling`/`round`/
`mod`/`rem` over two integers divide exactly at any magnitude -- only a
division kept as a fraction is limited: components past 31 bits fold back, and
a limb-sized big integer in an uneven `/` traps). The runtime reader emitted
for compiled `read`/`load` does not
parse ratio literals (a `1/3` token read at runtime is a symbol), and `mod`,
`evenp`/`oddp`, `gcd`/`lcm` and `isqrt` remain integer-only.

## Comments, feature conditionals and `*features*`

Besides the `;` line comment, the reader supports the Common Lisp `#| ... |#`
block comment (nesting, per the standard) and the `#+`/`#-` feature
conditionals: `#+expr form` keeps `form` only when the feature expression
holds, `#-expr form` only when it does not. A feature expression is a feature
name or an `(and ...)`/`(or ...)`/`(not ...)` combination (spelled bare or as
keywords, case-insensitive). The active features are `:rontolisp` on every
backend plus one backend-identifying feature — `:rontolisp-interpreter`,
`:rontolisp-jvm` or `:rontolisp-wasm` — so one source file can select
per-backend code, and `:unicode`, the portable spelling of "characters are
Unicode code points" (true on every backend, so a library that branches on it
takes its UTF-8 path). The interpreter and the JVM also have
`:thread-support` (they really spawn threads — see
[`rontolisp:make-thread`](functions/rontolisp-make-thread.md)); a WASM compile
in reactor mode (`--no-wasi`, or `--no-gc`) additionally has
`:rontolisp-reactor` — the module's entry points are exports a host calls,
which is how the Clack handler backend picks its transport (see the
[Clack guide](../guides/clack.md)) — and a `--component` compile additionally
has `:rontolisp-component`, which names the component BOUNDARY rather than a
backend: a component's host functions cross the canonical ABI, so
[`rontolisp:wasm-import`](../guides/wasm-gc-module.md) is refused there and a
source that declares one guards it with `#-rontolisp-component`. (A
`--component --no-wasi` build is a reactor too, so it has both.)

`*features*` is an ordinary special variable holding that list, on every
backend: a program may `push` onto it, `setq` it, and bind it with `let` like
any other special.

A source may also **announce a feature about itself**: a top-level
`(pushnew :my-feature *features*)` — bare or inside an `eval-when`/`progn` —
is read by the reader, so a `#+my-feature` in the same file sees it. That is
the header idiom real Common Lisp gets from loading a file form at a time, and
it behaves identically on all four backends here. Only a **literal** keyword
push counts: a push whose value the program computes
(`(pushnew (intern name :keyword) *features*)`) is a real run-time push but is
invisible to the reader, because deciding it would mean running the program to
decide how the program is read. A `.asd` that needs to announce a feature to
the files of the systems it defines uses `:rontolisp-features` instead (see the
[Systems guide](../guides/asdf-systems.md)).

```lisp
#| a block comment
   #| nesting like Common Lisp |#
   still commented |#
#+rontolisp (print :ok)             ; kept: :rontolisp is always active
#-(or sbcl ccl) (print :portable)   ; kept: neither feature is active
#+sbcl (print (uses #.unsupported-syntax))
(print (car *features*))            ; the first feature is always :rontolisp

(pushnew :my-feature *features*)
#+my-feature (print :announced)     ; kept: the reader saw the push above
(print (and (member :my-feature *features*) t))
```

Notes:

- Reading happens once, at the frontend: the interpreter reads with
  `:rontolisp-interpreter`, and compiling to a `.class`/`.wasm` file reads with
  `:rontolisp-jvm`/`:rontolisp-wasm`, so the set a compiled program's `#+`
  conditionals were resolved against is fixed at compile time. Files pulled in
  by the compile-time `load`/`require`/`asdf:load-system` include are read with
  the same target features. The run-time `*features*` list starts out holding
  that same set.
- A form skipped by a failing `#+`/`#-` guard is skipped at the raw character
  level without being parsed, so it may use syntax rontolisp does not support
  (that is the point of guarding it).
- `#.` read-time evaluation **is** supported: each `#.` datum is evaluated
  just before its top-level form runs — against the global environment on the
  interpreter, and against the compile-time (macro-time) evaluator on the
  JVM/WASM compile path — and the value is substituted into the form. In
  `.asd` files a `#.` form is instead skipped with a warning (see the
  [Systems guide](../guides/asdf-systems.md)); the browser playground's
  Compile buttons do not support `#.`.
- The runtime reader of compiled programs (`read`, `read-from-string`, runtime
  `load`) does not know block comments or feature conditionals, like backquote
  — see [Compiled read/load Limitations](../guides/read-load-limitations.md).
- `:common-lisp` is deliberately **not** in `*features*`: rontolisp is a
  subset, not a conforming implementation.

## Source position literals (`rontolisp:current-file`, `rontolisp:current-line`)

Two symbols the reader substitutes with the position they stand on, the way
`pi` and `array-dimension-limit` are substituted: `rontolisp:current-file` becomes the
origin file as a string (or `nil` when there is none — a REPL line, a
`read-from-string`), and `rontolisp:current-line` becomes the 1-based line the
symbol itself is on. They are ordinary literals afterwards, so they cost
nothing at run time and read the same on the interpreter and on every compile
backend.

A file pulled in by `load` / `require` / `asdf:load-system` names **itself**,
not the entry file it was spliced into — which is the point: in a program
assembled from many files, a message can say where it really came from. The
file is spelled exactly as the frontend saw it (the path given on the command
line, or the one `load` resolved), which is also how a read error spells it.

```console
$ cat lib.lisp
(defun where ()
  (list rontolisp:current-file rontolisp:current-line))
$ cat main.lisp
(load "lib.lisp")
(print (where))
(print (list rontolisp:current-file rontolisp:current-line))
$ rontolisp main.lisp
("lib.lisp" 2)
("main.lisp" 3)
```

Notes:

- Substitution happens at **read** time, so inside a `defmacro` template these
  name the macro's own definition site, not its call site. A logging macro
  therefore takes them as arguments at the call site, the way C code passes
  `__FILE__` / `__LINE__`:

  ```console
  (defmacro log-at (file line msg)
    `(format t "~a:~a: ~a~%" ,file ,line ,msg))

  (log-at rontolisp:current-file rontolisp:current-line "started")
  ; prints e.g. app.lisp:12: started
  ```

- Only the qualified spellings are recognized (`rontolisp:current-file`,
  `rontolisp::current-file`, `rl:current-file`). Unlike the rest of the
  `rontolisp` package these are **not** available unqualified after
  `(in-package rontolisp)`: reading happens before any `in-package` directive
  is interpreted.
- Being read-time, they are substituted wherever they appear, quoted data
  included — `'rontolisp:current-line` is the number, not the symbol. This is
  the same rule `#+`/`#-` and `#.` follow.

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

### Packed float arrays (`#d` / `#f`)

`#d(...)` and `#f(...)` denote a **packed float array**: a float-typed array whose
elements are stored unboxed. `#d(...)` is `double-float` (f64) and `#f(...)` is
`single-float` (f32 -- half the memory, double the SIMD lane count). They read like
`#(...)`, but every element is coerced to the array's float type, so `#d(1 2 3)` and
`#d(1.0 2.0 3.0)` are the same vector and `(array-element-type #d(1.0))` is
`double-float` (`single-float` for `#f`). Higher-rank literals use nested lists --
`#d((1.0 2.0) (3.0 4.0))` is a matrix -- and
`(make-array n :element-type 'double-float)` (or `'single-float`) builds one at
runtime.

Scalars stay `double`: reading an element widens it to a `double` (a single-float
element is widened f32 -> f64), and storing one narrows it to the array's width
(f64 -> f32 for a single-float array). Storing a non-real is a type error (a general
array holds any value). Otherwise a packed array behaves like a general array of the
same numbers for every operation -- `aref`, `(setf (aref ...))`, `length`,
`row-major-aref`, `array-rank`, `array-dimensions` and `coerce` all work on it --
except that it prints with its own `#d(...)` / `#f(...)` reader syntax, so its printed
form reads back as a packed array of the same width (preserving the unboxed
representation) rather than degrading to a general one. It is simply the unboxed,
float-specialized representation the numeric kernels use, so fill pointers, adjustable
and displaced arrays are not available on it (those need a general array). The
double-float width is the default and what `linalg` produces. For fast vectorized
kernels over packed arrays -- and their optional hardware acceleration -- see the
[`vec` package](../guides/simd-acceleration.md). A packed array is also a binary I/O
buffer: [`read-sequence`](functions/read-sequence.md) / [`write-sequence`](functions/write-sequence.md)
move its elements as raw little-endian IEEE-754 in one bulk transfer (any rank, row-major),
which is how a weight file or a numpy dump is loaded.

```lisp
(aref #d(1.0 2.0 3.0) 1)                   ; => 2.0
(array-element-type #d(1 2 3))             ; => DOUBLE-FLOAT
(array-element-type #f(1.0 2.0))           ; => SINGLE-FLOAT
(print #d((1.0 2.0) (3.0 4.0)))            ; #d((1.0 2.0) (3.0 4.0))
(coerce #d(1 2 3) 'list)                   ; => (1.0 2.0 3.0)
(let ((v (make-array 3 :element-type 'single-float :initial-element 0.0)))
  (setf (aref v 0) 5)
  v)                                        ; => #f(5.0 0.0 0.0)
```

## Hash tables

`make-hash-table`, `gethash`, `(setf (gethash ...))`, `remhash`, `clrhash`,
`hash-table-count`, `hash-table-p` and `maphash` work in all three backends.
Keys are compared structurally (as if by `equal`): a list key like `(list r c)`
matches an equal list, and numbers, symbols, characters and strings match by
value. `:test` is accepted for familiarity but does not change this -- an `eql`
table also matches structurally-equal aggregate keys. Iteration order (`maphash`)
is not guaranteed across backends, so portable code should not depend on it. A
table itself prints as SBCL's unreadable tag minus its trailing identity hash --
`#<HASH-TABLE :TEST EQUAL :COUNT n>`, the same text on every backend, with no
entry content. `:TEST` is always `EQUAL`, the test lookup actually implements and
the one `hash-table-test` reports, whatever `:test` the table was made with;
`:COUNT` is the live entry count, the same number `hash-table-count` returns.
A key is placed by a depth-capped structural hash and then decided by `equal`, so
lookup does not depend on the size of the key's printed form and a key whose
structure is CYCLIC is usable -- stored and retrieved under the same object:

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (princ-to-string h))                      ; => "#<HASH-TABLE :TEST EQUAL :COUNT 1>"
```

They
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
