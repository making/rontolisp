# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**14,637 / 19,485 tests pass (75.1%)** -- 2,048 fail, 2,800 signal an error.

7 top-level forms could not be read, 439 could not be evaluated, 4 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,125 | 102 | 129 | 83.0% | 11 |
| characters | 259 | 209 | 10 | 40 | 80.7% | 11 |
| conditions | 673 | 543 | 68 | 62 | 80.7% | 11 |
| cons | 1,879 | 1,630 | 172 | 77 | 86.7% | 11 |
| data-and-control-flow | 1,428 | 1,076 | 213 | 139 | 75.4% | 12 |
| environment | 210 | 121 | 19 | 70 | 57.6% | 11 |
| eval-and-compile | 306 | 204 | 54 | 48 | 66.7% | 11 |
| files | 87 | 26 | 8 | 53 | 29.9% | 11 |
| hash-tables | 157 | 128 | 22 | 7 | 81.5% | 13 |
| iteration | 843 | 649 | 171 | 23 | 77.0% | 13 |
| misc | 740 | 716 | 19 | 5 | 96.8% | 11 |
| numbers | 1,444 | 1,227 | 77 | 140 | 85.0% | 15 |
| objects | 846 | 340 | 203 | 303 | 40.2% | 37 |
| packages | 492 | 168 | 120 | 204 | 34.1% | 29 |
| pathnames | 214 | 120 | 26 | 68 | 56.1% | 12 |
| printer | 544 | 240 | 123 | 181 | 44.1% | 48 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 13 |
| reader | 575 | 364 | 71 | 140 | 63.3% | 19 |
| sequences | 3,287 | 2,997 | 120 | 170 | 91.2% | 11 |
| streams | 759 | 241 | 82 | 436 | 31.8% | 56 |
| strings | 509 | 395 | 65 | 49 | 77.6% | 12 |
| structures | 1,030 | 712 | 59 | 259 | 69.1% | 36 |
| symbols | 1,144 | 1,070 | 27 | 47 | 93.5% | 12 |
| system-construction | 77 | 23 | 4 | 50 | 29.9% | 11 |
| types-and-classes | 626 | 313 | 213 | 100 | 50.0% | 13 |
| **total** | **19,485** | **14,637** | **2,048** | **2,800** | **75.1%** | **450** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 256 | `The variable *MINI-UNIVERSE* is unbound` |
| 213 | `The variable *UNIVERSE* is unbound` |
| 111 | `UnsupportedOperationException: setf does not support place: X` |
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
| 40 | `The function MAKE-CONCATENATED-STREAM is undefined` |
| 40 | `X: :displaced-to is not supported` |
| 33 | `The function MAKE-ECHO-STREAM is undefined` |
| 31 | `The function COPY-STRUCTURE is undefined` |
| 31 | `UnsupportedOperationException: X :element-type must be the literal 'character or '(unsigned-byte 8)` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 29 | `The function NAME-CHAR is undefined` |
| 27 | `LispEvalException: X expects (compile name definition), got 1 argument(s)` |
| 27 | `The function PPRINT-TABULAR is undefined` |
| 26 | `The function READ-PRESERVING-WHITESPACE is undefined` |
| 25 | `LispEvalException: X: unknown specializer X (a class must be defined by defclass before the method)` |
| 25 | `LispPackageException: No such package: X` |
| 25 | `LispPackageException: X is only supported as a literal top-level form` |
| 25 | `The variable *CLASSES* is unbound` |
| 25 | `UnsupportedOperationException: X supports only a nil string-form (fresh-string) spec` |
| 23 | `X supports only the 'character or '(unsigned-byte 8) element type` |
| 22 | `Unknown keyword argument: :X` |
| 21 | `The assertion (X V 'X) failed.` |
| 21 | `The function WITH-CONDITION-RESTARTS is undefined` |
| 20 | `X expects a binary input stream` |
| 18 | `Function expects 1 argument, got 5` |
| 18 | `The assertion (X (X X) (X (X X) '(5))) failed.` |
| 18 | `The function PPRINT-FILL is undefined` |
| 18 | `UnsupportedOperationException: setf X only supports aliasing an existing class X (setf (find-class 'alias) (fi` |
| 17 | `Function expects 1 argument, got 3` |
| 17 | `The function UPGRADED-ARRAY-ELEMENT-TYPE is undefined` |
| 16 | `The function BOOLE is undefined` |

