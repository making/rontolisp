# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**8,571 / 17,982 tests pass (47.7%)** -- 2,957 fail, 6,454 signal an error.

7 top-level forms could not be read, 1,960 could not be evaluated, 3 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,283 | 389 | 214 | 680 | 30.3% | 25 |
| characters | 255 | 144 | 18 | 93 | 56.5% | 21 |
| conditions | 665 | 370 | 226 | 69 | 55.6% | 25 |
| cons | 1,799 | 749 | 149 | 901 | 41.6% | 87 |
| data-and-control-flow | 1,389 | 877 | 209 | 303 | 63.1% | 57 |
| environment | 209 | 96 | 11 | 102 | 45.9% | 18 |
| eval-and-compile | 295 | 188 | 54 | 53 | 63.7% | 28 |
| files | 87 | 12 | 8 | 67 | 13.8% | 17 |
| hash-tables | 156 | 89 | 29 | 38 | 57.1% | 20 |
| iteration | 780 | 511 | 202 | 67 | 65.5% | 82 |
| misc | 738 | 565 | 17 | 156 | 76.6% | 19 |
| numbers | 1,379 | 511 | 74 | 794 | 37.1% | 96 |
| objects | 777 | 287 | 189 | 301 | 36.9% | 112 |
| packages | 448 | 26 | 47 | 375 | 5.8% | 79 |
| pathnames | 214 | 79 | 26 | 109 | 36.9% | 18 |
| printer | 504 | 99 | 74 | 331 | 19.6% | 93 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 19 |
| reader | 567 | 53 | 286 | 228 | 9.3% | 33 |
| sequences | 2,453 | 1,705 | 216 | 532 | 69.5% | 851 |
| streams | 723 | 183 | 85 | 455 | 25.3% | 98 |
| strings | 495 | 228 | 100 | 167 | 46.1% | 32 |
| structures | 960 | 373 | 179 | 408 | 38.9% | 46 |
| symbols | 1,135 | 755 | 303 | 77 | 66.5% | 27 |
| system-construction | 58 | 20 | 4 | 34 | 34.5% | 36 |
| types-and-classes | 613 | 262 | 237 | 114 | 42.7% | 31 |
| **total** | **17,982** | **8,571** | **2,957** | **6,454** | **47.7%** | **1,970** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 370 | `IllegalArgumentException: X expects keyword arguments :X, got: :X` |
| 299 | `IllegalArgumentException: X expects keyword arguments :test/:test-not/:key, got: :X` |
| 233 | `The variable *MINI-UNIVERSE* is unbound` |
| 227 | `Function expects 1 argument, got 2` |
| 200 | `The variable *UNIVERSE* is unbound` |
| 181 | `The function MAKE-PACKAGE is undefined` |
| 153 | `X expects 1 arguments, got 2` |
| 147 | `Function expects 1 argument, got 0` |
| 139 | `X expects at least one dimension` |
| 138 | `X: there is no class named X` |
| 102 | `X expects 2 arguments, got 4` |
| 98 | `UnsupportedOperationException: setf does not support place: X` |
| 93 | `X expects 2 arguments, got 1` |
| 91 | `Unknown keyword argument: :X` |
| 86 | `X expects 1 arguments, got 0` |
| 80 | `The variable *NUMBERS* is unbound` |
| 76 | `complex numbers are not supported (imaginary part X)` |
| 68 | `The function FLOAT-RADIX is undefined` |
| 65 | `The variable *FLOATS* is unbound` |
| 64 | `X expects 1 arguments, got 5` |
| 63 | `Index 1 out of bounds for length 1` |
| 63 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 62 | `X is a macro or special operator, not a function` |
| 55 | `The variable #C is unbound` |
| 54 | `The function NUNION is undefined` |
| 54 | `X: :displaced-to cannot be combined with :fill-pointer/:adjustable/:initial-element` |
| 53 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 52 | `The function SET-UP-PACKAGES is undefined` |
| 52 | `X expects an array, got "X"` |
| 51 | `X expects 1 arguments, got 7` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *REALS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 49 | `The function NSET-EXCLUSIVE-OR is undefined` |
| 48 | `X: :X supports only the native default value` |
| 48 | `a macro function expects 1 or 2 arguments, got 0` |
| 46 | `IllegalArgumentException: Unsupported type specifier: X` |
| 46 | `The function FIND-METHOD is undefined` |
| 46 | `The function NINTERSECTION is undefined` |
| 44 | `X expects 2 arguments, got 3` |

