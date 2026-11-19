# k31. Java front end: `java.math.BigInteger` and `BigDecimal`

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first.**

YAVI names `BigDecimal` in 46 files and `BigInteger` in 44 (measured 2026-11-19,
`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`): a constraint
type each, and the `_bigDecimal` / `_bigInteger` members of every builder. The README's
two examples do not reach them -- a class a library only names in a declaration is passed
by (k24) -- so this is behind `.todo/k28` .. `k30` for the milestone (`.todo/k33`) and
ahead of them for a program that validates money.

- `BigInteger` over the host's integers, which are unbounded on every backend: an
  intrinsic whose value is the Lisp integer has to be told from an `Integer` and a `Long`
  box (`.kb/java-frontend.md`, "Boxes"), so measure a tagged value against Java source
  holding one.
- `BigDecimal`: an unscaled integer and a scale; `toString` / `toPlainString`, `compareTo`
  against `equals`, the rounding modes, `MathContext`. `.todo/k26` lists both under "not in
  scope" of its first target: this item is where they are taken up.
- The numbers YAVI compares them with (`ExactNumericConstraintBase`) are the members to
  write first.
- `MessageFormat` (k28) writes a `Number` it does not know by its `doubleValue`; a JDK
  writes a `BigInteger` and a `BigDecimal` by their own digits (YAVI's messages print the
  bound of a constraint), so `MessageFormat.number` learns them here, from a probe.
