# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**16,912 / 19,888 tests pass (85.0%)** -- 1,378 fail, 1,598 signal an error.

7 top-level forms could not be read, 335 could not be evaluated, 3 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,310 | 16 | 30 | 96.6% | 10 |
| characters | 259 | 228 | 5 | 26 | 88.0% | 10 |
| conditions | 673 | 562 | 51 | 60 | 83.5% | 10 |
| cons | 1,882 | 1,742 | 77 | 63 | 92.6% | 10 |
| data-and-control-flow | 1,428 | 1,249 | 70 | 109 | 87.5% | 11 |
| environment | 210 | 138 | 11 | 61 | 65.7% | 10 |
| eval-and-compile | 306 | 231 | 34 | 41 | 75.5% | 10 |
| files | 87 | 37 | 10 | 40 | 42.5% | 10 |
| hash-tables | 157 | 134 | 17 | 6 | 85.4% | 12 |
| iteration | 843 | 771 | 44 | 28 | 91.5% | 10 |
| misc | 740 | 733 | 7 | 0 | 99.1% | 10 |
| numbers | 1,444 | 1,303 | 38 | 103 | 90.2% | 14 |
| objects | 846 | 476 | 192 | 178 | 56.3% | 34 |
| packages | 500 | 431 | 38 | 31 | 86.2% | 10 |
| pathnames | 215 | 144 | 27 | 44 | 67.0% | 10 |
| printer | 724 | 460 | 150 | 114 | 63.5% | 48 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 11 |
| reader | 669 | 491 | 98 | 80 | 73.4% | 12 |
| sequences | 3,287 | 3,079 | 74 | 134 | 93.7% | 10 |
| streams | 801 | 671 | 73 | 57 | 83.8% | 11 |
| strings | 509 | 435 | 56 | 18 | 85.5% | 11 |
| structures | 1,104 | 823 | 75 | 206 | 74.5% | 29 |
| symbols | 1,145 | 1,090 | 23 | 32 | 95.2% | 10 |
| system-construction | 77 | 28 | 1 | 48 | 36.4% | 10 |
| types-and-classes | 626 | 346 | 191 | 89 | 55.3% | 12 |
| **total** | **19,888** | **16,912** | **1,378** | **1,598** | **85.0%** | **345** |

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
| 27 | `LispEvalException: X expects 2 arguments, got 1` |
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
| 14 | `The function UNTRACE is undefined` |
| 14 | `X: there is no class named X` |
| 14 | `X: unsupported keyword :X (only :initial-element)` |
| 13 | `The function DISASSEMBLE is undefined` |
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

