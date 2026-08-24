# Export a JVM Library (jvm-export / --no-main)

A compiled `.class` is normally a *command*: `java Prog` runs its top level and
exits. This guide is about the other shape — a *library* class whose functions
Java code calls directly. Two pieces make it work:

- [`rontolisp:jvm-export`](../reference/functions/rontolisp-jvm-export.md)
  declares a typed, Java-callable `public static` method for a `defun`, and
- `--no-main` drops the `main` entry point, so the class is nothing but its
  exports.

It is the JVM twin of the WASM side's
[`rontolisp:wasm-export`](../reference/functions/rontolisp-wasm-export.md) and
`--no-wasi` reactor mode, and solves the same problem: a compiled `defun`'s
untyped method (`public static Object NORM2(Object)`) takes and returns
internal representations no Java caller can safely construct — a string
argument passed directly is even silently mis-read, because the internal string
representation is not a bare Java `String`. The typed wrapper is the safe
boundary.

## A library, start to finish

`kernels.lisp`:

```lisp
(defvar *scale* 2.0)

(defun scaled-sum (a b)
  (* *scale* (+ a b)))

(defun greet (name)
  (concatenate 'string "hello, " name))

(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)
(rontolisp:jvm-export 'greet :params '(:string) :returns :string)
```

Compile it. A directory in the `-o` path becomes the class's Java package, and
the directory is created for you:

```bash
rontolisp kernels.lisp -o com/example/Kernels.class --no-main
```

The class now carries exactly the API the directives declared:

```java
package com.example;

public class Kernels {
    public static double scaledSum(double a, double b);
    public static String greet(String name);
}
```

And a Java caller uses it like any other class:

```java
import com.example.Kernels;

public class App {
    public static void main(String[] args) {
        System.out.println(Kernels.scaledSum(2.5, 3.5)); // 12.0
        System.out.println(Kernels.greet("ron"));        // hello, ron
    }
}
```

```bash
javac -cp . App.java && java -cp . App
```

The method name defaults to the Lisp name lower-camel-cased (`scaled-sum`
becomes `scaledSum`); `:as "name"` picks another. The full designator-to-Java
type table, and the boundary's exact-or-throw conversion rule, are on the
[reference page](../reference/functions/rontolisp-jvm-export.md).

## The top level runs at class initialization

