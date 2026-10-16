# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**16,511 / 19,769 tests pass (83.5%)** -- 1,324 fail, 1,934 signal an error.

7 top-level forms could not be read, 374 could not be evaluated, 2 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 1,311 | 15 | 30 | 96.7% | 11 |
| characters | 259 | 218 | 5 | 36 | 84.2% | 11 |
| conditions | 673 | 555 | 56 | 62 | 82.5% | 11 |
| cons | 1,882 | 1,742 | 77 | 63 | 92.6% | 11 |
| data-and-control-flow | 1,428 | 1,241 | 69 | 118 | 86.9% | 12 |
| environment | 210 | 132 | 11 | 67 | 62.9% | 11 |
| eval-and-compile | 306 | 224 | 37 | 45 | 73.2% | 11 |
| files | 87 | 33 | 9 | 45 | 37.9% | 11 |
| hash-tables | 157 | 132 | 19 | 6 | 84.1% | 13 |
| iteration | 843 | 742 | 76 | 25 | 88.0% | 11 |
| misc | 740 | 727 | 7 | 6 | 98.2% | 11 |
| numbers | 1,444 | 1,287 | 38 | 119 | 89.1% | 15 |
| objects | 846 | 365 | 181 | 300 | 43.1% | 37 |
| packages | 500 | 431 | 38 | 31 | 86.2% | 11 |
| pathnames | 214 | 124 | 22 | 68 | 57.9% | 12 |
| printer | 725 | 437 | 117 | 171 | 60.3% | 48 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 12 |
| reader | 576 | 389 | 61 | 126 | 67.5% | 18 |
| sequences | 3,287 | 3,065 | 75 | 147 | 93.2% | 11 |
| streams | 797 | 666 | 67 | 64 | 83.6% | 16 |
| strings | 509 | 435 | 56 | 18 | 85.5% | 12 |
| structures | 1,082 | 799 | 75 | 208 | 73.8% | 32 |
| symbols | 1,145 | 1,085 | 24 | 36 | 94.8% | 11 |
| system-construction | 77 | 28 | 1 | 48 | 36.4% | 11 |
| types-and-classes | 626 | 343 | 188 | 95 | 54.8% | 13 |
| **total** | **19,769** | **16,511** | **1,324** | **1,934** | **83.5%** | **383** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 258 | `The variable *MINI-UNIVERSE* is unbound` |
| 214 | `The variable *UNIVERSE* is unbound` |
| 105 | `UnsupportedOperationException: setf does not support place: X` |
| 71 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 53 | `The function SLOT-VALUE is undefined` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *METHODS* is unbound` |
| 49 | `The function FIND-METHOD is undefined` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 29 | `The function NAME-CHAR is undefined` |
| 28 | `The function PPRINT-TABULAR is undefined` |
| 27 | `LispEvalException: X expects 2 arguments, got 1` |
| 27 | `The function READ-PRESERVING-WHITESPACE is undefined` |
| 25 | `LispEvalException: X: unknown specializer X (a class must be defined by defclass before the method)` |
| 25 | `The variable *CLASSES* is unbound` |
| 24 | `Unknown keyword argument: :X` |
| 23 | `The function COMPUTE-APPLICABLE-METHODS is undefined` |
| 21 | `The assertion (X V 'X) failed.` |
| 21 | `The function WITH-CONDITION-RESTARTS is undefined` |
| 19 | `The function PPRINT-FILL is undefined` |
| 18 | `UnsupportedOperationException: setf X only supports aliasing an existing class X (setf (find-class 'alias) (fi` |
| 18 | `compile-file is not supported (no file compiler: a program is compiled whole)` |
| 17 | `The function UPGRADED-ARRAY-ELEMENT-TYPE is undefined` |
| 16 | `The function BOOLE is undefined` |
| 15 | `The function PPRINT-LINEAR is undefined` |
| 15 | `compile-file-pathname is not supported (no file compiler: nothing names its output)` |
| 14 | `The function SET-SYNTAX-FROM-CHAR is undefined` |
| 14 | `The function UNTRACE is undefined` |
| 14 | `X: there is no class named X` |
| 14 | `X: unsupported keyword :X (only :initial-element)` |
| 13 | `Function expects 1 argument, got 3` |
| 13 | `The function DISASSEMBLE is undefined` |
| 13 | `The function ENSURE-GENERIC-FUNCTION is undefined` |
| 12 | `UnsupportedOperationException: X option is not supported: (:X X)` |
| 11 | `The function MAKE-STRUCT-TEST-06 is undefined` |
| 11 | `X expects (reduce fn list) or (reduce fn list :initial-value init)` |
| 11 | `X: no package named X` |
| 11 | `X: unsupported option :X` |
| 11 | `remove-method is not supported (no method metaobjects exist to name a method)` |
| 10 | `GoSignal` |

