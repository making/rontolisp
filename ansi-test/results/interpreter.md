# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**16,942 / 19,888 tests pass (85.2%)** -- 1,375 fail, 1,571 signal an error.

7 top-level forms could not be read, 308 could not be evaluated, 3 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,310 | 16 | 30 | 96.6% | 9 |
| characters | 259 | 232 | 5 | 22 | 89.6% | 9 |
| conditions | 673 | 562 | 51 | 60 | 83.5% | 9 |
| cons | 1,882 | 1,742 | 77 | 63 | 92.6% | 9 |
| data-and-control-flow | 1,428 | 1,253 | 70 | 105 | 87.7% | 10 |
| environment | 210 | 138 | 11 | 61 | 65.7% | 9 |
| eval-and-compile | 306 | 239 | 35 | 32 | 78.1% | 9 |
| files | 87 | 37 | 10 | 40 | 42.5% | 9 |
| hash-tables | 157 | 134 | 17 | 6 | 85.4% | 11 |
| iteration | 843 | 771 | 44 | 28 | 91.5% | 9 |
| misc | 740 | 733 | 7 | 0 | 99.1% | 9 |
| numbers | 1,444 | 1,303 | 38 | 103 | 90.2% | 13 |
| objects | 846 | 480 | 192 | 174 | 56.7% | 33 |
| packages | 500 | 431 | 38 | 31 | 86.2% | 9 |
| pathnames | 215 | 144 | 27 | 44 | 67.0% | 9 |
| printer | 724 | 459 | 151 | 114 | 63.4% | 47 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 10 |
| reader | 669 | 496 | 93 | 80 | 74.1% | 11 |
| sequences | 3,287 | 3,079 | 74 | 134 | 93.7% | 9 |
| streams | 801 | 671 | 73 | 57 | 83.8% | 9 |
| strings | 509 | 435 | 56 | 18 | 85.5% | 10 |
| structures | 1,104 | 829 | 75 | 200 | 75.1% | 28 |
| symbols | 1,145 | 1,090 | 23 | 32 | 95.2% | 9 |
| system-construction | 77 | 28 | 1 | 48 | 36.4% | 9 |
| types-and-classes | 626 | 346 | 191 | 89 | 55.3% | 10 |
| **total** | **19,888** | **16,942** | **1,375** | **1,571** | **85.2%** | **318** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 259 | `The variable *MINI-UNIVERSE* is unbound` |
| 216 | `The variable *UNIVERSE* is unbound` |
| 71 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *METHODS* is unbound` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 28 | `The function PPRINT-TABULAR is undefined` |
| 27 | `The function READ-PRESERVING-WHITESPACE is undefined` |
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
| 14 | `X: there is no class named X` |
| 14 | `X: unsupported keyword :X (only :initial-element)` |
| 12 | `Function expects 1 argument, got 3` |
| 12 | `UnsupportedOperationException: setf does not support place: X` |
| 12 | `X: no package named X` |
| 11 | `No applicable method: X on X` |
| 11 | `The function MAKE-STRUCT-TEST-06 is undefined` |
| 11 | `UnsupportedOperationException: X option is not supported: :X` |
| 11 | `X expects (reduce fn list) or (reduce fn list :initial-value init)` |
| 11 | `X: unsupported option :X` |
| 11 | `end of file` |
| 10 | `The function FORMATTER is undefined` |
| 10 | `X: no clause matches 1` |
| 9 | `The function COMPILE-AND-LOAD is undefined` |
| 9 | `The function FILE-AUTHOR is undefined` |
| 9 | `The function MAKE-SBT-16 is undefined` |
| 9 | `UnsupportedOperationException: X :method-combination supports the X short forms only (progn/and/orXlist/nconc/` |

