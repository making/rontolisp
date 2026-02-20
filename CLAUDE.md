# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw clean spring-javaformat:apply package                    # Build executable JAR (-exec classifier)
./mvnw spring-javaformat:apply test                             # Run all tests
```

## Architecture Overview

Three execution modes share a common frontend (reader) and AST:

```
Source string
  → LispReader (reader pkg) → List<LispVal> (AST)
    → LispEvaluator (eval pkg)                          # interpret
    → JvmLispCompiler (codegen.jvm) → byte[] (.class)   # compile to JVM
    → WasmLispCompiler (codegen.wasm) → byte[] (.wasm)  # compile to WASM
```

`am.ik.jvm` and `am.ik.wasm` are **language-independent** bytecode generation libraries ported from the [bfc](https://github.com/making/bfc) project. They are kept in separate top-level packages because they have no dependency on Lisp concepts and could be reused for other compilers.

Package dependency direction (no cycles allowed):

```
cli → eval, codegen.*
codegen.jvm → compiler, am.ik.jvm
codegen.wasm → compiler, am.ik.wasm
compiler → rontolisp (AST types only)
eval, reader → rontolisp (AST types only)
```

The `compiler` package contains `LispCompiler` (shared interface) and `FreeVarAnalyzer` (static free/captured variable analysis shared by both JVM and WASM compilers).

The `Scope` interface exists in the top-level `am.ik.rontolisp` package to break what would otherwise be a circular dependency: `LispLambda` (top-level) needs to hold a closure environment, but `Environment` lives in the `eval` sub-package. `Scope` provides the minimal lookup contract that `Environment` implements.

## Design Decisions

### JVM Compiler: Class Version 50 (Java 6)

The generated `.class` files target Java 6 to **avoid mandatory StackMapTable**. Starting with Java 7 (class version 51), the JVM requires StackMapTable attributes for every method with branches. Computing correct stack maps dramatically increases code generation complexity. Java 6 bytecode still runs on all modern JVMs.

### WASM: Function Types Outside rec Group

The WASM type section defines `fd_write`, `_start`, and `print_i32` as **plain func types** outside the rec group. Only the cons struct is inside a rec group. This is because wasmtime's WASI host provides `fd_write` as a plain `(func ...)` type. If it were inside a rec group as `(sub final (func ...))`, wasmtime rejects the import as a type mismatch.

### WASM: Abstract Heap Type Encoding

`WasmWriter.writeHeapType()` subtracts `0x80` from abstract heap type codes >= `0x40` before passing them to `writeSignedLeb128`. This converts them to negative values that encode to a single byte (e.g., `i31` = `0x6C` → signed `-20` → LEB128 `0x6C`). Without this, `writeSignedLeb128(0x6C)` would produce two bytes `0xEC 0x00`, which wasmtime cannot parse.

### WASM: Two-Pass Function Body Construction

The `_start` body is built in two passes because WASM requires local declarations before instructions, but the number of locals is unknown until all `let` expressions are compiled. Pass 1 generates instructions and tracks allocated locals in `Ctx`. Pass 2 prepends the correct local declarations to the instruction bytes.

### First-Class Functions: Shared Architecture Across JVM and WASM

Both compilers use the same high-level approach for first-class functions (higher-order functions, closures, dynamic dispatch):

1. **FreeVarAnalyzer** (`compiler` pkg) provides shared static analysis: `findFreeVars()` identifies variables referenced but not bound in a scope; `findCapturedVars()` identifies locals that nested lambdas reference and therefore need boxing.
2. **Capture by reference** -- Variables captured by closures are boxed in mutable cells (JVM: `Object[1]`; WASM: `cell` struct with `(mut ref null eq)` field). The closure and outer scope share the same cell, so mutations are visible to both.
3. **Closure representation** -- A closure value is a small record containing a funcId (integer tag) and an env (captured variable cells). JVM: `Object[]` where `[0]` is `Integer` funcId and `[1..]` are cells. WASM: `closure` struct `{i32 funcId, (ref null eq) env}` where env is a cons list of cells.
4. **Dispatch functions** -- Per-arity `_invoke_N` functions dispatch on funcId. JVM uses `LOOKUPSWITCH`; WASM uses `br_table`. Only arities actually used in indirect calls get real dispatch bodies; unused arities get `unreachable`.
5. **Three-pass compilation** -- Pass 1 collects defuns. Pass 2a compiles defun bodies, 2b compiles top-level (_start/main), 2c iteratively compiles lambda bodies (lambdas may discover more lambdas). The order matters: top-level must compile before lambda iteration so that lambdas discovered during top-level compilation are included.
6. **Symbol resolution priority** -- (1) local variables, (2) captured variables from closure env, (3) known function names (creates a closure struct/array reference), (4) error.

### WASM: br_table Dispatch Requires Uniform Label Arity

WASM's `br_table` instruction requires all target labels (including the default) to have the same number of result types. The dispatch functions use a block structure where the result block is typed `(result (ref null eq))` but all br_table targets are void blocks. Each case body calls the target function, then `br` carries the return value to the typed result block. The default block falls through to `unreachable`.

### WASM: Pre-Allocated Dispatch Function Slots

Dispatch functions for arities 0-7 are pre-allocated at fixed function indices (`FUNC_DISPATCH_BASE` through `FUNC_DISPATCH_BASE + 7`). This avoids a chicken-and-egg problem: during compilation, indirect call sites need to know the dispatch function index, but the dispatch body can only be built after all functions are compiled. Pre-allocation means the index is known at compile time; the body is filled in at the end.

### Floating-Point Numbers: Static Type Dispatch with Recursive Detection

`LispDouble` (a `record` wrapping `double`) represents floating-point values across all three backends. Mixed integer/float arithmetic uses automatic promotion: if any operand is a double, the entire operation uses float arithmetic.

Both compilers use **static type dispatch** at compile time: `containsDouble()` recursively walks argument AST nodes to detect any `LispDouble` literal in the expression tree. This is necessary because in nested expressions like `(+ (* 2.0 3.0) (- 10.0 4.0))`, the direct arguments to `+` are `LispCons` nodes, not `LispDouble` -- so a shallow check would miss them. When doubles are detected, the compiler emits float-path bytecode; otherwise, integer-path.

**JVM**: Uses `Number.doubleValue()` for unboxing (both `Long` and `Double` extend `Number`), and `Double.valueOf()` for boxing. Double constants use `LDC2_W` with constant pool `DOUBLE` entries (tag 6, occupies two CP slots).

**WASM**: Floats are boxed in a `float_struct { f64 value }` (type index `TYPE_FLOAT = 7`) because `i31ref` only supports 31-bit integers. The `castFloatGetF64()` helper performs runtime dispatch: `ref.test i31` to check if a value is an integer (then `f64.convert_i32_s`) or a float struct (then `struct.get`). Printing uses `buildPrintF64Core()` which converts f64 to decimal string in linear memory. Note: `mod` is unsupported for floats in WASM because there is no `f64.rem` instruction.

**Interpreter**: `Environment` uses `hasDouble(args)` / `asDouble(val)` helpers to branch arithmetic and comparison operations.

### WASM Integration Tests Use Testcontainers; JVM Tests Do Not

WASM tests require wasmtime (an external runtime with specific version requirements for wasm-GC support). Testcontainers with `ImageFromDockerfile` builds a container with the latest wasmtime, ensuring wasm-GC is available. The image is cached after first build. JVM tests use `URLClassLoader` in-process because a JVM is already running the tests and class version 50 runs on any JRE.

## Development Workflows

### Adding a New Built-in Function

1. **Environment.java** (`eval` pkg): Add the function in `createGlobal()` using `env.define("name", new LispFunction(...))`.
2. **JvmLispCompiler.java**: Add a case in `compileCons()` switch to emit JVM bytecode.
3. **WasmLispCompiler.java**: Add a case in `compileCons()` switch to emit WASM instructions. All values on the WASM stack are `(ref eq)`; use `castI31GetS()` to unbox to `i32` and `ref.i31` to re-box.
4. **Tests**: Add cases in `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`.
5. **README**: Update the Built-in Functions table and `ReadmeExamplesTest`.

### Adding a New Special Form

1. **LispEvaluator.java**: Add a case in `evalCons()` switch. Special forms receive unevaluated arguments.
2. **JvmLispCompiler.java**: Add a `compile<Form>()` method and wire it in `compileCons()`.
3. **WasmLispCompiler.java**: Same pattern. Note: WASM block types use inline ref types (e.g., `if` blocks produce `(ref null eq)`).
4. Update the Compiler Limitations table in README if the compilers don't support the new form.

### Verifying WASM Output Manually

```bash
echo '(print (+ 1 2))' > test.lisp
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar test.lisp -o test.wasm
wasmtime --wasm gc test.wasm    # requires wasmtime 14+
```

## Development Requirements

### Prerequisites

- Java 25+

### Code Standards

- No external dependencies for core libraries (reader, eval, codegen, am.ik.jvm, am.ik.wasm). CLI tooling (e.g., JLine for REPL) is permitted.
- Write javadoc and comments in English
- Spring Java Format enforced via Maven plugin
- Use modern Java features (Records, Pattern Matching, Sealed Types, Text Blocks, etc.)
- Avoid circular references between classes and packages

### Testing Strategy

- JUnit 5 with AssertJ, Testcontainers for WASM integration tests
- Code examples in the README are verified by `ReadmeExamplesTest`
- WASM integration tests are skipped automatically if Docker is unavailable

### After Task Completion

- Ensure all code is formatted using `./mvnw spring-javaformat:apply`
- Run full test suite with `./mvnw test`
- For every task, notify that the task is complete and ready for review by the following command:

```
osascript -e 'display notification "<Message Body>" with title "<Message Title>"'
```
