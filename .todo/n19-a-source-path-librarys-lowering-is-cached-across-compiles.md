# A source-path library's lowering is cached across compiles

Difficulty: Medium

A Java program with a source-path library lowers that library again on every compile: the
classic YAVI program lowers 713 forms, 575,271 B of wasm, in 7.9 s, of which parsing all 380
files is 0.76 s and declaring the 171 it names 1.7 s (`.kb/java-frontend.md`, "A library from
the source path", "Declared whole, lowered as reached"). The library's sources do not change
between two compiles of a program that does.

**First measure where the 7.9 s goes** (parse, declare, `JavaLowering`, `CompileFrontend.expand`,
the backend) for the classic and the schema YAVI programs and for `examples/java/shop`. If the
library's own share is small, record the split in `.kb/java-frontend.md` and close the item.

Otherwise, a cache keyed by content, never by time stamp:

- Key: the hash of each source jar or directory's contents, the rontolisp build, and every
  input that changes a lowered form (flags, the target). A library class is lowered "as far
  as the program reaches it", so either the key holds the reached members or the cached unit
  is smaller than a class (one member's lowered form, with what it reaches recorded beside it).
  Decide from the measurement which unit pays.
- A miss or an unreadable entry compiles as today; a hit gives the same bytes as a miss
  (pin it: compile twice, compare outputs byte for byte, on the four backends).
- Where it lives (`~/.cache/rontolisp`, `target/` under the Maven plugin), its size bound,
  and a flag to bypass it.
- `doc/{en,ja}` of the Java source path; `.kb/java-frontend.md` with the before/after numbers.
