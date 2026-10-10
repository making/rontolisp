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
> generated class, a `java:reify` or `java:proxy` a class generated for it, and
> for the calls left to run time the compiler writes a small reflection bridge
> beside it (`Prog$JavaBridge.class`, or an entry
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
| `java:proxy` | Adapt a callable to one or more interfaces: `(java:proxy "iface"... callable)` |
| `java:subclass` | Extend a class with a callable: `(java:subclass "super" '("iface"...) '("method"...) args... callable)` |
| `java:reify` | Implement interfaces one method at a time: `(java:reify "iface" "method" function ...)` |
| `java:handle` | Stand for a Lisp value Java has no value of: `(java:handle value "text")` |
| `java:view` | Stand for a Lisp collection as a read-only Java one: `(java:view value items :list)` |

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

A Lisp value is a `java:call` receiver too, called as the object it becomes for an `Object`
parameter: a string as a `String`, an integer as an `Integer` (a `Long` when it does not fit
one), a float as a `Double`, a bignum as a `BigInteger`, a character as a `Character` (a
supplementary one as the `Integer` of its code point), `t` as `Boolean.TRUE`, the symbol
`|false|` as `Boolean.FALSE`. `nil`, a function, any other symbol, a list, an array and a hash
table are no receiver.

```lisp
(java:call "abc" "codePointAt" 0)   ; => 97
(java:call 42 "toString")           ; => "42"
```

## Value marshalling

Arguments and results are converted between rontolisp and Java automatically:

| rontolisp | Java (in) | Java (out) |
|-----------|-----------|------------|
| integer | `int`/`long`/`short`/`byte`/`float`/`double` (and their boxes), `BigInteger` | `int`/`long`/.../`BigInteger` → integer |
| bignum | `BigInteger` (or a supertype: `Number`, `Object`, ...) | `BigInteger` → integer |
| float | `double`/`float` (and boxes) | `double`/`float` → float |
| string | `String`, or `char` if length 1 | `String` → string |
| character | `char`/`Character` | `Character` → character |
| `t` / `nil` | `boolean` (`nil` also → any `null` reference) | `boolean` → `t`/`nil` (after `:java-false`, `t`/`\|false\|`) |
| the symbol named `false` | `boolean` false, `Boolean.FALSE` for any reference | after `:java-false`, Java's false |
| a `java` object | the wrapped host object | any other object → a `java` object |
| a function/lambda | a `java:proxy` over the matching interface, or after `:functional` an implementation of its abstract methods (an argument only) | — |
| a proper list / a vector (specialized too) | `T[]` (element-wise, incl. primitives), or `List`/`Collection`/`Iterable` | any Java array → a list (after `:octets`, a `byte[]` → an `(unsigned-byte 8)` vector) |
| a hash table | a fresh `java.util.LinkedHashMap` (`Map`, `HashMap`, `Object`, ...) | — |
| a `java:handle` | an object Java sees as its text | the value it stands for |
| a `java:view` | a read-only `List`, `Set` or `Map` of its items (a `List` view: an array of them where nothing takes it whole; a `:bytes` view: the `byte[]` of its octets) | the value it stands for |

A Java `null` (and a `void` method) comes back as `nil`. A proper list — or a
rank-1 array made with `make-array`, a specialized one included (`double-float`,
`single-float`, `bfloat16`, `(unsigned-byte 8|16|32)`) — passed where a Java array is expected is
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

A bignum is passed as a `java.math.BigInteger` where one (or a supertype such as
`Number` or `Object`) is expected, and nowhere narrower: not as a `long`, and not
as a `double` -- convert it with `float` first. A fixnum reaches a `BigInteger`
parameter too, when no primitive overload takes it. In the other direction a
`java.math.BigInteger` result is a Lisp integer, not a `java` object: compute with
it in Lisp rather than through `java:call`.

```lisp
;; in: a bignum -> BigInteger; a fixnum -> BigInteger where no primitive fits
(java:call (java:new "java.math.BigDecimal" (expt 10 20) 3) "toString")   ; => "100000000000000000.000"
```

```lisp
;; in: a specialized vector converts element-wise like any vector
(java:static "java.util.Arrays" "toString"
             (make-array 2 :element-type 'double-float :initial-element 0.5d0))   ; => "[0.5, 0.5]"
```

`nil` is Java's `null` wherever a reference is expected, so `'|false|` -- the symbol
spelled as Java spells false -- is the way to pass `Boolean.FALSE` to an `Object`
parameter, and what a callback answers for a `boolean` or `Boolean` result. A hash
table becomes a fresh `java.util.LinkedHashMap` of its entries in insertion order,
each key and value converted as an `Object` argument is (an `equalp` table's keys as
first stored):

```lisp
(let ((l (java:new "java.util.ArrayList")))
  (java:call l "add" '|false|)
  (java:call l "add" nil)
  (java:call l "toString"))   ; => "[false, null]"
```

```lisp
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "b" h) 2 (gethash "a" h) (list 1 2))
  (java:call (java:new "java.util.TreeMap" h) "toString"))   ; => "{a=[1, 2], b=2}"
