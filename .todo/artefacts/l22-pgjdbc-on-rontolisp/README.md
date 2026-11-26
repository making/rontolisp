# l22: pgjdbc compiled by rontolisp

What `.kb/java-frontend.md`, "pgjdbc: what a connection meets that no other item makes", was
taken with (2026-11-25). The census itself was l01's (`census.sh`, `StubGen.java`, removed
from the tree on 2026-11-26); nothing of pgjdbc or of a JDK is here.

- `census/`: the refusals of `Ladder` at this item's start (`start-*`), after its first part
  (`made-*`) and once develop was merged into it (`merged-*`: m14's `AccessController` and
  privileged actions had come), with no shells (`*-no-shells.txt`, what the front end refuses)
  and once every class is there (`*-with-shells.txt`); the members of the missing classes the
  lowered calls name, merged.
- `sizes/`: five programs of the classes the first part touched, `Ladder.java` (the program
  `ClPostgresE2eTest` runs), and `sizes.sh`, which
  compiles them, `examples/java/shop` and `Ladder` over `jdbc-postgresql/` to wasm, a
  component and a jar; `before.txt` and `after.txt` are its lines before and after (the
  same: byte-identical).
- `tools/four.sh FILE.java`: the JDK's lines, then the interpreter's, the JVM's, wasm's and
  the component's diffed against them. `tools/case.py SPEC NAME DIR` writes a case of
  `java-spec.yaml` as files (`python3 -I`), its expectation beside it.
