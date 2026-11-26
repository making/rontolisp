# java/rust-calls-java -- a Rust program calling a Java component

The same program as [`wit/rust-calls-lisp/`](../../wit/rust-calls-lisp), with the
counter written in Java: the same WIT world, the same Rust component. The Java
component exports `vowel-count` through
[`@WitExport`](../../../doc/en/java/wit.md#exporting-a-wit-world), and
[`wac`](https://github.com/bytecodealliance/wac) composes it with the Rust
component the Lisp example builds.

```
   describe("hello world")         rust-describer                Counter.java
   (host --invoke)                 (Rust reactor)                (Java reactor)
                                   --------------                --------------
                            ─────▶ export describe
                                   import vowel-count  ────────▶ export vowel-count
                                                      ◀────────  counts: 3
                                   format!("... {} vowels")
   "\"hello world\" has 3 vowels" ◀───────────────────────────
```

The program has no `main`: its entry point is the function its world exports.
Under a JDK, and on rontolisp's interpreter and JVM, `vowelCount` is an ordinary
static method, which the project's test calls (§2).

The Java language read here is the **experimental** subset of
[`doc/en/java/`](../../../doc/en/java/index.md).

## Prerequisites

- Maven, and the SNAPSHOTs of this repository (the plugin and
  `rontolisp-java-api`); from a checkout, install them first (§1)
- the Rust toolchain with the `wasm32-unknown-unknown` target,
  [`cargo-component`](https://github.com/bytecodealliance/cargo-component)
  and [`wac`](https://github.com/bytecodealliance/wac) (`cargo install wac-cli`)
- `wasmtime` 46+

[`build.sh`](build.sh) runs the whole thing end to end:

```bash
./build.sh
# "\"hello world\" has 3 vowels"
```

## 1. Build it as a Maven project

This is a Maven project like [`java-calls-rust/`](../java-calls-rust), with the plugin and
the annotation library of this repository as its SNAPSHOTs. From a checkout of
the repository, install what the pom names once:

```bash
../../../mvnw install -DskipTests
../../../mvnw -f rontolisp-java-api/pom.xml install -DskipTests
../../../mvnw -f rontolisp-maven-plugin/pom.xml install -DskipTests
```

Then, in this directory:

```bash
mvn package      # target/rust-calls-java.wasm, a component that exports vowel-count
mvn test         # CounterTest: vowelCount called as a method under a JDK, then on rontolisp
```

The pom names no `mainClass`: the plugin's `wasm` goal then compiles the classes
`@WitExport` marks in `src/main/java`, found as the compiler finds them.

The same component comes from the command line. `src/main/resources` is on the
source path because the WIT is a resource of the class's package there:

```bash
SP="src/main/resources:../../../rontolisp-java-api/src/main/java"
rontolisp src/main/java/com/example/vowels/Counter.java --java-source-path "$SP" \
          -o counter.wasm --component
wasmtime run --invoke 'vowel-count("hello world")' counter.wasm
# 3
```

`CounterTest` runs on the program rontolisp compiles as well, through
[`rontolisp test`](../../../doc/en/java/testing.md): the plugin's `test` goal runs it on
the interpreter after surefire, and `-Drontolisp.backends=interpreter,jvm,wasm,component`
on the four backends. By hand, on the interpreter, or compiled with `-o` to a module or a
class. There `vowelCount` is an ordinary method too:

```bash
rontolisp test --java-source-path "src/main/java:$SP" src/test/java/com/example/vowels/CounterTest.java
# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

## 2. The world and the class

[`vowels.wit`](src/main/resources/com/example/vowels/wit/vowels.wit) is the WIT
of [`wit/rust-calls-lisp/`](../../wit/rust-calls-lisp/wit/vowels.wit), copied so
the project builds on its own. Its `counter` world exports a plain function:

```wit
world counter {
  export vowel-count: func(text: string) -> s32;
}
```

[`Counter.java`](src/main/java/com/example/vowels/Counter.java) implements the
world: each function the world exports is a static method, named in camel case:

```java
@WitExport(value = "counter", wit = "wit/vowels.wit")
public class Counter {

    public static int vowelCount(String text) {
        ...
    }
}
```

rontolisp checks the method against the world when it compiles the program: a
missing method, a parameter or a result of another type is refused with the WIT
line. A program with no `main` is a reactor: the component exports
`vowel-count` and no `wasi:cli/run`, and the first call runs the program's
static initialization.

## 3. The Rust component, compose, run

The Rust component is the one the Lisp example builds and documents; it lives in
[`wit/rust-calls-lisp/rust-describer/`](../../wit/rust-calls-lisp/rust-describer)
and imports `vowel-count`. Nothing about it changes for a Java callee -- the WIT
is the whole contract:

```bash
cd ../../wit/rust-calls-lisp/rust-describer
cargo component build --release --target wasm32-unknown-unknown
cd -
cp ../../wit/rust-calls-lisp/rust-describer/target/wasm32-unknown-unknown/release/describer.wasm describer.wasm
cp target/rust-calls-java.wasm counter.wasm

wac plug describer.wasm --plug counter.wasm -o vowels.wasm
wasmtime run --invoke 'describe("hello world")' vowels.wasm
```
```console
"\"hello world\" has 3 vowels"
```

The result is the Lisp example's: the count came from Java this time.
