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

## Packaging for Maven consumers

The class file is already in its package directory, so a jar is one command,
and installing it into the local repository makes it an ordinary dependency:

```bash
jar cf acme-kernels-1.0.0.jar com/
mvn install:install-file -Dfile=acme-kernels-1.0.0.jar \
    -DgroupId=com.example -DartifactId=acme-kernels -Dversion=1.0.0 \
    -Dpackaging=jar
```

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>acme-kernels</artifactId>
    <version>1.0.0</version>
</dependency>
```

A scalar/string library needs nothing else at run time — the class is
self-contained. One acceleration note: a `--simd` build requires the consumer's
JVM to pass `--add-modules jdk.incubator.vector`; `--blas` and `--gpu` builds
probe for their native library at run time and degrade to the portable kernels
when it is absent, so they need nothing from the consumer.

## Limitations

- Only a fixed-arity top-level `defun` can be exported; `&optional`/`&rest`/
  `&key` lambda lists are refused (wrap them in a fixed-arity `defun`).
- The packed float array (`linalg:`/`vec:` values) is not yet a boundary type,
  so a numeric-array API currently crosses through `:bytes` (a copy each way)
  or stays Lisp-side.
- The jar above carries no Maven metadata of its own and no `Main-Class`;
  coordinates ride the `install:install-file` flags.
