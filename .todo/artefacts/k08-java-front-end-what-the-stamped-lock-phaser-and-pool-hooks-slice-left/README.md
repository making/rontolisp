# k08: what was measured (2026-11-19)

Results are in `.kb/java-frontend.md`: "The decisions" (a class of Java source under
`Number`, the abstract method a class leaves out) and "java.util.concurrent" (the field
updaters, `invokeAll` with a limit, `join(Duration)`).

- `run4.sh File.java` -- a program under a JDK 25 and on the four backends
  (`JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar`, `TMPDIR` for its work directory), the
  lines that differ per backend. k01's script.
- `probes/` -- each printed a JDK's lines on four backends at the end of k08, but for
  `Updaters3`. `Num` (the atomics, the adders, the accumulators and three classes of the
  program through `Number`: the members, `instanceof`, casts, patterns, `Object`'s
  methods, type arguments, a method reference, an array), `Num2` (a format, a map's key,
  a sorted list, the type two branches share, a switch, arrays, threads reading atomics
  through `Number`), `Updaters` (every member of the three updaters, an object of a
  class under the updater's, the wrong object and value through a raw type, what
  `newUpdater` refuses, four threads on one object), `Updaters2` (the ORDER of
  `newUpdater`'s refusals: 19 calls, and the names of the updaters' classes),
  `Updaters3` (a JDK alone: what the subset does not take -- a class literal of a
  primitive or an array type, a name that is no constant, a field out of the caller's
  reach -- and a swap of two boxes of 1000, `false` on a JDK and `true` here),
  `InvokeAll` (`invokeAll` at four limits over five pools: what is done, cancelled, ran
  and was interrupted; its refusals; `join(Duration)` with and without an interrupt).
- `sizes/` -- `OLD=<jar of aa58e6a22> NEW=<jar> size.sh *.java`, class bytes / wasm
  bytes: `Plain` 151,684 / 126,407 and `Count` 185,612 / 164,198 before and after;
  `Under` 161,555 / 132,331, `CountNumber` 186,917 / 165,166 and `CountField` 131,502 /
  115,737 are new programs. A class's bytes move with the length of the file's path.
