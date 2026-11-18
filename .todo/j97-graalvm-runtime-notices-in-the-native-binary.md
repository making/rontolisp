# j97. GraalVM runtime notices in rontolisp's own native binary

Difficulty: Low

j93 put `LICENSE`, `NOTICE` and `THIRD-PARTY-NOTICES.txt` into the jar and the native image,
but the GraalVM runtime (Substrate VM, the JDK classes it links in, their third-party
components) that `native-image` builds into rontolisp's own binary carries notices of its own
(`.kb/java-frontend-provenance.md`, "Not covered").

- Find what the GraalVM distribution the release build uses says must travel with a binary it
  produced (its `LICENSE`, `THIRD_PARTY_LICENSE`, the GPLv2+CPE terms of the linked JDK
  classes), and decide what the release must ship beside or inside the binary.
- Ship it the way j93 shipped the rest (the release step in CI, `rontolisp notices --tool`),
  extend `ThirdPartyNoticesTest` or the CI release check rather than adding a test, and move
  the "Not covered" line of the kb to what was done.
