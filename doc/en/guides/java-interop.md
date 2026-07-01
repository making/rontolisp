# Java Interop

The `java` package lets rontolisp drive arbitrary Java APIs by reflection —
construct objects, call instance and static methods, read fields, and turn a
rontolisp lambda into a Java interface instance. It is how the Swing demos in
`examples/` (`java-interop.lisp`, `swing.lisp`, `life-gui.lisp`) put a window on
the screen without any bespoke Java glue.

> **JVM interpreter only.** Interop values are opaque host-object references,
> which the JVM-class and WASM compiler backends cannot lower, so compiling a
> form that uses `java:` is a `Cannot compile: java:...` error. And because it
> loads and calls classes by reflection, it works only under the **JVM-hosted
> interpreter** (`java -jar rontolisp.jar program.lisp`) — **not** in the GraalVM
> native binary (`rontolisp program.lisp`). A native image only contains the
> classes and members its build registered for reflection, and rontolisp's build
> registers none for interop, so there even `(java:static "java.lang.Math" "max"
> 3 7)` fails with `No such class`. Treat `java:` as a feature of the JVM jar.

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

A Java `null` (and a `void` method) comes back as `nil`. Lisp lists, symbols,
arrays and hash tables are **not** bridged.

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
(run it on the interpreter, on a machine with a display):

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

- **JVM interpreter only** (`java -jar rontolisp.jar`): not on the WASM/JVM-class
  compiler backends, and not in the GraalVM native binary, whose image carries no
  reflection metadata for the interop classes.
- Lisp lists, symbols, arrays and hash tables are not marshalled — pass them as
  Java collections you build with `java:new`/`java:call` instead.
- Varargs and array parameters are not supported.
- Overload resolution is by argument cost, not the full Java type-inference
  rules; an ambiguous call resolves to the lowest-cost (then
  lowest-signature) candidate rather than signalling an ambiguity error.
- It is a full host-reflection bridge, so it can run arbitrary Java code: treat a
  program that uses `java:` with the same trust as any other JVM program.