`(defvar *scale* 2.0)` above must have run before the first `scaledSum` call
arrives, and no `main` will run it. An export-carrying class therefore runs its
top-level forms in the class initializer — once, when the JVM first touches the
class. This is the same design as the `--no-wasi` reactor, which runs its top
level at instantiation, and it has the same two sharp edges: a top-level form
that signals surfaces as `ExceptionInInitializerError` (and the class stays
poisoned for the caller's whole JVM), and a top-level `(uiop:quit ...)`
terminates the calling JVM. Keep a library's top level to definitions and
initialization.

`--no-main` is orthogonal to the directive: without the flag the class keeps a
`main` **and** its exports — a CLI tool that is also a library. `main` then
does nothing but trigger class initialization, so the program still runs
exactly once. With the flag, at least one `jvm-export` is required: `main` is
the only [tree-shaker](../compiling/jvm.md#optimize-dead-code-elimination) root
otherwise, and a main-less class without exports would shake to nothing.
Exports are extra shaker roots, which is what lets a library keep the default
`--optimize` size instead of carrying the whole runtime with `--optimize=off`.

## The packed float array

`linalg:` and `vec:` values are **packed float arrays**, and they cross the
boundary as themselves — `:float-vector` (rank 1) and `:float-matrix` (rank 2),
both carried by one Java class, `am.ik.rontolisp.runtime.RontoFloatArray`:

```lisp
(defun norm2 (x)
  (sqrt (vec:dot x x)))

(defun axpy (a x y)
  (vec:add (vec:scale x a) y))

(rontolisp:jvm-export 'norm2 :params '(:float-vector) :returns :float)
(rontolisp:jvm-export 'axpy :params '(:float :float-vector :float-vector) :returns :float-vector)
```

```java
import am.ik.rontolisp.runtime.RontoFloatArray;
import com.example.Kernels;

RontoFloatArray x = RontoFloatArray.of(new double[] { 3.0, 4.0 });   // copies, once
double n = Kernels.norm2(x);                                          // 5.0
RontoFloatArray y = Kernels.axpy(2.0, x, RontoFloatArray.of(new double[] { 1.0, 1.0 }));
double[] out = y.toArray();                                           // copies out, once
```

**Why a handle and not a `double[]`.** A packed float array is a bare
`double[]` (or `float[]`) carrying an embedded dimension header, so a plain
Java array is not one — passing `new double[]{3, 4}` would compile and answer a
wrong number. Converting one at every call would be safe but costs about ten
times the kernel it feeds, which turns a 3x win into a 3x loss:

| | ms/call | vs plain Java |
| --- | --- | --- |
| plain Java loop, C2 auto-vectorized | 0.89 | 1.00x |
| kernel on a pre-packed array (the floor) | 0.29 | 3.06x |
| **kernel behind the handle** | **0.29** | **3.12x** |
| kernel behind a facade that copies per call | 2.58 | 0.35x |

The handle holds the packed representation across calls: `of(...)` copies once,
`toArray()` copies out once, every call in between copies nothing. The
benchmark is [`examples/jvm/bench/`](https://github.com/making/rontolisp/tree/main/examples/jvm/bench).

**A handle aliases the Lisp array.** A handle a kernel *returns* is the very
array the Lisp side holds: `set(i, v)` through the handle is visible to a Lisp
closure over it, and a Lisp write is visible through `get(i)`. Nothing is
defensively copied — that copy is the last row of the table. `of(...)` and
`toArray()` are the two places where a copy happens, and they are the two
places where you asked for one. This also makes destination-passing work:
`RontoFloatArray.zeros(...)` builds a buffer that a `vec:...-into` export
writes into, so a Java-side loop allocates nothing per iteration.

**Under `--gpu`, a result stays on the device until you read it.** A handle a
`--gpu` kernel returned is not brought home at the boundary, so a Java-side
chain `h = Kernels.step(w, h)` leaves every intermediate on the device and only
the read at the end downloads one. Measured over 200 chained GEMVs on a
resident 2048x2048 matrix: 0.070 ms per iteration, the same as the loop that
never leaves Lisp, and one upload for the whole run instead of one per call.
What follows from it is that the *read* is where the cost sits — the first
`get(i)` or `toArray()` on a fresh device result pays the download, later ones
do not — so read a result once rather than element by element.

Both element widths cross the same designator (`of(double[])` and
`of(float[])`; `width()` reports which), and the rank comes from the header, so
a matrix is the same class with a rank-2 `dims()` rather than a second type. A
rank the designator does not declare throws at the boundary.

## A Maven project: `src/main/lisp`

If the kernels and the Java that calls them live in the same project, the Lisp does not
have to become an artifact at all — it is just another source set, and Maven already
knows how to package `target/classes`. One `<plugin>` block is the whole setup:

```xml
<plugin>
    <groupId>am.ik.rontolisp</groupId>
    <artifactId>rontolisp-maven-plugin</artifactId>
    <version>VERSION</version>
    <executions>
        <execution>
            <goals><goal>compile</goal></goals>
            <configuration>
                <simd>true</simd>
            </configuration>
        </execution>
    </executions>
</plugin>
```

```
pom.xml
src/main/lisp/com/example/Kernels.lisp   <- the kernels, with their jvm-export declarations
src/main/lisp/com/example/helpers.lisp   <- ordinary Lisp the kernels (load ...), no class
src/main/java/app/App.java               <- Kernels.scaledSum(...), Kernels.norm2(...)
```

```bash
mvn package     # one jar, Lisp classes and Java classes together
```

**A source set is Lisp, not a pile of exports.** A file becomes a class exactly when it
declares at least one `rontolisp:jvm-export` — that is what gives the class an entry point
a Java caller can use. Everything else in `src/main/lisp` stays ordinary Lisp: the support
code the exported files `(load ...)`, a program you run with the interpreter. It is not
compiled, it is not an error, and it does not have to be named like a class, so
`string-utils.lisp` is fine beside `Kernels.lisp`.

**The path under `src/main/lisp` is the class name** for the files that do export, exactly
as a `.java` file's path is: `src/main/lisp/com/example/Kernels.lisp` becomes
`com.example.Kernels`, and nothing has to be declared per file. `mvn install`,
`mvn deploy`, a Gradle consumer of the resulting jar and an IDE all work with no further
concepts, because the output is ordinary classes in the ordinary place.

The goal runs at `process-sources`, before javac, because `src/main/java` compiles
*against* what it writes — the kernel class and the `RontoFloatArray` handle type both.
The `testCompile` goal is its twin: `src/test/lisp` into `target/test-classes`, at
`process-test-sources`.

Every flag that reaches the JVM backend is a parameter under the same name — `simd`,
`blas`, `gpu`, `parallel`, `optimize`, `dynamic`, `noPrune`, `systemPath`, `dists` — and
`skip` (`-Drontolisp.skip=true`) turns the goal off. One default differs from the command
line: `noMain` is **on**, because a source set is a library, and that is also what makes
an unexported file ordinary Lisp rather than a class. Set `<noMain>false</noMain>` and
every file gets a `main` and is compiled, the way `rontolisp prog.lisp -o Prog.class`
compiles a program.

A compile error is reported as a build failure carrying the rontolisp diagnostic verbatim,
`file:line:column:` prefix included, so an IDE can jump to it. Compilation is incremental
in `maven-compiler-plugin`'s sense: if nothing is stale, nothing is compiled, and if
anything is, the whole source set is — a `(load "...")` splices one file into another, so
per-file timestamps cannot be trusted alone.

## Packaging for Maven consumers

When the kernels are built *separately* from the projects that use them — a team
publishing to other teams, a non-Maven consumer, anything pushed to a repository
— the compiler writes the artifact itself. `-o out.jar` compiles straight to a
jar, and `--maven-coordinates` stamps that jar with its own identity:

```bash
rontolisp kernels.lisp -o acme-kernels-1.0.0.jar \
    --class-name com.example.Kernels \
    --maven-coordinates com.example:acme-kernels:1.0.0 \
    --no-main
```

`--class-name` is not a convenience here — a `.jar` path names no class, where
a `.class` path always did. It works for `.class` output too, where it replaces
the name the `-o` path would give, so a build directory no longer has to be
shaped like the package.

The coordinates then ride **inside** the jar, as the
`META-INF/maven/<groupId>/<artifactId>/pom.xml` + `pom.properties` pair every
Maven-built jar already carries. Installing it therefore takes no coordinate
flags at all — no `-DgroupId`, no `-DartifactId`, no `-Dversion`, no
`-DpomFile`:

```bash
mvn install:install-file -Dfile=acme-kernels-1.0.0.jar
```

and it is an ordinary dependency from there on:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>acme-kernels</artifactId>
    <version>1.0.0</version>
</dependency>
```

`--emit-pom` writes that same pom next to the jar as `acme-kernels-1.0.0.pom`
as well, for a `deploy-file` that wants it as a separate file; it refuses to
overwrite a pom it did not write itself.

### What the jar contains

| entry | when |
| --- | --- |
| `META-INF/MANIFEST.MF` | always — with a `Main-Class` only when the class has a `main`, so `java -jar` runs a program jar and a `--no-main` library jar carries none |
| `com/example/Kernels.class` | always |
| `am/ik/rontolisp/runtime/*.class` | when a `:float-vector` / `:float-matrix` export declares the handle type |
| `META-INF/maven/.../pom.xml`, `pom.properties` | with `--maven-coordinates` |

The handle classes travel inside the artifact at their canonical names rather
than renamed into your package, because two rontolisp libraries have to agree
on the type for a caller to feed one's result to the other's kernel; the copies
are identical bytes. Forgetting them would not be a compile error here — it
would be a `NoClassDefFoundError` in the consumer.

The generated pom's `<dependencies>` is empty, and that is the point rather
than an omission: a compiled class embeds everything it calls, so the artifact
really has none. One acceleration note: a `--simd` build gets its vector
kernels only on a JVM started with `--add-modules jdk.incubator.vector` —
without the module the class degrades to the portable scalar kernels and says
so — and the generated pom repeats it in its `<description>`, because the
consumer never saw the build command. `--blas` and `--gpu` builds probe for
their native library at run time and degrade the same way, so they need nothing
from the consumer either.

Compiling the same program twice produces byte-identical jars: the entry order
and the entry timestamps are fixed, not taken from the clock.

## Limitations

- Only a fixed-arity top-level `defun` can be exported; `&optional`/`&rest`/
  `&key` lambda lists are refused (wrap them in a fixed-arity `defun`).
- A packed float array crosses at rank 1 or 2; rank 3 and above has no
  designator yet, and a general (boxed) array has none at all — `:bytes` is
  still the only designator for a non-float array.
- `-o out.jar` writes exactly one artifact: no `-sources` or `-javadoc` jar,
  and no signature. `install-file` / `deploy-file` take those as their own
  flags.
