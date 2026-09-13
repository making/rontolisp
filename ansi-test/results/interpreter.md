# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**13,771 / 19,485 tests pass (70.7%)** -- 2,109 fail, 3,605 signal an error.

7 top-level forms could not be read, 449 could not be evaluated, 4 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 765 | 88 | 503 | 56.4% | 11 |
| characters | 259 | 209 | 10 | 40 | 80.7% | 11 |
| conditions | 673 | 543 | 68 | 62 | 80.7% | 11 |
| cons | 1,879 | 1,630 | 172 | 77 | 86.7% | 11 |
| data-and-control-flow | 1,428 | 1,070 | 213 | 145 | 74.9% | 12 |
| environment | 210 | 121 | 19 | 70 | 57.6% | 11 |
| eval-and-compile | 306 | 204 | 54 | 48 | 66.7% | 11 |
| files | 87 | 26 | 8 | 53 | 29.9% | 11 |
| hash-tables | 157 | 128 | 22 | 7 | 81.5% | 13 |
| iteration | 843 | 536 | 208 | 99 | 63.6% | 13 |
| misc | 740 | 644 | 19 | 77 | 87.0% | 11 |
| numbers | 1,444 | 1,060 | 82 | 302 | 73.4% | 25 |
| objects | 846 | 340 | 203 | 303 | 40.2% | 37 |
| packages | 492 | 168 | 120 | 204 | 34.1% | 29 |
| pathnames | 214 | 120 | 26 | 68 | 56.1% | 12 |
| printer | 544 | 229 | 128 | 187 | 42.1% | 48 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 13 |
| reader | 575 | 275 | 106 | 194 | 47.8% | 19 |
| sequences | 3,287 | 2,964 | 117 | 206 | 90.2% | 11 |
| streams | 759 | 225 | 82 | 452 | 29.6% | 56 |
| strings | 509 | 395 | 65 | 49 | 77.6% | 12 |
| structures | 1,030 | 713 | 58 | 259 | 69.2% | 36 |
| symbols | 1,144 | 1,070 | 27 | 47 | 93.5% | 12 |
| system-construction | 77 | 23 | 4 | 50 | 29.9% | 11 |
| types-and-classes | 626 | 313 | 210 | 103 | 50.0% | 13 |
| **total** | **19,485** | **13,771** | **2,109** | **3,605** | **70.7%** | **460** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 252 | `The variable *MINI-UNIVERSE* is unbound` |
| 210 | `The variable *UNIVERSE* is unbound` |
| 111 | `UnsupportedOperationException: setf does not support place: X` |
| 80 | `The function FLOAT-RADIX is undefined` |
| 67 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 66 | `X is a macro or special operator, not a function` |
| 56 | `The function SET-UP-PACKAGES is undefined` |
| 53 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *METHODS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 48 | `X: :X supports only the native default value` |
| 47 | `The function UNUSE-PACKAGE is undefined` |
| 46 | `The function FIND-METHOD is undefined` |
| 42 | `The variable #:FOR is unbound` |
| 40 | `The function MAKE-CONCATENATED-STREAM is undefined` |
| 40 | `X: :displaced-to is not supported` |
| 38 | `The function BIT-VECTOR-P is undefined` |
| 33 | `The function MAKE-ECHO-STREAM is undefined` |
| 33 | `The function RATIONAL is undefined` |
| 32 | `The function SIMPLE-VECTOR-P is undefined` |
| 31 | `The function COPY-STRUCTURE is undefined` |
| 31 | `UnsupportedOperationException: X :element-type must be the literal 'character or '(unsigned-byte 8)` |
| 29 | `The function BIT-AND is undefined` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 29 | `The function NAME-CHAR is undefined` |
| 28 | `The function BIT-ANDC1 is undefined` |
| 28 | `The function BIT-ANDC2 is undefined` |
| 28 | `The function BIT-EQV is undefined` |
| 28 | `The function BIT-IOR is undefined` |
| 28 | `The function BIT-NAND is undefined` |
| 28 | `The function BIT-NOR is undefined` |
| 28 | `The function BIT-ORC1 is undefined` |
| 28 | `The function BIT-ORC2 is undefined` |
| 28 | `The function BIT-XOR is undefined` |
| 28 | `The function SIMPLE-BIT-VECTOR-P is undefined` |
| 27 | `LispEvalException: X expects (compile name definition), got 1 argument(s)` |
| 27 | `The function ARRAY-IN-BOUNDS-P is undefined` |
| 27 | `The function PPRINT-TABULAR is undefined` |
| 26 | `The function READ-PRESERVING-WHITESPACE is undefined` |

