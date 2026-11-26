# java/java-calls-rust -- a Java program calling a Rust component

The same program as [`wit/lisp-calls-rust/`](../../wit/lisp-calls-rust), written
in Java: the same WIT interface, the same Rust component. It
imports `example:textkit/casing` through
[`@WitImport`](../../../doc/en/java/wit.md), and
[`wac`](https://github.com/bytecodealliance/wac) composes it with the Rust
component the Lisp example builds.

```
   App.java                        rust-shouter
   (Java command)                  (Rust reactor)
   --------------                  --------------
   casing.shout("hello world") ───▶ export casing.shout
                                   "hello world".to_uppercase() + "!"
   "HELLO WORLD!" ◀────────────────
```

Under a JDK, and on rontolisp's interpreter and JVM, a service provider of the
program's own answers the interface, so the program runs standalone (§2).
Composed with the Rust component it runs against Rust instead, and the program
is not touched in between.

The Java language read here is the **experimental** subset of
[`doc/en/java/`](../../../doc/en/java/index.md).

## Prerequisites

- Maven, and the SNAPSHOTs of this repository (the plugin and
  `rontolisp-java-api`); from a checkout, install them first (§1)
- the Rust toolchain, [`cargo-component`](https://github.com/bytecodealliance/cargo-component)
  and [`wac`](https://github.com/bytecodealliance/wac) (`cargo install wac-cli`)
- `wasmtime` 46+

[`build.sh`](build.sh) runs the whole thing end to end:

```bash
./build.sh
# hello world  ->  HELLO WORLD!
# component model  ->  COMPONENT MODEL!
# rust and lisp  ->  RUST AND LISP!
```

## 1. Build it as a Maven project

This is a Maven project like [`hello-java/`](../hello-java), with the plugin and
the annotation library of this repository as its SNAPSHOTs. From a checkout of
the repository, install what the pom names once:

```bash
../../../mvnw install -DskipTests
../../../mvnw -f rontolisp-java-api/pom.xml install -DskipTests
../../../mvnw -f rontolisp-maven-plugin/pom.xml install -DskipTests
```

Then, in this directory:

```bash
mvn package      # target/java-calls-rust.wasm, a component that imports example:textkit/casing
mvn exec:exec    # the main class on the JDK; LocalCasing answers
mvn test         # AppTest: the same run under a JDK, its output asserted
```

Copied out of the repository the directory builds on its own: the pom declares
Central's snapshot repository, where the plugin and `rontolisp-java-api` are
published, and has no parent.

The same program runs through the command line. `src/main/resources` is on the
source path because the WIT is a resource of the interface's package there:

```bash
SP="src/main/resources:../../../rontolisp-java-api/src/main/java"
rontolisp src/main/java/com/example/casing/App.java --java-source-path "$SP"
rontolisp src/main/java/com/example/casing/App.java --java-source-path "$SP" \
          -o App.class && java -cp . App
```

## 2. The interface and the program

[`Casing.java`](src/main/java/com/example/casing/Casing.java) is the WIT
interface as a Java interface, checked against
[`wit/textkit.wit`](src/main/resources/com/example/casing/wit/textkit.wit) when
rontolisp compiles the program:

```java
@WitImport(value = "example:textkit/casing", wit = "wit/textkit.wit")
public interface Casing {

    String shout(String text);
}
```

[`App.java`](src/main/java/com/example/casing/App.java) loads the
implementation with `ServiceLoader` and calls `shout` on a few phrases:

```java
Casing casing = ServiceLoader.load(Casing.class).findFirst().orElseThrow();
for (String phrase : PHRASES) {
    System.out.println(phrase + "  ->  " + casing.shout(phrase));
}
```

The program says nothing about where the interface is implemented.
[`META-INF/services/com.example.casing.Casing`](src/main/resources/META-INF/services/com.example.casing.Casing)
names [`LocalCasing.java`](src/main/java/com/example/casing/LocalCasing.java),
which uppercases the text and appends `!` -- the same two lines as the Lisp
`wit-provide` fallback in the Lisp example. `ServiceLoader` finds it under a JDK,
on the interpreter and on the JVM. In a WebAssembly component a provider the
program names is **not consulted**: the module imports the interface, so the
composed Rust component answers there.

Only `string` crosses here, in both directions -- the flat, no-`result` subset of
rontolisp's WIT mapping. Full mapping:
[`doc/en/java/wit.md`](../../../doc/en/java/wit.md).

## 3. The Rust component, compose, run

The Rust component is the one the Lisp example builds and documents; it lives
[`wit/lisp-calls-rust/rust-shouter/`](../../wit/lisp-calls-rust/rust-shouter)
and exports `casing.shout`. Nothing about it changes for a Java caller -- the
WIT is the whole contract:

```bash
cd ../../wit/lisp-calls-rust/rust-shouter
cargo component build --release --target wasm32-unknown-unknown
cd -
cp ../../wit/lisp-calls-rust/rust-shouter/target/wasm32-unknown-unknown/release/shouter.wasm shouter.wasm

wac plug target/java-calls-rust.wasm --plug shouter.wasm -o textkit.wasm
wasmtime run textkit.wasm
```
```console
hello world  ->  HELLO WORLD!
component model  ->  COMPONENT MODEL!
rust and lisp  ->  RUST AND LISP!
```

The output is the same as `mvn exec:exec` and the Lisp example's: on the JDK
this is Java calling Java, composed it is Java calling Rust.
