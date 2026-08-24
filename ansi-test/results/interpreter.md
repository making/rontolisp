# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**8,491 / 17,992 tests pass (47.2%)** -- 2,949 fail, 6,552 signal an error.

7 top-level forms could not be read, 1,951 could not be evaluated, 2 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,283 | 389 | 214 | 680 | 30.3% | 25 |
| characters | 255 | 144 | 18 | 93 | 56.5% | 21 |
| conditions | 665 | 370 | 226 | 69 | 55.6% | 25 |
| cons | 1,809 | 723 | 137 | 949 | 40.0% | 77 |
| data-and-control-flow | 1,387 | 875 | 209 | 303 | 63.1% | 59 |
| environment | 209 | 96 | 11 | 102 | 45.9% | 18 |
| eval-and-compile | 295 | 187 | 54 | 54 | 63.4% | 28 |
| files | 87 | 12 | 8 | 67 | 13.8% | 17 |
| hash-tables | 156 | 78 | 27 | 51 | 50.0% | 20 |
| iteration | 780 | 510 | 203 | 67 | 65.4% | 82 |
| misc | 738 | 556 | 17 | 165 | 75.3% | 19 |
| numbers | 1,379 | 511 | 74 | 794 | 37.1% | 96 |
| objects | 776 | 286 | 179 | 311 | 36.9% | 113 |
| packages | 448 | 25 | 47 | 376 | 5.6% | 79 |
| pathnames | 214 | 79 | 26 | 109 | 36.9% | 18 |
| printer | 504 | 99 | 74 | 331 | 19.6% | 93 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 19 |
| reader | 567 | 53 | 285 | 229 | 9.3% | 33 |
| sequences | 2,455 | 1,691 | 218 | 546 | 68.9% | 849 |
| streams | 723 | 179 | 89 | 455 | 24.8% | 98 |
| strings | 495 | 228 | 100 | 167 | 46.1% | 32 |
| structures | 960 | 373 | 179 | 408 | 38.9% | 46 |
| symbols | 1,136 | 745 | 313 | 78 | 65.6% | 26 |
| system-construction | 58 | 20 | 4 | 34 | 34.5% | 36 |
| types-and-classes | 613 | 262 | 237 | 114 | 42.7% | 31 |
| **total** | **17,992** | **8,491** | **2,949** | **6,552** | **47.2%** | **1,960** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 370 | `IllegalArgumentException: X expects keyword arguments :X, got: :X` |
| 291 | `IllegalArgumentException: X expects keyword arguments :test/:test-not/:key, got: :X` |
| 233 | `The variable *MINI-UNIVERSE* is unbound` |
| 227 | `Function expects 1 argument, got 2` |
| 200 | `The variable *UNIVERSE* is unbound` |
| 181 | `The function MAKE-PACKAGE is undefined` |
| 151 | `X expects 1 arguments, got 2` |
| 147 | `Function expects 1 argument, got 0` |
| 139 | `X expects at least one dimension` |
| 138 | `X: there is no class named X` |
| 103 | `UnsupportedOperationException: setf does not support place: X` |
| 92 | `X expects 2 arguments, got 1` |
| 92 | `X expects 2 arguments, got 4` |
| 86 | `X expects 1 arguments, got 0` |
| 80 | `The variable *NUMBERS* is unbound` |
| 73 | `complex numbers are not supported (imaginary part X)` |
| 70 | `Unknown keyword argument: :X` |
| 68 | `The function FLOAT-RADIX is undefined` |
| 65 | `The variable *FLOATS* is unbound` |
| 64 | `X expects 1 arguments, got 5` |
| 63 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 62 | `Index 1 out of bounds for length 1` |
| 59 | `X is a macro or special operator, not a function` |
| 55 | `The variable #C is unbound` |
| 54 | `The function NUNION is undefined` |
| 54 | `X: :displaced-to cannot be combined with :fill-pointer/:adjustable/:initial-element` |
| 53 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 52 | `The function SET-UP-PACKAGES is undefined` |
| 52 | `X expects 1 arguments, got 7` |
| 51 | `X expects an array, got "X"` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *REALS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 49 | `The function NSET-EXCLUSIVE-OR is undefined` |
| 48 | `X: :X supports only the native default value` |
| 47 | `a macro function expects 1 or 2 arguments, got 0` |
| 46 | `IllegalArgumentException: Unsupported type specifier: X` |
| 46 | `The function FIND-METHOD is undefined` |
| 46 | `The function NINTERSECTION is undefined` |
| 44 | `The function SUBLIS is undefined` |

