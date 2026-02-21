# CLAUDE.md

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
  -> LispReader (reader pkg) -> List<LispVal> (AST)
    -> LispEvaluator (eval pkg)                          # interpret
    -> JvmLispCompiler (codegen.jvm) -> byte[] (.class)  # compile to JVM
    -> WasmLispCompiler (codegen.wasm) -> byte[] (.wasm) # compile to WASM
```

`am.ik.jvm` and `am.ik.wasm` are **language-independent** bytecode generation libraries ported from [bfc](https://github.com/making/bfc). They have no dependency on Lisp concepts and could be reused for other compilers.

Package dependency direction (no cycles allowed):

```
cli -> eval, codegen.*
codegen.jvm -> compiler, am.ik.jvm
codegen.wasm -> compiler, am.ik.wasm
compiler -> rontolisp (AST types only)
eval, reader -> rontolisp (AST types only)
```

The `compiler` package contains `LispCompiler` (shared interface) and `FreeVarAnalyzer` (shared by both JVM and WASM compilers). The `Scope` interface in `am.ik.rontolisp` breaks a circular dependency between `LispLambda` (top-level) and `Environment` (eval pkg).

## Key Design Constraints

- **JVM Class Version 50 (Java 6)**: Avoids mandatory StackMapTable (required from version 51+). Runs on all modern JVMs.
- **WASM function types outside rec group**: wasmtime's WASI host requires plain `(func ...)` types for imports. Only the cons struct goes inside a rec group.
- **Type predicates: symbolp/stringp in compilers**: Quoted symbols and string literals share the same runtime representation (JVM: `String`, WASM: string struct). They are distinguished by the leading `"` character: string literals include surrounding quotes (e.g., `"\"hello\""`), quoted symbols do not (e.g., `"foo"`). The `charAt(0) == '"'` convention is used to differentiate them.
- **Type predicates: consp in JVM compiler**: Both cons cells and function references use `Object[]` at runtime. Cons cells have `arr[0]` as a Lisp value, while function references have `arr[0]` as `Integer` (funcId). The `arr[0] instanceof Integer` check distinguishes them.
- **Three-pass compilation** (both compilers): Pass 1 collects defuns. Pass 2a compiles defun bodies, 2b compiles top-level, 2c iteratively compiles lambda bodies. Top-level must compile before lambda iteration.
- **WASM tests use Testcontainers**: wasmtime with wasm-GC support runs in Docker. JVM tests use in-process `URLClassLoader`.

## Development Workflows

### Implementation Order

When adding a new built-in function or special form, follow this order:

1. **Interpreter** (`LispEvaluator` / `Environment`) -> run `LispEvaluatorTest`
2. **JVM compiler** (`JvmLispCompiler`) -> run `JvmLispCompilerTest`
3. **WASM compiler** (`WasmLispCompiler`) -> run `WasmLispCompilerIntegrationTest`
4. **native-image test** -> run `NativeImageTest`
5. Update `src/test/resources/ci-test.lisp` and `src/test/resources/ci-test-expected.txt` if needed
6. Update the Built-in Functions table / Compiler Limitations table in README and `ReadmeExamplesTest`

### Adding a New Built-in Function

1. **Environment.java** (`eval` pkg): Add in `createGlobal()` using `env.define("name", new LispFunction(...))`.
2. **JVM compiler**: Create `Jvm<Name>Compiler.java` with a `compile()` static method, then add a case in `JvmExprCompiler.compileCons()` switch.
3. **WASM compiler**: Create `Wasm<Name>Compiler.java` with a `compile()` static method, then add a case in `WasmExprCompiler.compileCons()` switch. All values on the WASM stack are `(ref eq)`; use `WasmEmitHelper.castI31GetS()` to unbox to `i32` and `ref.i31` to re-box.

### Adding a New Special Form

1. **LispEvaluator.java**: Add a case in `evalCons()` switch. Special forms receive unevaluated arguments.
2. **JVM compiler**: Create `Jvm<Form>Compiler.java` and wire it in `JvmExprCompiler.compileCons()`.
3. **WASM compiler**: Create `Wasm<Form>Compiler.java` and wire it in `WasmExprCompiler.compileCons()`.

### Expression Compiler Module Structure

Each codegen backend (`codegen/jvm/`, `codegen/wasm/`) follows this file structure:

- **`*LispCompiler.java`**: Top-level compiler (3-pass orchestration, Ctx/Builder, records)
- **`*ExprCompiler.java`**: Entry point (`compileExpr`) + dispatch (`compileCons`) + symbol resolution (`compileSymbolRef`)
- **`*EmitHelper.java`**: Shared bytecode emission helpers (boxing/unboxing, literals, branch patching, etc.)
- **`*RuntimeBuilder.java`**: Runtime support code (dispatch functions, toString, print helpers)
- **`Jvm<Name>Compiler.java` / `Wasm<Name>Compiler.java`**: One class per built-in function or special form

**File splitting criteria:**

- Each Lisp function/special form gets its own compiler class (e.g., `JvmCarCompiler`, `WasmIfCompiler`)
- Functions sharing identical logic with only opcode parameters are grouped into one class (e.g., `*ArithCompiler` for `+`, `-`, `*`, `/`, `mod`; `*ComparisonCompiler` for `=`, `<`, `>`, `<=`, `>=`)
- Tightly coupled methods that call each other privately are kept in one class (e.g., `*QuoteCompiler` has `compileQuote` + `compileQuotedVal` + `compileQuotedCons`; `*LambdaCompiler` has `compileValue` + `compileCall`)
- All classes are package-private with static methods, following the existing utility class pattern

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
- `src/test/resources/ci-test.lisp` and `src/test/resources/ci-test-expected.txt` are end-to-end tests covering all compiler features. When a feature is added or changed, update both files.
- Code examples in the README are verified by `ReadmeExamplesTest`
- WASM integration tests are skipped automatically if Docker is unavailable

### After Task Completion

- Ensure all code is formatted using `./mvnw spring-javaformat:apply`
- Run full test suite with `./mvnw test`
- For every task, notify that the task is complete and ready for review by the following command:

```
osascript -e 'display notification "<Message Body>" with title "<Message Title>"'
```
