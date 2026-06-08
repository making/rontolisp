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
    -> [LispMacroExpander] -> expanded AST               # macro expansion (defun/cond/and/or/setf -> if/let/progn/setq/rplaca/rplacd)
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
- **`%` prefix convention**: Internal helper functions that are not part of the public Lisp API use a `%` prefix (e.g., `%remf-tail`). These are implementation details used by macros and should not be called directly by users. They are registered in `Environment.java` and have dedicated compiler classes (`Jvm<Name>Compiler`, `Wasm<Name>Compiler`), but are not documented in the README.
- **Built-in function wrappers**: `BuiltinFunctionWrappers` (compiler pkg) generates synthetic `(setq name (lambda ...))` defuns for built-in operators. These are injected in Pass 1 of both compilers so that built-in operators like `+`, `car` can be used as first-class function values (passed to `map`, `reduce`, `funcall`). The wrapper body uses the operator in call position, where `compileCons` inlines it. User defuns with the same name take priority.
- **JVM method name mangling**: The JVM spec forbids `/`, `<`, `>` in method names. `JvmLispCompiler.mangleMethodName()` maps these to `$div`, `$lt`, `$gt`, `$le`, `$ge`.
- **`read` is interpreter-only**: `read` calls `LispReader.readFromString()` to parse stdin input into Lisp values. JVM compiler generates standalone `.class` files without the parser, and reimplementing the reader in WASM bytecode is impractical. `read-line` (returns raw string) is supported in all three modes.
- **`eval` is supported in all three backends** (interpreter, WASM, JVM):
  - *Interpreter*: registered as a `LispFunction` in `LispEvaluator`'s constructor (not in `Environment.createGlobal()`) to avoid a circular dependency. Evaluates the argument in the global environment.
  - *WASM*: a runtime tree-walking interpreter with lexical + global environments, emitted into the module by `WasmEvalRuntimeBuilder` (split out of `WasmRuntimeBuilder`) and invoked by `WasmEvalCompiler`. It comprises five functions -- `_lookup` (registry name-offset -> record address), `_env_lookup` (offset + env -> binding cons or null), `_eval` (`(form, env) -> value`), `_apply` (`(fn, argList) -> value`), and `_store` (`(place, value, env) -> value`) -- plus a WASM global (`GLOBAL_ENV`, a mutable `(ref null eq)`) holding the persistent top-level environment. They appear only when the program actually calls `eval` (`WasmLispCompiler.programUsesEval`); otherwise trivial stubs keep the fixed function indices stable (the global is always present but unused). Dispatch is enabled for every registered arity when eval is used. Three key tricks: (1) because the `StringTable` deduplicates strings, a quoted symbol (e.g. `'car`) and a `let`/`lambda`/`setq` name compile to the same data offset, so both variable lookup and operator resolution are plain `i32` offset comparisons rather than byte-wise string compares; a compile-time registry blob (records of `nameOffset, funcId, arity`) is appended after the string data and scanned by `_lookup`. (2) An environment is an association list of `cons(name, value)` bindings (empty = `ref.null eq`); lexical lookup misses fall through to `GLOBAL_ENV`, and `setq` mutates an existing binding's (mutable) cdr or prepends a new binding to `GLOBAL_ENV`, so definitions persist across `eval` calls. (3) Interpreted closures created by `lambda` reuse the compiled closure struct `{i32 funcId, (ref null eq) env}` with the sentinel `funcId == -1` and `env = cons(lambdaTail, capturedEnv)`, while compiled functions are applied via the arity dispatch functions; `_apply` handles both, so `funcall`/`map`/`reduce` (implemented inside `_eval` using `_apply`) work with interpreted closures too. Registry-resolved calls evaluate exactly the registered arity (extra args ignored, matching the compiler's binary comparison operators) to avoid dispatch traps. `_store` (type `TYPE_CALLABLE_BASE+2`) is the shared assignment primitive: for a symbol place it mutates the lexical/global binding (or creates a global one), and for an accessor place it mutates the resolved cons (`(car x)`/`c[ad]+r` set car/cdr, `(cdr x)` sets cdr, `(nth n x)`/`(second/third/fourth x)` walk then set car). `setq`, `setf`, `push` and `pop` all delegate to `_store`. `_eval` supports self-evaluating atoms, lexical/global variable references, `quote`/`if`/`progn`/`let`/`lambda`/`cond`/`and`/`or`/`when`/`unless`/`setq`/`setf`/`push`/`pop`/`eval` (nested)/`funcall`/`map`/`reduce`/`list`, variadic `+ - * /`, `car`/`cdr` compositions (`cadr`, ... matched by the `c[ad]+r` name pattern), the numbered accessors `first`/`second`/`third`/`fourth`/`nth`, and application of any registered function or interpreted closure. Remaining differences from the interpreter (binary comparison operators, >7-param functions, zero-arg arithmetic traps) are listed in the README "Compiled `eval` limitations".
  - *JVM*: a runtime tree-walking interpreter emitted into the generated `.class` by `JvmEvalRuntimeBuilder` and invoked by `JvmEvalCompiler`, mirroring the WASM design with five `private static` methods -- `_lookup` (`(Object name) -> Object[]{Integer funcId, Integer arity}` or null), `_envLookup` (`(Object name, Object env) -> binding cons or null`), `_eval` (`(Object form, Object env) -> Object`), `_apply` (`(Object fn, Object argList) -> Object`), and `_store` (`(Object place, Object value, Object env) -> Object`) -- plus a mutable `private static Object _genv` field holding the persistent top-level environment. They are emitted only when the program actually calls `eval` (`JvmLispCompiler.programUsesEval`); unlike WASM, **no stubs are needed** because JVM methods are referenced by name+descriptor, not by fixed index. When eval is used, dispatch methods for every arity `0..MAX_CALLABLE_ARITY` (7) are force-generated so `_apply` can dispatch by argument count. Runtime value representation is shared with the compiled output: `null`=nil, `Long`=integer, `Double`=float, `String`=symbol (or string literal when it starts with `"`), `Object[2]`=cons, and `Object[]` whose first element is an `Integer`=function value (`{Integer funcId, captures...}`); interpreted closures created by `lambda` use the sentinel `Object[]{Integer(-1), lambdaTail, capturedEnv}` where `lambdaTail = ((params) body...)`. JVM differs from WASM in two simplifications enabled by the JVM object model: operator/variable/special-form resolution uses `String.equals` and `instanceof` directly (no string-offset registry blob -- `_lookup` is an `if`-chain over the compiled functions), so quoted symbols and binding names need not share an interned offset. Bytecode is assembled by a small label-based `Asm` helper (JVM has no structured control flow, so branches are back-patched). A key constraint: the generated class uses **version 50** and relies on HotSpot's failover to the type-inference verifier (no `StackMapTable`); that verifier is flow-insensitive about `instanceof`, so (a) `String`/array methods on values held in `Object` slots must be preceded by an explicit `checkcast` (`aloadStr`/`idx`/`arrLen` helpers), and (b) a slot read after a conditional guard must be definitely assigned on every path (e.g. `_store` initializes `TARGET` to null up front). Supported forms and the limitations (binary comparison operators, >7-param functions, zero-arg arithmetic edge cases, `let` binding-list shape) are identical to WASM and listed in the README "Compiled `eval` limitations".

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
4. **BuiltinFunctionWrappers.java**: Add a wrapper entry in `WRAPPER_DEFS` with the appropriate arity and body AST so the function can be used as a first-class value.

