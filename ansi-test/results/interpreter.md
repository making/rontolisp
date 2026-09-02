# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**9,109 / 18,063 tests pass (50.4%)** -- 2,802 fail, 6,152 signal an error.

7 top-level forms could not be read, 1,929 could not be evaluated, 3 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,355 | 596 | 85 | 674 | 44.0% | 17 |
| characters | 255 | 144 | 18 | 93 | 56.5% | 20 |
| conditions | 665 | 370 | 226 | 69 | 55.6% | 24 |
| cons | 1,812 | 768 | 153 | 891 | 42.4% | 83 |
| data-and-control-flow | 1,390 | 894 | 203 | 293 | 64.3% | 55 |
| environment | 209 | 100 | 11 | 98 | 47.8% | 17 |
| eval-and-compile | 295 | 188 | 54 | 53 | 63.7% | 27 |
| files | 87 | 12 | 8 | 67 | 13.8% | 16 |
| hash-tables | 156 | 101 | 23 | 32 | 64.7% | 19 |
| iteration | 780 | 512 | 202 | 66 | 65.6% | 81 |
| misc | 738 | 585 | 18 | 135 | 79.3% | 18 |
| numbers | 1,379 | 571 | 87 | 721 | 41.4% | 95 |
| objects | 777 | 288 | 188 | 301 | 37.1% | 111 |
| packages | 448 | 27 | 47 | 374 | 6.0% | 78 |
| pathnames | 214 | 82 | 26 | 106 | 38.3% | 17 |
| printer | 494 | 191 | 123 | 180 | 38.7% | 102 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 18 |
| reader | 569 | 58 | 286 | 225 | 10.2% | 30 |
| sequences | 2,456 | 1,773 | 147 | 536 | 72.2% | 847 |
| streams | 723 | 184 | 85 | 454 | 25.4% | 97 |
| strings | 495 | 248 | 94 | 153 | 50.1% | 31 |
| structures | 960 | 374 | 178 | 408 | 39.0% | 45 |
| symbols | 1,135 | 763 | 297 | 75 | 67.2% | 26 |
| system-construction | 58 | 20 | 4 | 34 | 34.5% | 35 |
| types-and-classes | 613 | 260 | 239 | 114 | 42.4% | 30 |
| **total** | **18,063** | **9,109** | **2,802** | **6,152** | **50.4%** | **1,939** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 370 | `IllegalArgumentException: X expects keyword arguments :X, got: :X` |
| 299 | `IllegalArgumentException: X expects keyword arguments :test/:test-not/:key, got: :X` |
| 233 | `The variable *MINI-UNIVERSE* is unbound` |
| 230 | `Function expects 1 argument, got 2` |
| 217 | `The function MAKE-PACKAGE is undefined` |
| 200 | `The variable *UNIVERSE* is unbound` |
| 152 | `X expects 1 arguments, got 2` |
| 149 | `X: there is no class named X` |
| 147 | `Function expects 1 argument, got 0` |
| 121 | `X: :displaced-to cannot be combined with :fill-pointer/:adjustable/:initial-element` |
| 102 | `X expects 2 arguments, got 4` |
| 101 | `UnsupportedOperationException: setf does not support place: X` |
| 93 | `X expects 2 arguments, got 1` |
| 86 | `X expects 1 arguments, got 0` |
| 82 | `Unknown keyword argument: :X` |
| 80 | `The function FLOAT-RADIX is undefined` |
| 80 | `The variable *NUMBERS* is unbound` |
| 75 | `complex numbers are not supported (imaginary part X)` |
| 69 | `Index 1 out of bounds for length 1` |
| 65 | `The variable *FLOATS* is unbound` |
| 63 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 62 | `X is a macro or special operator, not a function` |
| 56 | `The function SET-UP-PACKAGES is undefined` |
| 56 | `The variable #C is unbound` |
| 54 | `The function NUNION is undefined` |
| 53 | `The function MAKE-TWO-WAY-STREAM is undefined` |
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
| 43 | `The function NSET-DIFFERENCE is undefined` |
| 40 | `The function MAKE-CONCATENATED-STREAM is undefined` |
| 39 | `The variable CALL-ARGUMENTS-LIMIT is unbound` |
| 38 | `X expects 2 arguments, got 0` |

