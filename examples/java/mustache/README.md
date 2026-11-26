# Templates with jmustache: `mustache/`

[`Shelf.java`](src/main/java/Shelf.java) renders a bookshelf page with
[jmustache](https://github.com/samskivert/jmustache) from three templates of its resources:
[`shelf.mustache`](src/main/resources/templates/shelf.mustache), the page; [`layout.mustache`](src/main/resources/templates/layout.mustache),
the layout the page fills (`{{<layout}}` with `{{$title}}` and `{{$content}}` blocks); and
[`book.mustache`](src/main/resources/templates/book.mustache), a partial (`{{>book}}`). It shows sections over a
list and over a value, inverted sections, `-last`, and HTML escaping of what the values hold.

jmustache is not built in: it is compiled from its sources jar with the program, on every
backend, of what the program reaches of it. It is a Maven project
([`examples/java/`](../README.md)):

```bash
cd examples/java/mustache
mvn package               # target/mustache.wasm and target/mustache-component.wasm
wasmtime run target/mustache.wasm
wasmtime run target/mustache-component.wasm
mvn exec:exec             # JDK
mvn -Pnative package && ./target/mustache    # native executable
```

From the repository's root, `--java-source-dep` reads it from its sources jar (Maven Central,
through `~/.m2/repository`) for the interpreter and the JVM, and `--java-source-path` hands
the templates:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
LIBS="--java-source-dep com.samskivert:jmustache:1.16 --java-source-path examples/java/mustache/src/main/resources"
APP=examples/java/mustache/src/main/java/Shelf.java
java -jar $JAR $LIBS $APP                                      # interpreter
java -jar $JAR $LIBS $APP -o /tmp/Shelf.class && java -cp /tmp Shelf   # JVM
```

- **What a template names** in a record (`{{title}}`, `{{finished}}` for `isFinished()`) is
  found by jmustache's default collector through reflection by name, which the subset answers
  for the program's own classes. Inside `{{#pages}}..{{/pages}}` the value is an `Integer`:
  jmustache asks it for `read` first, which a class of the JDK does not answer, then the book
  around it.
- **The templates are resources** read through `Shelf.class.getResourceAsStream`, by a name
  the loader computes of a constant prefix and suffix, so the compiled program carries the
  files of its source path that name may be (the templates); it runs from any directory.

`mustache.wasm` is about 570 KB: what the program reaches of jmustache's 2,500 lines, and the
members of `Book` a template may name.
