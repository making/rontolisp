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
    -> [LispMacroExpander] -> expanded AST               # macro expansion (defun/cond/and/or -> if/let/progn/setq)
    -> LispEvaluator (eval pkg)                          # interpret
    -> JvmLispCompiler (codegen.jvm) -> byte[] (.class)  # compile to JVM
    -> WasmLispCompiler (codegen.wasm) -> byte[] (.wasm) # compile to WASM
```

`am.ik.jvm` and `am.ik.wasm` are **language-independent** bytecode generation libraries ported from [bfc](https://github.com/making/bfc).

Package dependency direction (no cycles allowed):

```
cli -> eval, codegen.*
codegen.jvm -> compiler, am.ik.jvm
codegen.wasm -> compiler, am.ik.wasm
compiler -> rontolisp (AST types only)
eval, reader -> rontolisp (AST types only)
```

## Key Design Constraints

- **JVM Class Version 50 (Java 6)**: Avoids mandatory StackMapTable (required from version 51+).
- **WASM function types outside rec group**: wasmtime's WASI host requires plain `(func ...)` types for imports. Only the cons struct goes inside a rec group.
- **symbolp/stringp**: Quoted symbols and string literals share runtime representation. Distinguished by leading `"` character (`charAt(0) == '"'`).
- **consp in JVM**: Both cons cells and function references use `Object[]`. Distinguished by `arr[0] instanceof Integer`.
- **Three-pass compilation**: Pass 1 collects defuns. Pass 2a compiles defun bodies, 2b compiles top-level, 2c iteratively compiles lambda bodies. Top-level must compile before lambda iteration.

## Development Workflows

### Implementation Order

When adding a new built-in function or special form:

1. **Interpreter** (`LispEvaluator` / `Environment`) -> run `LispEvaluatorTest`
2. **JVM compiler** -> run `JvmLispCompilerTest`
3. **WASM compiler** -> run `WasmLispCompilerIntegrationTest`
4. **native-image test** -> run `NativeImageTest`
5. Update `src/test/resources/ci-test.lisp` and `ci-test-expected.txt` if needed
6. Update Built-in Functions / Compiler Limitations in README and `ReadmeExamplesTest`

### Adding a New Built-in Function

1. **Environment.java**: Add in `createGlobal()` using `env.define("name", new LispFunction(...))`.
2. **JVM compiler**: Create `Jvm<Name>Compiler.java`, add case in `JvmExprCompiler.compileCons()`.
3. **WASM compiler**: Create `Wasm<Name>Compiler.java`, add case in `WasmExprCompiler.compileCons()`. Use `WasmEmitHelper.castI31GetS()` to unbox to `i32` and `ref.i31` to re-box.

### Adding a New Macro

Macros expand into existing primitives (`if`, `let`, `progn`) at the AST level. `LispMacroExpander` (in `am.ik.rontolisp` package) is shared by the evaluator and both compilers.

1. **LispMacroExpander.java**: Add a `public static LispVal expand<Name>(LispCons cons)` method that returns the expanded AST.
2. **LispEvaluator.java**: Add case in `evalCons()` switch: `return eval(LispMacroExpander.expand<Name>(cons), env);`
3. **JvmExprCompiler.java**: Add case: `JvmExprCompiler.compileExpr(LispMacroExpander.expand<Name>(cons), ctx, className);`
4. **WasmExprCompiler.java**: Add case: `WasmExprCompiler.compileExpr(LispMacroExpander.expand<Name>(cons), ctx);`

No per-compiler class is needed since macros reuse existing compilation paths.

### Adding a New Special Form

1. **LispEvaluator.java**: Add case in `evalCons()` switch. Special forms receive unevaluated arguments.
2. **JVM compiler**: Create `Jvm<Form>Compiler.java`, wire in `JvmExprCompiler.compileCons()`.
3. **WASM compiler**: Create `Wasm<Form>Compiler.java`, wire in `WasmExprCompiler.compileCons()`.

### Verifying WASM Output Manually

```bash
echo '(print (+ 1 2))' > test.lisp
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar test.lisp -o test.wasm
wasmtime --wasm gc test.wasm    # requires wasmtime 14+
```

## Development Requirements

- Java 25+
- No external dependencies for core libraries (reader, eval, codegen, am.ik.jvm, am.ik.wasm)
- Spring Java Format enforced via Maven plugin
- Use modern Java features (Records, Pattern Matching, Sealed Types, Text Blocks, etc.)
- Avoid circular references between classes and packages
- `ci-test.lisp` / `ci-test-expected.txt` are end-to-end tests. Update both when adding features.
- README code examples verified by `ReadmeExamplesTest`
- WASM integration tests skipped if Docker unavailable

### After Task Completion

- Format: `./mvnw spring-javaformat:apply`
- Test: `./mvnw test`
- Notify: `osascript -e 'display notification "<Message Body>" with title "<Message Title>"'`
