# j69. Java front end: the declarations and statements past the first slice

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".

**First target (j68): all of this item** -- these are Java 25's declarations and statements.
Order by what programs use most: `switch`, enums, nested and anonymous classes, records,
then patterns and sealed types.

After j68 (its decisions and the spike: `.todo/artefacts/j68-java-front-end-spike/`).
The first slice refuses these by name; each is a lowering over forms that already exist.

- `switch`: the statement with fall-through, the arrow form, the expression with `yield`,
  over `int`, `char`, `String` and an enum; then patterns (`case Circle c ->`, guards,
  record deconstruction) and exhaustiveness over a sealed hierarchy.
- `enum`: constants as instances made once, `values()`, `ordinal()`, `name()`, fields,
  constructors and methods on constants, `switch` over them.
- `record`: the canonical constructor, accessors, `equals`/`hashCode`/`toString`; compact
  constructors.
- Nested classes: static nested (the spike hoists them, names unqualified), inner classes
  holding their outer instance, local and anonymous classes capturing locals.
- `instanceof` with a binding, and its scope through `&&`, `!` and an early `return`.
- The unnamed `_` (a local, a lambda or catch parameter, a pattern), statements ahead of
  an explicit `super(..)`/`this(..)` (no `this` read before it).
- Labeled `break`/`continue` (a named `block`), `do`/`while` with `continue`, a `for`
  with several declarators, the comma in its update.
- Static and instance initializer blocks, the order of static initialisation (a class is
  initialised at its first use: decide whether the closed program may run them all ahead
  in dependency order, and say what it changes).
- Varargs, array initialisers of several dimensions, `new int[n][m]`, `final` locals and
  the definite-assignment errors a compiler owes.
- `default` and `static` interface methods, `sealed`/`permits`, `abstract` checks,
  access modifiers checked (a `private` member read from another class is an error).
- Text blocks, the remaining literals (octal, binary, `float`, `char` escapes), the
  conditional operator's typing table, the remaining compound operators.

Each lands with its `java-spec.yaml` cases, expectations from the JDK, on four backends.
