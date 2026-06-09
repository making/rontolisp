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
#<lambda>
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

## Language Reference

### Data Types

| Type | Example | Description |
|------|---------|-------------|
| Integer | `42`, `-5`, `1,000` | 64-bit signed integer that auto-promotes to a big integer on overflow (interpreter and JVM), 31-bit signed integer (WASM) |
| Double | `3.14`, `-0.5`, `3,000.50` | 64-bit floating-point number |
| String | `"hello"` | String literal (interpreter only) |
| Symbol | `x`, `foo` | Identifier |
| Keyword | `:foo`, `:bar` | Self-evaluating symbol starting with `:` |
| Nil | `nil` | False / empty list |
| T | `t` | True |
| Cons | `(1 2 3)` | Linked list built from cons cells |

Numeric literals may use `,` as a grouping separator between digits in the
integer part, so `1,000` reads as `1000` and `(+ 1,000 100)` evaluates to
`1100`. The comma is only treated as a separator when it sits between two
digits; it is stripped before parsing and applies to all three backends. This
differs from Common Lisp, where `,` is the unquote character (not supported
here).

In the **interpreter and the JVM compiler**, integer arithmetic never silently
wraps: when a `long` operation (`+`, `-`, `*`, `/`, `1+`, `1-`, `abs`, ...)
would overflow, the result is automatically promoted to an arbitrary-precision
big integer, and integer literals larger than a `long` are read as big integers.
A big-integer result that fits back in a `long` is demoted again, so values keep
a single canonical representation. For example, with
`(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))`, `(fact 32)` returns the
exact `263130836933693530167218012160000000`. The **WASM compiler** does not
support this: its integers are limited to 31-bit (`i31ref`) and overflow wraps.

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

### Macros

| Macro | Syntax | Description |
|-------|--------|-------------|
| `defun` | `(defun name (params...) body...)` | Expands to `(setq name (lambda (params...) body...))` |
| `cond` | `(cond (test1 body1...) ...)` | Conditional with multiple clauses. Returns body of first truthy test |
| `and` | `(and expr1 expr2...)` | Short-circuit AND. Returns first nil or last value. `(and)` returns `t` |
| `or` | `(or expr1 expr2...)` | Short-circuit OR. Returns first non-nil value or nil. `(or)` returns `nil` |
| `when` | `(when condition body...)` | Evaluates body when condition is true, returns nil otherwise |
| `unless` | `(unless condition body...)` | Evaluates body when condition is nil, returns nil otherwise |
| `dotimes` | `(dotimes (var count result?) body...)` | Evaluate body with `var` bound to `0`..`count-1`. Returns `result` (or nil) |
| `1+` | `(1+ x)` | Expands to `(+ x 1)` |
| `1-` | `(1- x)` | Expands to `(- x 1)` |
| `zerop` | `(zerop x)` | Expands to `(= x 0)` |
| `plusp` | `(plusp x)` | Expands to `(> x 0)` |
| `minusp` | `(minusp x)` | Expands to `(< x 0)` |
| `evenp` | `(evenp x)` | Expands to `(= (mod x 2) 0)` |
| `oddp` | `(oddp x)` | Expands to `(not (= (mod x 2) 0))` |
| `first` | `(first lst)` | Expands to `(car lst)` |
| `nth` | `(nth n lst)` | Expands to `(car (nthcdr n lst))`. 0-based indexing |
| `second` | `(second lst)` | Expands to `(nth 1 lst)` |
| `third` | `(third lst)` | Expands to `(nth 2 lst)` |
| `fourth` | `(fourth lst)` | Expands to `(nth 3 lst)` |
| `setf` | `(setf place value)` | Generalized assignment. Supports `car`, `cdr`, `nth`, `first`..`fourth`, `caXXXr` as places |
| `push` | `(push item place)` | Prepend item to list at place. Returns the new list |
| `pop` | `(pop place)` | Remove and return the first element from list at place |
| `remf` | `(remf place indicator)` | Remove key-value pair from property list at place. Returns `t` if found, `nil` otherwise |

