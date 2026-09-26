# Java Interop

The `java` package lets rontolisp drive arbitrary Java APIs by reflection —
construct objects, call instance and static methods, read fields, and turn a
rontolisp lambda into a Java interface instance. It is how the Swing demos in
`examples/` (`java-interop.lisp`, `swing.lisp`, `life-gui.lisp`) put a window on
the screen without any bespoke Java glue.

> **JVM only (interpreter and compiled `.class`).** Interop values are opaque
> host-object references, so the feature needs a real
> JVM: it works under the **JVM-hosted interpreter** (`java -jar rontolisp.jar
> program.lisp`) and in a **JVM-compiled program** (`-o Prog.class`, run with
> `java Prog`) — a call the compiler resolves becomes a direct call in the
> generated class, and for the calls left to run time the compiler writes a
> small reflection bridge beside it (`Prog$JavaBridge.class`, or an entry
> inside `-o prog.jar`), which the program then needs on its class path (see
> [Compiling against a Java release or a class
> path](#compiling-against-a-java-release-or-a-class-path) for the JRE it needs).
> The WASM backend cannot lower host references, so
> compiling `java:` to `.wasm` remains a `Cannot compile: java:...` error. The
> GraalVM native binary (`rontolisp program.lisp`) can **compile** a `java:`
> program to a `.class`, but cannot **interpret** one: a native image only
> contains the classes and members its build registered for reflection, and
> rontolisp's build registers none for interop, so there even `(java:static
> "java.lang.Math" "max" 3 7)` fails with `No such class`.

## The functions

The package is not part of Common Lisp, so its functions are referenced with the
`java:` qualifier (or unqualified after `(in-package java)`).

| Function | Purpose |
|----------|---------|
| `java:new` | Construct a host object: `(java:new "fqcn" args...)` |
| `java:call` | Invoke an instance method: `(java:call obj "method" args...)` |
| `java:static` | Invoke a static method: `(java:static "fqcn" "method" args...)` |
| `java:field` | Read a static or instance field: `(java:field class-or-obj "name")` |
| `java:proxy` | Adapt a callable to an interface: `(java:proxy "iface" callable)` |

A constructed or returned object prints opaquely as `#<java <class-name>>` and
can be passed back into `java:call`/`java:field`:

```lisp
(java:call (java:new "java.lang.StringBuilder" "ab") "length")   ; => 2
```

```lisp
(java:static "java.lang.Math" "max" 3 7)   ; => 7
```

```lisp
(java:field "java.lang.Integer" "MAX_VALUE")   ; => 2147483647
```

## Value marshalling

Arguments and results are converted between rontolisp and Java automatically:

| rontolisp | Java (in) | Java (out) |
|-----------|-----------|------------|
| integer | `int`/`long`/`short`/`byte`/`float`/`double` (and their boxes) | `int`/`long`/... → integer |
| float | `double`/`float` (and boxes) | `double`/`float` → float |
| string | `String`, or `char` if length 1 | `String` → string |
| character | `char`/`Character` | `Character` → character |
| `t` / `nil` | `boolean` (`nil` also → any `null` reference) | `boolean` → `t`/`nil` |
| a `java` object | the wrapped host object | any other object → a `java` object |
| a function/lambda | a `java:proxy` over the matching interface | — |
| a proper list / a vector | `T[]` (element-wise, incl. primitives), or `List`/`Collection`/`Iterable` | any Java array → a list |

A Java `null` (and a `void` method) comes back as `nil`. A proper list — or a
rank-1 array made with `make-array` — passed where a Java array is expected is
converted element-wise to the component type (including primitive arrays like
`int[]`), and where a `List`/`Collection`/`Iterable` is expected it becomes a
`java.util.List`; nested lists convert recursively. In the other direction a
Java **array** result becomes a Lisp list, while a returned `java.util.List`
stays an opaque `java` object whose methods you call:

```lisp
;; in: the list becomes a Collection
(java:static "java.util.Collections" "max" (list 3 9 4))   ; => 9
```

```lisp
;; in: (1 2 3) -> int[]; out: the int[] result -> a list
(java:static "java.util.Arrays" "copyOf" (list 1 2 3) 2)   ; => (1 2)
```

Symbols, hash tables, dotted (improper) lists and multidimensional (rank-2+)
arrays are **not** bridged.

## Overload resolution

When a class has several constructors or methods of the same name and arity,
`java` picks the overload whose arguments convert at the **lowest total cost** —
an exact match beats a widening conversion, which beats a lossy/boxed one — with
ties broken by a stable signature ordering. So an integer argument prefers an
`int` parameter over `long`/`double`, and the choice never depends on the order
reflection happens to return methods:

```lisp
;; Math.max is overloaded for int/long/float/double; an integer picks int,
;; so the result is an integer, not a float.
(java:static "java.lang.Math" "max" 3 7)   ; => 7
```

When no integer overload exists the integer is converted to the available type:

```lisp
(java:static "java.lang.Math" "sqrt" 16)   ; => 4.0
```

## Resolving calls before they run

The interpreter and a compiled class resolve every call by that one rule. A call whose
receiver class and argument kinds are known from the program text is resolved once, before
it first runs, to exactly one method -- the model is Clojure's type-hinted interop, applied
by the interpreter too. Any other call is resolved when it runs, from the receiver's class
and the arguments' kinds. The method chosen is the same either way, with the one exception
below.

What the program text says about a value:

- a literal's kind: `3`, `2.5`, `"x"`, `#\a`, `t`, `nil`, a `lambda`;
- `(java:new "C" ...)` is exactly a `C`;
- a resolved call's value has the type its method declares: `append` on a `StringBuilder`
  answers a `StringBuilder`, so a chain resolves link by link; a method declared to return
  `Object` says nothing;
- `(the (java:object "C") x)` and `(declare (type (java:object "C") v))` say that the value
  is a `C` (or `nil`). `C` is a binary class name, as for `java:new` (`java.util.Map$Entry`).
  `(java:object "C" :exact)` says it is exactly a `C`, never `nil`, as `java:new` answers;
- a `let` or `let*` variable has its initializer's type, unless it is special or something
  in its scope assigns it (`setq`, `setf`, `incf`, ..., in a closure too);
- `(declaim (type (java:object "C") v))` types the global `v` in the forms after it. A
  `defvar`'s initial value does not: any form may assign the variable.

A declared type is trusted: a value that is not a `C` is an error where it meets the call,
whether it is the receiver or an argument -- never converted for a method it was not chosen
for.

```lisp
(defun total-length (sb)
  (declare (type (java:object "java.lang.StringBuilder") sb))
  (java:call sb "length"))
(total-length (java:new "java.lang.StringBuilder" "abc"))   ; => 3
```

```console
(defun parse (s)
  (declare (type (java:object "java.lang.String") s))
  (java:static "java.lang.Integer" "parseInt" s))
(parse 42)   ; error: java:static: argument 1 is not a java.lang.String, got 42
```

Both calls below are resolved before they run: `sb` is exactly a `StringBuilder`.

```lisp
(let ((sb (java:new "java.lang.StringBuilder" "ab")))
  (java:call sb "reverse")
  (java:call sb "toString"))   ; => "ba"
```

A compiled class makes each resolved call a direct call of its method -- no reflection --
and writes the reflection bridge only for the calls left to run time. The interpreter runs a
resolved call the same way: the method chosen, the arguments checked and converted.

An argument resolves a call only when every kind it can have selects the same method. A
`String` answer may be `nil`, which selects `append(boolean)`, so
`(java:call sb "append" (java:call x "toString"))` is resolved when it runs.

### The declared receiver class decides the candidates

A call on a receiver of declared class `C` resolves among `C`'s methods, as in Java -- so
does one on a `let` variable whose initializer is typed `C`. A public overload of the same
name that only the run-time class adds is not a candidate -- the one place where resolving
early chooses differently from resolving at run time:

```lisp
(defun remove-one (c)
  (declare (type (java:object "java.util.Collection") c))
  (java:call c "remove" 1))
(let ((a (java:new "java.util.ArrayList")) (b (java:new "java.util.ArrayList")))
  (dolist (x (list 10 20 1)) (java:call a "add" x) (java:call b "add" x))
  (remove-one a)             ; Collection.remove(Object): removes the element 1
  (java:call b "remove" 1)   ; ArrayList.remove(int): removes the element at index 1
  (list (java:call a "toString") (java:call b "toString")))
; => ("[10, 20]" "[10, 1]")
```

### Parameter tags

A method name, or the class name of `java:new`, may carry the parameter types, which names
the overload directly: `"max(long,long)"`, `"java.lang.StringBuilder(int)"`. `_` matches any
type and leaves that parameter to the cost rule; a type without a package means `java.lang`;
`T[]` or `T...` is an array.

```lisp
(java:static "java.lang.String" "valueOf(int)" #\a)   ; => "97"
```

```lisp
(java:static "java.lang.Math" "max(long,_)" 3 7)   ; => 7
```

```lisp
(java:call (java:new "java.lang.StringBuilder(int)" 64) "capacity")   ; => 64
```

### Reflection warnings

`(setq java:*warn-on-reflection* t)` reports each call in the forms that follow which is
left to run-time resolution, with the reason: the interpreter when it loads a form (with
the form's line), the compiler at compile time (with the call's position).
`--warn-java-reflection` turns it on from the start:

```console
$ rontolisp --warn-java-reflection len.lisp -o Len.class
len.lisp:1:16: warning: java:call "length" is resolved by reflection at run time: the receiver's class is not known
```

### Compiling against a Java release or a class path

The interpreter resolves against the classes it runs with. The JVM compiler reads class
files instead: the JDK's `lib/ct.sym` -- the running JDK's, else `JAVA_HOME`'s, else that
of the `java` on `PATH` -- for the newest release it holds or for `--java-release N`,
followed by the directories and jars of `--java-classpath`. A compiled class calls the
methods chosen at compile time, so it is stamped for that release (class version 44 + N, at
least Java 17's 61): a JRE older than the release refuses to load it. A call that names a
class the compile cannot see is resolved when it runs, and the reflection bridge such a call
uses needs a JRE at least as new as the one rontolisp was built with.

```console
$ rontolisp app.lisp -o app.jar --java-release 21 --java-classpath lib/guava.jar
```

### Compiling without reflection

`--java-static` makes every call that needs reflection a compile error: one left to run
time, a `java:proxy`, and a function passed where an interface is expected (it becomes a
`java.lang.reflect.Proxy`). The compile lists them all at once. What compiles has no
reflection in it, so GraalVM `native-image` builds the jar into an executable with no
reachability metadata -- no `reflect-config.json`, no agent run:

```console
$ rontolisp app.lisp --java-static -o app.jar
$ native-image --no-fallback -jar app.jar -o app
$ ./app
```

```console
$ rontolisp len.lisp --java-static -o len.jar
error: --java-static: 1 java: call cannot be compiled without reflection:
  len.lisp:1:16: java:call "length": it is resolved by reflection at run time: the receiver's class is not known
```

A `(declare (type (java:object "C") v))` or a `(the (java:object "C") x)` is what makes such a
call resolve.

## Varargs

A varargs method (e.g. `String.format(String, Object...)`) accepts any number
of trailing arguments; they are packed into the varargs array automatically. A
fixed-arity overload is preferred when both match, and a list/vector passed in
the varargs position can also supply the whole array itself:

```lisp
;; 1 and "x" are packed into the Object... array
(java:static "java.lang.String" "format" "%s-%s" 1 "x")   ; => "1-x"
```

```lisp
;; the list is the CharSequence[] varargs array itself
(java:static "java.lang.String" "join" "-" (list "a" "b" "c"))   ; => "a-b-c"
```

## Callbacks via java:proxy

`java:proxy` makes a host interface instance backed by a rontolisp callable. The
callable is applied as `(callable "method-name" arg...)` for every interface
method, so a single lambda can implement the whole interface and dispatch on the
method name. Its return value is marshalled back to the method's return type
(`void` methods ignore it):

```lisp
;; A java.util.function.Supplier whose get() returns a rontolisp value.
(java:call (java:proxy "java.util.function.Supplier" (lambda (method) 42)) "get")
; => 42
```

A callable passed directly where an interface is expected is wrapped in a proxy
automatically, which is what lets a Swing `ActionListener` be a plain lambda:

```console
(java:call button "addActionListener"
  (lambda (method event) (handle-click)))
```

## A Swing example

`examples/jvm/java-interop.lisp` builds a small window directly through the package
(interpret it -- or compile it to a `.class` -- on a machine with a display):

```console
(defvar *frame* (java:new "javax.swing.JFrame" "java interop"))
(defvar *label* (java:new "javax.swing.JLabel" "click count: 0"))
(defvar *button* (java:new "javax.swing.JButton" "Increment"))
(defvar *panel* (java:new "javax.swing.JPanel" (java:new "java.awt.BorderLayout" 12 12)))
(defvar *count* 0)

(java:call *button* "addActionListener"
  (java:proxy "java.awt.event.ActionListener"
    (lambda (method event)
      (setq *count* (+ *count* 1))
      (java:call *label* "setText"
        (concatenate 'string "click count: " (princ-to-string *count*))))))

(java:call *panel* "add" *label* (java:field "java.awt.BorderLayout" "CENTER"))
(java:call *panel* "add" *button* (java:field "java.awt.BorderLayout" "SOUTH"))

(java:call *frame* "setContentPane" *panel*)
(java:call *frame* "setDefaultCloseOperation"
  (java:field "javax.swing.WindowConstants" "DISPOSE_ON_CLOSE"))
(java:call *frame* "setSize" 360 180)
(java:call *frame* "setVisible" t)
```

`examples/jvm/swing.lisp` builds a reusable grid-window helper on top of these five
functions -- wrapped in a `swing` [package](../reference/packages.md) of its own,
spliced in with `(require :swing "swing.lisp")` -- and `examples/jvm/life-gui.lisp`
animates Conway's Game of Life with it (`swing:grid-window`, `swing:paint`, ...).

## Native image

A compiled `java:` program builds into a GraalVM native image. One whose calls
all resolve before they run needs nothing more: compile it with `--java-static`
([Compiling without reflection](#compiling-without-reflection)) and build the
jar as it is. The calls left to run time are reflective and need reachability
metadata, which the tracing agent records from a run:

```bash
rontolisp prog.lisp -o prog.jar
java -agentlib:native-image-agent=config-output-dir=config -jar prog.jar
native-image -jar prog.jar -H:ConfigurationFileDirectories=config
```

The metadata covers only the calls the traced run made. A call that selects an
overload the run never selected fails in the image with
`MissingReflectionRegistrationError` -- for example `(java:static
"java.lang.Math" "max" a b)` with `a` and `b` of unknown kinds, passed floats
after a run that only passed integers. Trace runs that exercise every call
shape the program uses, or declare the types so the calls resolve.

## Limitations

- **JVM only**: the interpreter (`java -jar rontolisp.jar`) and JVM-compiled
  classes (`java Prog`). Not on the WASM backend, and not when interpreting in
  the GraalVM native binary, whose image carries no reflection metadata for the
  interop classes (the native binary can still *compile* a `java:` program to a
  `.class`).
- In a compiled class the five functions work in call position only: they have
  no first-class value, so `#'java:call` or `(funcall 'java:new ...)` is a
  compile error (wrap them in your own `defun` instead), and the embedded
  `eval` runtime does not know them either. A compiled program that uses
  `java:` needs a JRE of the release its calls were resolved against, and one
  that leaves a call to run time a JRE at least as new as the one rontolisp was
  built with.
- Symbols, hash tables, dotted (improper) lists and multidimensional (rank-2+)
  arrays are not marshalled — pass them as Java collections you build with
  `java:new`/`java:call` instead.
- A returned `java.util.List` (unlike a Java array) stays an opaque `java`
  object: it keeps its identity and mutability, so read it with
  `java:call` (`"get"`, `"size"`, ...) rather than list functions.
- Overload resolution is by argument cost, not the full Java type-inference
  rules; an ambiguous call resolves to the lowest-cost (then
  lowest-signature) candidate rather than signalling an ambiguity error. A
  parameter tag names an overload explicitly.
- It is a full host-reflection bridge, so it can run arbitrary Java code: treat a
  program that uses `java:` with the same trust as any other JVM program.
