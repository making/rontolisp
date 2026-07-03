# Java Interop

The `java` package lets rontolisp drive arbitrary Java APIs by reflection —
construct objects, call instance and static methods, read fields, and turn a
rontolisp lambda into a Java interface instance. It is how the Swing demos in
`examples/` (`java-interop.lisp`, `swing.lisp`, `life-gui.lisp`) put a window on
the screen without any bespoke Java glue.

> **JVM only (interpreter and compiled `.class`).** Interop values are opaque
> host-object references resolved by reflection, so the feature needs a real
> JVM: it works under the **JVM-hosted interpreter** (`java -jar rontolisp.jar
> program.lisp`) and in a **JVM-compiled program** (`-o Prog.class`, run with
> `java Prog`) — the compiler embeds a small reflection bridge into the
> generated class, so the output stays a single self-contained `.class` file
> (running one that uses `java:` requires a JRE at least as new as the one
> rontolisp was built with). The WASM backend cannot lower host references, so
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

`examples/java-interop.lisp` builds a small window directly through the package
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

`examples/swing.lisp` builds a reusable grid-window helper on top of these five
functions, and `examples/life-gui.lisp` animates Conway's Game of Life with it.

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
  `java:` needs a JRE at least as new as the one rontolisp was built with.
- Symbols, hash tables, dotted (improper) lists and multidimensional (rank-2+)
  arrays are not marshalled — pass them as Java collections you build with
  `java:new`/`java:call` instead.
- A returned `java.util.List` (unlike a Java array) stays an opaque `java`
  object: it keeps its identity and mutability, so read it with
  `java:call` (`"get"`, `"size"`, ...) rather than list functions.
- Overload resolution is by argument cost, not the full Java type-inference
  rules; an ambiguous call resolves to the lowest-cost (then
  lowest-signature) candidate rather than signalling an ambiguity error.
- It is a full host-reflection bridge, so it can run arbitrary Java code: treat a
  program that uses `java:` with the same trust as any other JVM program.
