# k41. Java front end: the order of eager static initializations differs from run to run

Difficulty: Low

Found 2026-11-20 with k30, there before it: YAVI's classic example compiled twice by ONE
jar gives two `.wasm` of the same size (655,275 B) and different bytes: their
`wasm-tools print` texts differ in the function that runs the static initializations
before `main` -- the same `global.set`s in another order -- and in two lines of the one
after it.

```bash
R="java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
cd .todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/classic
$R --java-source-dep am.ik.yavi:yavi:0.18.0 shelf/Book.java -o /tmp/a.wasm
$R --java-source-dep am.ik.yavi:yavi:0.18.0 shelf/Book.java -o /tmp/b.wasm
cmp /tmp/a.wasm /tmp/b.wasm
```

- Lead: `JavaLowering.initEffects` and `methodEffects` are `HashMap`s keyed by
  `JavaClass` / `Method`, whose hash is the object's identity, and
  `JavaInitOrder.decide` walks the first; classes that do not need each other come out
  in the order of that walk. The order is one the language allows (eager classes are
  those nothing can tell the order of), so no program prints differently.
- Why it matters: every size in `.kb/java-frontend.md` is compared "byte for byte"
  against a base build, which this defeats for a program with enough classes, and a
  build that is not reproducible cannot be verified.
- The test: lowering one source twice in one JVM gives the same forms (two lowerings
  make two sets of `JavaClass` objects, so the walk differs there too); a dozen classes
  with a static field each make a chance pass unlikely.