### Adding a New Macro

Macros expand into existing primitives (`if`, `let`, `progn`, `rplaca`, `rplacd`) at the AST level. `LispMacroExpander` (in `am.ik.rontolisp` package) is shared by the evaluator and both compilers.

1. **LispMacroExpander.java**: Add a `public static LispVal expand<Name>(LispCons cons)` method that returns the expanded AST.
2. **LispEvaluator.java**: Add case in `evalCons()` switch: `return eval(LispMacroExpander.expand<Name>(cons), env);`
3. **JvmExprCompiler.java**: Add case: `JvmExprCompiler.compileExpr(LispMacroExpander.expand<Name>(cons), ctx, className);`
4. **WasmExprCompiler.java**: Add case: `WasmExprCompiler.compileExpr(LispMacroExpander.expand<Name>(cons), ctx);`

No per-compiler class is needed since macros reuse existing compilation paths.

5. **First-class value support** (required if the macro should be passable to `map`/`reduce`/`funcall`):
   - **Environment.java**: Register as `LispFunction` so the interpreter can resolve it in value position.
   - **BuiltinFunctionWrappers.java**: Add a wrapper entry using the expanded body form (e.g., `(+ a 1)` for `1+`) so the compilers can resolve it in value position.
   - Both registrations are needed: `Environment` for the interpreter, `BuiltinFunctionWrappers` for JVM/WASM compilers. Omitting `Environment` causes `Undefined symbol` errors in interpreter/native-image mode.

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
- Javadoc: `./mvnw javadoc:jar` - confirm 0 warnings/errors
- Notify: `osascript -e 'display notification "<Message Body>" with title "<Message Title>"'`
