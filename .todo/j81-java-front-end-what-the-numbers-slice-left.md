# j81. Java front end: what the numbers slice (j72) left

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated.

State after j72 (2026-11-17, `.kb/java-frontend.md`, "Numbers"): `long` is
`%mask-signed-field 64`, `float`/`byte`/`short` and their boxes exist, an array of a
primitive type has the packed element type that holds it (`long[]` and arrays of references
are `T`), an index outside an array is `ArrayIndexOutOfBoundsException`.

- **The store check** (`ArrayStoreException`, stated in `doc/*/java/deviations.md`): an array
  of references records no element type. Options to measure before choosing: a header slot
  every array of references carries (a representation change on four backends), a weak
  identity table filled only in a program that widens an array type (`S[]` to `T[]`,
  `Object`, an interface) and checked only at a store through such a type. The static half
  is free: a program that never widens an array type of references cannot fail a store.
- **Casts and `instanceof` to `short[]`, `char[]`, `long[]` and arrays of references** are
  refused by name: `short[]` and `char[]` share `(unsigned-byte 16)`, `long[]` and every
  array of references are `T`. The same decision as the store check (an element type the
  array carries) answers them.
- **`getClass().getName()` and `toString()` of an array** (`[I@..`, `[Ljava.lang.String;@..`):
  `%java-class-name` refuses an array, `%java-to-string` prints `[@..`.
- **`Float` and `Math` members past the first ones**: `Float.intBitsToFloat`,
  `Float.floatToRawIntBits`, `Math.ulp`, `Math.nextUp`, `Math.fma`, `Math.scalb` over floats.
