# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**16,142 / 19,528 tests pass (82.7%)** -- 1,367 fail, 2,019 signal an error.

7 top-level forms could not be read, 378 could not be evaluated, 2 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,309 | 17 | 30 | 96.5% | 11 |
| characters | 259 | 218 | 5 | 36 | 84.2% | 11 |
| conditions | 673 | 553 | 58 | 62 | 82.2% | 11 |
| cons | 1,882 | 1,742 | 77 | 63 | 92.6% | 11 |
| data-and-control-flow | 1,428 | 1,232 | 72 | 124 | 86.3% | 12 |
| environment | 210 | 132 | 11 | 67 | 62.9% | 11 |
| eval-and-compile | 306 | 222 | 39 | 45 | 72.5% | 11 |
| files | 87 | 33 | 9 | 45 | 37.9% | 11 |
| hash-tables | 157 | 130 | 21 | 6 | 82.8% | 13 |
| iteration | 843 | 729 | 89 | 25 | 86.5% | 11 |
| misc | 740 | 729 | 7 | 4 | 98.5% | 11 |
| numbers | 1,444 | 1,272 | 40 | 132 | 88.1% | 15 |
| objects | 846 | 344 | 200 | 302 | 40.7% | 37 |
| packages | 500 | 431 | 38 | 31 | 86.2% | 11 |
| pathnames | 214 | 124 | 22 | 68 | 57.9% | 12 |
| printer | 536 | 248 | 117 | 171 | 46.3% | 48 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 12 |
| reader | 576 | 370 | 66 | 140 | 64.2% | 18 |
| sequences | 3,287 | 3,053 | 77 | 157 | 92.9% | 11 |
| streams | 797 | 656 | 73 | 68 | 82.3% | 16 |
| strings | 509 | 420 | 56 | 33 | 82.5% | 12 |
| structures | 1,030 | 744 | 58 | 228 | 72.2% | 36 |
| symbols | 1,145 | 1,081 | 26 | 38 | 94.4% | 11 |
| system-construction | 77 | 27 | 1 | 49 | 35.1% | 11 |
| types-and-classes | 626 | 343 | 188 | 95 | 54.8% | 13 |
| **total** | **19,528** | **16,142** | **1,367** | **2,019** | **82.7%** | **387** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 258 | `The variable *MINI-UNIVERSE* is unbound` |
| 214 | `The variable *UNIVERSE* is unbound` |
| 108 | `UnsupportedOperationException: setf does not support place: X` |
| 71 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 66 | `X is a macro or special operator, not a function` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *METHODS* is unbound` |
| 46 | `The function FIND-METHOD is undefined` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 29 | `The function NAME-CHAR is undefined` |
| 28 | `The function PPRINT-TABULAR is undefined` |
| 27 | `LispEvalException: X expects 2 arguments, got 1` |
| 27 | `The function READ-PRESERVING-WHITESPACE is undefined` |
| 25 | `LispEvalException: X: unknown specializer X (a class must be defined by defclass before the method)` |
| 25 | `The variable *CLASSES* is unbound` |
| 22 | `Unknown keyword argument: :X` |
| 21 | `The assertion (X V 'X) failed.` |
| 21 | `The function WITH-CONDITION-RESTARTS is undefined` |
| 19 | `The function PPRINT-FILL is undefined` |
| 18 | `Function expects 1 argument, got 5` |
| 18 | `UnsupportedOperationException: setf X only supports aliasing an existing class X (setf (find-class 'alias) (fi` |
| 18 | `compile-file is not supported (no file compiler: a program is compiled whole)` |
| 17 | `Function expects 1 argument, got 3` |
| 17 | `The function UPGRADED-ARRAY-ELEMENT-TYPE is undefined` |
| 16 | `The function BOOLE is undefined` |
| 15 | `The function PPRINT-LINEAR is undefined` |
| 15 | `compile-file-pathname is not supported (no file compiler: nothing names its output)` |
| 14 | `The function COMPUTE-APPLICABLE-METHODS is undefined` |
| 14 | `The function SET-SYNTAX-FROM-CHAR is undefined` |
| 14 | `The function UNTRACE is undefined` |
| 14 | `X: there is no class named X` |
| 14 | `X: unsupported keyword :X (only :initial-element)` |
| 13 | `The function DISASSEMBLE is undefined` |
| 12 | `The function ENSURE-GENERIC-FUNCTION is undefined` |
| 12 | `The function MAKE-LOAD-FORM is undefined` |
| 12 | `UnsupportedOperationException: X option is not supported: (:X X)` |
| 11 | `The function MAKE-STRUCT-TEST-06 is undefined` |
| 11 | `X expects (reduce fn list) or (reduce fn list :initial-value init)` |
| 11 | `X: no package named X` |
| 11 | `X: unsupported option :X` |

