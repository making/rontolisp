# j70. Java front end: generics, inference, functional interfaces, boxing

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".

**First target (j68): all of this item.** Java 25 cannot be read without it, and the
library (j71) is generic code.

After j68. The spike (`.todo/artefacts/j68-java-front-end-spike/`) reads a type argument
only to know an `ArrayList`'s element type, rewrites `Integer` to `int` in the parser, and
types a lambda only against a user interface with one abstract method. The lowering picks
its form from static types, so each of these is owed before the library (j71) can be
written in Java:

- Declared type parameters on classes, interfaces and methods; bounds; erasure for the
  signature in a method's name; bridge-free overriding (the override of `T get()` by
  `String get()` is one dispatch entry).
- Inference: the diamond, a generic method's arguments, `var`, the target type of a
  conditional and of a lambda nested in a call; wildcards as far as reading and writing
  through them needs.
- Lambdas and method references (`Type::m`, `obj::m`, `Type::new`) against any functional
  interface, `java.util.function` included; a class and a lambda under the same interface
  (the spike's dispatcher tests `functionp` first: keep or replace with a measurement).
- Boxing as a conversion the types insert: `Integer` distinct from `int`, `null` in a box,
  `==` on boxes, unboxing a `null` raising. Decide the box: with `int` a Lisp integer the
  box can be the value itself and only `null` needs telling apart.
- Overload resolution by the three phases (no boxing, boxing, varargs) and most-specific,
  replacing the spike's score; the ambiguity errors.
- Reference casts and array stores that check; `instanceof` and casts through an
  interface and a type variable.

The errors a Java compiler reports (an incompatible type, an unresolved name, an
unreachable statement where it matters to a lowering) are reported with a position, in
the JDK's wording where it has one; the corpus holds programs that must be refused.
