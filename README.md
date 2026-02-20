# femtolisp

A minimal Common Lisp subset implemented in Java. It supports three execution modes:

- **Interpreter** -- Tree-walking evaluation with REPL support
- **JVM compiler** -- Compiles Lisp to `.class` bytecode runnable on any JRE
- **WASM compiler** -- Compiles Lisp to `.wasm` binary using wasm-GC and WASI Preview 1

No external runtime dependencies for core libraries. The JVM and WASM bytecode generators are written from scratch without ASM or other code generation libraries. The CLI uses JLine for interactive REPL features (history, line editing).

## Requirements

- Java 25+ (for building and running the JAR)
- [GraalVM](https://www.graalvm.org/) 25+ (optional, for native image build)
- [wasmtime](https://wasmtime.dev/) (for running `.wasm` output, optional)

## Build

```bash
./mvnw clean package
```

This produces `target/femtolisp-0.1.0-SNAPSHOT-exec.jar`, an executable JAR with all dependencies included.

### Native Image (GraalVM)

Build a native executable using GraalVM:

```bash
./mvnw -Pnative clean package
```

This produces `target/femtolisp`, a standalone native binary with instant startup.

**Requirements:**
- GraalVM 25+ (with `native-image` tool)

**Usage:**

```bash
# REPL
./target/femtolisp

# File interpretation
./target/femtolisp program.lisp

# Compile to JVM bytecode
./target/femtolisp hello.lisp -o Hello.class

# Compile to WASM
./target/femtolisp hello.lisp -o hello.wasm
```

## Usage

### REPL

```bash
java -jar target/femtolisp-0.1.0-SNAPSHOT-exec.jar
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
java -jar target/femtolisp-0.1.0-SNAPSHOT-exec.jar program.lisp
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
java -jar target/femtolisp-0.1.0-SNAPSHOT-exec.jar hello.lisp -o Hello.class
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
java -jar target/femtolisp-0.1.0-SNAPSHOT-exec.jar hello.lisp -o hello.wasm
wasmtime --wasm gc hello.wasm
```

```
3
```

The generated `.wasm` binary uses:

- **wasm-GC** -- Integers are represented as `i31ref`. All values on the stack are typed as `(ref eq)`.
- **WASI Preview 1** -- `fd_write` for stdout output.

Requires a wasm-GC capable runtime such as wasmtime 14+.

## Language Reference

### Data Types

| Type | Example | Description |
|------|---------|-------------|
| Integer | `42`, `-5` | 64-bit signed integer (interpreter), 31-bit signed integer (WASM) |
| String | `"hello"` | String literal (interpreter only) |
| Symbol | `x`, `foo` | Identifier |
| Nil | `nil` | False / empty list |
| T | `t` | True |
| Cons | `(1 2 3)` | Linked list built from cons cells |

### Special Forms

| Form | Syntax | Description |
|------|--------|-------------|
| `quote` | `(quote expr)` or `'expr` | Returns the expression unevaluated |
| `if` | `(if cond then else?)` | Conditional. `nil` is false, everything else is true |
| `let` | `(let ((x 1) (y 2)) body...)` | Local variable bindings |
| `defun` | `(defun name (params...) body...)` | Define a named function |
| `lambda` | `(lambda (params...) body...)` | Anonymous function |
| `progn` | `(progn expr1 expr2...)` | Evaluate expressions in sequence, return the last |
| `setq` | `(setq name value)` | Assign a value to a variable |

### Built-in Functions

| Function | Example | Result |
|----------|---------|--------|
| `+` | `(+ 1 2 3)` | `6` |
| `-` | `(- 10 3)` | `7` |
| `*` | `(* 3 4)` | `12` |
| `/` | `(/ 10 3)` | `3` (integer division) |
| `mod` | `(mod 10 3)` | `1` |
| `=` | `(= 1 1)` | `t` |
| `<` | `(< 1 2)` | `t` |
| `>` | `(> 2 1)` | `t` |
| `<=` | `(<= 1 1)` | `t` |
| `>=` | `(>= 2 1)` | `t` |
| `print` | `(print 42)` | Prints `42` with a newline |
| `null` | `(null nil)` | `t` |

Arithmetic and comparison operators work on integers only. `+`, `-`, `*`, `/` accept two or more arguments.

## Compiler Limitations

The compilers support a subset of what the interpreter handles:

| Feature | Interpreter | JVM Compiler | WASM Compiler |
|---------|:-----------:|:------------:|:-------------:|
| Arithmetic (`+`, `-`, `*`, `/`, `mod`) | Yes | Yes | Yes |
| Comparison (`=`, `<`, `>`, `<=`, `>=`) | Yes | Yes | Yes |
| `print` | Yes | Yes | Yes |
| `if` | Yes | Yes | Yes |
| `let` | Yes | Yes | Yes |
| `progn` | Yes | Yes | Yes |
| `defun` / `lambda` | Yes | Yes | Yes |
| `setq` | Yes | Yes | Yes |
| `quote` | Yes | Yes | Yes |
| Recursion | Yes | Yes | Yes |
| String values | Yes | Yes | Yes |

## Project Structure

```
am.ik.femtolisp              -- Lisp data types (sealed interface + records)
am.ik.femtolisp.reader       -- Lexer + Parser
am.ik.femtolisp.eval         -- Tree-walking interpreter + Environment
am.ik.femtolisp.compiler     -- Compiler common interface
am.ik.femtolisp.codegen.jvm  -- JVM .class generation
am.ik.femtolisp.codegen.wasm -- WASM .wasm generation (wasm-GC + WASI)
am.ik.femtolisp.cli          -- REPL + CLI entry point
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
