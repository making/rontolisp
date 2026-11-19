# k34. examples/java: a program that validates with YAVI

Difficulty: Medium

The user asked on 2026-11-19 for an example that uses YAVI, in addition to the k33
milestone.

Since k29, YAVI's classic example compiles from the unpatched coordinates and prints the
JDK's lines on the interpreter, the JVM, wasm and the component:

```
rontolisp --java-source-dep am.ik.yavi:yavi:0.18.0 shelf/Book.java -o book.wasm
```

The program, the oracle's lines and the command lines are in
`.todo/artefacts/k24-java-front-end-a-program-that-uses-an-existing-library/` and
`.todo/artefacts/k29-java-text-normalizer/`.

This item takes the "next" of `.todo/k33`'s step 3. k33 keeps the schema example and the
order.

## To do

1. Write the example: a directory `yavi` under `examples/java/`.
   - It is a small program of our own: a record or class validated with
     `ValidatorBuilder`, with one valid value and a few invalid ones. Their violations
     print with YAVI's messages. Include a decomposed string if it reads naturally.
   - A JDK 25 should run it as it stands, with YAVI on the class path:
     `java -cp yavi-0.18.0.jar ...`. Its output is the oracle.
   - Do not copy YAVI's README or tests. Write the example here; YAVI is Apache-2.0 and
     shares an owner with rontolisp (`.kb/java-frontend-provenance.md`).
2. Add a section to `examples/java/README.md`.
   - Give the four command lines with `--java-source-dep am.ik.yavi:yavi:0.18.0`, the
     resulting wasm size, and what decides whether a library works ("only what the front
     end supports").
   - Link the source-path section of `doc/en/java/` and `doc/ja/java/`.
   - If those docs have an example section, mirror a short mention of this example there
     too.
3. Check the example in `examples.yaml`.
   - The file has no field for `--java-source-dep` yet, so add one. The field passes the
     option to every leg the example runs on.
   - The legs need YAVI's sources jar from the network or `~/.m2`. Decide how
     `ExamplesE2eTest` treats that. Either it reads `~/.m2` the way `--java-source-dep`
     does and skips with the reason when the jar is absent, or the case is opt-in. A
     default `./mvnw test` must not fail offline.
   - Write down the choice and the measured compile time per leg (wasm took 7.9 s in k24).
     The run must fit the time `ExamplesE2eTest` allows.
4. The README's schema example (`Yavi.schema()`) joins this example, or a second one,
   when it compiles from the unpatched coordinates: since k30 (2026-11-20) it prints the
   JDK's lines on the four backends with five edits around classes the library lacks,
   which are `.todo/k33`'s step 5.

## Done when

- The example prints the same output on a JDK and on the four backends.
- `examples.yaml` checks it, or skips it with a stated reason.
- The README section and the docs mention are in.
