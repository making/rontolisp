# rontolisp

A minimal Common Lisp subset implemented in Java. It supports three execution modes:

- **Interpreter** -- Tree-walking evaluation with REPL support
- **JVM compiler** -- Compiles Lisp to `.class` bytecode runnable on any JRE
- **WASM compiler** -- Compiles Lisp to `.wasm` binary using wasm-GC and WASI Preview 1

No external runtime dependencies for core libraries. The JVM and WASM bytecode generators are written from scratch without ASM or other code generation libraries. The CLI uses JLine for interactive REPL features (history, line editing).

**Try it in your browser: [https://making.github.io/rontolisp/](https://making.github.io/rontolisp/)** -- a playground where rontolisp itself runs as WebAssembly. Evaluate expressions in the REPL, and compile your source to downloadable `.class` and `.wasm` files, entirely client-side. See [`web/README.md`](web/README.md) for how it is built and deployed.


## Requirements

- Java 25+ (for building and running the JAR)
- [GraalVM](https://www.graalvm.org/) 25+ (optional, for native image build)
- [wasmtime](https://wasmtime.dev/) (for running `.wasm` output, optional)

## Build

```bash
./mvnw clean package
```

This produces `target/rontolisp-0.1.0-SNAPSHOT-exec.jar`, an executable JAR with all dependencies included.

### Native Image (GraalVM)

Build a native executable using GraalVM:

```bash
./mvnw -Pnative clean package
```

This produces `target/rontolisp`, a standalone native binary with instant startup.

**Requirements:**
- GraalVM 25+ (with `native-image` tool)

**Usage:**

```bash
# REPL
./target/rontolisp

# File interpretation
./target/rontolisp program.lisp

# Compile to JVM bytecode
./target/rontolisp hello.lisp -o Hello.class

# Compile to WASM
./target/rontolisp hello.lisp -o hello.wasm
```

## Usage

### REPL

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar
```

```
> (+ 1 2)
3
> (* 3 (+ 4 5))
27
> (defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
fact
> (fact 10)
3628800
> (let ((x 10) (y 20)) (+ x y))
30
> (quit)
```

The REPL supports line editing, history navigation (up/down keys), and Ctrl-C to cancel input. Type `(quit)` or Ctrl-D to exit.

### File Interpretation

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar program.lisp
```

Example (`program.lisp`):

```lisp
(defun square (x) (* x x))
(print (square 5))
(print (square 12))
```

```
25
144
```

### Compile to JVM Bytecode

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar hello.lisp -o Hello.class
java Hello
```

Example (`hello.lisp`):

```lisp
(print (+ 1 2))
```

```
3
```

The generated `.class` file targets Java 6 (class version 50) and depends only on `java.lang` and `java.io` standard library classes. It runs on any JRE 6+.

### Compile to WASM

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar hello.lisp -o hello.wasm
wasmtime --wasm gc hello.wasm
```

```
3
```

The generated `.wasm` binary uses:

- **wasm-GC** -- Integers are represented as `i31ref`. Floating-point numbers are boxed in a `float_struct { f64 }`. All values on the stack are typed as `(ref eq)`.
- **WASI Preview 1** -- `fd_write` for stdout output.

Requires a wasm-GC capable runtime such as wasmtime 14+.

### Compile to a WASI 0.2 component

Add `--component` to emit a WASI 0.2 (Preview 2) **component** instead of a Preview 1 core module. The component prints through `wasi:cli/stdout` and is run with `wasmtime run`:

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar hello.lisp --component -o hello.wasm
wasmtime run -W gc=y hello.wasm
```

```
3
```

The default output (without `--component`) stays a Preview 1 core module, so nothing changes for existing usage.

Notes and current limitations of component mode:

- Requires a runtime with both the component model and wasm-GC enabled (wasmtime 24+; pass `-W gc=y`).
- Only `print` / stdout output is supported. Reading (`read`, `read-line`) and file I/O (`open`, `close`, `load`, `with-open-file`) are not yet available in component mode.
- The compiled Lisp behaves identically to the Preview 1 output for the supported features.

### Self-Hosted REPL

Because `read`, `eval` and `print` are available in every backend, a REPL can be written in RontoLisp itself and compiled to a standalone `.class` or `.wasm`:

Example (`repl.lisp`):

```lisp
(princ "> ")
(setq form (read))
(while form
  (print (eval form))
  (princ "> ")
  (setq form (read)))
```

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar repl.lisp               # interpret
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar repl.lisp -o repl.class
java repl                                                                  # REPL on the JVM
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar repl.lisp -o repl.wasm
wasmtime --wasm gc repl.wasm                                               # REPL on WASM
```

```
> (defun square (x) (* x x))
square
> (mapcar #'square '(1 2 3))
(1 4 9)
> (- 5)
-5
```

`read` returns `nil` at EOF, so the loop exits on Ctrl-D (entering `nil` or `()` also ends it). Each form entered at the prompt is parsed by the runtime reader and evaluated by the embedded `eval` runtime, so the [Compiled `eval` limitations](#compiled-eval-limitations) and [Compiled `read`/`load` limitations](#compiled-readload-limitations) apply.

## Language Reference

### Data Types

| Type | Example | Description |
|------|---------|-------------|
| Integer | `42`, `-5`, `1,000` | 64-bit signed integer that auto-promotes to a big integer on overflow (interpreter and JVM), 31-bit signed integer (WASM) |
| Ratio | `1/3`, `-2/5` | Exact rational number (Common Lisp ratio), always normalized; supported by all three backends |
| Double | `3.14`, `-0.5`, `3,000.50`, `1d0`, `6.02e23` | 64-bit floating-point number |
| String | `"hello"` | String literal |
| Symbol | `x`, `foo` | Identifier |
| Keyword | `:foo`, `:bar` | Self-evaluating symbol starting with `:` |
| Nil | `nil` | False / empty list |
| T | `t` | True |
| Pi | `pi` | The constant π, read as the double `3.141592653589793` |
| Cons | `(1 2 3)` | Linked list built from cons cells |
| Function | `#'car`, `(lambda (x) x)` | Function object obtained via `#'`/`function`/`lambda` |

Numeric literals may use `,` as a grouping separator between digits in the
integer part, so `1,000` reads as `1000` and `(+ 1,000 100)` evaluates to
`1100`. The comma is only treated as a separator when it sits between two
digits; it is stripped before parsing and applies to all three backends. This
differs from Common Lisp, where `,` is the unquote character (not supported
here).

Float literals may carry a Common Lisp exponent marker -- a mantissa followed
by one of `e`, `s`, `f`, `d`, `l` (case-insensitive), an optional sign, and an
exponent, e.g. `1d0`, `1e0`, `1.5d3` (`1500.0`), `-2e-3`, `6.02e23`. This works
in all three backends (it is a reader-level feature). **Unlike Common Lisp,
rontolisp has a single floating-point type, so every marker reads as the same
64-bit double** -- the single/short/long-float distinction (`1d0` vs `1e0` vs
`1f0`) is not preserved, and there is no `*read-default-float-format*`. A marker
that is not followed by exponent digits is not a float: `1d` and `1d0x` read as
symbols (like `1+`), not numbers.

In the **interpreter and the JVM compiler**, integer arithmetic never silently
wraps: when a `long` operation (`+`, `-`, `*`, `/`, `1+`, `1-`, `abs`, ...)
would overflow, the result is automatically promoted to an arbitrary-precision
big integer, and integer literals larger than a `long` are read as big integers.
A big-integer result that fits back in a `long` is demoted again, so values keep
a single canonical representation. For example, with
`(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))`, `(fact 32)` returns the
exact `263130836933693530167218012160000000`. The **WASM compiler** does not
support this: its integers are limited to 31-bit (`i31ref`) and overflow wraps.

**All three backends** support Common Lisp ratios (exact rational numbers).
`1/3` reads as a ratio literal, and integer division that does not divide
evenly returns a ratio instead of truncating:

```lisp
> 1/3
1/3
> (/ 1 2)
1/2
> (+ 1/2 1/3)
5/6
> (/ 1 2.0)
0.5
> (float 1/2)
0.5
```

Ratio results are always normalized -- reduced by the gcd with the sign on the
numerator (`2/4` reads as `1/2`), and demoted to an integer when the
denominator reduces to one (`(/ 10 2)` is `5`, `(+ 1/2 1/2)` is `1`).
Arithmetic, comparisons (`= < > <= >=`), `eq`/`eql`, `abs`/`min`/`max`/`1+`/`1-`/
`signum`, the predicates (`numberp`, `rationalp`, `zerop`, `plusp`, `minusp`),
`truncate`/`floor`/`ceiling`/`round`, `expt` with an integer exponent
(`(expt 2 -1)` is `1/2`), and `numerator`/`denominator` all handle ratios;
mixing in a float switches to float contagion. Unary `(/ x)` is the reciprocal
(`(/ 2)` is `1/2`).

Per backend, the components follow the integer representation: the
**interpreter and the JVM compiler** use big integers (a ratio of huge
numerators/denominators stays exact), while the **WASM compiler** keeps them
in the 31-bit `i31` range with no overflow promotion, like all of its integer
arithmetic. The runtime reader emitted for compiled `read`/`load` does not
parse ratio literals (a `1/3` token read at runtime is a symbol), and `mod`,
`evenp`/`oddp`, `gcd`/`lcm` and `isqrt` remain integer-only.

### Special Forms

| Form | Syntax | Description |
|------|--------|-------------|
| `quote` | `(quote expr)` or `'expr` | Returns the expression unevaluated |
| `if` | `(if cond then else?)` | Conditional. `nil` is false, everything else is true |
| `let` | `(let ((x 1) (y 2)) body...)` | Local variable bindings |
| `lambda` | `(lambda (params...) body...)` | Anonymous function |
| `progn` | `(progn expr1 expr2...)` | Evaluate expressions in sequence, return the last |
| `setq` | `(setq name value)` | Assign a value to a variable |
| `while` | `(while test body...)` | Evaluate body repeatedly while test is non-nil. Returns nil |
| `return` | `(return value?)` | Non-local exit from the nearest enclosing loop (`do`/`dolist`/`dotimes`), which evaluates to `value` (or nil) |
| `defun` | `(defun name (params...) body...)` | Define a function in the function namespace. Returns the function name |
| `defvar` | `(defvar name value?)` | Define a global variable, binding `value` only if `name` is not already bound (idempotent). With no `value`, leaves it unbound. Returns the name |
| `defparameter` | `(defparameter name value)` | Define a global variable, **always** (re)binding `value` even if `name` is already bound. Returns the name |
| `defconstant` | `(defconstant name value)` | Like `defparameter` (rontolisp does not enforce constancy). Returns the name |
| `function` | `(function name)` or `#'name` | Look up a function in the function namespace and return it as a value |

rontolisp is a **Lisp-2** like Common Lisp: functions and variables live in separate
namespaces. A bare symbol evaluates as a variable (`car` alone is an unbound-variable
error), a symbol in call position resolves in the function namespace only (a variable
named `car` does not shadow the function `car`), and a function is obtained as a value
with `#'name`, `(function name)` or `(symbol-function 'name)`. See
[Function Namespace and First-Class Functions](#function-namespace-and-first-class-functions).

### Macros

| Macro | Syntax | Description |
|-------|--------|-------------|
| `cond` | `(cond (test1 body1...) ...)` | Conditional with multiple clauses. Returns body of first truthy test |
| `case` | `(case key (k1 body1...) ((k2 k3) body2...) (otherwise body...))` | Dispatch on a key compared with `eql`. Keys are unevaluated; a list key matches any element; `t`/`otherwise` is the default. Returns nil if nothing matches |
| `ecase` | `(ecase key (k1 body1...) ((k2 k3) body2...))` | Exhaustive `case`: no default clause (`t`/`otherwise` are ordinary keys), and an unmatched key signals an `error` |
| `ccase` | `(ccase key (k1 body1...) ...)` | Like `ecase`; an unmatched key signals an `error`. Without a restart system this is identical to `ecase` (not correctable) |
| `and` | `(and expr1 expr2...)` | Short-circuit AND. Returns first nil or last value. `(and)` returns `t` |
| `or` | `(or expr1 expr2...)` | Short-circuit OR. Returns first non-nil value or nil. `(or)` returns `nil` |
| `when` | `(when condition body...)` | Evaluates body when condition is true, returns nil otherwise |
| `unless` | `(unless condition body...)` | Evaluates body when condition is nil, returns nil otherwise |
| `dotimes` | `(dotimes (var count result?) body...)` | Evaluate body with `var` bound to `0`..`count-1`. Returns `result` (or nil) |
| `do` | `(do ((var init step?)...) (end-test result...) body...)` | Iterate with parallel-stepped variables. Returns the `result` forms when `end-test` is true |
| `do*` | `(do* ((var init step?)...) (end-test result...) body...)` | Like `do` but bindings and steps are sequential (`let*`-style): each init/step form sees the variables already updated this iteration |
| `prog1` | `(prog1 first body...)` | Evaluate all forms in order, return the value of `first` |
| `prog2` | `(prog2 first second body...)` | Evaluate all forms in order, return the value of `second` |
| `psetq` | `(psetq v1 e1 v2 e2 ...)` | Parallel assignment: every right-hand side is evaluated before any variable is assigned. Returns nil |
| `typecase` | `(typecase x (integer body...) (string body...) (t default...))` | Dispatch on the type of `x`. Supported type names: `integer`, `float`, `number`, `rational`, `string`, `symbol`, `keyword`, `cons`, `list`, `null`, `atom` (plus `t`/`otherwise`). Returns nil if nothing matches |
| `etypecase` | `(etypecase x (integer body...) (string body...))` | Exhaustive `typecase`: no default clause, and an object whose type matches no clause signals an `error` |
| `error` | `(error "bad value: ~a" x)` | Signal an error, aborting execution. The first argument must be a literal control string (same directives as `format`: `~a`, `~s`, `~%`). The interpreter and JVM throw an exception carrying the message; WASM traps. Like `format`, it is a macro with no function value (`#'error` is unsupported) |
| `setf` | `(setf place value)` | Generalized assignment. Supports `car`, `cdr`, `nth`, `first`..`fourth`, `rest`, `caXXXr` as places |
| `push` | `(push item place)` | Prepend item to list at place. Returns the new list |
| `pop` | `(pop place)` | Remove and return the first element from list at place |
| `remf` | `(remf place indicator)` | Remove key-value pair from property list at place. Returns `t` if found, `nil` otherwise |
| `let*` | `(let* ((x 1) (y x)) body...)` | Sequential bindings: each init form sees the previous bindings. Expands to nested `let` |
| `dolist` | `(dolist (var list result?) body...)` | Evaluate body with `var` bound to each element. Returns `result` (or nil) with `var` bound to nil |
| `incf` | `(incf place delta?)` | Expands to `(setf place (+ place delta))`. `delta` defaults to 1. Returns the new value |
| `decf` | `(decf place delta?)` | Expands to `(setf place (- place delta))`. `delta` defaults to 1. Returns the new value |
| `format` | `(format t "Hello ~a, ~d!~%" 'world 42)`, `(format nil "~a" x)` | Formatted output to standard output (`t`, returns nil) or to a string (`nil`). See [format](#format) |
| `with-open-file` | `(with-open-file (s "f.txt" :direction :output) (write-line "hi" s))` | Open a file, bind the stream to `s`, evaluate the body, close the file. Returns the body value. Only the `:direction` option (`:input` default, `:output`) is supported, and it must be a literal keyword |

Macros have no function value: `#'cond` or `(funcall 'setf ...)` is an error. Convenience
accessors and predicates that expand inline in call position (`first`, `rest`, `nth`,
`second`..`fourth`, `1+`, `1-`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`) are listed
under [Built-in Functions](#built-in-functions) because they are also usable as function
values (`#'first`).

#### format

A minimal subset of Common Lisp's `format`, implemented as a macro shared by the
interpreter and both compilers. With destination `t` it expands into
`princ`/`prin1`/`terpri` calls and returns nil; with destination `nil` it builds and
returns the formatted string (expanding into `princ-to-string`/`prin1-to-string` calls
folded with the internal string concatenation). The destination must be the literal `t`
or `nil` and the control string must be a string literal. All arguments are evaluated
left to right before any output.

| Directive | Meaning |
|-----------|---------|
| `~a`, `~A` | Aesthetic: prints the argument like `princ` (strings without quotes) |
| `~s`, `~S` | Standard: prints the argument like `prin1` (readable, strings quoted) |
| `~d`, `~D` | Decimal: prints an integer argument like `princ` |
| `~%` | Newline (`terpri` for destination `t`, a newline character for `nil`) |
| `~~` | A literal `~` |

```lisp
(format t "Hello ~a, you are ~d years old.~%" 'world 42)
;; Hello world, you are 42 years old.
(format t "~s and ~a~%" "str" "str")
;; "str" and str
(format nil "list=~a" (list 1 2 3))
;; => "list=(1 2 3)"
(princ (format nil "Hello ~a!" 'world))
;; Hello world!
```

Limitations: other destinations (streams, strings with fill pointers) are not supported,
the control string cannot be a runtime value, and the remaining directives (`~c`, `~f`,
`~{`, ...) are not implemented. Like the other macros, `format` is not recognized by the
embedded `eval` runtime in compiled output (see
[Compiled `eval` limitations](#compiled-eval-limitations)).

### Built-in Functions

| Function | Example | Result |
|----------|---------|--------|
| `+` | `(+ 1 2 3)`, `(+ 1.5 2.5)` | `6`, `4.0` |
| `-` | `(- 10 3)`, `(- 3.5 1.5)` | `7`, `2.0` |
| `*` | `(* 3 4)`, `(* 2.0 3.0)` | `12`, `6.0` |
| `/` | `(/ 1 2)`, `(/ 10 2)`, `(/ 7.0 2.0)` | `1/2` (exact ratio), `5`, `3.5` |
| `mod` | `(mod 10 3)`, `(mod -13 4)` | `1`, `3` (result takes the sign of the divisor) |
| `rem` | `(rem 13 4)`, `(rem -13 4)` | `1`, `-1` (result takes the sign of the dividend) |
| `=` | `(= 1 1)`, `(= 3 3 3)` | `t` (variadic) |
| `eq` | `(eq 'foo 'foo)`, `(eq 1.5 1.5)` | `t`, `nil` (object identity: symbols and small integers compare equal, but floats and ratios are distinct objects, so never `eq`; reference identity for cons cells) |
| `eql` | `(eql 1.5 1.5)`, `(eql 3 3.0)` | `t`, `nil` (like `eq`, but numbers of the same type and value are equal — e.g. floats and ratios) |
| `equal` | `(equal '(1 2 (3)) '(1 2 (3)))`, `(equal "abc" "abc")` | `t`, `t` (structural equality: cons cells compared recursively by car and cdr, otherwise like `eql`) |
| `<` | `(< 1 2)`, `(< 1 2 3)` | `t` (variadic; true when strictly increasing) |
| `>` | `(> 2 1)`, `(> 3 2 1)` | `t` (variadic) |
| `<=` | `(<= 1 1)` | `t` (variadic) |
| `>=` | `(>= 2 1)` | `t` (variadic) |
| `print` | `(print 42)` | Prints `42` with a newline |
| `prin1` | `(prin1 42)` | Like `print` but without newline |
| `princ` | `(princ "hello")` | Prints without quotes and without newline |
| `terpri` | `(terpri)` | Prints a newline only |
| `princ-to-string` | `(princ-to-string '(1 "x"))` | `"(1 x)"` -- the string `princ` would print |
| `prin1-to-string` | `(prin1-to-string "abc")` | `"\"abc\""` -- the string `prin1` would print (readable form) |
| `concatenate` | `(concatenate 'string "foo" "bar")` | `"foobar"` (only the `'string` result type is supported; the compilers require the literal `'string`) |
| `string-upcase` | `(string-upcase "abc")` | `"ABC"` (case conversion is ASCII-only in the WASM backend) |
| `string-downcase` | `(string-downcase "ABC")` | `"abc"` |
| `string-capitalize` | `(string-capitalize "hello world")` | `"Hello World"` (first letter of each word) |
| `subseq` | `(subseq "hello" 1 3)` | `"el"` (works on strings and lists, e.g. `(subseq '(1 2 3 4) 1 3)` => `(2 3)`; the `end` argument is optional) |
| `string=` | `(string= "abc" "abc")` | `t` (case-sensitive string equality) |
| `string-equal` | `(string-equal "ABC" "abc")` | `t` (case-insensitive, ASCII) |
| `string-trim` | `(string-trim " " "  hi  ")` | `"hi"` (removes the bag's characters from both ends) |
| `string-left-trim` | `(string-left-trim "x" "xxhi")` | `"hi"` |
| `string-right-trim` | `(string-right-trim "x" "hixx")` | `"hi"` |
| `read-line` | `(read-line)`, `(read-line stream)` | Read one line from stdin (or from an input stream), return as string. `nil` on EOF |
| `open` | `(open "f.txt")`, `(open "f.txt" :output)` | Open a file and return a stream. The direction must be the literal `:input` (default, read) or `:output` (create/truncate, write) |
| `close` | `(close stream)` | Close a stream opened by `open`. Returns `t` |
| `write-line` | `(write-line "hi" stream)`, `(write-line "hi")` | Write the string plus a newline to an output stream (or to standard output). Returns the string |
| `read` | `(read)` | Read one S-expression from stdin (all three backends). `nil` on EOF |
| `eval` | `(eval '(+ 1 2))` | Evaluate an expression (all three backends). Returns the result |
| `load` | `(load "bar.lisp")` | Read and evaluate every top-level form in a file in the global environment (all three backends). Returns `t` |
| `null` | `(null nil)` | `t` |
| `not` | `(not nil)` | `t` (identical to `null`) |
| `atom` | `(atom 1)` | `t` |
| `numberp` | `(numberp 42)` | `t` |
| `integerp` | `(integerp 42)` | `t` |
| `floatp` | `(floatp 3.14)` | `t` |
| `rationalp` | `(rationalp 1/2)` | `t` (integers and ratios) |
| `numerator` | `(numerator 3/4)` | `3` (an integer is its own numerator) |
| `denominator` | `(denominator 3/4)` | `4` (`1` for integers) |
| `symbolp` | `(symbolp 'foo)` | `t` |
| `stringp` | `(stringp "hello")` | `t` |
| `listp` | `(listp '(1 2))` | `t` |
| `consp` | `(consp '(1 2))` | `t` |
| `keywordp` | `(keywordp :foo)` | `t` |
| `cons` | `(cons 1 2)` | `(1 . 2)` |
| `car` | `(car (cons 1 2))` | `1` |
| `cdr` | `(cdr (cons 1 2))` | `2` |
| `caar`..`cddddr` | `(cadr '(1 2 3))` | `2` (compositions of `car`/`cdr`, 2-4 levels) |
| `first` | `(first '(1 2 3))` | `1` (same as `car`) |
| `rest` | `(rest '(1 2 3))` | `(2 3)` (same as `cdr`) |
| `nth` | `(nth 1 '(1 2 3))` | `2` (0-based indexing) |
| `second` `third` `fourth` | `(second '(1 2 3))` | `2` |
| `list` | `(list 1 2 3)` | `(1 2 3)` |
| `nthcdr` | `(nthcdr 2 '(1 2 3))` | `(3)` (skip first n elements) |
| `length` | `(length '(1 2 3))`, `(length "abc")` | `3`, `3` (lists and strings; `0` for nil) |
| `reverse` | `(reverse '(1 2 3))` | `(3 2 1)` |
| `member` | `(member 2 '(1 2 3))` | `(2 3)` (tail whose car is `eql` to the item, or nil) |
| `find` | `(find 2 '(1 2 3))` | `2` (first element `eql` to the item, or nil) |
| `find-if` | `(find-if #'evenp '(1 3 6 7))` | `6` (first element satisfying the predicate, or nil) |
| `find-if-not` | `(find-if-not #'evenp '(2 4 5 6))` | `5` (first element failing the predicate, or nil) |
| `member-if` | `(member-if #'oddp '(2 4 5 6))` | `(5 6)` (tail starting at the first element satisfying the predicate, or nil) |
| `position` | `(position 3 '(1 2 3))` | `2` (0-based index of the first element `eql` to the item, or nil) |
| `position-if` | `(position-if #'evenp '(1 3 6 7))` | `2` (0-based index of the first element satisfying the predicate, or nil) |
| `count` | `(count 2 '(1 2 3 2 2))` | `3` (number of elements `eql` to the item) |
| `count-if` | `(count-if #'evenp '(1 2 3 4))` | `2` (number of elements satisfying the predicate) |
| `assoc` | `(assoc 'b '((a 1) (b 2)))` | `(b 2)` (first pair whose car is `eql` to the key, or nil) |
| `assoc-if` | `(assoc-if #'oddp '((2 a) (3 b)))` | `(3 b)` (first pair whose car satisfies the predicate, or nil) |
| `getf` | `(getf '(:a 1 :b 2) :b)` | `2` (value following the indicator in a property list, or nil; the partner of `remf`. Two arguments only: no `&optional default`) |
| `last` | `(last '(1 2 3))` | `(3)` (last cons cell, nil for an empty list) |
| `butlast` | `(butlast '(1 2 3))` | `(1 2)` (copy without the last element; nil for an empty or single-element list) |
| `remove` | `(remove 2 '(1 2 3 2))` | `(1 3)` (new list without items `eql` to the given one) |
| `remove-if` | `(remove-if #'evenp '(1 2 3 4))` | `(1 3)` (new list without items satisfying the predicate) |
| `remove-if-not` | `(remove-if-not #'evenp '(1 2 3 4))` | `(2 4)` (new list keeping only items satisfying the predicate) |
| `remove-duplicates` | `(remove-duplicates '(1 2 1 3))` | `(2 1 3)` (copy with duplicate elements removed, keeping the last occurrence; `eql` compare, no `:test`/`:key`) |
| `delete` | `(delete 2 '(1 2 3 2))` | `(1 3)` (destructive `remove`: splices out matching cells in place; use the return value since the head may change) |
| `delete-if` | `(delete-if #'evenp '(1 2 3 4))` | `(1 3)` (destructive `remove-if`) |
| `delete-if-not` | `(delete-if-not #'evenp '(1 2 3 4))` | `(2 4)` (destructive `remove-if-not`) |
| `substitute` | `(substitute 0 2 '(1 2 3 2))` | `(1 0 3 0)` (copy with every element `eql` to the old item replaced by the new one; positional args only, no `:test`/`:key`) |
| `nsubstitute` | `(nsubstitute 0 2 '(1 2 3 2))` | `(1 0 3 0)` (destructive `substitute`: rewrites matching cars in place) |
| `nconc` | `(nconc (list 1 2) (list 3 4))` | `(1 2 3 4)` (destructively concatenate two lists; two arguments only) |
| `copy-list` | `(copy-list '(1 2 3))` | `(1 2 3)` (shallow copy of a list) |
| `nreverse` | `(nreverse '(1 2 3))` | `(3 2 1)` (destructively reverse a list by rewiring each `cdr`; use the return value) |
| `make-list` | `(make-list 3)` | `(nil nil nil)` (list of n nil elements; no `:initial-element`) |
| `union` | `(union '(1 2 3) '(2 3 4))` | `(4 1 2 3)` (set union, `eql` compare, no `:test`/`:key`; result order unspecified) |
| `intersection` | `(intersection '(1 2 3) '(2 3 4))` | `(3 2)` (set intersection, `eql` compare; result order unspecified) |
| `set-difference` | `(set-difference '(1 2 3) '(2))` | `(3 1)` (elements of the first list not in the second, `eql` compare; result order unspecified) |
| `adjoin` | `(adjoin 1 '(2 3))` | `(1 2 3)` (prepend the item unless already a member; `eql` compare) |
| `list*` | `(list* 1 2 '(3 4))`, `(list* 1 2 3)` | `(1 2 3 4)`, `(1 2 . 3)` (cons the leading arguments onto the last one as the tail) |
| `acons` | `(acons 'a 1 nil)` | `((a . 1))` (prepend a `(key . value)` pair to an alist) |
| `endp` | `(endp nil)`, `(endp '(1))` | `t`, `nil` (end-of-list test; a synonym for `null`, the improper-list error is relaxed) |
| `elt` | `(elt '(a b c) 1)` | `b` (0-based element access; lists only, no string indexing) |
| `rassoc` | `(rassoc 2 (list (cons 'a 1) (cons 'b 2)))` | `(b . 2)` (first pair whose cdr is `eql` to the value, or nil) |
| `revappend` | `(revappend '(1 2 3) '(4 5))` | `(3 2 1 4 5)` (reverse the first list and append the second) |
| `nreconc` | `(nreconc '(1 2 3) '(4 5))` | `(3 2 1 4 5)` (destructive `revappend`: expands to `(nconc (nreverse x) y)`, reusing the cons cells of the first list) |
| `maplist` | `(maplist #'identity '(1 2 3))` | `((1 2 3) (2 3) (3))` (apply to successive tails, collect results; single-list form) |
| `mapcon` | `(mapcon (lambda (x) (list (car x))) '(1 2 3))` | `(1 2 3)` (apply to successive tails, concatenate the result lists; single-list form) |
| `sort` | `(sort '(3 1 2) #'<)` | `(1 2 3)` (destructively sort a list with a comparison predicate; not stable) |
| `rplaca` | `(rplaca x val)` | Destructively replace car of cons cell, return the cell |
| `rplacd` | `(rplacd x val)` | Destructively replace cdr of cons cell, return the cell |
| `1+` | `(1+ 41)` | `42` (same as `(+ x 1)`) |
| `1-` | `(1- 43)` | `42` (same as `(- x 1)`) |
| `zerop` | `(zerop 0)` | `t` |
| `plusp` | `(plusp 3)` | `t` |
| `minusp` | `(minusp -3)` | `t` |
| `evenp` | `(evenp 4)` | `t` |
| `oddp` | `(oddp 3)` | `t` |
| `abs` | `(abs -5)`, `(abs -3.14)` | `5`, `3.14` |
| `min` | `(min 3 5)`, `(min 5 2 8 1)` | `3`, `1` (variadic) |
| `max` | `(max 3 5)`, `(max 5 2 8 1)` | `5`, `8` (variadic) |
| `float` | `(float 42)` | `42.0` (convert to double) |
| `truncate` | `(truncate 3.7)`, `(truncate -3.7)` | `3`, `-3` (toward zero) |
| `floor` | `(floor 3.7)`, `(floor -3.7)` | `3`, `-4` (toward negative infinity) |
| `ceiling` | `(ceiling 3.2)`, `(ceiling -3.2)` | `4`, `-3` (toward positive infinity) |
| `round` | `(round 3.5)`, `(round 2.5)` | `4`, `2` (banker's rounding) |
| `sqrt` | `(sqrt 16)`, `(sqrt 2)` | `4.0`, `1.4142135623730951` (always a float) |
| `isqrt` | `(isqrt 17)` | `4` (integer square root, floor of the real root) |
| `expt` | `(expt 2 10)`, `(expt 2.0 3)` | `1024`, `8.0` |
| `random` | `(random 100)`, `(random 1.0)` | a value in `[0, 100)` / `[0.0, 1.0)` (the result type follows the limit; `(random 1)` is always `0`) |
| `exp` | `(exp 0)` | `1.0` (interpreter/JVM use `Math.exp`; WASM uses a software approximation) |
| `log` | `(log 1)` | `0.0` (natural log; interpreter/JVM only) |
| `sin` `cos` `tan` | `(sin 0)`, `(cos 0)` | `0.0`, `1.0` (interpreter/JVM only) |
| `asin` `acos` `atan` | `(atan 0)` | `0.0` (interpreter/JVM only) |
| `sinh` `cosh` `tanh` | `(tanh 0)` | `0.0` (interpreter/JVM only) |
| `gcd` | `(gcd 12 18)`, `(gcd 24 36 60)` | `6`, `12` (variadic; greatest common divisor, `(gcd)` is `0`) |
| `lcm` | `(lcm 4 6)`, `(lcm 2 3 4)` | `12`, `12` (variadic; least common multiple; `0` if any argument is `0`, `(lcm)` is `1`) |
| `signum` | `(signum -5)`, `(signum 3.5)` | `-1`, `1.0` (sign, preserving integer/float type) |
| `logand` | `(logand 12 10)`, `(logand 12 10 6)` | `8`, `0` (variadic bitwise AND; `(logand)` is `-1`) |
| `logior` | `(logior 12 10)`, `(logior 1 2 4 8)` | `14`, `15` (variadic bitwise inclusive OR; `(logior)` is `0`) |
| `logxor` | `(logxor 12 10)` | `6` (variadic bitwise exclusive OR; `(logxor)` is `0`) |
| `lognot` | `(lognot 5)` | `-6` (bitwise NOT, i.e. ones' complement) |
| `ash` | `(ash 1 4)`, `(ash 255 -4)` | `16`, `15` (arithmetic shift: left for a non-negative count, right otherwise) |
| `funcall` | `(funcall #'+ 3 4)` | Apply a function to args. Accepts a function value (`#'f`, a lambda) or a symbol naming a function (`(funcall 'car ...)`) |
| `mapcar` | `(mapcar #'car '((1 2) (3 4)))` | Apply a function to each element, return new list |
| `mapc` | `(mapc #'print '(1 2 3))` | Apply a function to each element for effect, return the original list |
| `mapcan` | `(mapcan (lambda (x) (list x x)) '(1 2))` | `(1 1 2 2)` (apply a function and concatenate the result lists; uses non-destructive `append`) |
| `apply` | `(apply #'+ 1 2 '(3 4))` | `10` (apply a function to the leading args plus the spread final list) |
| `reduce` | `(reduce #'+ '(1 2 3) :initial-value 0)` | Left fold: `(f (f (f init a) b) c)`. Plain form `(reduce f list)` uses the first element as init; the `:initial-value` keyword (literal) supplies an explicit seed |
| `every` | `(every #'evenp '(2 4 6))` | `t` if the predicate is non-nil for every element, else `nil` (single-list form) |
| `some` | `(some #'oddp '(2 4 5))` | The first non-nil predicate result, or `nil` if every element fails (single-list form) |
| `notany` | `(notany #'evenp '(1 3 5))` | `t` if the predicate is nil for every element, else `nil` (the complement of `some`) |
| `notevery` | `(notevery #'evenp '(2 4 5))` | `t` if the predicate is nil for some element, else `nil` (the complement of `every`) |
| `symbol-function` | `(symbol-function 'car)` | Return the function named by a symbol (compilers: the argument must be a quoted symbol literal) |
| `identity` | `(identity 42)` | `42` (return the argument unchanged) |

**Deviations from Common Lisp.** Some functions accept fewer arguments than the Common
Lisp standard: `log` takes only one argument (no base: `(log x base)` is unsupported),
`atan` takes only one argument (no two-argument `(atan y x)` form), and `last` takes only
a list (no optional count: `(last list n)` is unsupported). `butlast` likewise takes only
a list, `getf` takes no `&optional default`, `nconc` concatenates exactly two lists, and
`remove-duplicates` compares with `eql` only (no `:test`/`:key`); `(setf (getf ...) v)` is
unsupported (use `remf` to delete a property). The rounding functions
`truncate`/`floor`/`ceiling`/`round` accept a single argument and return one value (no
optional divisor and no second remainder value). These remain on the to-do list.

`read` works in all three backends. It reads one line from stdin and parses one S-expression from it. The interpreter uses the full Lisp reader; the JVM and WASM compilers each emit a small reader/parser into their output (the JVM reuses the JDK at runtime, so it has full parity; the WASM reader is limited to the value kinds listed under [Compiled `read`/`load` limitations](#compiled-readload-limitations)). Use `read-line` to read raw strings instead.

`load` works in all three backends. It reads a file and evaluates every top-level form in the global environment, so `defun`/`setq` definitions in the loaded file remain available to subsequent code. In compiled output the loaded definitions live in the runtime `eval` interpreter's global environment, so they are used through `eval` (e.g. `(load "lib.lisp")` then `(eval '(square 5))`). The WASM `load` reads the file with WASI `path_open`, so the module must be run with a directory granted (e.g. `wasmtime --wasm gc --dir . prog.wasm`).

`with-open-file`/`open`/`close`/`write-line` and the stream-taking `read-line` work in all three backends. A stream value is an opaque handle (interpreter/JVM: an index into a stream table; WASM: the WASI file descriptor), so streams can be stored in variables and passed to functions, but they are only valid within the producing run. `open` supports the `:input` and `:output` directions only ( `:output` creates or truncates), and the direction must be a literal keyword so the compilers can pick the file mode statically. Like `load`, the WASM `open` resolves paths relative to the first preopened directory, so run with `--dir`.

```lisp
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out)
  (write-line "world" out))

(with-open-file (in "greeting.txt")
  (print (read-line in)) ; => "hello"
  (print (read-line in)) ; => "world"
  (print (read-line in))) ; => nil (EOF)
```

#### `--dynamic` (late binding)

By default the JVM and WASM compilers resolve every call and variable reference statically and reject anything they cannot find at compile time (`Cannot compile: cube`). That catches typos, but it also means a source that calls a function defined later by `load` must wrap the call in `eval` (`(eval '(cube 3))`) to compile.

The `--dynamic` flag relaxes this: a call or reference that cannot be resolved statically is deferred to the runtime `eval` environment (late binding) instead of failing. This lets a program you tested in the interpreter compile unchanged -- typically to run it faster -- without rewriting `(cube 3)` into `(eval '(cube 3))`.

```bash
echo '(load "lib.lisp") (print (cube 3))' > prog.lisp
rontolisp prog.lisp -o Prog.class --dynamic   # compiles; (cube 3) resolves at runtime
rontolisp prog.lisp -o prog.wasm  --dynamic
```

A call `(f a b)` compiles to `_apply(_eval('(function f), null), (list a b))`: the operator is resolved against the runtime function namespace while the arguments are compiled normally, so locals of the enclosing compiled function stay visible (e.g. `(defun caller (n) (cube n))` works). A bare reference `x` compiles to `_eval('x, null)`, which resolves the variable namespace only. Because the fallback uses the embedded `eval` runtime, `--dynamic` always emits it (as if the program used `eval`), and an unknown symbol that is never defined at runtime errors when it is reached rather than at compile time. Functions resolved this way run on the runtime `eval` interpreter, so they are subject to the [Compiled `eval` limitations](#compiled-eval-limitations) above.

`eval` works in all three backends. In the interpreter it is the full tree-walking evaluator. The WASM and JVM compilers each emit a small tree-walking interpreter into their output (`_eval`/`_apply`/`_store` plus the helpers `_envLookup`/`_lookup`) that runs the form at runtime, so no separate evaluator or parser is needed.

The compiled `eval` (WASM and JVM) implements a lexical environment plus a persistent global environment, and aims for parity with the interpreter: self-evaluating atoms, variable references, closures, the special forms and higher-order functions (`let`, `lambda`, `cond`, `while`, `dotimes`, `setq`, `setf`, `push`, `pop`, `funcall`, `mapcar`, `mapc`, `reduce`, nested `eval`, ...), and application of any function or interpreted closure all behave as in the interpreter. Rather than enumerate everything, the differences are listed below.

#### Compiled `eval` limitations

The compiled `eval` (WASM and JVM) differs from the interpreter only in these cases:

- **`let` binding lists must use the `((name value) ...)` form** (a bare `(let (x) ...)` is not supported).
- **Comparison operators are binary inside `eval`.** Compiled top-level code supports variadic `=`, `<`, `>`, `<=`, `>=` and variadic `min`/`max`/`gcd`/`lcm` (desugared into nested binary operations at compile time), but that desugaring does not reach forms interpreted at runtime by `eval`, where these operators take two arguments and extra arguments are ignored (so `(eval '(= 1 1 2))` evaluates `(= 1 1)` and returns true). `+ - * / list` are fully variadic everywhere. User functions with more than 7 parameters return `nil`.
- **Edge cases that fail.** A zero-argument `(+)`/`(-)`/`(*)`/`(/)` fails at runtime (a trap in WASM, an exception in JVM). Unary `(- x)` and `(/ x)` negate/invert like the interpreter.
- **No big-integer promotion.** Arithmetic inside the runtime `eval` interpreter uses fixed-width integers and wraps on overflow, even on the JVM where compiled code itself promotes to big integers.
- **An unbound variable evaluates to the symbol itself.** The interpreter signals `The variable x is unbound`; the runtime `eval` has no error channel and returns the symbol instead. An undefined function in call position returns `nil`.
- **`let*`, `do`, `do*`, `dolist`, `return`, `defvar`, `defparameter`, `defconstant`, `incf`, `decf`, `format`, `error`, `ecase`, `etypecase`, `ccase`, `concatenate`, `with-open-file` and the file-stream functions (`open`, `close`, `write-line`) are not supported.** These forms are expanded or handled at compile time only; the runtime `eval` interpreter does not recognize them. The sequence functions (`length`, `reverse`, `member`, `member-if`, `find`, `find-if`, `position`, `count`, `assoc`, `assoc-if`, `getf`, `last`, `butlast`, `remove`, `remove-if`, `remove-if-not`, `remove-duplicates`, `delete`, `delete-if`, `delete-if-not`, `substitute`, `nsubstitute`, `nconc`, `copy-list`, `nreverse`, `make-list`, `union`, `intersection`, `set-difference`, `adjoin`, `identity`, `mapcan`, `sort`, `every`, `some`) and `princ-to-string`/`prin1-to-string` work, since they resolve through the compiled function registry.
- **The `rontolisp` package functions are not supported.** `rontolisp:version`, `rontolisp:list-functions`, `rontolisp:list-macros` and `rontolisp:list-special-forms` are resolved to compile-time constants; the runtime `eval`/`load` does not recognize them.

These differences come from the design: the runtime `eval` resolves operators by name against a compile-time registry of the functions that were actually compiled into the output, and built-in functions are shared with the compiled code.

#### Compiled `read`/`load` limitations

The JVM `read`/`load` reuse the JDK at runtime (`Long.parseLong`, `BigInteger`, `Double.parseDouble`, `java.nio.file`), so they parse the same value kinds as the interpreter: integers, big integers, floats, strings, symbols, `nil`/`t`, lists, `'quote` and `#'function`.

In every backend `read` parses one S-expression from a line of stdin: blank and comment-only lines are skipped (it keeps reading until a line contains a datum), EOF returns `nil`, and a form must fit on a single line.

The WASM reader has a hand-written parser and is narrower:

- **Integers are 31-bit.** Numeric tokens are parsed to `i31` and wrap on overflow; there is no big-integer or floating-point parsing (a token containing `.` is read as a symbol).
- **Symbol interning is runtime-backed.** Symbols that appear in the compiled program resolve to the same offset the compiled `eval` uses; symbols seen only at runtime (e.g. a lambda parameter inside a loaded file) are interned in a runtime table so repeated occurrences stay consistent.
- **`load` requires a preopened directory.** It opens the file via WASI `path_open` relative to the first preopened directory (fd 3), so run with `--dir`.

Arithmetic and comparison operators work on both integers and doubles. When any operand is a double, the result is promoted to double (e.g., `(+ 1 1.5)` returns `2.5`). `+`, `-`, `*`, `/` accept two or more arguments. `mod` supports doubles in the interpreter and JVM compiler but not in the WASM compiler.

#### Math function backend support

The math built-ins differ in how widely they are supported, because the WASM backend only has native instructions for a few operations:

- **`sqrt`, `isqrt`, `gcd`, `lcm`, `signum`, `expt`** are supported on all three backends (interpreter, JVM, WASM) and through the compiled `eval`. `sqrt` uses the native `f64.sqrt` instruction.
- **`exp`** is supported on all three backends. The interpreter and JVM use `Math.exp`; WASM has no native transcendental instruction, so it emits a software approximation in f64 (argument reduction by repeated squaring plus a Taylor polynomial), accurate to roughly 1e-6 relative error. The WASM result is therefore close to but not bit-identical to `Math.exp` (`(exp 0)` is exactly `1.0`). This is enough to run, e.g., a sigmoid `(/ 1.0 (+ 1.0 (exp (- 0 x))))` compiled to WASM.
- **The remaining transcendental functions** (`log`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `tanh`) have no native WASM instruction and are **interpreter/JVM only**. Using one in a program compiled to WASM is rejected at compile time (`Cannot compile: sin`).
- **`expt`** keeps an exact rational result for an integer or ratio base raised to an integer exponent (with big-integer promotion in the interpreter and JVM); a negative exponent yields the reciprocal (`(expt 2 -1)` is `1/2`) and a float base/exponent uses `Math.pow` and returns a float. The WASM `expt`, like all WASM integer arithmetic, uses 31-bit values with no overflow promotion.
- **`isqrt`, `gcd`, `lcm`, `signum`** operate on the i31 integer range in the WASM backend (no big-integer promotion); the interpreter and JVM promote to big integers as needed.
- **`random`** is supported on all three backends. It returns a non-negative random number below the (positive) limit, of the same type as the limit (an integer limit yields an integer, a float limit a float). The integer and float paths are chosen from the literal shape of the argument, so use a float literal (`(random 1.0)`) when a float result is wanted. The interpreter and JVM draw from `Math.random()`; the WASM backend has no entropy source, so it uses a **deterministic** linear-congruential generator (`seed = (seed * 1103515245 + 12345) & 0x7fffffff`) seeded from a fixed value, producing a reproducible pseudo-random sequence. `random` is not available inside the compiled `eval`.
- **`logand`, `logior`, `logxor`, `lognot`, `ash`** are supported on all three backends. The interpreter and JVM compute on exact `BigInteger` values (`ash` shifts left for a non-negative count, right otherwise); the WASM backend uses the i31 integer range, so a result that overflows 31 bits is truncated.

### Packages

rontolisp has a small namespace (package) system with three built-in packages:

- **`cl`** — the standard package. All built-in functions, macros, special forms and the `*package*` variable belong here.
- **`cl-user`** — the default working package. It *uses* `cl`, so standard symbols are available unqualified. The current package when a program starts. User definitions go here.
- **`rontolisp`** — a package for implementation-specific symbols. It does **not** use `cl`. It owns the `version`, `list-functions`, `list-macros` and `list-special-forms` functions.

A symbol can be referenced with a package qualifier, `package:symbol` (e.g. `cl:car`, `rontolisp:version`). `*package*` evaluates to the name of the current package, and `(in-package name)` switches it (the name is a keyword, a symbol, or a string: `:rontolisp`, `rontolisp`, `"rontolisp"`).

```lisp
(print *package*)              ; => cl-user
(print (rontolisp:version))    ; => (:version "0.1.0-SNAPSHOT" :build-timestamp "..." :git-commit "..." :git-branch "...")
```

`rontolisp:version` returns the same information as `rontolisp --version`, as a property list.

Because the `rontolisp` package does not use `cl`, standard symbols must be qualified with `cl:` inside it, while `version` (which it owns) is available unqualified:

```lisp
(in-package rontolisp)
(cl:print (version))           ; the rontolisp package owns version
(cl:print (cl:car '(1 2)))     ; standard symbols need the cl: prefix here
;; (car '(1 2)) would be an error: Undefined symbol: car (use cl:car)
```

The default package `cl-user` is empty and uses `cl`, so ordinary programs do not need any qualifiers.

#### Package introspection

`rontolisp:list-functions`, `rontolisp:list-macros` and `rontolisp:list-special-forms` return the symbols of a package by category, sorted alphabetically. The optional argument is a package designator — a keyword, a bare symbol, a quoted symbol or a string (`:cl`, `cl`, `'cl`, `"cl"`) — and defaults to `:cl`. An unknown package is an error (`No such package: foo`).

```lisp
(print (rontolisp:list-macros))
; => (and case ccase cond decf do do* dolist dotimes ecase error etypecase format incf let* or pop prog1 prog2 psetq push remf setf typecase unless when with-open-file)
(print (rontolisp:list-special-forms))
; => (defconstant defparameter defun defvar function if in-package lambda let progn quote return setq while)
(print (length (rontolisp:list-functions)))
; => 157
(defun square (x) (* x x))
(print (rontolisp:list-functions :cl-user))
; => (square)
(print (rontolisp:list-functions :rontolisp))
; => (list-functions list-macros list-special-forms version)
```

The classification follows the function namespace: a name is listed as a function exactly when it is usable as a function value via `#'name` (so `first`, `length`, `1+`, ... are functions even though they compile via inline expansion), and `list-macros`/`list-special-forms` list the operators that have no function value. Notes:

- `list-functions` of `cl-user` lists the user-defined functions (`defun`s); names that are package-qualified, `%`-prefixed internals or shadow a `cl` symbol are excluded. In compiled output it is a **compile-time snapshot** of the program's `defun`s — functions defined at runtime through `load`/`eval` (even with `--dynamic`) are not included, and functions defined while `(in-package :rontolisp)` is in effect are not listed for any package.
- Car/cdr compositions (`cadr`, `caddr`, ...) are recognized by pattern, not enumerated, so they do not appear in `list-functions`.
- The package designator must be a literal; a computed designator is rejected at read/compile time (the interpreter additionally accepts a computed designator through `funcall`).
- Like `version`, these functions are not supported inside the compiled runtime `eval`/`load`.

Packages are resolved at read/compile time (in source order), so `in-package` is a top-level directive and `*package*` reflects the current package rather than being a mutable runtime variable. In compiled output a runtime-loaded file's package directives are not processed; the `rontolisp` package's functions (`version`, `list-functions`, ...) are not available as first-class values (they cannot be passed to `mapcar`/`funcall`); and a `cl` symbol name must not be shadowed as a local variable inside a package that does not use `cl`.

### Function Namespace and First-Class Functions

rontolisp is a **Lisp-2**, following Common Lisp: functions and variables live in
separate namespaces.

- A bare symbol evaluates as a **variable**. Evaluating `car` alone is an error
  (`The variable car is unbound` in the interpreter; a compile error in the compilers).
- A symbol in **call position** `(f args...)` resolves in the function namespace only.
  A variable named `car` never shadows the function `car`: `(let ((car 5)) (car (list car 2)))`
  returns `5`.
- A function becomes a **value** through `#'name` (reader syntax for `(function name)`),
  `#'(lambda ...)`, or `(symbol-function 'name)`. This works for built-in operators
  (`#'+`, `#'car`, `#'1+`, `#'cadr`), user `defun`s, and lambdas.
- `funcall`/`mapcar`/`reduce` also accept a **symbol** naming a function (a function
  designator): `(funcall 'car '(1 2))` returns `1`. The compilers support this when the
  symbol is a quoted literal.
- `defun` defines into the function namespace and returns the function name.
  `(setq f (lambda ...))` binds a **variable** to a function value; call it with
  `(funcall f ...)`, not `(f ...)`.
- `#'` of a macro or special operator (e.g. `#'if`, `#'defun`) is an error.

Function values can be passed as arguments, returned from functions, and stored in data
structures in all three execution modes.

**Higher-order functions:**

```lisp
(defun apply-twice (f x) (funcall f (funcall f x)))
(defun square (x) (* x x))
(print (apply-twice #'square 3))    ; => 81
```

**Closures (capture by reference):**

```lisp
(defun make-counter ()
  (let ((n 0))
    (lambda ()
      (setq n (+ n 1))
      n)))
(setq c (make-counter))
(funcall c) ; => 1
(funcall c) ; => 2
(funcall c) ; => 3
```

**Lambda as argument:**

```lisp
(defun apply-twice (f x) (funcall f (funcall f x)))
(print (apply-twice (lambda (x) (+ x 10)) 5))  ; => 25
```

**Built-in operators as first-class values:**

Built-in operators like `+`, `car`, `1+` can be passed to higher-order functions via `#'`:

```lisp
(print (reduce #'+ '(1 2 3 4 5) :initial-value 0))   ; => 15
(print (reduce #'* '(1 2 3 4 5) :initial-value 1))   ; => 120
(print (mapcar #'car '((1 2) (3 4) (5 6))))          ; => (1 3 5)
(print (mapcar #'1+ '(1 2 3)))                       ; => (2 3 4)
(print (funcall #'+ 3 4))                          ; => 7
(setq my-op #'+)
(print (funcall my-op 10 20))                      ; => 30
(print (funcall (symbol-function 'car) '(9 8)))    ; => 9
```

**Compiler restrictions.** In the JVM/WASM compilers, `#'name` resolves against the
functions known at compile time (user `defun`s and built-in operators); `#'mapcar`,
`#'reduce`, `#'apply` and `#'funcall` themselves are not available as values (`#'mapcan`
and `#'sort` are). `symbol-function` requires a quoted symbol literal argument. In
`--dynamic` mode an unresolved `#'name` is deferred to the runtime `eval` environment like
any other unresolved reference. In compiled code `apply` reuses the runtime `eval`
machinery (it forces the `eval` runtime to be emitted) and dispatches by the actual
argument count, so the applied function must have a matching arity -- binary built-in
wrappers (e.g. `(apply #'+ '(1 2))`, `(apply #'cons 1 '(2))`) and user `defun`s of any
fixed arity work, while variadic built-ins applied to a different count (e.g.
`(apply #'+ '(1 2 3))`, `(apply #'list ...)`) do not, matching the
[Compiled `eval` limitations](#compiled-eval-limitations). The interpreter has no such
restriction.

## Project Structure

```
am.ik.rontolisp              -- Lisp data types (sealed interface)
am.ik.rontolisp.reader       -- Lexer + Parser
am.ik.rontolisp.eval         -- Tree-walking interpreter + Environment
am.ik.rontolisp.compiler     -- Shared compiler interface + FreeVarAnalyzer
am.ik.rontolisp.codegen.jvm  -- JVM .class generation
am.ik.rontolisp.codegen.wasm -- WASM .wasm generation (wasm-GC + WASI)
am.ik.rontolisp.cli          -- REPL + CLI entry point
am.ik.jvm                    -- JVM bytecode primitives
am.ik.wasm                   -- WASM binary primitives
```

## Testing

```bash
./mvnw test
```

The test suite includes:

- **Unit tests** -- Reader, evaluator, and environment (lexer tokenization, parsing, expression evaluation)
- **JVM compiler tests** -- Compiles Lisp, loads the generated `.class` via `URLClassLoader`, runs it, and verifies stdout
- **WASM compiler tests** -- Verifies binary structure (magic number, sections, GC instructions)
- **WASM integration tests** -- Compiles Lisp to `.wasm` and runs it with wasmtime inside a Docker container via [Testcontainers](https://testcontainers.com/). Requires Docker; skipped automatically if Docker is unavailable.
- **CLI tests** -- REPL input/output, file interpretation, compilation output

## License

Apache License 2.0
