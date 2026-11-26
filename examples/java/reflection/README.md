# Classes chosen by name: `reflection/`

[`Reflection.java`](src/main/java/com/example/reflection/Reflection.java) greets with classes it never
names in its source. [`greetings.txt`](src/main/resources/greetings.txt) lists their names;
each is found by `Class.forName`, made by its constructor taking a name
(`getConstructor(String.class).newInstance(..)`) and asked `greet()` through
`getMethod("greet").invoke(..)`. Then it prints three properties of each violation YAVI
reports, asking `ViolationDetail` for its methods by name, as a writer of JSON or of a log
line does.

A program compiled ahead of time has no class path to look a name up in where it runs, so
it says which classes and members it uses by reflection in GraalVM's reachability metadata:
[`reachability-metadata.json`](src/main/resources/META-INF/native-image/com.example/reflection/reachability-metadata.json)
declares `English` and `Japanese`, their constructor of a `String` and `greet()`. YAVI's own
jar declares `ViolationDetail` (`META-INF/native-image/am.ik.yavi/yavi/reflect-config.json`).
**One metadata file serves both compilers**: GraalVM's `native-image` reads it from the class
path, rontolisp from the program's resources (`target/classes`, or `src/main/resources` on the
command line) and from the libraries' jars. A name the metadata does not declare is not
found: `Class.forName` throws `ClassNotFoundException` under both, where the JDK finds any
class of its class path. Under rontolisp the classes of the program answer every other
question of reflection as they would anyway; a library's class answers where metadata
declares it, and runs the members the metadata names
([Reflection from reachability metadata](../../../doc/en/java/index.md#reflection-from-reachability-metadata)).

It is a Maven project ([`examples/java/`](../README.md)):

```bash
cd examples/java/reflection
mvn package               # target/reflection.wasm and target/reflection-component.wasm
wasmtime run target/reflection.wasm
wasmtime run target/reflection-component.wasm
mvn exec:exec             # JDK
mvn -Pgraalvm package && ./target/reflection-graalvm    # GraalVM's native-image, with a GraalVM as the JDK
mvn -Pnative package && ./target/reflection             # rontolisp's native executable
```

From the repository's root, the resources and YAVI's sources jar are the source path:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
LIBS="--java-source-path examples/java/reflection/src/main/resources --java-source-dep am.ik.yavi:yavi:0.18.1"
APP=examples/java/reflection/src/main/java/com/example/reflection/Reflection.java
java -jar $JAR $LIBS $APP                                          # interpreter
java -jar $JAR $LIBS $APP -o /tmp/Reflection.class && java -cp /tmp Reflection   # JVM
```

Every one of them prints the JDK's lines:

```
English: Hello, Ann!
Japanese: こんにちは、Annさん
no class com.example.reflection.Esperanto
name=name, messageKey=charSequence.notBlank, message="name" must not be blank
name=age, messageKey=numeric.lessThan, message="age" must be less than 150
```

Take the entries out of `reachability-metadata.json` and both compilers print `no class` for
`English` and `Japanese` too; add a class `Esperanto.java` without declaring it and the JDK
greets with it, while `native-image` and rontolisp do not find it.
