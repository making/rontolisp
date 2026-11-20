# k56. Java on wasm: an `int` operation the types prove carries the generic fallback

Difficulty: High

Left by k51 (2026-11-20). The Java front end knows an `int` or a `long` operand's type, but
lowers `a + b` to the masked generic form (`.kb/java-frontend.md`, "Numbers"): on wasm-GC
that is the fusion fast path AND the whole generic fallback (`.kb/wasm-int-fusion.md`),
which no leaf of a Java program ever takes.

- **Measured** (base `e5ab4044b`, `-Drontolisp.wasm.debug-func-sizes`): a method of ten
  `x = x + b;` is 2,066 B for `int`, 1,996 B for `long` -- some 200 B per operation. The
  reader of the zone table (`ZoneChanges.region`, `find`, `rule`, `written`, `number`:
  some 100 lines of Java) is 17.9 KB of the 50 KB the first region costs a program
  (`.kb/java-frontend.md`, "java.time: the regions"); a reader of copies between the
  table's sets measured 8.6 KB for 40 lines, which is why the table has none.
- **To decide**: how the backend learns what the front end proved -- a typed primitive
  of the core forms (operands in `int` range, wrap at 32 or 64 bits) or a declaration
  the backends read -- and what it is on the JVM backend and the interpreter, which pay
  the mask too. A new primitive goes by `.kb/adding-primitives.md`; behavior stays pinned
  identical on the four backends.
- **Measure before and after**: the zone reader's functions, `examples/java/yavi/Booking.java`
  and `Member.java` (wasm), `Sieve` (`.kb/java-frontend.md`, "Numbers"), and the time of
  the region walk (`.todo/artefacts/k42-java-time-the-rules-of-a-region/`).
- **Then**: measure the copies of the zone table again ("java.time: the regions", "Not
  taken"): 11.5 KB saved for a program holding every region, against the reader's bytes.
