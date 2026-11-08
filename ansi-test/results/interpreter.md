# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**17,474 / 20,077 tests pass (87.0%)** -- 1,251 fail, 1,352 signal an error.

0 top-level forms could not be read, 257 could not be evaluated, 4 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,319 | 17 | 20 | 97.3% | 8 |
| characters | 259 | 233 | 4 | 22 | 90.0% | 8 |
| conditions | 673 | 600 | 29 | 44 | 89.2% | 8 |
| cons | 1,882 | 1,749 | 75 | 58 | 92.9% | 8 |
| data-and-control-flow | 1,428 | 1,271 | 63 | 94 | 89.0% | 8 |
| environment | 210 | 142 | 11 | 57 | 67.6% | 8 |
| eval-and-compile | 306 | 258 | 26 | 22 | 84.3% | 8 |
| files | 87 | 37 | 10 | 40 | 42.5% | 8 |
| hash-tables | 157 | 136 | 15 | 6 | 86.6% | 10 |
| iteration | 843 | 777 | 44 | 22 | 92.2% | 8 |
| misc | 740 | 731 | 9 | 0 | 98.8% | 8 |
| numbers | 1,444 | 1,250 | 105 | 89 | 86.6% | 11 |
| objects | 846 | 599 | 128 | 119 | 70.8% | 19 |
| packages | 500 | 436 | 38 | 26 | 87.2% | 8 |
| pathnames | 215 | 144 | 27 | 44 | 67.0% | 8 |
| printer | 732 | 464 | 166 | 102 | 63.4% | 38 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 8 |
| reader | 668 | 547 | 77 | 44 | 81.9% | 11 |
| sequences | 3,287 | 3,128 | 54 | 105 | 95.2% | 8 |
| streams | 801 | 671 | 73 | 57 | 83.8% | 8 |
| strings | 509 | 437 | 54 | 18 | 85.9% | 9 |
| structures | 1,287 | 1,022 | 70 | 195 | 79.4% | 17 |
| symbols | 1,145 | 1,090 | 23 | 32 | 95.2% | 8 |
| system-construction | 77 | 28 | 1 | 48 | 36.4% | 8 |
| types-and-classes | 625 | 405 | 132 | 88 | 64.8% | 10 |
| **total** | **20,077** | **17,474** | **1,251** | **1,352** | **87.0%** | **261** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 259 | `The variable *MINI-UNIVERSE* is unbound` |
| 233 | `The variable *UNIVERSE* is unbound` |
| 71 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *METHODS* is unbound` |
| 28 | `The function PPRINT-TABULAR is undefined` |
| 25 | `LispEvalException: There is no method on #<function X with qualifiers X and specializers (#X :X X :X X :X X :X` |
| 25 | `LispEvalException: X: unknown specializer X (a class must be defined by defclass before the method)` |
| 25 | `The variable *CLASSES* is unbound` |
| 21 | `The assertion (X V 'X) failed.` |
| 21 | `The function WITH-CONDITION-RESTARTS is undefined` |
| 20 | `compile-file is not supported (no file compiler: a program is compiled whole)` |
| 19 | `The function PPRINT-FILL is undefined` |
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
| 11 | `X expects (reduce fn list) or (reduce fn list :initial-value init)` |
| 11 | `X: unsupported option :X` |
| 11 | `make-sequence: unsupported result type #X :X X :X X :X X :X X :X X` |
| 10 | `X: no clause matches 1` |
| 10 | `end of file` |
| 9 | `The function COMPILE-AND-LOAD is undefined` |
| 9 | `The function FILE-AUTHOR is undefined` |
| 9 | `The function MAKE-SBT-16 is undefined` |
| 9 | `UnsupportedOperationException: make-string supports only the literal :initial-element and :element-type keywor` |
| 9 | `fill: unsupported keyword :X` |
| 8 | `Can't get random element of type X` |
| 8 | `The function MAKE-STRUCT-TEST-31 is undefined` |
| 8 | `The function MAKE-STRUCT-TEST-63 is undefined` |
| 8 | `The function MAKE-STRUCT-TEST-64 is undefined` |
| 8 | `The function MAKE-STRUCT-TEST-65 is undefined` |
| 8 | `The function MAKE-STRUCT-TEST-66 is undefined` |
| 7 | `X expects a pathname designator` |

