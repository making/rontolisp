# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**15,670 / 19,486 tests pass (80.4%)** -- 1,557 fail, 2,259 signal an error.

7 top-level forms could not be read, 417 could not be evaluated, 2 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,269 | 23 | 64 | 93.6% | 11 |
| characters | 259 | 212 | 7 | 40 | 81.9% | 11 |
| conditions | 673 | 553 | 58 | 62 | 82.2% | 11 |
| cons | 1,879 | 1,660 | 142 | 77 | 88.3% | 11 |
| data-and-control-flow | 1,428 | 1,221 | 75 | 132 | 85.5% | 12 |
| environment | 210 | 127 | 16 | 67 | 60.5% | 11 |
| eval-and-compile | 306 | 220 | 39 | 47 | 71.9% | 11 |
| files | 87 | 33 | 9 | 45 | 37.9% | 11 |
| hash-tables | 157 | 130 | 21 | 6 | 82.8% | 13 |
| iteration | 843 | 728 | 92 | 23 | 86.4% | 11 |
| misc | 740 | 729 | 7 | 4 | 98.5% | 11 |
| numbers | 1,444 | 1,254 | 52 | 138 | 86.8% | 15 |
| objects | 846 | 342 | 201 | 303 | 40.4% | 37 |
| packages | 500 | 431 | 38 | 31 | 86.2% | 11 |
| pathnames | 214 | 120 | 26 | 68 | 56.1% | 12 |
| printer | 536 | 241 | 124 | 171 | 45.0% | 48 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 12 |
| reader | 576 | 368 | 68 | 140 | 63.9% | 18 |
| sequences | 3,287 | 3,017 | 101 | 169 | 91.8% | 11 |
| streams | 758 | 447 | 106 | 205 | 59.0% | 55 |
| strings | 509 | 404 | 56 | 49 | 79.4% | 12 |
| structures | 1,030 | 744 | 58 | 228 | 72.2% | 36 |
| symbols | 1,145 | 1,077 | 28 | 40 | 94.1% | 11 |
| system-construction | 77 | 26 | 1 | 50 | 33.8% | 11 |
| types-and-classes | 626 | 317 | 209 | 100 | 50.6% | 13 |
| **total** | **19,486** | **15,670** | **1,557** | **2,259** | **80.4%** | **426** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 258 | `The variable *MINI-UNIVERSE* is unbound` |
| 213 | `The variable *UNIVERSE* is unbound` |
| 108 | `UnsupportedOperationException: setf does not support place: X` |
| 71 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 66 | `X is a macro or special operator, not a function` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *METHODS* is unbound` |
| 48 | `X supports only the 'character or '(unsigned-byte 8) element type` |
| 46 | `The function FIND-METHOD is undefined` |
| 31 | `UnsupportedOperationException: X :element-type must be the literal 'character or '(unsigned-byte 8)` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 29 | `The function NAME-CHAR is undefined` |
| 28 | `The function PPRINT-TABULAR is undefined` |
| 27 | `LispEvalException: X expects (compile name definition), got 1 argument(s)` |
| 27 | `The function READ-PRESERVING-WHITESPACE is undefined` |
| 25 | `LispEvalException: X: unknown specializer X (a class must be defined by defclass before the method)` |
| 25 | `The variable *CLASSES* is unbound` |
| 25 | `UnsupportedOperationException: X supports only a nil string-form (fresh-string) spec` |
| 22 | `Unknown keyword argument: :X` |
| 21 | `The assertion (X V 'X) failed.` |
| 21 | `The function WITH-CONDITION-RESTARTS is undefined` |
| 21 | `X expects a binary input stream` |
| 19 | `The function PPRINT-FILL is undefined` |
| 18 | `Function expects 1 argument, got 5` |
| 18 | `UnsupportedOperationException: setf X only supports aliasing an existing class X (setf (find-class 'alias) (fi` |
| 17 | `Function expects 1 argument, got 3` |
| 17 | `The function UPGRADED-ARRAY-ELEMENT-TYPE is undefined` |
| 16 | `The function BOOLE is undefined` |
| 15 | `The function PPRINT-LINEAR is undefined` |
| 15 | `compile-file is not supported (no file compiler: a program is compiled whole)` |
| 15 | `compile-file-pathname is not supported (no file compiler: nothing names its output)` |
| 14 | `The function COMPUTE-APPLICABLE-METHODS is undefined` |
| 14 | `The function SET-SYNTAX-FROM-CHAR is undefined` |
| 14 | `The function UNTRACE is undefined` |
| 14 | `X: there is no class named X` |
| 14 | `X: unsupported keyword :X (only :initial-element)` |
| 13 | `The function DISASSEMBLE is undefined` |
| 13 | `UnsupportedOperationException: X supports only a (var string) spec` |
| 12 | `The function ENSURE-GENERIC-FUNCTION is undefined` |
| 12 | `The function MAKE-LOAD-FORM is undefined` |

