# j98: the probes

Java programs printing a transcript, run under JDK 25 and under the four backends
(`compare.sh File.java all`). Written here; a JDK's output is what each was compared
with and is not kept. The numbers are in `.kb/java-frontend.md` ("The library",
"java.io", "Exceptions").

| Probe | What it asks | Lines | Differs from the JDK |
|---|---|---|---|
| `Sb.java` | `StringBuilder`'s searches, `compareTo`, `appendCodePoint`, `repeat` | 19 | nothing |
| `Bits.java` | `Integer` / `Long` over the bits, the unsigned members, `decode`, `parseUnsignedLong` | 299 | nothing |
| `Parse.java`, `Pui.java`, `Pui2.java` | the refusals of the parses over 25 texts and 9 radixes; where `parseUnsignedInt` changes its refusal | 1,549 / 54 / 116 | the message of a `NullPointerException`; on wasm a digit outside ASCII |
| `Maths.java` | `Math`'s members over 21 doubles, 13 floats, 9 ints, 10 longs | 1,488 | nothing |
| `Chars.java`, `CharNow.java` | `Character` over 51 code points; the six old tests before this item | 59 / 54 | `getNumericValue` of a number that is no digit; on wasm `digit` outside ASCII |
| `Strs.java` | `String`'s new members, `CASE_INSENSITIVE_ORDER` | 287 | the message of a `NullPointerException` |
| `Utils.java` | `Objects`, `Collections`, `Arrays`, the entries, `Comparator`, the properties, the errors, `%h` | 69 | nothing |
| `Klass.java`, `K.java` | a class compared with a class; the 16 comparisons javac was asked (12 refused) | 6 | line 22 of `K.java` is compared here |
| `Eof.java` | a `null` line in a text | 3 | nothing (before: wasm and the JVM) |
| `Halves.java` | the halves of a pair appended one by one, a digit outside ASCII | 1 | every backend, each its own way |
| `U16.java`, `U16Rand.java` | UTF-16's grid; 3,000 seeded sequences | 186 / 3,000 | nothing |

`size/`: the programs whose wasm size is quoted (`Letters`, `LettersLoop`, `Parse`,
`Hello`, `ListAdd`, `MapPut`, `Format`, `Throws`, `Reader`, `Named`, `Wide`, `Looked`,
`Bytes`).
