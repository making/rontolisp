# k31. Java front end: `java.math.BigInteger` and `BigDecimal`

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first.**

YAVI names `BigDecimal` in 46 files and `BigInteger` in 44 (measured 2026-11-19,
`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/`): a constraint
type each, and the `_bigDecimal` / `_bigInteger` members of every builder. The classic
example does not reach them -- a class a library only names in a declaration is passed
by (k24). **The schema example does** (measured 2026-11-20 behind k30, edit 4 of the
artefact's `patch.sh`): `core/UntypedInput.convertNumber` and `decimal` convert every
number given for another numeric type through a `BigDecimal` (`valueOf(long)`,
`new BigDecimal(String)` / `(BigInteger)`, `intValueExact` .. `byteValueExact`,
`toBigIntegerExact`, `scale`, `doubleValue`, `floatValue`), and `typeName` compares
`BigInteger.class`. So this is on the milestone's path (`.todo/k33`, step 5), and
those members are the first to write.

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
