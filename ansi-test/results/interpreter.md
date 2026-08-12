# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**7,254 / 17,689 tests pass (41.0%)** -- 3,370 fail, 7,065 signal an error.

7 top-level forms could not be read, 2,221 could not be evaluated, 26 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,277 | 408 | 195 | 674 | 31.9% | 32 |
| characters | 255 | 147 | 15 | 93 | 57.6% | 22 |
| conditions | 665 | 374 | 216 | 75 | 56.2% | 26 |
| cons | 1,701 | 558 | 93 | 1,050 | 32.8% | 107 |
| data-and-control-flow | 1,378 | 915 | 121 | 342 | 66.4% | 69 |
| environment | 209 | 81 | 10 | 118 | 38.8% | 19 |
| eval-and-compile | 295 | 193 | 41 | 61 | 65.4% | 29 |
| files | 87 | 8 | 3 | 76 | 9.2% | 18 |
| hash-tables | 156 | 77 | 27 | 52 | 49.4% | 21 |
| iteration | 751 | 553 | 133 | 65 | 73.6% | 112 |
| misc | 738 | 556 | 17 | 165 | 75.3% | 20 |
| numbers | 1,368 | 521 | 61 | 786 | 38.1% | 108 |
| objects | 695 | 239 | 163 | 293 | 34.4% | 239 |
| packages | 455 | 25 | 42 | 388 | 5.5% | 73 |
| pathnames | 215 | 21 | 3 | 191 | 9.8% | 18 |
| printer | 505 | 99 | 74 | 332 | 19.6% | 93 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 20 |
| reader | 560 | 52 | 288 | 220 | 9.3% | 41 |
| sequences | 2,403 | 1,459 | 202 | 742 | 60.7% | 903 |
| streams | 709 | 142 | 85 | 482 | 20.0% | 113 |
| strings | 495 | 192 | 100 | 203 | 38.8% | 33 |
| structures | 960 | 311 | 241 | 408 | 32.4% | 47 |
| symbols | 1,141 | 48 | 1,002 | 91 | 4.2% | 22 |
| system-construction | 58 | 12 | 3 | 43 | 20.7% | 37 |
| types-and-classes | 613 | 263 | 235 | 115 | 42.9% | 32 |
| **total** | **17,689** | **7,254** | **3,370** | **7,065** | **41.0%** | **2,254** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 370 | `IllegalArgumentException: X expects keyword arguments :X, got: :X` |
| 290 | `IllegalArgumentException: X expects keyword arguments :test/:test-not/:key, got: :X` |
| 222 | `The variable *MINI-UNIVERSE* is unbound` |
| 206 | `Function expects 1 argument, got 2` |
| 200 | `The variable *UNIVERSE* is unbound` |
| 181 | `The function MAKE-PACKAGE is undefined` |
| 163 | `X: there is no class named X` |
| 144 | `X expects 1 arguments, got 2` |
| 139 | `X expects at least one dimension` |
| 135 | `Function expects 1 argument, got 0` |
| 127 | `Not a function: X` |
| 123 | `The function MERGE is undefined` |
| 101 | `UnsupportedOperationException: setf does not support place: X` |
| 96 | `The function COUNT-IF-NOT is undefined` |
| 92 | `X expects 2 arguments, got 4` |
| 86 | `X expects 2 arguments, got 1` |
| 84 | `X expects 1 arguments, got 0` |
| 80 | `The variable *NUMBERS* is unbound` |
| 75 | `complex numbers are not supported (imaginary part X)` |
| 68 | `The function FLOAT-RADIX is undefined` |
| 66 | `car expects a cons cell, got: A` |
| 65 | `The variable *FLOATS* is unbound` |
| 63 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 63 | `X expects 1 arguments, got 5` |
| 57 | `Unknown keyword argument: :X` |
| 55 | `The variable #C is unbound` |
| 54 | `The function NUNION is undefined` |
| 53 | `The function SET-UP-PACKAGES is undefined` |
| 52 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 52 | `X expects 1 arguments, got 7` |
| 52 | `X: :displaced-to cannot be combined with :fill-pointer/:adjustable/:initial-element` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The function SET-EXCLUSIVE-OR is undefined` |
| 50 | `The variable *REALS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 49 | `The function NSET-EXCLUSIVE-OR is undefined` |
| 48 | `X: :X supports only the native default value` |
| 47 | `X expects an array, got "X"` |
| 46 | `The function FIND-METHOD is undefined` |
| 46 | `The function NINTERSECTION is undefined` |

