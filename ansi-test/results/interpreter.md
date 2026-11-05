# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**17,097 / 19,894 tests pass (85.9%)** -- 1,324 fail, 1,473 signal an error.

0 top-level forms could not be read, 307 could not be evaluated, 4 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,310 | 16 | 30 | 96.6% | 9 |
| characters | 259 | 233 | 4 | 22 | 90.0% | 9 |
| conditions | 673 | 582 | 36 | 55 | 86.5% | 9 |
| cons | 1,882 | 1,742 | 77 | 63 | 92.6% | 9 |
| data-and-control-flow | 1,428 | 1,263 | 67 | 98 | 88.4% | 10 |
| environment | 210 | 138 | 11 | 61 | 65.7% | 9 |
| eval-and-compile | 306 | 250 | 34 | 22 | 81.7% | 9 |
| files | 87 | 37 | 10 | 40 | 42.5% | 9 |
| hash-tables | 157 | 134 | 17 | 6 | 85.4% | 11 |
| iteration | 843 | 777 | 44 | 22 | 92.2% | 9 |
| misc | 740 | 733 | 7 | 0 | 99.1% | 9 |
| numbers | 1,444 | 1,315 | 38 | 91 | 91.1% | 13 |
| objects | 846 | 482 | 192 | 172 | 57.0% | 32 |
| packages | 500 | 436 | 38 | 26 | 87.2% | 9 |
| pathnames | 215 | 144 | 27 | 44 | 67.0% | 9 |
| printer | 731 | 467 | 162 | 102 | 63.9% | 40 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 10 |
| reader | 668 | 539 | 80 | 49 | 80.7% | 12 |
| sequences | 3,287 | 3,085 | 76 | 126 | 93.9% | 9 |
| streams | 801 | 670 | 74 | 57 | 83.6% | 9 |
| strings | 509 | 435 | 56 | 18 | 85.5% | 10 |
| structures | 1,104 | 837 | 67 | 200 | 75.8% | 28 |
| symbols | 1,145 | 1,090 | 23 | 32 | 95.2% | 9 |
| system-construction | 77 | 28 | 1 | 48 | 36.4% | 9 |
| types-and-classes | 626 | 370 | 167 | 89 | 59.1% | 10 |
| **total** | **19,894** | **17,097** | **1,324** | **1,473** | **85.9%** | **311** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 259 | `The variable *MINI-UNIVERSE* is unbound` |
| 217 | `The variable *UNIVERSE* is unbound` |
| 71 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *METHODS* is unbound` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 28 | `The function PPRINT-TABULAR is undefined` |
| 25 | `LispEvalException: X expects an instance, got #<function X` |
| 25 | `LispEvalException: X: unknown specializer X (a class must be defined by defclass before the method)` |
| 25 | `The variable *CLASSES* is unbound` |
| 24 | `The function COMPUTE-APPLICABLE-METHODS is undefined` |
| 24 | `Unknown keyword argument: :X` |
| 21 | `The assertion (X V 'X) failed.` |
| 21 | `The function WITH-CONDITION-RESTARTS is undefined` |
| 20 | `compile-file is not supported (no file compiler: a program is compiled whole)` |
| 19 | `The function PPRINT-FILL is undefined` |
| 19 | `X expects an instance, got #<function X` |
| 17 | `The function UPGRADED-ARRAY-ELEMENT-TYPE is undefined` |
| 17 | `compile-file-pathname is not supported (no file compiler: nothing names its output)` |
| 15 | `The function PPRINT-LINEAR is undefined` |
| 14 | `The function DISASSEMBLE is undefined` |
| 14 | `The function UNTRACE is undefined` |
| 14 | `X: unsupported keyword :X (only :initial-element)` |
| 13 | `X: there is no class named X` |
| 12 | `Function expects 1 argument, got 3` |
| 12 | `No applicable method: X on X` |
| 12 | `UnsupportedOperationException: setf does not support place: X` |
| 12 | `X: no package named X` |
| 11 | `The function MAKE-STRUCT-TEST-06 is undefined` |
| 11 | `UnsupportedOperationException: X option is not supported: :X` |
| 11 | `X expects (reduce fn list) or (reduce fn list :initial-value init)` |
| 11 | `X: unsupported option :X` |
| 11 | `make-sequence: unsupported result type #X :X X :X X :X X :X X :X X` |
| 10 | `The function FORMATTER is undefined` |
| 10 | `X: no clause matches 1` |
| 10 | `end of file` |
| 9 | `The function COMPILE-AND-LOAD is undefined` |
| 9 | `The function FILE-AUTHOR is undefined` |
| 9 | `The function MAKE-SBT-16 is undefined` |
| 9 | `UnsupportedOperationException: X :method-combination supports the X short forms only (progn/and/orXlist/nconc/` |