```

Any other symbol, ratios, dotted (improper) lists and multidimensional (rank-2+) arrays
are **not** bridged; a [`java:handle`](#handles-javahandle) stands for one.

### Java's false back: `:java-false`

Java's false comes back as `nil`, Common Lisp's only false. A `java:new`, `java:call`,
`java:static` or `java:field` ending in `:java-false` answers it as `|false|` instead -- a
`boolean` result, a `Boolean.FALSE`, an array's elements. The marker goes after the
arguments, before or after `:functional`:

```lisp
(let ((l (java:new "java.util.ArrayList")))
  (java:call l "add" '|false|)
  (list (java:call l "get" 0) (java:call l "get" 0 :java-false)))   ; => (NIL |false|)
```

A `java:proxy`, `java:reify` or `java:subclass` ending in it hands its functions Java's
false as `|false|`, as does a function passed where an interface is expected at a call
ending in it. A function passed where a `java.util.Comparator` is expected, at a call ending
in both markers, answers `compare` as Clojure's `AFunction.compare` reads a function: `t` is
-1, `|false|` is 1 when the function answers true for the two arguments swapped and 0
otherwise, a float or ratio is truncated, an integer is its low 32 bits; `nil` throws the
`NullPointerException` and any other answer the `ClassCastException` that `AFunction.compare`
throws, which the call reports as the method's failure:

```lisp
(let ((l (java:new "java.util.ArrayList")))
  (dolist (x (list 3 1 2)) (java:call l "add" x))
  (java:call l "sort" (lambda (a b) (if (< a b) t '|false|)) :functional :java-false)
  (java:call l "toString"))   ; => "[1, 2, 3]"
```

### Octets back: `:octets`

A Java array comes back as a list, so a `byte[]` is a list of signed bytes. A `java:new`,
`java:call`, `java:static` or `java:field` ending in `:octets` answers a `byte[]` -- the
result, a field's value, an element of an array it answers -- as an `(unsigned-byte 8)` vector
of its octets instead, beside the other markers in any order. A function the call converts,
and a `java:proxy`, `java:reify` or `java:subclass`'s, is still handed a list. On the
interpreter the vector is Java's array itself; a compiled program's is a copy. A
[`:bytes` view](#views-javaview) hands one to Java:

```lisp
(let ((md (java:static "java.security.MessageDigest" "getInstance" "MD5")))
  (subseq (java:call md "digest" (java:view 'v (make-array 0 :element-type '(unsigned-byte 8)) :bytes) :octets)
          0 4))   ; => #(212 29 140 217)
```

### Handles: java:handle

`(java:handle value "text")` makes a Java object that stands for a Lisp value Java has no
value of. Java sees the text as its `toString`, and two handles of one text are `equals`. A
handle hashes as its text and orders by it, or, as `(java:handle value "text" hash
"order")`, hashes as the integer's low 32 bits and orders by the order text: a handle keys a
`HashMap` and sorts in a `TreeSet` as the value would in its own language. Wherever Java
hands a handle back -- a result, an array's element, a callback's argument -- `java:` answers
the value:

```lisp
(let ((m (java:new "java.util.HashMap")))
  (java:call m "put" (java:handle 'apple "apple") 1)
  (list (java:call m "toString")
        (java:call m "get" (java:handle nil "apple"))
        (java:call (java:call m "keySet") "toArray")))   ; => ("{apple=1}" 1 (APPLE))
```

The full form is `(java:handle value text hash order class)`. A nil `hash` makes a handle
equal only to a handle of the very same value, and a nil `text` then spells it as `Object`
does, the class and the hash. An `order` that is a function compares the value with the
object Java compares the handle with, by the sign of its answer, and a nil `order` makes the
handle order nothing. `class` splits equality and order: handles of two classes are never
equal and never compare, a `ClassCastException` naming both. A handle of a real number is a
`java.lang.Number` of it ([java:handle](../reference/functions/java-handle.md)). An object
that stands for a value and implements interfaces too is a `java:reify` given `:value`
([Implementing interfaces with java:reify](#implementing-interfaces-with-javareify)).

### Views: java:view

`(java:view value items shape)` makes a read-only Java collection that stands for a Lisp one:
its elements are the items converted as `Object` arguments are, once; `shape` is `:list`,
`:vector` (a `List` that is also `RandomAccess` and `Comparable`), `:set` or `:map` (of a hash
table or a plist). Java reads it through the `java.util` interfaces -- `equals` and `hashCode`
are theirs, every write an `UnsupportedOperationException` -- its `toString` is a printer's
answer for the value, and Java hands it back as the value:

```lisp
(let* ((items (list 1 "two"))
       (v (java:view items items :list (lambda (x) (format nil "<~{~A~^ ~}>" x))))
       (l (java:new "java.util.ArrayList")))
  (java:call l "add" v)
  (list (java:call l "toString") (eq (java:call l "get" 0) items)))   ; => ("[<1 two>]" T)
```

A view is passed itself wherever its class fits. A `List` view where a Java array is expected
is an array of its items, but only after every way to pass it whole, a varargs array holding
it as one element included: a Java array a call answers is a list here, and its view
converts back to the array a later call expects.

`(java:view value octets :bytes)` makes no collection: wherever a `byte[]` fits (`Object`
included) Java is handed the `byte[]` of an `(unsigned-byte 8)` vector, and what it stores
there the vector holds after the call -- on the interpreter the vector's own storage, which
an object keeping the array writes later too; in a compiled program a copy written back
when the call returns:

```lisp
(let* ((b (make-array 3 :element-type '(unsigned-byte 8)))
       (v (java:view b b :bytes)))
  (java:static "java.lang.System" "arraycopy"
               (java:view 's (make-array 3 :element-type '(unsigned-byte 8) :initial-element 7) :bytes)
               0 v 1 2)
  b)   ; => #(0 7 7)
```

The Clojure front end hands Java every value this way: a vector, list, set or map as a view
printed as Clojure prints it, a byte array as its `byte[]`, a keyword, symbol or ratio as a
handle hashed and ordered as Clojure's `Keyword`, `Symbol` and `Ratio` are, and any other
value as a handle equal only to itself ([java:view](../reference/functions/java-view.md)).

A `java` object is `eq` and `eql` only to itself: the same object answered by two
calls is `eq`, while two objects that are `equals` are not. `equal` and `equalp`
ask the object's `equals`. So an `eq` or `eql` hash table keys a `java` object by
identity (a key mutated after it was stored is still found), and an `equal` or
`equalp` table by `equals` and `hashCode`:

```lisp
(let ((a (java:new "java.io.File" "x"))
      (b (java:new "java.io.File" "x")))
  (list (eq a b) (eql a b) (equal a b) (eq a a)))   ; => (NIL NIL T T)
```

With a Lisp value on the right, `equal` hands the object's `equals` the value as
a Java method's `Object` parameter receives it (a string a `String`, a character
a `Character`, `nil` `null`); a symbol, list, vector or ratio is equal to no
`java` object. A Lisp value on the left is never `equal` to a `java` object, as in
Clojure, whose `=` asks its left operand.

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

A function costs less for a functional interface (one abstract method) than for another
interface, so `TreeSet(Comparator)` wins over `TreeSet(Collection)`, as a Java lambda's
target does:

```lisp
(let ((s (java:new "java.util.TreeSet" (lambda (a b) (- b a)) :functional)))
  (java:call s "add" 1)
  (java:call s "add" 2)
  (java:call s "toString"))   ; => "[2, 1]"
```

## Resolving calls before they run

The interpreter and a compiled class resolve every call by that one rule. A call whose
class is known from the program text -- the class `java:new` or `java:static` names, the type
of a `java:call` receiver -- is resolved once, before it first runs, among that class's
methods: to exactly one method when the argument kinds are known too, otherwise to the
overloads the arguments can select, among which the call chooses by the kinds its arguments
have each time it runs. The model is Clojure's type-hinted interop, applied by the
interpreter too. A call whose receiver class is not known is resolved when it runs, from the
receiver's class and the arguments' kinds. The method chosen is the same either way, with
the one exception below.

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
- `(java:reify "I" ...)` and `(java:proxy "I" ...)` with a literal interface make an object
  of a class that implements `I` and nothing else a program can name: a call on it resolves
  among `I`'s methods, and one that passes it resolves as its argument. A `let` variable
  keeps that type, which `(java:object "I" :exact)` spells -- no object's class is exactly
  an interface, so for one `:exact` means this. A `(java:proxy "I" "J" ...)` of several
  literal interfaces implements each: a call passing it resolves, a call on it is resolved
  by its class when it runs, and no specifier spells its type.

A call on a value whose known class is an interface also resolves to `Object`'s public
methods the interface does not declare (`toString`, `getClass`, ...), as Java's own call
`list.toString()` does.

A call on a value known to be a string, float, character, bignum or `t` resolves among the
methods of the class it is called as (`String`, `Double`, ...). An integer's box depends on
its size, so a call on one is resolved when it runs.

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

A compiled class makes a call resolved to one method a direct call of it, and a call
resolved to overloads a comparison of its arguments' kinds followed by a direct call of the
overload they select -- no reflection either way -- and writes the reflection bridge only
for the calls left to run time. The interpreter runs a resolved call the same way: the
method chosen, the arguments checked and converted.

```lisp
(defun bigger (a b) (java:static "java.lang.Math" "max" a b))
(list (bigger 3 7) (bigger 2.5 1) (bigger #\a 1))   ; => (7 2.5 97)
```

`a` and `b` may be anything, so each call of `bigger` chooses among `max(int,int)`,
`max(long,long)`, `max(float,float)` and `max(double,double)` by the cost rule.

An argument decides the method before the call runs only when every kind it can have
selects the same one. A `String` answer may be `nil`, which selects `append(boolean)`, so
`(java:call sb "append" (java:call sb "toString"))` chooses between `append(String)` and
`append(boolean)` when it runs.

### The declared receiver class decides the candidates

A call on a receiver of declared class `C` resolves among `C`'s methods, as in Java -- so
does one on a `let` variable whose initializer is typed `C`. A public overload of the same
name that only the run-time class adds is not a candidate, whether the argument kinds are
known before the call runs or only when it does -- the one place where resolving early
chooses differently from resolving at run time:

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

The interpreter resolves against the JDK it runs on and the program's class path
([Java libraries](#java-libraries)). The JVM compiler reads class files instead: the
JDK's `lib/ct.sym` -- the running JDK's, else `JAVA_HOME`'s, else that of the `java` on
`PATH` -- for the newest release it holds or for `--java-release N`, followed by the
class path. A compiled class calls the
methods chosen at compile time, so it is stamped for that release (class version 44 + N, at
least Java 17's 61): a JRE older than the release refuses to load it. A call that names a
class the compile cannot see is resolved when it runs, and the reflection bridge such a call
uses needs a JRE at least as new as the one rontolisp was built with.

```console
$ rontolisp app.lisp -o app.jar --java-release 21 --java-classpath lib/guava.jar
```

### Compiling without reflection

`--java-static` makes every call that needs reflection a compile error: one left to run
time, and a `java:reify` or `java:proxy` whose interface is named at run time or not found
when compiling (it becomes a `java.lang.reflect.Proxy`). The compile lists them all at once.
A `java:reify`, a `java:proxy` of a literal interface and a function passed where an
interface is expected -- including an argument whose kind is known only when the call runs,
where an overload expects an interface, as `String.join(CharSequence, Iterable)` does -- are
classes generated at compile time and need no reflection. What compiles has no reflection in
it, so GraalVM `native-image` builds the jar into an executable with no
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

## Implementing interfaces with java:reify

`java:reify` implements a host interface one method at a time: each method name is
followed by the function that implements it, called with the method's arguments. The
object it makes can be passed wherever the interface is expected, and a Java caller calls
it like any other implementation:

```lisp
(let ((support (java:new "java.beans.PropertyChangeSupport" "bean"))
      (seen nil))
  (let ((listener (java:reify "java.beans.PropertyChangeListener" "propertyChange"
                    (lambda (e) (push (java:call e "getNewValue") seen)))))
    (java:call support "addPropertyChangeListener" listener)
    (java:call support "firePropertyChange" "size" 1 2)
    (java:call support "removePropertyChangeListener" listener)
    (java:call support "firePropertyChange" "size" 2 3))
  seen)
; => (2)
```

The methods are chosen before the form runs, by the same rule in the interpreter and a
compiled program:

- A name designates one method. One several methods share is tagged with the parameter
  types, as a `java:call` name is (`"append(char)"`); a name that matches more than one
  method, or none, is an error.
- An abstract method no name designates throws `UnsupportedOperationException` when it is
  called; a default method keeps the interface's body; `toString`, `equals` and `hashCode`
  may be named, and are otherwise `#<java-reify I>` and identity.
- A function's value is converted to the method's return type as an argument is, except
  that a function is not made a proxy on the way back: return a `java:reify` or
  `java:proxy` object where an interface is expected.
- A quoted list of interface names, `(java:reify '("I" "J") ...)`, makes one object
  implementing each, a name designating a method of any of them.
- `:value v` after the interfaces makes the object stand for `v`, as a
  [handle](#handles-javahandle) does: Java hands it back as `v`, and unless named, its
  `equals`, `hashCode` and `toString` are those of a handle with a nil hash, of the class
  `:class` names.

A compiled program implements each `java:reify` whose names are literal strings with a
class generated for it (`Prog$Reify0.class`), so it needs no reflection: see [Compiling
without reflection](#compiling-without-reflection). The [reference
page](../reference/functions/java-reify.md) has more examples.

## Callbacks via java:proxy

`java:proxy` makes a host interface instance backed by a rontolisp callable. The
callable is applied as `(callable "method-name" arg...)` for every interface
method, so a single lambda can implement the whole interface and dispatch on the
method name. Its return value is marshalled back to the method's return type
(`void` methods ignore it, and a function it returns is not made a proxy):

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

A `java:new`, `java:call` or `java:static` ending in `:functional`, after its arguments
(a `java:subclass` after its callable, for its constructor arguments), converts a function
the way Java converts a lambda instead: each abstract method of the
interface calls it with the method's arguments alone, and default methods keep their
bodies. The Clojure front end ends its calls in it, so a Clojure `fn` takes no method name:

```lisp
(let ((lst (java:new "java.util.ArrayList")))
  (dolist (x (list 3 1 2)) (java:call lst "add" x))
  (java:static "java.util.Collections" "sort" lst (lambda (a b) (- b a)) :functional)
  (java:call lst "toString"))
; => "[3, 2, 1]"
```

## Class proxies via java:subclass

`java:subclass` makes a host class instance backed by a rontolisp callable --
what `java:proxy` cannot do, since a `java.lang.reflect.Proxy` implements
interfaces only. The form names the superclass, the extra interfaces, the
overridden methods and the constructor arguments:

```lisp
(java:subclass "java.io.File" '() '("lastModified") "recent"
  (lambda (this method &rest args) 42))
```

The callable is applied as `(callable this "method-name" arg...)` for every
named method: `this` first, then the name. The constructor arguments choose the
superclass constructor by the shared overload rule. A named method runs its body
(`toString`/`equals`/`hashCode` included); a method left out is inherited when
the class implements it, and throws `UnsupportedOperationException` with the
method's name when it is called and nothing implements it. A `proxy-super`
(written in Clojure) reaches the superclass implementation through the generated
`super$` accessor, called as an ordinary method:

```lisp
(java:call (java:subclass "java.io.File" '() '("toString") "x"
             (lambda (this method &rest args) "over!"))
           "super$toString$0")
; => "x"
```

A `java:subclass` whose names are literal strings is a class generated at
compile time, so the construction compiles under `--java-static`. One left to
run time -- a name computed at run time, or a class the compile cannot see (a
project class needs `--java-classpath`) -- is refused by name; the interpreter
resolves it when it runs. The [reference
page](../reference/functions/java-subclass.md) has more examples.

## Errors and non-local exits

An exception a Java member throws is signalled as a `java:java-exception`, a `simple-error`
that reports the member and the exception and carries the exception itself, which
`java:java-exception-cause` answers:

```lisp
(handler-case (java:static "java.lang.Integer" "parseInt" "x")
  (error (e) (format nil "~a" e)))
; => "error calling java.lang.Integer.parseInt: java.lang.NumberFormatException: For input string: \"x\""
```

```lisp
(handler-case (java:static "java.lang.Integer" "parseInt" "x")
  (java:java-exception (e)
    (java:call (java:java-exception-cause e) "getMessage")))
; => "For input string: \"x\""
```

Handed back to Java -- as an argument of a member, or as the receiver of a `java:call`
whose class is not known before it runs -- a `java:java-exception` is the exception it
carries:

```lisp
(handler-case (java:static "java.lang.Integer" "parseInt" "x")
  (java:java-exception (e)
    (java:call (java:call (java:new "java.lang.RuntimeException" "wrapped" e) "getCause")
               "getMessage")))
; => "For input string: \"x\""
```

A condition a rontolisp function signals while Java calls it back, and a `return-from`,
`throw` or `go` out of that function, propagates through the Java frames in between as it
does through Lisp frames, to the code that made the Java call:

```lisp
(block found
  (java:call (java:static "java.util.List" "of" 1 2 3) "forEach"
             (lambda (method x) (when (= x 2) (return-from found x))))
  nil)
; => 2
```

```lisp
(handler-case
    (java:call (java:static "java.util.List" "of" 1) "forEach"
               (lambda (method x) (error "bad element ~a" x)))
  (error (e) (format nil "~a" e)))
; => "bad element 1"
```

To the Java code in between it is an ordinary exception, and only what that code lets
propagate arrives: one it catches and ignores never does, and one it wraps, or rethrows on
another thread, arrives as the failure of the Java call (`FutureTask.get` wraps it in an
`ExecutionException`).

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

## Java libraries

A program's Java class path holds the libraries its `java:` calls reach beyond the
JDK. `--java-classpath` names directories and jars, separated as for `java -cp`;
`--java-dep` names a library by its Maven coordinates (`groupId:artifactId:version`,
repeatable), together with what it depends on. The interpreter loads classes from the
class path, a JVM compile resolves calls against it, and a Clojure program's host forms
see its classes as they see the JDK's.

```console
$ rontolisp app.lisp --java-dep com.google.guava:guava:33.4.0-jre
$ rontolisp app.lisp -o app.jar --java-dep com.google.guava:guava:33.4.0-jre
$ java -jar app.jar
```

`--java-dep` resolves as Maven resolves a project's dependencies: where two versions of
a library meet, the one nearest the requested coordinates wins, and the jars join the
class path after the `--java-classpath` entries, in Maven's class path order. They come
from Maven Central through the local repository `mvn` uses -- `~/.m2/repository`, or
the `localRepository` of `settings.xml`. A SNAPSHOT, `LATEST`, `RELEASE` or version range,
given or in a dependency's POM, resolves through Central's `maven-metadata.xml` as Maven
resolves it. The local repository keeps that metadata and asks Central again once a day,
as it does for a file Central did not have. Central serves no SNAPSHOT, as in Maven: a
SNAPSHOT is looked up only in the repositories named with `--java-repository`.

A library Central does not hold -- Clojars, a company repository, a `file:` directory --
is named with `--java-repository [ID=]URL` (repeatable; `https:`, `http:` or `file:`).
Those repositories are searched after Central, in the order given. The `ID` (default
`java-repository-N`) is what `settings.xml` matches: its `<server>` supplies the
credentials and a `<mirror>` whose `mirrorOf` names the id replaces the URL. An id of
`central` replaces Central's URL instead of adding a repository.

```console
$ rontolisp app.lisp --java-dep clj-http:clj-http:3.12.3 \
    --java-repository clojars=https://repo.clojars.org/
```

`settings.xml` applies as it does for `mvn`: `~/.m2/settings.xml`, merged over
`$MAVEN_HOME/conf/settings.xml` when `MAVEN_HOME` is set. Its `offline` is honored, a
mirror covering Central is contacted in Central's place (a `blocked` one fails), a proxy
carries the requests, and the `<server>` of the repository contacted supplies Basic
credentials, `httpHeaders` and timeouts. A password encrypted with
`mvn --encrypt-password` is decrypted with the master password in
`~/.m2/settings-security.xml`.

The `<repositories>` of the active `settings.xml` profiles (named in `<activeProfiles>`,
or holding to their `<activation>`; the global and the user's file together) are searched
as `mvn` searches them: ahead of Central and the `--java-repository` ones, the profile
defined last first, a profile's own repositories in order. A profile repository with the
id of one of those replaces it.

A Clojure program's `deps.edn` dependencies that hold classes join the class path
after these ([Projects: deps.edn](../clojure/semantics.md#projects-depsedn)).

What each output carries:

- A program jar (`-o app.jar`) copies the class path into `app-lib/` beside it and names
  the copies in its manifest's `Class-Path`, so `java -jar app.jar` and `native-image
  -jar app.jar` find them. Ship the jar with its `app-lib/`.
- A war (`-o app.war`) packs the jars into `WEB-INF/lib/` and a directory's files into
  `WEB-INF/classes/`.
- A class (`-o Prog.class`) carries nothing: run it with the class path, as `java -cp
  .:lib/guava.jar Prog`.
- A library jar (`--no-main`) carries no class path either: its pom
  (`--maven-coordinates`, `--emit-pom`) lists the `--java-dep` coordinates as its
  dependencies, for the consumer's Maven to resolve, and a `--java-classpath` entry is
  the consumer's to provide.

The GraalVM native binary cannot load a class at run time, so there the class path
reaches only what a compile resolves calls against and what its output carries.

## Native image

A compiled `java:` program builds into a GraalVM native image. One whose calls
all resolve before they run needs nothing more: compile it with `--java-static`
([Compiling without reflection](#compiling-without-reflection)) and build the
jar as it is -- its `java:reify` and `java:proxy` objects and the functions it
passes where an interface is expected are classes generated at compile time,
so they need nothing either. The calls left to run time are reflective and need reachability
metadata, which the tracing agent records from a run:

```bash
rontolisp prog.lisp -o prog.jar
java -agentlib:native-image-agent=config-output-dir=config -jar prog.jar
native-image -jar prog.jar -H:ConfigurationFileDirectories=config
```

The metadata covers only the calls the traced run made. A call that selects an
overload the run never selected fails in the image with
`MissingReflectionRegistrationError` -- for example `(java:call sb "append" x)`
on an `sb` whose class is not known, passed a float after a run that only
passed integers. Trace runs that exercise every call
shape the program uses, or declare the types so the calls resolve.

## Limitations

- **JVM only**: the interpreter (`java -jar rontolisp.jar`) and JVM-compiled
  classes (`java Prog`). Not on the WASM backend, and not when interpreting in
  the GraalVM native binary, whose image carries no reflection metadata for the
  interop classes (the native binary can still *compile* a `java:` program to a
  `.class`).
- In a compiled class the `java:` functions work in call position only: they have
  no first-class value, so `#'java:call` or `(funcall 'java:new ...)` is a
  compile error (wrap them in your own `defun` instead), and the embedded
  `eval` runtime does not know them either. A compiled program that uses
  `java:` needs a JRE of the release its calls were resolved against, and one
  that leaves a call to run time a JRE at least as new as the one rontolisp was
  built with.
- Symbols other than `|false|`, dotted (improper) lists and multidimensional
  (rank-2+) arrays are not marshalled — pass them as Java collections you build
  with `java:new`/`java:call`, or as a `java:handle` or `java:view`, instead.
- A returned `java.util.List` (unlike a Java array) stays an opaque `java`
  object: it keeps its identity and mutability, so read it with
  `java:call` (`"get"`, `"size"`, ...) rather than list functions.
- Overload resolution is by argument cost, not the full Java type-inference
  rules; an ambiguous call resolves to the lowest-cost (then
  lowest-signature) candidate rather than signalling an ambiguity error. A
  parameter tag names an overload explicitly.
- It is a full host-reflection bridge, so it can run arbitrary Java code: treat a
  program that uses `java:` with the same trust as any other JVM program.
