# k65. JVM: a declared integer leaf keeps its guard and its fallback

Difficulty: Medium

Left by k56 (2026-11-20). The wasm backend takes a variable or a `the` value a declaration
gives an integer range raw, with no bail (`.kb/declarations-type-checks.md`, "Declared
integer ranges"); the JVM reads no such declaration. Every fused method still opens with an
`instanceof Long` per leaf and carries the generic fallback and an `ArithmeticException`
region -- even where nothing in it is checked any longer, as under a
`%mask-signed-field`, the wrap of every Java `int` and `long` operation
(`.kb/jvm-int-fusion.md`).

- **Measured** (k56's build): `Zone.class` (`ZonedDateTime.of(.., ZoneId.of("Europe/Paris"))`,
  271,945 B) has 198 `_fx$` methods; before k56 205 of them were some 21.5 KB of code.
  HotSpot's times were within noise of the build before (`.kb/java-frontend.md`, "Numbers").
- **To do**: a trusted leaf as a `checkcast Long` (and a range check where the declaration is
  narrower than a `long`), a method with nothing to bail on emitted without its fallback and
  its exception table, and whether a declared `let` local takes the raw `long` representation
  without its shadow. A false declaration stays a catchable error, as the double-float
  declarations' (`.kb/declarations-type-checks.md`, "The false-declaration policy").
- **Measure**: `Zone.class`, `Regions.jar` (`.todo/artefacts/k42-java-time-the-rules-of-a-region/`),
  the times of `Sieve` and of the walk on the JVM.
