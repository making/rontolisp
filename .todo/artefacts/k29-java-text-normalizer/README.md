# k29: `java.text.Normalizer`, measured 2026-11-19

What `.kb/unicode-normalization.md`, "Cost", was measured with (JDK 25, wasmtime 49).

```bash
R="java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
java Sizes.java                                   # the tables' text under four encodings
$R Bench.java -o bench.wasm && wasmtime run bench.wasm   # ns a call; `java Bench.java` is the JDK's
$R building.lisp -o b.wasm && wasmtime run b.wasm # three ways to build the result
$R scan.lisp -o s.wasm && wasmtime run s.wasm     # four ways to scan a text
# YAVI checking decomposed text, against `java -cp yavi-0.18.0.jar shelf/Note.java`
$R --java-source-dep am.ik.yavi:yavi:0.18.0 shelf/Note.java -o note.wasm && wasmtime run note.wasm
```

The comparison with the JDK over 208,930 texts is `UnicodeNormalizationTablesTest`'s with
its 30,000 seeded texts made 200,000.
