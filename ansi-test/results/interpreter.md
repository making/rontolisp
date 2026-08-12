# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**7,878 / 17,687 tests pass (44.5%)** -- 2,750 fail, 7,059 signal an error.

7 top-level forms could not be read, 2,224 could not be evaluated, 0 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,277 | 408 | 195 | 674 | 31.9% | 31 |
| characters | 255 | 147 | 15 | 93 | 57.6% | 21 |
| conditions | 665 | 374 | 216 | 75 | 56.2% | 25 |
| cons | 1,702 | 558 | 93 | 1,051 | 32.8% | 105 |
| data-and-control-flow | 1,378 | 917 | 121 | 340 | 66.5% | 68 |
| environment | 209 | 81 | 10 | 118 | 38.8% | 18 |
| eval-and-compile | 295 | 193 | 41 | 61 | 65.4% | 28 |
| files | 87 | 8 | 3 | 76 | 9.2% | 17 |
| hash-tables | 156 | 77 | 27 | 52 | 49.4% | 20 |
| iteration | 751 | 553 | 133 | 65 | 73.6% | 111 |
| misc | 738 | 556 | 17 | 165 | 75.3% | 19 |
| numbers | 1,368 | 521 | 61 | 786 | 38.1% | 107 |
| objects | 695 | 239 | 163 | 293 | 34.4% | 238 |
| packages | 455 | 31 | 36 | 388 | 6.8% | 72 |
| pathnames | 215 | 21 | 3 | 191 | 9.8% | 17 |
| printer | 505 | 99 | 74 | 332 | 19.6% | 92 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 19 |
| reader | 560 | 51 | 289 | 220 | 9.1% | 40 |
| sequences | 2,403 | 1,459 | 202 | 742 | 60.7% | 902 |
| streams | 709 | 142 | 85 | 482 | 20.0% | 112 |
| strings | 495 | 192 | 100 | 203 | 38.8% | 32 |
| structures | 960 | 311 | 241 | 408 | 32.4% | 46 |
| symbols | 1,138 | 665 | 387 | 86 | 58.4% | 24 |
| system-construction | 58 | 12 | 3 | 43 | 20.7% | 36 |
| types-and-classes | 613 | 263 | 235 | 115 | 42.9% | 31 |
| **total** | **17,687** | **7,878** | **2,750** | **7,059** | **44.5%** | **2,231** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 370 | `IllegalArgumentException: X expects keyword arguments :X, got: :X` |
| 290 | `IllegalArgumentException: X expects keyword arguments :test/:test-not/:key, got: :X` |
| 223 | `The variable *MINI-UNIVERSE* is unbound` |
| 207 | `Function expects 1 argument, got 2` |
| 200 | `The variable *UNIVERSE* is unbound` |
| 181 | `The function MAKE-PACKAGE is undefined` |
| 163 | `X: there is no class named X` |
| 144 | `X expects 1 arguments, got 2` |
| 139 | `X expects at least one dimension` |
| 136 | `Function expects 1 argument, got 0` |
| 127 | `Not a function: X` |
| 123 | `The function MERGE is undefined` |
| 104 | `UnsupportedOperationException: setf does not support place: X` |
| 96 | `The function COUNT-IF-NOT is undefined` |
| 92 | `X expects 2 arguments, got 4` |
| 86 | `X expects 2 arguments, got 1` |
| 84 | `X expects 1 arguments, got 0` |
| 80 | `The variable *NUMBERS* is unbound` |
| 75 | `complex numbers are not supported (imaginary part X)` |
| 68 | `The function FLOAT-RADIX is undefined` |
| 66 | `car expects a cons cell, got: A` |
| 65 | `The variable *FLOATS* is unbound` |
| 64 | `X expects 1 arguments, got 5` |
| 63 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 57 | `Unknown keyword argument: :X` |
| 55 | `The variable #C is unbound` |
| 54 | `The function NUNION is undefined` |
| 53 | `The function SET-UP-PACKAGES is undefined` |
| 52 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 52 | `X: :displaced-to cannot be combined with :fill-pointer/:adjustable/:initial-element` |
| 51 | `X expects 1 arguments, got 7` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The function SET-EXCLUSIVE-OR is undefined` |
| 50 | `The variable *REALS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 49 | `The function NSET-EXCLUSIVE-OR is undefined` |
| 48 | `X: :X supports only the native default value` |
| 47 | `X expects an array, got "X"` |
| 46 | `The function FIND-METHOD is undefined` |
| 46 | `The function NINTERSECTION is undefined` |

