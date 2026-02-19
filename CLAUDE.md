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
compiler, eval, reader → femtolisp (AST types only)
```

The `Scope` interface exists in the top-level `am.ik.femtolisp` package to break what would otherwise be a circular dependency: `LispLambda` (top-level) needs to hold a closure environment, but `Environment` lives in the `eval` sub-package. `Scope` provides the minimal lookup contract that `Environment` implements.

## Design Decisions

### JVM Compiler: Class Version 50 (Java 6)

The generated `.class` files target Java 6 to **avoid mandatory StackMapTable**. Starting with Java 7 (class version 51), the JVM requires StackMapTable attributes for every method with branches. Computing correct stack maps dramatically increases code generation complexity. Java 6 bytecode still runs on all modern JVMs.

### WASM: Function Types Outside rec Group

The WASM type section defines `fd_write`, `_start`, and `print_i32` as **plain func types** outside the rec group. Only the cons struct is inside a rec group. This is because wasmtime's WASI host provides `fd_write` as a plain `(func ...)` type. If it were inside a rec group as `(sub final (func ...))`, wasmtime rejects the import as a type mismatch.

### WASM: Abstract Heap Type Encoding

`WasmWriter.writeHeapType()` subtracts `0x80` from abstract heap type codes >= `0x40` before passing them to `writeSignedLeb128`. This converts them to negative values that encode to a single byte (e.g., `i31` = `0x6C` → signed `-20` → LEB128 `0x6C`). Without this, `writeSignedLeb128(0x6C)` would produce two bytes `0xEC 0x00`, which wasmtime cannot parse.

### WASM: Two-Pass Function Body Construction

The `_start` body is built in two passes because WASM requires local declarations before instructions, but the number of locals is unknown until all `let` expressions are compiled. Pass 1 generates instructions and tracks allocated locals in `Ctx`. Pass 2 prepends the correct local declarations to the instruction bytes.

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
java -jar target/femtolisp-0.1.0-SNAPSHOT-exec.jar test.lisp -o test.wasm
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