### Built-in Functions

| Function | Example | Result |
|----------|---------|--------|
| `+` | `(+ 1 2 3)`, `(+ 1.5 2.5)` | `6`, `4.0` |
| `-` | `(- 10 3)`, `(- 3.5 1.5)` | `7`, `2.0` |
| `*` | `(* 3 4)`, `(* 2.0 3.0)` | `12`, `6.0` |
| `/` | `(/ 10 3)`, `(/ 7.0 2.0)` | `3` (integer division), `3.5` |
| `mod` | `(mod 10 3)` | `1` |
| `=` | `(= 1 1)` | `t` |
| `eq` | `(eq 'foo 'foo)` | `t` (general equality; reference identity for cons cells) |
| `<` | `(< 1 2)` | `t` |
| `>` | `(> 2 1)` | `t` |
| `<=` | `(<= 1 1)` | `t` |
| `>=` | `(>= 2 1)` | `t` |
| `print` | `(print 42)` | Prints `42` with a newline |
| `prin1` | `(prin1 42)` | Like `print` but without newline |
| `princ` | `(princ "hello")` | Prints without quotes and without newline |
| `terpri` | `(terpri)` | Prints a newline only |
| `read-line` | `(read-line)` | Read one line from stdin, return as string. `nil` on EOF |
| `read` | `(read)` | Read one S-expression from stdin (interpreter only). `nil` on EOF |
| `eval` | `(eval '(+ 1 2))` | Evaluate an expression (all three backends). Returns the result |
| `null` | `(null nil)` | `t` |
| `not` | `(not nil)` | `t` (identical to `null`) |
| `atom` | `(atom 1)` | `t` |
| `numberp` | `(numberp 42)` | `t` |
| `integerp` | `(integerp 42)` | `t` |
| `floatp` | `(floatp 3.14)` | `t` |
| `symbolp` | `(symbolp 'foo)` | `t` |
| `stringp` | `(stringp "hello")` | `t` |
| `listp` | `(listp '(1 2))` | `t` |
| `consp` | `(consp '(1 2))` | `t` |
| `keywordp` | `(keywordp :foo)` | `t` |
| `cons` | `(cons 1 2)` | `(1 . 2)` |
| `car` | `(car (cons 1 2))` | `1` |
| `cdr` | `(cdr (cons 1 2))` | `2` |
| `caar`..`cddddr` | `(cadr '(1 2 3))` | `2` (compositions of `car`/`cdr`, 2-4 levels) |
| `list` | `(list 1 2 3)` | `(1 2 3)` |
| `nthcdr` | `(nthcdr 2 '(1 2 3))` | `(3)` (skip first n elements) |
| `rplaca` | `(rplaca x val)` | Destructively replace car of cons cell, return the cell |
| `rplacd` | `(rplacd x val)` | Destructively replace cdr of cons cell, return the cell |
| `abs` | `(abs -5)`, `(abs -3.14)` | `5`, `3.14` |
| `min` | `(min 3 5)`, `(min 1.5 2.5)` | `3`, `1.5` |
| `max` | `(max 3 5)`, `(max 1.5 2.5)` | `5`, `2.5` |
| `float` | `(float 42)` | `42.0` (convert to double) |
| `truncate` | `(truncate 3.7)`, `(truncate -3.7)` | `3`, `-3` (toward zero) |
| `floor` | `(floor 3.7)`, `(floor -3.7)` | `3`, `-4` (toward negative infinity) |
| `ceiling` | `(ceiling 3.2)`, `(ceiling -3.2)` | `4`, `-3` (toward positive infinity) |
| `round` | `(round 3.5)`, `(round 2.5)` | `4`, `2` (banker's rounding) |
| `funcall` | `(funcall f arg...)` | Apply function `f` to args |
| `map` | `(map f list)` | Apply `f` to each element, return new list |
| `reduce` | `(reduce f init list)` | Left fold: `(f (f (f init a) b) c)`. 2-arg form `(reduce f list)` uses first element as init |

`read` is interpreter-only. It requires the Lisp reader (parser) at runtime, which is not reimplemented in the JVM or WASM backends. Use `read-line` to read raw strings in compiled code.

`eval` works in all three backends. In the interpreter it is the full tree-walking evaluator. The WASM and JVM compilers each emit a small tree-walking interpreter into their output (`_eval`/`_apply`/`_store` plus the helpers `_envLookup`/`_lookup`) that runs the form at runtime, so no separate evaluator or parser is needed.

The compiled `eval` (WASM and JVM) implements a lexical environment plus a persistent global environment, and aims for parity with the interpreter: self-evaluating atoms, variable references, closures, the special forms and higher-order functions (`let`, `lambda`, `cond`, `while`, `dotimes`, `setq`, `setf`, `push`, `pop`, `funcall`, `map`, `reduce`, nested `eval`, ...), and application of any function or interpreted closure all behave as in the interpreter. Rather than enumerate everything, the differences are listed below.

#### Compiled `eval` limitations

The compiled `eval` (WASM and JVM) differs from the interpreter only in these cases:

- **`let` binding lists must use the `((name value) ...)` form** (a bare `(let (x) ...)` is not supported).
- **Comparison operators are binary.** Like the rest of the compiler, `=`, `<`, `>`, `<=`, `>=` take two arguments; extra arguments are ignored (so `(eval '(= 1 1 2))` evaluates `(= 1 1)` and returns true). `+ - * / list` are fully variadic. User functions with more than 7 parameters return `nil`.
- **Edge cases that fail.** A zero-argument `(+)`/`(-)`/`(*)`/`(/)` fails at runtime (a trap in WASM, an exception in JVM), and unary `(- x)` and `(/ x)` return `x` rather than negating/inverting it.
- **No big-integer promotion.** Arithmetic inside the runtime `eval` interpreter uses fixed-width integers and wraps on overflow, even on the JVM where compiled code itself promotes to big integers.

These differences come from the design: the runtime `eval` resolves operators by name against a compile-time registry of the functions that were actually compiled into the output, and built-in functions are shared with the compiled code.

Arithmetic and comparison operators work on both integers and doubles. When any operand is a double, the result is promoted to double (e.g., `(+ 1 1.5)` returns `2.5`). `+`, `-`, `*`, `/` accept two or more arguments. `mod` supports doubles in the interpreter and JVM compiler but not in the WASM compiler.

### First-Class Functions

Functions are first-class values in all three execution modes. They can be passed as arguments, returned from functions, and stored in data structures.

**Higher-order functions:**

```lisp
(defun apply-twice (f x) (f (f x)))
(defun square (x) (* x x))
(print (apply-twice square 3))    ; => 81
```

**Closures (capture by reference):**

```lisp
(defun make-counter ()
  (let ((n 0))
    (lambda ()
      (setq n (+ n 1))
      n)))
(setq c (make-counter))
(c) ; => 1
(c) ; => 2
(c) ; => 3
```

**Lambda as argument:**

```lisp
(defun apply-twice (f x) (f (f x)))
(print (apply-twice (lambda (x) (+ x 10)) 5))  ; => 25
```

**Built-in operators as first-class values:**

Built-in operators like `+`, `car`, `1+` can be passed directly to higher-order functions:

```lisp
(print (reduce + 0 '(1 2 3 4 5)))              ; => 15
(print (reduce * 1 '(1 2 3 4 5)))              ; => 120
(print (map car '((1 2) (3 4) (5 6))))          ; => (1 3 5)
(print (map 1+ '(1 2 3)))                       ; => (2 3 4)
(print (funcall + 3 4))                          ; => 7
(setq my-op +)
(print (funcall my-op 10 20))                    ; => 30
```

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
