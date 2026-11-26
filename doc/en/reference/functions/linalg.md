# linalg Package Functions

The `linalg` package provides numpy-style vector and matrix operations over
the built-in arrays (the elementwise operations and reductions work for any
rank). It is **not part of Common Lisp**;
reference its functions with the `linalg:` qualifier (the package does not use
`cl`, so most programs stay in `cl-user` and call the qualified names). The
package is implemented once in Lisp source and behaves identically on every
backend, and its constructors build packed double-float arrays, so it computes
in floating point (`det`, `inv` and `solve` run like numpy's). Each name below
links to its own page; the [Vectors & Matrices
guide](../../guides/linear-algebra.md) gives an overview and worked examples.

| Function | Example | Result |
|----------|---------|--------|
| `linalg:zeros` | `(linalg:zeros 3)`, `(linalg:zeros '(2 2))` | `#d(0.0 0.0 0.0)`, `#d((0.0 0.0) (0.0 0.0))` (shape: integer or `(rows cols)` list) |
| `linalg:ones` | `(linalg:ones '(2 2))` | `#d((1.0 1.0) (1.0 1.0))` |
| `linalg:full` | `(linalg:full '(2 2) 7)` | `#d((7.0 7.0) (7.0 7.0))` |
| `linalg:zeros-like` | `(linalg:zeros-like #2A((1 2) (3 4)))` | `#d((0.0 0.0) (0.0 0.0))` (zeros with the input's shape and width) |
| `linalg:eye` | `(linalg:eye 2)` | `#d((1.0 0.0) (0.0 1.0))` (the identity matrix) |
| `linalg:arange` | `(linalg:arange 5)`, `(linalg:arange 2 10 2)` | `#d(0.0 1.0 2.0 3.0 4.0)`, `#d(2.0 4.0 6.0 8.0)` (stop exclusive; step may be negative) |
| `linalg:linspace` | `(linalg:linspace 0 1 5)` | `#d(0.0 0.25 0.5 0.75 1.0)` (n evenly spaced values, inclusive) |
| `linalg:from-list` | `(linalg:from-list '((1 2) (3 4)))` | `#d((1.0 2.0) (3.0 4.0))` (a flat list gives a vector) |
| `linalg:to-list` | `(linalg:to-list (linalg:eye 2))` | `((1.0 0.0) (0.0 1.0))` |
| `linalg:shape` | `(linalg:shape #2A((1 2 3) (4 5 6)))` | `(2 3)` |
| `linalg:ndim` | `(linalg:ndim #2A((1 2) (3 4)))` | `2` (the number of dimensions; 0 for a number) |
| `linalg:size` | `(linalg:size (linalg:eye 3))` | `9` (the total element count) |
| `linalg:reshape` | `(linalg:reshape (linalg:arange 6) '(2 3))` | `#d((0.0 1.0 2.0) (3.0 4.0 5.0))` (row-major; one extent may be `-1` and is inferred) |
| `linalg:flatten` | `(linalg:flatten (linalg:eye 2))` | `#d(1.0 0.0 0.0 1.0)` |
| `linalg:transpose` | `(linalg:transpose #2A((1 2 3) (4 5 6)))` | `#d((1.0 4.0) (2.0 5.0) (3.0 6.0))` (a vector is returned unchanged) |
| `linalg:pad` | `(linalg:pad #(1 2) 1)` | `#d(0.0 1.0 2.0 0.0)` (constant-0 padding; a list gives per-axis `(before after)` pairs) |
| `linalg:expand-dims` | `(linalg:expand-dims #(1 2 3) 0)` | `#d((1.0 2.0 3.0))` (a new extent-1 axis; numpy's `expand_dims` / torch's `unsqueeze`) |
| `linalg:squeeze` | `(linalg:squeeze #2A((1 2 3)))` | `#d(1.0 2.0 3.0)` (drops extent-1 axes; `:axis` picks which) |
| `linalg:concatenate` | `(linalg:concatenate (list #(1 2) #(3)))` | `#d(1.0 2.0 3.0)` (join a LIST of arrays along an existing `:axis`) |
| `linalg:stack` | `(linalg:stack (list #(1 2) #(3 4)))` | `#d((1.0 2.0) (3.0 4.0))` (join along a NEW `:axis`) |
| `linalg:slice` | `(linalg:slice #(0 1 2 3 4 5) '((nil nil 2)))` | `#d(0.0 2.0 4.0)` (basic numpy slicing; one `nil` / `(start end [step])` spec per axis) |
| `linalg:triu` | `(linalg:triu (linalg:ones '(3 3)) :k 1)` | `#d((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0))` (upper triangle; the causal mask) |
| `linalg:tril` | `(linalg:tril #2A((1 2) (3 4)))` | `#d((1.0 0.0) (3.0 4.0))` (lower triangle) |
| `linalg:add` | `(linalg:add #(1 2 3) 10)` | `#d(11.0 12.0 13.0)` (elementwise; a scalar operand broadcasts) |
| `linalg:sub` | `(linalg:sub #(5 5) 1)` | `#d(4.0 4.0)` |
| `linalg:mul` | `(linalg:mul m1 m2)` | The Hadamard (elementwise) product -- not the matrix product |
| `linalg:div` | `(linalg:div #(1 2 3) 2)` | `#d(0.5 1.0 1.5)` (a packed double-float array) |
| `linalg:+` | `(linalg:+ #(1 2) #(3 4) #(10 10))` | `#d(14.0 16.0)` (n-ary `add`; the CL operator spelling) |
| `linalg:-` | `(linalg:- #(10 10) 1 2)` | `#d(7.0 7.0)` (n-ary `sub`; one argument negates) |
| `linalg:*` | `(linalg:* #(1 2) #(3 4))` | `#d(3.0 8.0)` (n-ary `mul`, Hadamard -- not the matrix product) |
| `linalg:/` | `(linalg:/ #(1 2 3) 2)` | `#d(0.5 1.0 1.5)` (n-ary `div`; one argument gives the reciprocal) |
| `linalg:emap` | `(linalg:emap (lambda (x) (* x x)) (linalg:arange 4))` | `#d(0.0 1.0 4.0 9.0)` (apply a function to every element) |
| `linalg:exp` | `(linalg:exp (linalg:zeros 3))` | `#d(1.0 1.0 1.0)` (elementwise `e^x`) |
| `linalg:log` | `(linalg:log #(1 1 1))` | `#d(0.0 0.0 0.0)` (elementwise natural log) |
| `linalg:tanh` | `(linalg:tanh (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise hyperbolic tangent) |
| `linalg:sin` | `(linalg:sin (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise sine) |
| `linalg:cos` | `(linalg:cos (linalg:zeros 3))` | `#d(1.0 1.0 1.0)` (elementwise cosine) |
| `linalg:tan` | `(linalg:tan (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise tangent) |
| `linalg:asin` | `(linalg:asin (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise arc sine) |
| `linalg:acos` | `(linalg:acos (linalg:ones 3))` | `#d(0.0 0.0 0.0)` (elementwise arc cosine) |
| `linalg:atan` | `(linalg:atan (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise arc tangent) |
| `linalg:sinh` | `(linalg:sinh (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise hyperbolic sine) |
| `linalg:cosh` | `(linalg:cosh (linalg:zeros 3))` | `#d(1.0 1.0 1.0)` (elementwise hyperbolic cosine) |
| `linalg:sqrt` | `(linalg:sqrt #(4 9 16))` | `#d(2.0 3.0 4.0)` (elementwise square root) |
| `linalg:abs` | `(linalg:abs #(-3 2 -1))` | `#d(3.0 2.0 1.0)` (elementwise absolute value) |
| `linalg:square` | `(linalg:square #(1 2 3))` | `#d(1.0 4.0 9.0)` (elementwise `x * x`) |
| `linalg:negative` | `(linalg:negative #(1 -2 3))` | `#d(-1.0 2.0 -3.0)` (elementwise negation) |
| `linalg:sign` | `(linalg:sign #(-5 0 7))` | `#d(-1.0 0.0 1.0)` (elementwise sign) |
| `linalg:reciprocal` | `(linalg:reciprocal #(2 4 8))` | `#d(0.5 0.25 0.125)` (elementwise `1 / x`, in float) |
| `linalg:power` | `(linalg:power #(1 2 3) 2)` | `#d(1.0 4.0 9.0)` (elementwise `a ** b`; either operand may be a scalar) |
| `linalg:maximum` | `(linalg:maximum #(1 5 3) #(4 2 3))` | `#d(4.0 5.0 3.0)` (elementwise larger; either operand may be a scalar) |
| `linalg:minimum` | `(linalg:minimum #(1 5 3) 4)` | `#d(1.0 4.0 3.0)` (elementwise smaller; either operand may be a scalar) |
| `linalg:clip` | `(linalg:clip #(-2 0 3) -1.0 1.0)` | `#d(-1.0 0.0 1.0)` (elementwise `min(max(x, lo), hi)`) |
| `linalg:relu` | `(linalg:relu #(-2 0 3))` | `#d(0.0 0.0 3.0)` (elementwise `max(x, 0.0)`) |
| `linalg:erf` | `(linalg:erf #(0 1))` | `#d(0.0 0.842700792949715)` (elementwise Gauss error function) |
| `linalg:softmax` | `(linalg:softmax #(1 1 1 1))` | `#d(0.25 0.25 0.25 0.25)` (max-subtracted softmax; `:axis` normalizes per slice) |
| `linalg:log-softmax` | `(linalg:log-softmax #(0 0))` | `#d(-0.6931471805599453 -0.6931471805599453)` (the stable log of `softmax`) |
| `linalg:dot` | `(linalg:dot v1 v2)` | numpy-style dispatch: vec.vec scalar, mat.vec / vec.mat vector, mat.mat matrix product |
| `linalg:matmul` | `(linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8)))` | `#d((19.0 22.0) (43.0 50.0))` (the matrix product; rank >= 3 stacks on the last two axes) |
| `linalg:outer` | `(linalg:outer #(1 2) #(3 4 5))` | `#d((3.0 4.0 5.0) (6.0 8.0 10.0))` (the outer product) |
| `linalg:cross` | `(linalg:cross #(1 0 0) #(0 1 0))` | `#d(0.0 0.0 1.0)` (the 3-D cross product; length-2 vectors answer the implied scalar z) |
| `linalg:sum` | `(linalg:sum #2A((1 2) (3 4)))` | `10` (a reduction follows the element type; `:axis` / `:keepdims` keywords) |
| `linalg:mean` | `(linalg:mean #(1 2 3 4))` | `5/2` (a reduction follows the element type; `:axis` / `:keepdims` keywords) |
| `linalg:var` | `(linalg:var #(1 2 3 4))` | `1.25` (variance; `:axis` / `:keepdims` / `:ddof` keywords) |
| `linalg:std` | `(linalg:std #(2 4 4 4 5 5 7 9))` | `2.0` (the square root of `linalg:var`, same keywords) |
| `linalg:amax` | `(linalg:amax #2A((1 9) (3 4)))` | `9` (the largest element; `:axis` / `:keepdims` keywords) |
| `linalg:amin` | `(linalg:amin #(5 2 8))` | `2` (the smallest element; `:axis` / `:keepdims` keywords) |
| `linalg:argmax` | `(linalg:argmax #(1 9 3))` | `1` (first index on ties; `:axis` gives per-slice indices) |
| `linalg:argmin` | `(linalg:argmin #(5 2 8))` | `1` (first index on ties; `:axis` gives per-slice indices) |
| `linalg:norm` | `(linalg:norm #(3 4))` | `5.0` (the Euclidean / Frobenius norm) |
| `linalg:trace` | `(linalg:trace #2A((1 2) (3 4)))` | `5` (square matrices only) |
| `linalg:diff` | `(linalg:diff #(1 2 4 7 0))` | `#d(1.0 2.0 3.0 -7.0)` (the `:n`-th discrete difference along `:axis`; defaults 1 and the last axis) |
| `linalg:gradient` | `(linalg:gradient #(0 1 4 9 16))` | `#d(1.0 2.0 4.0 6.0 7.0)` (central differences, same length as the input; optional scalar spacing or coordinate vector) |
| `linalg:det` | `(linalg:det #2A((1 2) (3 4)))` | `-2.0` (floating point; a singular matrix may give a small epsilon) |
| `linalg:inv` | `(linalg:inv #2A((4 0) (2 4)))` | `#d((0.25 0.0) (-0.125 0.25))` (signals an error for a singular matrix) |
| `linalg:solve` | `(linalg:solve a b)` | The solution of `a . x = b` (`b` a vector or matrix) |
| `linalg:array-equal` | `(linalg:array-equal (linalg:eye 2) #2A((1 0) (0 1)))` | `t` (same shape and numerically equal elements; arrays themselves are only `eq`-comparable) |
| `linalg:equal` | `(linalg:equal #(1 5 3) #(2 5 1))` | `#d(0.0 1.0 0.0)` (elementwise `=` as a 0/1 mask; broadcasts) |
| `linalg:greater` | `(linalg:greater #(1 5 3) 2)` | `#d(0.0 1.0 1.0)` (elementwise `>` as a 0/1 mask) |
| `linalg:greater-equal` | `(linalg:greater-equal #(1 5 3) #(1 6 2))` | `#d(1.0 0.0 1.0)` (elementwise `>=` as a 0/1 mask) |
| `linalg:less` | `(linalg:less #(1 5 3) 3)` | `#d(1.0 0.0 0.0)` (elementwise `<` as a 0/1 mask) |
| `linalg:less-equal` | `(linalg:less-equal #(1 5 3) 3)` | `#d(1.0 0.0 1.0)` (elementwise `<=` as a 0/1 mask) |
| `linalg:where` | `(linalg:where #(1 0 1) 10 20)` | `#d(10.0 20.0 10.0)` (elementwise select on a non-zero mask; broadcasts) |
| `linalg:take-rows` | `(linalg:take-rows #2A((1 2 3) (4 5 6) (7 8 9)) #(2 0))` | `#d((7.0 8.0 9.0) (1.0 2.0 3.0))` (the axis-0 slices selected by an index vector) |
| `linalg:row` | `(linalg:row #2A((1 2 3) (4 5 6) (7 8 9)) 1)` | `#d(4.0 5.0 6.0)` (one axis-0 slice, axis dropped -- numpy's `x[i]`) |
| `linalg:gather` | `(linalg:gather #2A((10 11 12) (20 21 22)) #(2 0))` | `#d(12.0 20.0)` (the per-row elements `a[i, idx[i]]` of a matrix) |
| `linalg:one-hot` | `(linalg:one-hot #(1 0 2) 3)` | `#d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0))` (row i holds 1.0 in column `indices[i]`) |
| `linalg:seed` | `(linalg:seed 42)` | `42` (resets the shared random generator; seeded draws are identical on every backend) |
| `linalg:rand` | `(linalg:rand 4)` | Uniform `[0, 1)` draws with the given shape |
| `linalg:randn` | `(linalg:randn '(2 2))` | Standard-normal draws (Irwin-Hall; tails clip at +/- 6 sigma) |
| `linalg:uniform` | `(linalg:uniform 10 20 4)` | Uniform draws in `[lo, hi)` with the given shape |
| `linalg:choice` | `(linalg:choice 60000 4)` | 4 uniform indices in `[0, 60000)`, with replacement (a packed double vector) |
| `linalg:permutation` | `(linalg:permutation 10)` | The integers `0..9` in a Fisher-Yates shuffle (a packed double vector) |

