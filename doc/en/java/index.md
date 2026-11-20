# A subset of the Java language (experimental)

**Experimental.** rontolisp reads a subset of the Java language and lowers it to the same
core forms as Common Lisp, so a `.java` program runs on every backend. It is a subset by
design and nothing here is a compatibility promise: a construct outside it is refused by
name, with its position, before anything runs. The front end and its JDK classes are
written for rontolisp from the published specifications; no JDK code is read, bundled or
shipped in a compiled program.

A `.java` file is read as this subset; `--source-language java` says so for any other file
and for `-e`. A program is the file it starts at, the files of its source tree that
file names, and what it reaches of the libraries on its source path
([Using a third-party library](#using-a-third-party-library)).

```bash
rontolisp Hello.java                                # interpreter
rontolisp Hello.java -o Hello.class && java Hello   # JVM
rontolisp Hello.java -o hello.wasm && wasmtime run hello.wasm
rontolisp Hello.java -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp -e 'void main() { IO.println(1 + 2); }' --source-language java
```

`--no-gc` is refused: that backend has no structures, strings or closures. With no program,
`--source-language java` starts a REPL ([At the prompt](#at-the-prompt)).

[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) holds a
program of several packages with its expected output, an HTTP server with its client, a
miniature httpbin, and a form validated with a library read from its sources jar.

```java
interface Shape {
    double area();
}

class Circle implements Shape {
    final double r;

    Circle(double r) {
        this.r = r;
    }

    public double area() {
        return Math.PI * r * r;
    }

    public String toString() {
        return "circle " + r;
    }
}

class Square implements Shape {
    final double side;

    Square(double side) {
        this.side = side;
    }

    public double area() {
        return side * side;
    }
}

void main() {
    List<Shape> shapes = new ArrayList<>();
    shapes.add(new Circle(1.5));
    shapes.add(new Square(2));
    double total = 0;
    for (Shape shape : shapes) {
        total += shape.area();
    }
    IO.println(shapes.get(0) + ", " + shapes.size() + " shapes, total " + total);
    IO.println(Integer.MAX_VALUE + 1);
}
```

```
circle 1.5, 2 shapes, total 11.068583470577035
-2147483648
```

## Where a program starts

At the first class of the file when it has a `main`, otherwise at the class named as the
file. `main(String[])` is chosen before `main()`; an instance `main` runs on an instance
made with the constructor of no argument. The argument array is empty. A file whose
top level holds methods and fields is a compact source file: they are the members of one
class, and every [JDK class](classes.md) the subset has is in scope without an import. A
class declared beside them is a member of that class too: an inner class, unless it is
`static`, a record, an enum or an interface.

## A program of several files

A class the program names that no file read so far declares is read from the file its
package and name spell under the root of the source tree, the directory above the
starting file's package: for `app/Main.java` declaring `package app;`, `util.Strings` is
`util/Strings.java` and `Helper` of the package `app` is `app/Helper.java`. Every class of a
file read joins the program, and the program is compiled whole. The directories above the
starting file must spell its package. A `(load "app/Main.java")` from Common Lisp runs the
program as `rontolisp app/Main.java` does ([Across languages](#across-languages)).

```console
$ rontolisp app/Main.java
$ rontolisp app/Main.java -o main.wasm
```

An `import` names a class, a package on demand, `module java.base` (every JDK class the
subset has but `java.sql`'s), `module java.sql`, or with `static` a static member of a
class, or all of them on demand. A package imported on demand shadows a module imported, and a name
two packages of one kind bring is refused (`reference to Date is ambiguous`). A class
that is not `public` and a member with no access modifier are reached from their own
package only, a `protected` member from its package and from the classes under its class.

## Using a third-party library

A class the program names that its source tree does not declare is read next from the
program's **source path**: the roots of the libraries it uses, as source. A library is
compiled with the program, on every backend, as far as the program reaches it; nothing is
carried beside the output, so a `.wasm` holds what it uses of the library.

```console
$ rontolisp app/Main.java --java-source-path libs/geo-sources.jar:other/src/main/java
$ rontolisp app/Main.java --java-source-dep org.example:geo:1.2.0 -o main.wasm
```

- `--java-source-path` takes directories and jars, separated as a class path is. A class
  `geo.Table` is `geo/Table.java` under the first root that has it.
- `--java-source-dep` takes Maven coordinates and may be repeated: the sources jar of the
  library (`geo-1.2.0-sources.jar`) and of what it depends on at run time join the source
  path, from Maven Central through `~/.m2/repository`; `--java-repository` adds a
  repository. A library named that publishes no sources jar is refused. One it depends on
  that publishes none is a warning, and its classes are not there.

[`examples/java/yavi`](https://github.com/making/rontolisp/tree/develop/examples/java/yavi)
has two programs over YAVI, `--java-source-dep am.ik.yavi:yavi:0.18.0`, that run on every
backend: one validates a record, the other reads one from a map with a schema.

This is not `--java-classpath` and `--java-dep`: those are class files the host runs for a
`java:` call from Common Lisp or Clojure, and a program of the Java subset calls none.

Whether a library works is decided by what the program reaches of it. Only the methods
called, the classes instantiated and the static initializers used are compiled, each with
the checks a program's own code gets. So a library works when the part the program uses
stays within the subset and the [JDK classes](classes.md) it has. What the program reaches
and the subset lacks is an error at the place in the library, with the way the program
comes to it:

```
geo-sources.jar!/geo/Table.java:42:54: the class java.util.Currency is not supported by the experimental Java subset yet
  reached through geo.Table.column(String,geo.Table.ToMoney)
```

A class a library names only in a declaration, such as a parameter's type of a method the
program never calls, is passed by. An annotation in a library whose type is not on the
source path is dropped, as a compiler reads a class file without it: annotation-only
dependencies need not be there. A class a library declares in the file of another class
is found in whichever file of its package declares it, as its class file would be: the
library was compiled whole. In the program's own source tree, such a class is found once
that file was read, as the `java` launcher finds it. A source file of the program wins over
one of the same name on the source path.

### Resources

`getResourceAsStream` of a class or a class loader reads a file of the program's source
tree or of a library on the source path, and `getResource` answers its URL. The files are found when the program is compiled
and their bytes are compiled into the output, so a `.wasm` reads them with no file system.
A constant name brings the one file it names. A name computed when the program runs brings
every file its loader may find: those of the source path, and those of the source tree where
the program's loader may be asked. Source files (`.java`), class files (`.class`) and files
and directories whose names start with a dot are not brought. More than 10,000 files or
16 MiB to bring, or more than 100,000 entries to look at, is refused when the program is
compiled: a program in a large directory, such as the home directory, keeps those resources
in a directory of their own on the source path.

```
InputStream in = Main.class.getResourceAsStream("data.txt");   // app/data.txt beside app/Main.java
InputStream top = Main.class.getResourceAsStream("/top.txt");  // top.txt at the top of the tree
```

The loaders are those of the `java` launcher. A class of the program has the program's
loader, which looks in the source tree and then under each root of the source path, in
order. A class of a library on the source path has the system loader
(`ClassLoader.getSystemClassLoader()`), which looks under the roots only. A JDK class
finds none of them. In the source tree a name is matched as written: `..` and `.` are not
resolved, as the launcher does not resolve them. Under a directory of the source path
they are, as a class path directory resolves them. In a jar the name must match an entry
exactly. The program's loader's parent is the system loader, whose parent is the platform
loader (`ClassLoader.getPlatformClassLoader()`), which finds none of them. The playground's
uploaded files are its program's source tree.

A resource's URL names the file where the program was compiled: `file:` and its absolute
path in the source tree or a directory of the source path, `jar:file:` and the jar's path,
`!/` and the entry in a jar, written as the launcher writes them. `openStream` reads the
bytes compiled into the output, so the URL reads on a machine that has no such file.
`getResources` answers the URL of every place holding the name, the source tree first.

```
URL url = Main.class.getResource("data.txt");   // file:/home/me/app/data.txt
String text = new String(url.openStream().readAllBytes());
```

`ResourceBundle.getBundle` finds a bundle as a JDK does: for each candidate locale from the
most specific, a public class of the bundle's name extending `ResourceBundle`, else a
`.properties` file, read as UTF-8 or, where it is no UTF-8, as ISO 8859-1; where only the
base bundle or none is found, the default locale's candidates. A constant base name brings
its `.properties` files and the classes of its package named after it; a base name computed
when the program runs brings every file, as a computed resource name does, and the bundle
classes the program reaches otherwise. A member class is a bundle of a name with a `$`
(`app.Texts$Words`), as a JDK finds one. `getBundle` of a base name, and of a base name and
a locale, asks the loader of the class calling it.

```
ResourceBundle messages = ResourceBundle.getBundle("app.messages", Locale.UK);
// app/messages_en_GB.properties, then app/messages_en.properties, then app/messages.properties
```

`ServiceLoader.load` finds the providers the files `META-INF/services/<service>` of its
loader's resources name when the program is compiled, the source tree's first and then the
source path's in order, and makes each where the program asks for it. A `Class<?>`, or a
`Class<T>` in a generic method, is told by its name when the program runs among the services
those files name. A provider fails where a JDK's does: a class not found, of another
type, with no public constructor of no parameters, or that cannot be made.

## At the prompt

`--source-language java` with no program, and the playground's Java pick, read a buffer at a
time: a statement, an expression, a variable, a method, a class or an import. A statement
needs no `;`. An expression is shown under a generated name, `$1`, `$2`, ..., which a later
buffer can read with the expression's type; a variable under its own name. A string and a
`char` are shown as literals, an array with its type and elements.

```console
java> int twice(int n) { return 2 * n; }
java> int quad(int n) { return twice(twice(n)); }
java> quad(1)
$1 ==> 4
java> int twice(int n) { return n + 1; }
java> quad(1)
$2 ==> 3
java> var names = new String[] { "a", "b" }
names ==> String[2] { "a", "b" }
java> 1 / 0
Error: Exception java.lang.ArithmeticException: / by zero
```

A declaration replaces the earlier one of its name (a method: of its name and parameter types),
and what was declared before calls the new one: a method, an override, a subclass added later
is reached by every call. A class keeps its static fields until it is declared again; then the
variables whose type names it hold `null`. The session reads no file of a source tree; a class
of a library on the [source path](#using-a-third-party-library) is read as a program's is.
The playground has no source path. Its uploaded files are the source tree of its program
and of its session instead: a class the program names is read from the upload its name
spells, `Place` from `Place.java` and `geo.Place` from `geo/Place.java`, and is checked
as the program's own. The page uploads a file by its name alone, so an uploaded class is
in the unnamed package.

## Across languages

A Common Lisp program loads a `.java` file. A file with a `main` runs it; a file with none
defines its classes and runs nothing. A static method is the function named by its class, its
name and its parameter types: `|Geometry.area(int)|`, `|app.Geometry.area(int)|` in a package,
`|Point.new(int,int)|` for a constructor. A `native` static method of a Java program is the
Common Lisp function its name spells, a hyphen before each capital: `stringUpcase` is
`string-upcase`, read in the package the program is loaded in.

```console
$ cat Geometry.java
record Point(int x, int y) { }

class Geometry {
    static native int square(int n);
    static native String stringUpcase(String s);

    static int norm2(Point p) {
        return square(p.x()) + square(p.y());
    }

    static String shout(String s) {
        return stringUpcase(s) + "!";
    }
}
$ cat main.lisp
(defun square (n) (* n n))
(load "Geometry.java")
(print (|Geometry.norm2(Point)| (|Point.new(int,int)| 3 4)))
(print (|Geometry.shout(String)| "hi"))
$ rontolisp main.lisp
25
"HI!"
```

Nothing is converted but this: a `char` crosses as a character, a `boolean` comes back as `T`
or `NIL`. A number is a number, a string a string, `null` is `NIL`, an object of the program a
structure, an `Object[]` a vector. A Common Lisp list is no `List`: hand a Java method a vector.
An integer handed to an `int`, `long`, `short`, `byte` or `char` parameter must be one of the
type's values: the compiled program takes it as that type declares (on wasm an integer past the
type's range traps; on the JVM a value that is no integer is an error). An integer a `native` method answers for an `int`, `long`, `short` or
`byte` is cut to the type's width, its low bits, as a value of the type is.

## The tools

`rontolisp test` refuses a `.java` file: the subset has no test framework, and a program's
exit status is its verdict. `rontolisp format` refuses one: it re-indents S-expressions.

## A Maven project

The `wasm` goal of `rontolisp-maven-plugin` compiles the program in a Maven project's
`src/main/java` to WebAssembly at `package`, starting at its main class. javac still
compiles the same sources and surefire still runs their tests, so the program also runs
on a JDK.

```xml
<plugin>
    <groupId>am.ik.rontolisp</groupId>
    <artifactId>rontolisp-maven-plugin</artifactId>
    <version>VERSION</version>
    <configuration>
        <mainClass>app.Main</mainClass>
    </configuration>
    <executions>
        <execution>
            <id>preview1</id>
            <goals><goal>wasm</goal></goals>
        </execution>
        <execution>
            <id>component</id>
            <goals><goal>wasm</goal></goals>
            <configuration>
                <component>true</component>
                <outputFile>${project.build.directory}/app-component.wasm</outputFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

```console
$ mvn package
$ wasmtime run target/app-1.0.0.wasm
$ wasmtime run target/app-component.wasm
```

- The program is the one `rontolisp src/main/java/app/Main.java -o target/app-1.0.0.wasm`
  compiles: the main class's file and the files of the tree it names
  ([A program of several files](#a-program-of-several-files)).
- The output is `target/<finalName>.wasm` unless `outputFile` names another;
  `<native>true</native>` writes the native executable `target/<finalName>`.
- The parameters are the command line's flags in camel case: `component`, `noWasi`,
  `optimize`, `simd`, `hostRandom`, `hostFetch`, `reentrant`, `hostBoundary`,
  `reportLocations`, `emitWit`, `emitJsGlue`, `native`, `nativeTarget`, `nativeCpu`,
  `dynamic`, `noPrune`, `warningsAsErrors`, `javaSourcePath`. `skip`
  (`-Drontolisp.skip=true`) turns the goal off.
- The front end reads source, not class files
  ([Using a third-party library](#using-a-third-party-library)). The sources jar of each
  `compile`, `provided` and `system` dependency is fetched from the project's repositories
  and joins the source path, after the directories and jars `<javaSourcePath>` lists. A dependency with no sources jar is a warning; a class of it the
  program names fails the goal with its name and position, for `@Nullable` from such a jar:
  `.../app/Main.java:7:9: cannot find symbol: class Nullable`.
- The project's [resources](#resources) are those of `target/classes` (`classesDirectory`),
  where `process-resources` has copied and filtered them, searched ahead of `<javaSourcePath>`
  and the dependencies, as the class path of `java` orders them.
- When no file of the tree or of a directory on the source path, no resource and no jar on it is
  newer than the last build, none was added or removed, the parameters and the source path are
  the same and the output exists, nothing is compiled. Otherwise the whole program is.
- An [HTTP](#http) server built with `component` runs from `target/` under
  `wasmtime serve`.

## What is read

| | |
|---|---|
| Types | `int`, `long`, `short`, `byte`, `char`, `double`, `float`, `boolean`, `String`, classes, interfaces, enums, records, arrays; `var` for a local; type arguments, wildcards (`?`, `? extends T`, `? super T`) and raw types; an inner class of a parameterized class (`Outer<String>.Inner`) |
| Declarations | classes with `extends` and `implements`, abstract classes and methods; classes, interfaces, records and methods with type parameters and their bounds; enums with fields, constructors, methods and constants that have a body; records with compact and canonical constructors; interfaces with abstract, `default`, `static` and `private` methods and constants; `sealed`, `permits` and `non-sealed`; static nested, inner, local and anonymous classes; fields, static fields, a field hiding one of a class above it, static and instance initializer blocks; constructors with `this(..)`, `super(..)` and `outer.super(..)` and statements ahead of them; overloaded and variable arity methods; `synchronized` methods and `volatile` fields; `package` and `import` |
| Statements | local variables (`final`, the unnamed `_`), `if`, `while`, `do`, `for`, the enhanced `for` over an array or an `Iterable`, `switch` with colon or arrow cases, patterns and guards, labeled statements, `break` and `continue` with or without a label, `yield`, `return`, `throw`, `try` with `catch` (of one type or several) and `finally`, `try`-with-resources, `synchronized` over an object or a class literal |
| Expressions | every operator of the primitive types with its conversions, string concatenation, the conditional, assignments and compound assignments, casts (to several types, `(A & B) x`, a lambda and a method reference too), `instanceof` with a type, a type pattern or a record pattern, `switch` expressions, `new` with or without the constructor's type arguments (`new <T>Box(..)`), `outer.new Inner()` with or without a class body, `Outer.this`, array creation with one or several lengths and initializers, text blocks, method calls with or without type arguments, `super.m(..)`, `super.x` for a field, `Interface.super.m(..)`, `Outer.super.m(..)`, `new Box<>(..)` with or without a class body, lambdas and method references (`Type::name`, `expression::name`, `super::name`, `Type::new`, `int[]::new`, `List<String>::size`, `Type::<T>name`, `Type::<T>new`) for any functional interface |

An annotation is read and dropped, on a declaration and on a type (`List<@Label String>`),
but for what a compiler checks of `@Override` and `@FunctionalInterface` and for the
warnings `@SuppressWarnings("unchecked")` and `@SafeVarargs` silence. Its name has to be
one of an annotation type the file can see -- those of `java.lang` and
`java.lang.annotation`, one the program or a library declares with `@interface` -- and any
other name is refused as a compiler refuses it. Nothing reads an annotation where the
program runs. An `int`, a `long`, a `short` and a `byte` wrap as two's
complement; a `float` is rounded to single precision at every operation. A `double` and a
`float` print as the shortest text that reads back as the same value of their type.

```java
sealed interface Shape permits Circle, Rect { }

record Circle(double r) implements Shape { }

record Rect(double w, double h) implements Shape { }

enum Unit {
    CM(1), M(100);

    final int scale;

    Unit(int scale) {
        this.scale = scale;
    }
}

double area(Shape shape) {
    return switch (shape) {
        case Circle c -> Math.PI * c.r() * c.r();
        case Rect(double w, double h) -> w * h;
    };
}

void main() {
    Shape[] shapes = {new Circle(1), new Rect(2, 2), new Rect(2, 3)};
    for (Shape shape : shapes) {
        String kind = shape instanceof Rect r && r.w() == r.h() ? "square" : "shape";
        IO.println(shape + " is a " + kind + " of area " + area(shape));
    }
    for (Unit unit : Unit.values()) {
        String size = switch (unit) {
            case CM -> "small";
            case M -> "large";
        };
        IO.println(unit + " " + unit.ordinal() + " " + unit.scale + " " + size);
    }
}
```

```
Circle[r=1.0] is a shape of area 3.141592653589793
Rect[w=2.0, h=2.0] is a square of area 4.0
Rect[w=2.0, h=3.0] is a shape of area 6.0
CM 0 1 small
M 1 100 large
```

## Unchecked warnings

What a compiler warns of as unchecked -- a member called through a raw type, a raw type
converted to a parameterized one, a cast the program that runs cannot check, a variable
arity parameter of a type it cannot check -- is printed on standard error before the
program runs, as a compiler prints it. A compile prints them as it compiles; the compiled
program prints nothing. A declaration annotated `@SuppressWarnings("unchecked")` warns of
nothing inside it. A `catch` clause nothing reaches, because the clauses before it take
every exception of its `try` it could, is warned of in the same way
(`unreachable catch clause`).

```java
void main() {
    List names = new ArrayList();
    names.add("ann");
    IO.println(names);
}
```

```
[ann]
```

Run as `Raw.java`, it prints first, on standard error:

```console
Raw.java:3: warning: [unchecked] unchecked call to add(E) as a member of the raw type List
    names.add("ann");
             ^
  where E is a type-variable:
    E extends Object declared in interface List
1 warning
```

## Type parameters, lambdas and method references

A type parameter exists only while the program is read: the type arguments of a call and of
`new Box<>(..)` are found from the arguments, from the type the value is wanted as and from
the lambdas among the arguments, and a value is boxed or unboxed where a type argument is a
box. A lambda and a method reference are made for any interface with one abstract method,
the program's or the library's.

```java
record Pair<A, B>(A first, B second) {
    <C> Pair<A, C> withSecond(Function<? super B, ? extends C> f) {
        return new Pair<>(first, f.apply(second));
    }
}

<T extends Comparable<T>> T max(List<T> items) {
    T best = items.get(0);
    for (T item : items) {
        if (item.compareTo(best) > 0) {
            best = item;
        }
    }
    return best;
}

<T, R> List<R> map(List<T> items, Function<? super T, ? extends R> f) {
    List<R> out = new ArrayList<>();
    for (T item : items) {
        out.add(f.apply(item));
    }
    return out;
}

void main() {
    List<String> words = new ArrayList<>();
    words.add("pear");
    words.add("fig");
    words.add("banana");
    List<Integer> lengths = map(words, String::length);
    Pair<String, Integer> longest = new Pair<>(max(words), max(lengths));
    IO.println(lengths + " " + longest + " " + longest.withSecond(n -> n * 1.5));
    Comparator<String> byLength = Comparator.comparingInt(String::length);
    Predicate<String> isShort = word -> word.length() < 5;
    IO.println(byLength.reversed().compare("fig", "pear") + " " + map(words, isShort.negate()::test));
}
```

```
[4, 3, 6] Pair[first=pear, second=6] Pair[first=pear, second=9.0]
1 [false, false, true]
```

## The JDK classes

The subset has its own implementation of a part of the JDK's classes: `java.lang`,
`java.util` with its streams, regular expressions and concurrency, `java.io` over memory,
`java.math`, `java.time`, `java.net.http` and an HTTP server, among others.
[The JDK classes](classes.md) lists each class with its members, and how formatting,
regular expressions, streams and hash tables behave; any other class or member is refused
by name.

## Threads

A `Thread` is started, joined, interrupted and named as the language says. `synchronized`
holds the monitor of an object, `wait` and `notify` hand it from one thread to another,
and a `volatile` field is read as the last write left it. What a thread leaves uncaught is
written to standard error with the thread's name and its causes, and ends that thread alone. The program
ends when every thread that is no daemon has.

```java
class Counter {
    private int count;

    synchronized void add() {
        count++;
    }

    synchronized int get() {
        return count;
    }
}

void main() throws InterruptedException {
    Counter counter = new Counter();
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
        Thread worker = new Thread(() -> {
            for (int k = 0; k < 1000; k++) {
                counter.add();
            }
        }, "worker-" + i);
        workers.add(worker);
        worker.start();
    }
    for (Thread worker : workers) {
        worker.join();
    }
    IO.println(counter.get() + " after " + workers.get(3).getName());
}
```

```
4000 after worker-3
```

On the interpreter and the JVM a thread is a virtual thread of the host, and threads run
in parallel. A `.wasm` has one stack, and its threads take turns on it: a thread runs until
it waits, sleeps, yields or ends, and goes on from there at a later turn. A program whose
threads share what they share under a monitor, a `volatile` field or an atomic prints the
same on every backend; what the turns change for any other is in
[Deviations](deviations.md#threads).

**Run** on these pages, and the playground, run a program that starts threads as such a
`.wasm`, compiled in the page ([Deviations](deviations.md#threads)).

`java.util.concurrent` has locks, latches, barriers, queues, a concurrent map, executors
-- a pool of a program's own, a scheduled one, a fork/join pool -- and `CompletableFuture`,
and `java.util` a `Timer`, all written over the same monitors. `Vector`, `Hashtable`, `StringBuffer`
and the wrappers of `Collections.synchronizedList` and the like hold their own monitor
in every member.

```java
void main() {
    Map<String, Integer> lengths = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (String word : List.of("one", "three", "seven")) {
            pool.execute(() -> lengths.put(word, word.length()));
        }
    }
    CompletableFuture<Integer> total = CompletableFuture.supplyAsync(() -> lengths.get("three"))
        .thenApply(n -> n + lengths.size());
    IO.println(lengths + " " + total.join());
}
```

```
{one=3, seven=5, three=5} 8
```

## HTTP

`java.net.http` has the client. An `HttpClient` sends an `HttpRequest` and answers an
`HttpResponse` whose body is what the handler makes of the reply: `send` once the reply is
in, `sendAsync` as a `CompletableFuture`. Every request goes through
[`rontolisp:fetch`](../guides/http-fetch.md), so one source runs on every transport fetch
has:

| Target | Build | Transport |
|---|---|---|
| Interpreter, JVM | `rontolisp App.java`, `-o App.class` | the JDK's `HttpClient` |
| WASM component | `-o app.wasm --component`, run with `wasmtime run -S http=y` | `wasi:http` |
| Native executable | `--native` | the executable's own client |

A plain Preview 1 module has no transport: the compile refuses the program, naming the
flags that give it one. `java.net` is part of `java.base`; a compact source file imports
the client with `import module java.net.http;`.

```java
import module java.net.http;

void main() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://httpbin.ik.am/get"))
        .header("Accept", "application/json")
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    IO.println(response.statusCode() + " " + response.headers().firstValue("content-type").orElse("none"));
    HttpRequest missing = HttpRequest.newBuilder(URI.create("https://httpbin.ik.am/status/404")).build();
    IO.println(client.sendAsync(missing, HttpResponse.BodyHandlers.discarding())
        .thenApply(HttpResponse::statusCode)
        .join());
}
```

```
200 application/json
404
```

A builder refuses what a JDK's refuses, with its message: a restricted or malformed header,
a URI that is no absolute `http` or `https` one. `followRedirects(Redirect.NORMAL)` and
`ALWAYS` follow a 301, 302, 303, 307 and 308 as a JDK's client does, and
`previousResponse()` answers the replies on the way. A connection that is refused is a
`ConnectException`. `HttpRequest.Builder.timeout` limits how long a reply may take to start
and `HttpClient.Builder.connectTimeout` how long a connection may take, each a
`java.time.Duration`: a request that passes one is an `HttpTimeoutException` or an
`HttpConnectTimeoutException`. What the transports cannot give and what is not there:
[Deviations](deviations.md#the-http-client).

`com.sun.net.httpserver` has the server: an `HttpServer`, its contexts, and an `HttpHandler`
answering an `HttpExchange`. It serves through the transport of the target, so one source
is a server on each:

| Target | Build | Who listens |
|---|---|---|
| Interpreter, JVM | `rontolisp App.java`, `-o App.class` | the program, on the address `create` was given |
| WASM component | `-o app.wasm --component`, run with `wasmtime serve -S cli=y app.wasm` | the host (`--addr`), which calls the program for each request through `wasi:http` |
| Native executable | `--native` | the executable, on the address `create` was given |

A plain Preview 1 module cannot listen: `create` throws an `IOException` naming
`--component` and `--native`. A compact source file imports the server with
`import module jdk.httpserver;`.

A program lives while a server it started runs: `main` may return or wait, and requests
are answered until `stop`. A handler runs on a thread of the executor the server was given
and may wait for the program's other threads; a context has its filters
(`getFilters`) and its authenticator. Under `wasmtime serve` the host starts the program
for the requests it likes, and the address of `create` is not bound.
`sendResponseHeaders(status, length)` announces a body of that length, `-1` none, and `0` a
body of a length not told ahead, which goes out chunk by chunk as it is written where the
transport can (under `wasmtime serve`), and whole elsewhere.

This program serves one context and asks it once from `main`, so it runs where the target
has threads, the interpreter and the JVM:

```java
import module java.net.http;
import module jdk.httpserver;

void main() throws IOException, InterruptedException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hello", exchange -> {
        String name = exchange.getRequestURI().getQuery();
        byte[] body = ("Hello, " + name + "!").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    });
    server.start();

    URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hello?Ann");
    HttpResponse<String> response = HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    IO.println(response.statusCode() + " " + response.body());
    server.stop(0);
}
```

```
200 Hello, Ann!
```

A server on its own, and how to run it on each target, is
[`examples/java/http`](https://github.com/making/rontolisp/tree/develop/examples/java/http).
What the transports cannot give and what is not there:
[Deviations](deviations.md#the-http-server).

What differs from the language and what is refused: [Deviations](deviations.md).
