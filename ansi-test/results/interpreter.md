# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**8,198 / 17,874 tests pass (45.9%)** -- 3,013 fail, 6,663 signal an error.

7 top-level forms could not be read, 2,116 could not be evaluated, 0 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,283 | 389 | 214 | 680 | 30.3% | 25 |
| characters | 255 | 144 | 18 | 93 | 56.5% | 21 |
| conditions | 665 | 370 | 223 | 72 | 55.6% | 25 |
| cons | 1,809 | 721 | 137 | 951 | 39.9% | 77 |
| data-and-control-flow | 1,388 | 862 | 207 | 319 | 62.1% | 58 |
| environment | 209 | 81 | 10 | 118 | 38.8% | 18 |
| eval-and-compile | 295 | 187 | 54 | 54 | 63.4% | 28 |
| files | 87 | 13 | 7 | 67 | 14.9% | 17 |
| hash-tables | 156 | 77 | 27 | 52 | 49.4% | 20 |
| iteration | 753 | 487 | 199 | 67 | 64.7% | 109 |
| misc | 738 | 556 | 17 | 165 | 75.3% | 19 |
| numbers | 1,379 | 508 | 74 | 797 | 36.8% | 96 |
| objects | 695 | 239 | 163 | 293 | 34.4% | 238 |
| packages | 455 | 24 | 43 | 388 | 5.3% | 72 |
| pathnames | 215 | 71 | 29 | 115 | 33.0% | 17 |
| printer | 505 | 99 | 74 | 332 | 19.6% | 92 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 19 |
| reader | 560 | 50 | 290 | 220 | 8.9% | 40 |
| sequences | 2,454 | 1,685 | 217 | 552 | 68.7% | 851 |
| streams | 709 | 146 | 87 | 476 | 20.6% | 112 |
| strings | 495 | 192 | 100 | 203 | 38.8% | 32 |
| structures | 960 | 311 | 241 | 408 | 32.4% | 46 |
| symbols | 1,138 | 710 | 344 | 84 | 62.4% | 24 |
| system-construction | 58 | 12 | 3 | 43 | 20.7% | 36 |
| types-and-classes | 613 | 264 | 235 | 114 | 43.1% | 31 |
| **total** | **17,874** | **8,198** | **3,013** | **6,663** | **45.9%** | **2,123** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 370 | `IllegalArgumentException: X expects keyword arguments :X, got: :X` |
| 290 | `IllegalArgumentException: X expects keyword arguments :test/:test-not/:key, got: :X` |
| 229 | `The variable *MINI-UNIVERSE* is unbound` |
| 213 | `Function expects 1 argument, got 2` |
| 200 | `The variable *UNIVERSE* is unbound` |
| 181 | `The function MAKE-PACKAGE is undefined` |
| 163 | `X: there is no class named X` |
| 151 | `X expects 1 arguments, got 2` |
| 139 | `X expects at least one dimension` |
| 138 | `Function expects 1 argument, got 0` |
| 104 | `UnsupportedOperationException: setf does not support place: X` |
| 92 | `X expects 2 arguments, got 4` |
| 89 | `X expects 2 arguments, got 1` |
| 86 | `X expects 1 arguments, got 0` |
| 80 | `The variable *NUMBERS* is unbound` |
| 74 | `complex numbers are not supported (imaginary part X)` |
| 68 | `The function FLOAT-RADIX is undefined` |
| 65 | `The variable *FLOATS* is unbound` |
| 63 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 63 | `Unknown keyword argument: :X` |
| 63 | `X expects 1 arguments, got 5` |
| 62 | `Index 1 out of bounds for length 1` |
| 55 | `The variable #C is unbound` |
| 54 | `The function NUNION is undefined` |
| 54 | `X: :displaced-to cannot be combined with :fill-pointer/:adjustable/:initial-element` |
| 52 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 52 | `The function SET-UP-PACKAGES is undefined` |
| 51 | `X expects 1 arguments, got 7` |
| 51 | `X expects an array, got "X"` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *REALS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 49 | `The function NSET-EXCLUSIVE-OR is undefined` |
| 48 | `X: :X supports only the native default value` |
| 46 | `The function FIND-METHOD is undefined` |
| 46 | `The function NINTERSECTION is undefined` |
| 45 | `a macro function expects 1 or 2 arguments, got 0` |
| 44 | `IllegalArgumentException: Unsupported type specifier: X` |
| 44 | `The function SUBLIS is undefined` |
| 44 | `UnsupportedOperationException: X :element-type must be the literal 'character or '(unsigned-byte 8)` |

