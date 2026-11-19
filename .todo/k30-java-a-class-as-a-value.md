# k30. Java front end: a `Class` as a value

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first.**

What stops the first example of YAVI's README (`Yavi.schema()._string("title")..fromMap`),
measured 2026-11-19: `ArgumentDefinition.value(name, String.class, ..)` in
`builder/YaviSchema` is `a class literal but compared with a class or before getName()`
(`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`,
`schema/`). Taken out of `.todo/k26`, which held it as a lead.

- State: no object stands for a class (`.kb/java-frontend.md`, "The decisions",
  `Object`'s methods): `getClass()` and `Name.class` are read where compared, before
  `getName()` / `getSimpleName()`, as a lock, before `::isInstance` / `::cast`.
- What YAVI asks of one (`core/UntypedInput`, `StringCoercingInputReader`,
  `InputReader.value(node, Class<?>)`): a key of a `Map<Class<?>, ..>` (`equals`,
  `hashCode`), `isInstance`, `cast`, `getSuperclass()` in a loop to `null`,
  `getName()` / `getSimpleName()`, `isEnum()`, `getEnumConstants()`, `isArray()`.
  1,857 class literals in the 380 files.
- Design to settle by measuring: a value per class made where a literal or `getClass()` is
  a VALUE (a structure holding the binary name, the predicate and the class above), so that
  a program comparing two classes pays nothing more than now; the classes of the boxes and
  of `String`, which are Lisp values; what `getClass()` of a lambda answers.
- Behind it the schema example is unmeasured: run the artefact's `schema/shelf/Book.java`
  again and file what it meets (`.todo/k33`).
