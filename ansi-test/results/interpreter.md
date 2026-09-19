# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**15,018 / 19,486 tests pass (77.1%)** -- 1,680 fail, 2,788 signal an error.

7 top-level forms could not be read, 439 could not be evaluated, 3 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,178 | 49 | 129 | 86.9% | 11 |
| characters | 259 | 212 | 7 | 40 | 81.9% | 11 |
| conditions | 673 | 553 | 58 | 62 | 82.2% | 11 |
| cons | 1,879 | 1,656 | 146 | 77 | 88.1% | 11 |
| data-and-control-flow | 1,428 | 1,193 | 102 | 133 | 83.5% | 12 |
| environment | 210 | 123 | 16 | 71 | 58.6% | 11 |
| eval-and-compile | 306 | 216 | 42 | 48 | 70.6% | 11 |
| files | 87 | 26 | 8 | 53 | 29.9% | 11 |
| hash-tables | 157 | 129 | 21 | 7 | 82.2% | 13 |
| iteration | 843 | 723 | 97 | 23 | 85.8% | 13 |
| misc | 740 | 717 | 18 | 5 | 96.9% | 11 |
| numbers | 1,444 | 1,252 | 52 | 140 | 86.7% | 15 |
| objects | 846 | 342 | 201 | 303 | 40.4% | 37 |
| packages | 492 | 173 | 115 | 204 | 35.2% | 29 |
| pathnames | 214 | 120 | 26 | 68 | 56.1% | 12 |
| printer | 544 | 241 | 123 | 180 | 44.3% | 48 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 13 |
| reader | 576 | 368 | 68 | 140 | 63.9% | 18 |
| sequences | 3,287 | 3,017 | 100 | 170 | 91.8% | 11 |
| streams | 759 | 245 | 78 | 436 | 32.3% | 56 |
| strings | 509 | 404 | 56 | 49 | 79.4% | 12 |
| structures | 1,030 | 712 | 59 | 259 | 69.1% | 36 |
| symbols | 1,144 | 1,076 | 27 | 41 | 94.1% | 12 |
| system-construction | 77 | 26 | 1 | 50 | 33.8% | 11 |
| types-and-classes | 626 | 316 | 210 | 100 | 50.5% | 13 |
| **total** | **19,486** | **15,018** | **1,680** | **2,788** | **77.1%** | **449** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 256 | `The variable *MINI-UNIVERSE* is unbound` |
| 213 | `The variable *UNIVERSE* is unbound` |
| 108 | `UnsupportedOperationException: setf does not support place: X` |
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

