# j78. Java front end: what the type parameters, inference, lambdas and boxes slice (j70) left

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".

After j70 (`.kb/java-frontend.md`, "Type parameters", "Inference", "Lambdas", "Boxes"). Each
line is refused by name, stated in `doc/*/java/deviations.md`, or a program a compiler takes
that this refuses with a compiler's wording.

- **The class around an inner class is no part of the inner class's type.** `Outer<A>.Inner`
  is refused by the parser; a member of an inner class typed by the outer class's parameter
  is refused when read from outside that class (`inScope`), and INSIDE it through another
  parameterization (`Stack<String> other; other.head.value`) it is typed by the class's own
  parameter, so a program a compiler takes can get `incompatible types: T cannot be converted
  to String`. Carry the enclosing arguments in `ClassType`.
- **Inference is the specification's only as far as open variables reach.** Refused where a
  compiler infers: a lambda inside a generic call that is itself an argument, when the
  lambda's parameter types come only from the outer call's target; a method reference whose
  parameters nothing but the referenced method's own signature settles (only `Type::name`'s
  first parameter is taken from the type); a `switch` expression with lambda arms as an
  argument. A lub of several interfaces is one class or `Object`, never an intersection.
  Accepted where a compiler refuses: what an open variable is read as is not checked against
  the other uses of the same variable.
- **Errors a compiler owes that are not reported**: a type argument outside its parameter's
  bound; the unchecked warnings; a field written through `? extends T`.
- **Refused by name**: a cast to an intersection type; an anonymous class made with `<>`; a
  method reference through a parameterized type (`List<String>::size`) or with its own type
  arguments; a field read through the second bound of `T extends A & B` (a method is found).
- **To measure, not assume** (four backends, the `bench.sh` method): the cast a compiler puts
  where a value is read through a type argument, against trusting the type (today: trusted,
  a polluted heap passes); `(or box (%java-null-unbox))` dropped where the box was just made;
  the `Long` cons on wasm now that `long` arithmetic is one machine operation (j72: a
  `List<Long>` summed, 28.02 -> 1.35 s on wasm, `.kb/java-frontend.md`, "Boxes").
