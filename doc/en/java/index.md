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

`--no-gc` compiles a reactor of scalar values ([A reactor on `--no-gc`](#a-reactor-on---no-gc)). With no program,
`--source-language java` starts a REPL ([At the prompt](#at-the-prompt)).

[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) holds a
program of several packages with its expected output, an HTTP server with its client, a
miniature httpbin, a Servlet application, a JDBC program on PostgreSQL, and a form
validated with a library read from its sources jar. Those that use a library are
[Maven projects](#a-maven-project), and so are the smallest program of each kind:
`hello-java` (a compact source file), `hello-servlet` and `hello-mvcfn`.

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
made with the constructor of no argument. The argument array holds the program's
command-line arguments, without its name: those after `--` on the interpreter
(`rontolisp Main.java -- a b`), and those the host hands a compiled program
(`java Main a b`, `wasmtime run main.wasm a b`, a `--native` executable's). A program with no
`main` is a reactor, whose entry points a host calls. With classes `@WitExport` marks in its
source tree, it is a component exporting their WIT worlds
([Exporting a WIT world](wit.md#exporting-a-wit-world)). With none, a module compiled
`--no-wasi` or `--no-gc` exports the public static methods of the class it starts at
([A reactor of public static methods](#a-reactor-of-public-static-methods)). A file whose
top level holds methods and fields is a compact source file: they are the members of one
class, and every [JDK class](classes.md) the subset has is in scope without an import. A
class declared beside them is a member of that class too: an inner class, unless it is
`static`, a record, an enum or an interface.

## A reactor of public static methods

A program with no `main` and no `@WitExport` class compiles with `--no-wasi` to a module that
imports nothing and exports each public static method of the class it starts at under the
method's name, as a Common Lisp program's `rontolisp:wasm-export`s are exported
([Reactor modules by hand](../guides/wasm-browser.md#reactor-modules-by-hand)). No annotation
and no dependency: under a JDK the class is an ordinary one, whose methods a
[test](testing.md) calls.

```console
$ cat Calc.java
public class Calc {

    static int calls;

    public static int add(int a, int b) {
        return a + b;
    }

    public static String greet(String name) {
        calls++;
        return "Hello, " + name + "! (" + calls + ")";
    }
}
$ rontolisp Calc.java -o calc.wasm --no-wasi --emit-js-glue
```

`--emit-js-glue` writes `calc.js` beside the module: `instantiate` creates an instance and
calls its `_initialize`, which runs the static initializers once, and each export becomes a
function over JavaScript values. The same code runs on Node 22+, in a browser and on
Cloudflare Workers:

```js
import { readFile } from "node:fs/promises";
import { instantiate } from "./calc.js";

const calc = instantiate(new WebAssembly.Module(await readFile("calc.wasm")));
console.log(calc.add(2, 3));       // 5
console.log(calc.greet("Java"));   // Hello, Java! (1)
```

- A parameter is a `byte`, `short`, `int`, `long`, `double`, `boolean` or `String`, and so
  is a result, or `void`. A `long` is a `BigInt` in JavaScript; a `String` crosses as UTF-8
  in linear memory, which the glue copies. Another type is refused at the method, and so
  are two public static methods of one name: a module exports a name once.
- An exception an export leaves uncaught traps, and so does a `null` `String` result. A
  host that keeps serving makes a new instance after a trap.
- The module is wasm-GC. `--no-gc` compiles a class of scalar values to a module any engine
  runs: [A reactor on `--no-gc`](#a-reactor-on---no-gc).
- The interpreter, the JVM, a WASI module and `--native` refuse the program, which has no
  `main` to run; a component exports a WIT world, which `@WitExport` declares.

[`examples/java/no-wasi/`](https://github.com/making/rontolisp/tree/develop/examples/java/no-wasi)
is the smallest, whose exports `wasmtime run --invoke` calls;
[`examples/cloudflare-workers/hello-java/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-java)
is a Worker over such a class.

### A reactor on `--no-gc`

With `--no-gc` the same class compiles to a plain module, which any WebAssembly engine runs,
wasm-GC or not, when every value it holds is a primitive, a `String` or a `double[]`
([The `--no-gc` backend](../guides/wasm-nogc.md)). The `Worker` of
[`examples/cloudflare-workers/hello-java/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-java)
is 1,103 bytes there and 3,956 with `--no-wasi`.

```console
$ rontolisp Calc.java -o calc.wasm --no-gc
```

There is no glue: a host calls the exports directly. A `String` argument is written into the
module's memory through its `__ronto_alloc` export and passed as a pointer and a length, and a
`String` result comes back as such a pair:

```js
const calc = new WebAssembly.Instance(new WebAssembly.Module(await readFile("calc.wasm"))).exports;
const text = (s) => {
  const bytes = new TextEncoder().encode(s);
  const at = calc.__ronto_alloc(bytes.length);
  new Uint8Array(calc.memory.buffer, at, bytes.length).set(bytes);
  return [at, bytes.length];
};
const [at, length] = calc.greet(...text("Java"));
console.log(new TextDecoder().decode(new Uint8Array(calc.memory.buffer, at, length)));   // Hello, Java! (1)
```

- The static initializers run once, when the module is instantiated. An `int` and a `long`
  wrap as the language says; an exception an export leaves uncaught traps, and so do an index
  outside a `double[]` and a `null` `String` result.
- What the backend does not have is refused by name, at its position in the source: a read of
  an object, an array of another type, a lambda, a box, a `StringBuilder`, `try`, a class
  whose initialization can throw, a static `String` assigned during a call, and the library
  members it cannot compile: `toUpperCase` and a `float` in a `+`. A program with a `main` is
  a command, which `--no-gc` does not make. With `--component` the exports are a
  `@WitExport` world's ([Exporting a WIT world](wit.md#exporting-a-wit-world)).

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
subset has but those of `module java.sql`, `module java.naming` and `module java.logging`), `module java.sql` (`java.sql`, `javax.sql`,
`java.util.logging`, `javax.transaction.xa`, `javax.xml.transform`), `module java.naming` (`javax.naming`,
`javax.naming.spi`), `module java.logging` (`java.util.logging`), or with `static` a static member of a
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
  repository, `--java-snapshots` Central's snapshot repository, and
  `--java-update-snapshots` (like `mvn -U`) asks the repositories again for a SNAPSHOT. A library named that
  publishes no sources jar is refused. One it depends on that publishes none is a warning,
  and its classes are not there.

[`examples/java/yavi`](https://github.com/making/rontolisp/tree/develop/examples/java/yavi)
has two programs over YAVI, `--java-source-dep am.ik.yavi:yavi:0.18.1`, that run on every
backend: one validates a record, the other reads one from a map with a schema.
[`examples/java/mustache`](https://github.com/making/rontolisp/tree/develop/examples/java/mustache)
renders a page with jmustache, `--java-source-dep com.samskivert:jmustache:1.16`, from a
layout, a page and a partial among its resources. jmustache finds what a template names in a
value of the program's classes (a record's components, a getter) through reflection by name,
which the subset answers for the program's own classes ([Deviations](deviations.md#what-differs)).

This is not `--java-classpath` and `--java-dep`: those are class files the host runs for a
`java:` call from Common Lisp or Clojure, and a program of the Java subset calls none.

Whether a library works is decided by what the program reaches of it. Only the methods
called, the classes instantiated and the static initializers used are compiled, each with
the checks a program's own code gets. So a library works when the part the program runs
stays within the subset and the [JDK classes](classes.md) it has. A statement the program
reaches that names what the subset lacks -- a JDK class or method it does not have, or a
class no root of the source path has -- is a warning at the place in the library, with
the way the program comes to it, and throws `NoClassDefFoundError` (a class) or
`NoSuchMethodError` (a method) where it runs, as a JVM links a class's code to what it
names where that code first runs:

```
warning: geo-sources.jar!/geo/Table.java:42:54: the class java.util.Currency is not supported by the experimental Java subset yet; where it runs, it throws java.lang.NoClassDefFoundError
  reached through geo.Table.column(String,geo.Table.ToMoney)
```

No value is of such a class: a type test of it is `false`, a cast to it lets `null` alone
through, and a `catch` clause of it takes nothing. In the program's own files, what the
subset lacks is an error where it is written.

A class a library names only in a declaration, such as a parameter's type of a method the
program never calls, is passed by. Reflection over a member whose types name one -- the
method listed or looked up by name -- throws `NoClassDefFoundError` there, as a JDK's
reflection does where its class path lacks the class. An annotation in a library whose type is not on the
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
A component that `wasmtime serve` runs has none at all: a file a served program reads, a
template for one, is a resource. A constant name brings the one file it names. A name computed when the program runs brings
every file its loader may find: those of the source path, and those of the source tree where
the program's loader may be asked. A name joined from text that starts or ends with a constant,
as a template loader's `"templates/" + name + ".mustache"`, brings only the files such a name
may find: in the source tree and in a jar those whose path starts with the constant start and
ends with the constant end, and under a directory of the source path, which resolves a `..`
the computed part may hold, those whose name ends with the constant end. Source files (`.java`), class files (`.class`) and files
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
A directory is a resource too, as the launcher has it: its URL ends in `/` in the source tree,
and its stream reads the names of its entries, one a line. In a jar a directory is found only
where the jar holds an entry of it.

```
URL url = Main.class.getResource("data.txt");   // file:/home/me/app/data.txt
String text = new String(url.openStream().readAllBytes());
```

`ResourceBundle.getBundle` finds a bundle as a JDK does: for each candidate locale from the
most specific, a public class of the bundle's name extending `ResourceBundle`, else a
`.properties` file, read as UTF-8 or, where it is no UTF-8, as ISO 8859-1; where only the
base bundle or none is found, the default locale's candidates. A constant base name brings
its `.properties` files and the classes of its package named after it; a base name computed
when the program runs brings the `.properties` files under the base name's constant start
(`"app.texts." + name` those under `app/texts/`), and every bundle class of the source tree
and the source path: a class under `ResourceBundle`, or under such a
class, by the name its file declares it under. A member class is a bundle of a name with a `$`
(`app.Texts$Words`), as a JDK finds one. `getBundle` of a base name, and of a base name and
a locale, asks the loader of the class calling it. A `ResourceBundle.Control` of the library
(`Control.getControl`, `getNoFallbackControl`) or of the program decides the formats, the
candidate and fallback locales, how a bundle is made and whether it is kept, and is asked in
the order a JDK asks it; `getBundle` with one brings every file and every bundle class,
since it may name any. `getBundle` of a module finds the bundles of
the module's loader: a class's module is its loader's unnamed module, or `java.base` for a
JDK class, which holds none. `Properties.storeToXML` and `loadFromXML` write and read the
XML document of the properties DTD.

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

### Reflection from reachability metadata

A compiled program has no class path to look a name up in where it runs. What it finds by a
name it computes, and what of a library's classes it uses by reflection, it declares in
GraalVM's reachability metadata, the files GraalVM's `native-image` reads: so one metadata
file serves both compilers. They are read when the program is compiled, from
`META-INF/native-image/` of the source tree and of each root of the source path, at any
depth (`META-INF/native-image/<groupId>/<artifactId>/` by convention): a library's jar or
sources jar brings its own, and a Maven project's `src/main/resources` holds the program's.
Of each file `reachability-metadata.json`, its `reflection` entries are read, and of the
older `reflect-config.json` its entries; the other sections are not (resources, bundles and
services are found when the program is compiled whatever the metadata says).

```json
{
  "reflection": [
    {
      "condition": { "typeReached": "com.example.Registry" },
      "type": "com.example.reflection.English",
      "methods": [
        { "name": "<init>", "parameterTypes": [ "java.lang.String" ] },
        { "name": "greet", "parameterTypes": [] }
      ],
      "fields": [ { "name": "label" } ]
    }
  ]
}
```

- **A class an entry names is found by `Class.forName`**, by a name the program computes:
  of the program, of a library on the source path, or of the JDK. A name no entry declares
  is not found (`ClassNotFoundException`), as GraalVM's `native-image` answers by default,
  where a JDK finds any class of its class path; a class an index of annotated classes
  lists is found as before ([Annotations](servlet.md#annotations)). `Class.forName` of a
  constant name finds its class without an entry, as `native-image` folds such a call.
- **A library's class an entry names answers reflection as a JDK does**: its members listed
  and looked up by name and types, with their types and modifiers, the class's modifiers.
  It runs the members the entry declares: a method or a constructor (`<init>`) by its name
  and parameter types, a field by its name, every member of a kind by `allDeclaredMethods`,
  `allPublicMethods`, `allDeclaredFields`, `allPublicFields`, `allDeclaredConstructors`,
  `allPublicConstructors`; an entry of the older form with no `parameterTypes` declares every
  method of the name. Any other member is refused where the program uses it
  (`UnsupportedOperationException`: it runs only by reflection), as `native-image` refuses
  it; its constructor of no parameter runs declared or not. A lookup naming a member by
  constants (`Plugin.class.getMethod("greet")`) declares it, as `native-image` folds one. A
  library's class no entry declares answers no members, as before
  ([Deviations](deviations.md#what-differs)). A class of the program needs no entry: it
  answers and runs all of its members.
- **A member of the JDK an entry names is in the program**, as a lookup naming it by
  constants puts it there: `{"type": "java.lang.String", "methods": [{"name": "isBlank",
  "parameterTypes": []}]}` lets a template engine's `{{blank}}` of a `String` run.
- **A `typeReached` condition** counts where the program reaches its class: any class of
  the program, a class of a library or of the JDK whose static initialization or an
  instance of which the program needs, and every class above one of those. An entry with
  no condition always counts. Where `native-image` asks the condition when the program runs,
  this asks it when it is compiled: an entry counts from the start of a program that
  reaches its class anywhere.
- A program that reflects over no class an entry names compiles to the same bytes with or
  without metadata.

`--java-metadata-repository` also reads the shared repository of GraalVM's reachability
metadata (`oracle/graalvm-reachability-metadata`) for the libraries `--java-source-dep`
resolves: a version of `org.graalvm.buildtools:graalvm-reachability-metadata`, whose
`repository` zip is fetched as a sources jar is, or a directory or zip of it. A library's
metadata there is the one its `index.json` lists for the library's version, else the
latest. Its entries naming a class of the JDK are not read: they record what a JDK's own
classes asked by reflection where the metadata was made, which the subset's library does
not ask. The Maven plugin's `metadataRepository` is the same.

```console
$ rontolisp app/Main.java --java-source-dep org.example:geo:1.2.0 --java-metadata-repository 1.1.19
```

[`examples/java/reflection`](https://github.com/making/rontolisp/tree/develop/examples/java/reflection)
finds its classes by the names a resource lists and calls them by reflection, and asks
YAVI's `ViolationDetail` for its properties by name; it prints the same lines on the JDK,
under `native-image` and on every backend.

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
type's range traps; on the JVM a value that is no integer is an error). So must an array
handed to a parameter of an array type be the vector its type makes, holding values of its
element type: `(make-array n :element-type '(unsigned-byte 32))` for an `int[]`,
`'(unsigned-byte 8)` a `byte[]`, `'(unsigned-byte 16)` a `char[]` or `short[]`,
`'double-float` a `double[]`, `'single-float` a `float[]`, `'bit` a `boolean[]`, a vector of
no element type any other (on wasm another traps where it is read). An integer a `native`
method answers for an `int`, `long`, `short` or
`byte` is cut to the type's width, its low bits, as a value of the type is.

A WIT interface the program imports is a Java interface annotated `@WitImport`, and a WIT
world it exports a class annotated `@WitExport`, the same source on a JDK and on every
target: [WIT interfaces and worlds](wit.md).

## The tools

`rontolisp test FILE.java` runs the tests a file's classes declare with the annotations of
JUnit Jupiter, against JUnit Jupiter compiled from the program's own dependency and the
subset's own part of AssertJ, and exits with their verdict: [Testing a Java
program](testing.md). `rontolisp format` refuses a `.java` file: it re-indents
S-expressions.

## A Maven project

The `wasm` goal of the [Maven plugin](../compiling/maven-plugin.md#a-java-project)
compiles the program in a Maven project's `src/main/java` to WebAssembly at `package`,
while javac still compiles the same sources and surefire still runs their tests. Its `test`
goal runs those tests on the program rontolisp makes ([Testing a Java
program](testing.md#a-maven-project)). The applications of
[`examples/java/`](https://github.com/making/rontolisp/tree/develop/examples/java) that use
a library are such projects.

## What is read

| | |
|---|---|
| Types | `int`, `long`, `short`, `byte`, `char`, `double`, `float`, `boolean`, `String`, classes, interfaces, enums, records, arrays; `var` for a local; type arguments, wildcards (`?`, `? extends T`, `? super T`) and raw types; an inner class of a parameterized class (`Outer<String>.Inner`) |
| Declarations | classes with `extends` and `implements`, abstract classes and methods; classes, interfaces, records and methods with type parameters and their bounds; enums with fields, constructors, methods and constants that have a body; records with compact and canonical constructors; interfaces with abstract, `default`, `static` and `private` methods and constants; `sealed`, `permits` and `non-sealed`; static nested, inner, local and anonymous classes; fields, static fields, a field hiding one of a class above it, static and instance initializer blocks; constructors with `this(..)`, `super(..)` and `outer.super(..)` and statements ahead of them; overloaded and variable arity methods; a receiver parameter (`C this`, an inner class's constructor's `Outer Outer.this`), which is written for its annotations and is no parameter; `synchronized` methods and `volatile` fields; `package` and `import` |
| Statements | local variables (`final`, the unnamed `_`), `if`, `while`, `do`, `for`, the enhanced `for` over an array or an `Iterable`, `switch` with colon or arrow cases, patterns and guards, labeled statements, `break` and `continue` with or without a label, `yield`, `return`, `throw`, `try` with `catch` (of one type or several) and `finally`, `try`-with-resources, `synchronized` over an object or a class literal, `assert` (never enabled: [Deviations](deviations.md)) |
| Expressions | every operator of the primitive types with its conversions, string concatenation, the conditional, assignments and compound assignments, casts (to several types, `(A & B) x`, a lambda and a method reference too), `instanceof` with a type, a type pattern or a record pattern, `switch` expressions, `new` with or without the constructor's type arguments (`new <T>Box(..)`), `outer.new Inner()` with or without a class body, `Outer.this`, array creation with one or several lengths and initializers, text blocks, method calls with or without type arguments, `super.m(..)`, `super.x` for a field, `Interface.super.m(..)`, `Outer.super.m(..)`, `new Box<>(..)` with or without a class body, lambdas and method references (`Type::name`, `expression::name`, `super::name`, `Type::new`, `int[]::new`, `List<String>::size`, `Type::<T>name`, `Type::<T>new`) for any functional interface |

An annotation is read and dropped, on a declaration and on a type (`List<@Label String>`),
but for what a compiler checks of `@Override` and `@FunctionalInterface` and for the
warnings `@SuppressWarnings("unchecked")` and `@SafeVarargs` silence. Its name has to be
one of an annotation type the file can see -- those of `java.lang` and
`java.lang.annotation`, one the program or a library declares with `@interface` -- and any
other name is refused as a compiler refuses it. Where the program runs, an annotation of a
class, of a member, a parameter, a record component or a package is read through
reflection, and the generic types a declaration writes are answered as a JDK answers them
([Deviations](deviations.md#what-differs)); a program that reads none holds none. An `int`, a `long`, a `short` and a `byte` wrap as two's
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
the program's or the library's. A call that gives a raw type where its method wants a
parameterized one is typed as a compiler types it: the type its value is wanted as says
nothing of the type arguments, and its value is of the erasure of the type the method
returns, so `Collections.max(rawList)` is a `Comparable`.

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
`java.util` with its streams, regular expressions and concurrency, `java.io` over memory
and [files](#files), `java.nio.file`, `java.math`, `java.time`, `java.net.http`, an HTTP
server, [sockets](#sockets) and [digests and keys](#digests-and-keys), among others.
[The JDK classes](classes.md) lists each class with its members, and how formatting,
regular expressions, streams and hash tables behave; any other class or member is refused
by name.

## Files

`File`, the file streams, `RandomAccessFile`, `Path` and `Files` reach the host's file
system: on the interpreter and the JVM, every file the process may reach, a relative path
resolved against the working directory; in a `.wasm`, the directories its host preopens, a
relative path resolved against the first of them. What is refused is the JDK's exception
with its words. In the browser a program that reaches a file is refused before it runs:
the page has no file system.

```bash
cat > Notes.java <<'END'
import java.nio.file.*;

void main() throws Exception {
    Path notes = Path.of("notes.txt");
    Files.writeString(notes, "one\ntwo\n");
    IO.println(Files.readAllLines(notes) + " " + Files.size(notes));
}
END
rontolisp Notes.java                                         # [one, two] 8
rontolisp Notes.java -o notes.wasm && wasmtime run --dir . notes.wasm
```

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
| WASM component | `-o app.wasm --component`, run with `wasmtime run` | `wasi:http` |
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
`HttpConnectTimeoutException`. `HttpClient.Builder.cookieHandler` gives a client a
`java.net.CookieManager` or a handler of the program's own: the client sends the cookies it
answers and hands it those of each reply. What the transports cannot give and what is not
there: [Deviations](deviations.md#the-http-client).

`com.sun.net.httpserver` has the server: an `HttpServer`, its contexts, and an `HttpHandler`
answering an `HttpExchange`. It serves through the transport of the target, so one source
is a server on each:

| Target | Build | Who listens |
|---|---|---|
| Interpreter, JVM | `rontolisp App.java`, `-o App.class` | the program, on the address `create` was given |
| WASM component | `-o app.wasm --component`, run with `wasmtime serve app.wasm` | the host (`--addr`), which calls the program for each request through `wasi:http` |
| Native executable | `--native` | the executable, on the address `create` was given |
| WASM reactor | `-o worker.wasm --no-wasi` | the host, which calls the module's `handle-request` export for each request: [a Cloudflare Worker](#a-cloudflare-worker) |

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
[Deviations](deviations.md#the-http-server). A program written against the Jakarta Servlet
API serves through this server on a container compiled with it: [A Servlet
application](servlet.md). Routes written as functions in the style of Spring MVC's WebMvc.fn
serve on that container too: [A WebMvc.fn application](webmvc-fn.md).

### A Cloudflare Worker

A server program compiled with `--no-wasi` is a reactor: a module that imports nothing and
that its host calls. The program is not changed. `main` runs once, when the instance
starts; where it creates the server the application is handed to the reactor and no port is
bound, and each request is one call of the `handle-request` export.
`--emit-worker-entry` writes the JavaScript beside the module that makes a Cloudflare Worker
of it: the glue `--emit-js-glue` writes, and the Worker's `index.js` over it, once (an
`index.js` already there is left as it is):

```console
$ rontolisp Server.java -o src/worker.wasm --no-wasi --host-output --optimize=size --emit-worker-entry
$ cat src/index.js
...
import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
```

A program on `com.sun.net.httpserver`, a [Servlet application](servlet.md) and a
[WebMvc.fn application](webmvc-fn.md) are built the same way, the two with their libraries
on the source path.
[`examples/cloudflare-workers/hello-servlet-one-source`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-servlet-one-source)
and
[`hello-mvcfn-one-source`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-mvcfn-one-source)
are complete Worker projects (`./build.sh && npx wrangler dev`) over the files of
`examples/java`. What every `--no-wasi` module is:
[Reactor mode](../guides/wasm-gc-module.md#no-wasi-reactor-mode). A program with no `main`
exports its methods to the host instead:
[A reactor of public static methods](#a-reactor-of-public-static-methods).

The host calls the program for one request at a time, and its clock moves between the
calls, not within one:

- A handler may start threads and wait for them. A wait no thread of the program can end
  within the call -- `Thread.sleep`, a time limit, what another request brings -- ends the
  instance: the call fails, the Worker answers 500, and the next request starts the program
  anew. `System.exit` and an exception `main` leaves uncaught end the instance too.
- With [`--host-timer`](../guides/wasm-gc-module.md#waits-in-the-host---host-timer) the
  Worker waits for the program instead, through JSPI: a wait for a time waits in its timer,
  and a request whose threads cannot go on waits in the Worker, which calls the program for
  other requests meanwhile -- an asynchronous servlet's timeout, a request waiting for what
  another brings. The threads go on after the last answer: the Worker calls the program
  again for what they have left, within `ctx.waitUntil`.
- What the program prints is the Worker's log with `--host-output` (standard output to
  `console.log`, standard error to `console.error`), the report of what ended an instance
  included; without it the output is discarded and the host sees only
  `RuntimeError: unreachable`. `System.getenv` answers `null`. What it logs through
  `java.util.logging` or SLF4J goes to the console method of the record's level with
  `--wasi-logging` ([The host's log](logging.md#the-hosts-log)).
- `SecureRandom`, which a session's id is drawn from, needs `--host-random`. A session
  outlives the instance where it is kept in Workers KV through `wasi:keyvalue`
  ([Sessions in `wasi:keyvalue`](servlet.md#sessions-in-wasikeyvalue)).
- A body crosses as text unless the build says `--host-boundary=streaming`, which carries
  its octets.
- `java.net.http` is refused by the build unless it says `--host-fetch`. The generated Worker
  sends each request through the platform's `fetch`, and the call waits for the reply
  (JSPI); a thread of the program waits as it does for any reply.
- A socket, so a JDBC connection, is a `SocketException` where it is opened unless the
  build says `--host-sockets`. With it the generated Worker makes each connection with
  `connect()` of `cloudflare:sockets`, and the program waits for a socket as on any other
  target, its other threads going on meanwhile. The host only connects: a `ServerSocket`
  and a bind are a `BindException`. A socket ends with the request that opened it, so one
  kept for the next request throws a `SocketException` at its first use there, and none can
  be waited for while `main` runs
  ([A JDBC program on a Worker](jdbc.md#on-a-cloudflare-worker)).

What differs from the other transports: [Deviations](deviations.md#the-http-server).

## Sockets

`java.net.Socket` and `ServerSocket` are the program's own TCP sockets, and
`javax.net.SocketFactory` makes them. A program runs with them on the interpreter, the JVM,
a `--native` executable and a component, which needs the network of its host
(`wasmtime run -S inherit-network=y app.wasm`); a plain Preview 1 module has none
and throws a `SocketException` at its first socket, and a `--no-wasi` module has its
host's when built with `--host-sockets` ([A Cloudflare Worker](#a-cloudflare-worker)). A write goes out before it returns, a
read answers what has come, and `setSoTimeout` gives a read its deadline. Where the target
has one stack, a thread waiting in `accept` or a read lets the program's other threads go
on, so a server and its client can be threads of one program:

```java
void main() throws IOException, InterruptedException {
    ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread echo = new Thread(() -> {
        try (Socket peer = server.accept()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(peer.getOutputStream(), true);
            String line;
            while ((line = in.readLine()) != null) {
                out.println(line.toUpperCase());
            }
        }
        catch (IOException e) {
            IO.println(e);
        }
    });
    echo.start();
    try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        for (String word : List.of("hello", "sockets")) {
            out.println(word);
            IO.println(in.readLine());
        }
    }
    echo.join();
    server.close();
}
```

```
HELLO
SOCKETS
```

What the targets do otherwise and what is not there: [Deviations](deviations.md#sockets). A program
written against JDBC reaches a PostgreSQL server over them with a driver compiled with
it: [A JDBC program on PostgreSQL](jdbc.md).

## Digests and keys

`MessageDigest`, `Mac` and `SecretKeyFactory` compute MD5, MD2, SHA-1, the SHA-2 family,
SHA-3 and SHAKE, their HMACs and PBKDF2 over those of SHA-1 and SHA-2, on every target; a name written as a constant brings that
algorithm alone into what is compiled. A `SecureRandom` draws the host's entropy.

```java
String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte b : bytes) {
        text.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
    }
    return text.toString();
}

void main() throws GeneralSecurityException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
    IO.println(hex(digest));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    IO.println(hex(mac.doFinal("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8))));
    PBEKeySpec spec = new PBEKeySpec("pencil".toCharArray(), "salt".getBytes(StandardCharsets.UTF_8), 4096, 256);
    IO.println(hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()));
}
```

```
ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8
0997564f292923271312698037b6b0a06a8be7fbd912480847c5a1ace4b8d1c7
```

The names and what is not there: [Deviations](deviations.md#digests-and-keys).

What differs from the language and what is refused: [Deviations](deviations.md).
