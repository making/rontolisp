# k82: what was measured (2026-11-21)

Results are in `.kb/java-frontend.md`, "The copies of `copyOf` and the halves of a surrogate
pair", and `.kb/characters-code-points.md`, "The two halves of a surrogate pair meeting".

- `sz.sh A.jar B.jar File.java...` -- the wasm bytes of each program under two exec jars,
  eight compiles at a time (`SZ_OUT` names the directory the modules go to). The size
  programs are those of the k12, k26, k47, k55, k60, k62 and k71 artefacts and the four of
  `sizes/` here. `four.sh` and `fn.sh` of `.todo/artefacts/k60-java-front-end-what-k55-left/`
  ran each probe on the four backends and named the functions whose bytes moved;
  `extract.py` and `update.py` of `.todo/artefacts/k71-java-front-end-what-k60-left/` put a
  corpus case's JDK lines back.
- `probes/` -- each asked of JDK 25 (`*.jdk.txt`) and run on the four backends.
  - `CopyOf2.java` (52 lines): which of 23 lists, 12 sets and 11 maps each `copyOf` answers
    as it is, and the identity of the empty ones. All a JDK's lines on the four backends;
    the jar of 2077268c9 answered `false` for every copy. The empties as one object each
    were made, measured (+277 B in a `List.of` program, +812 B in `SzAsList`) and taken out.
  - `Emoji.java`, `Chars.java`, `Seams.java`: text made of `toCharArray`'s and `chars()`'s
    units by appending, `+=`, a stream, chains of concatenation with halves at the seams.
    Before, the interpreter, the JVM and wasm disagreed (wasm printed the six bytes of two
    surrogates for an emoji); now the four answer alike, a JDK's lines but for the counts
    of code points `deviations.md` states and, in `Seams.java`, a surrogate printed alone.
  - `Lone.java`, `PrintHalves.java`: a surrogate with no partner in a constant, in
    `getBytes`, printed alone, and halves printed one after the other. A JDK writes `?` and
    joins the halves in its encoder; the interpreter and the JVM do (the host's stream);
    wasm writes three bytes each. Constants keep the surrogate on wasm now (they were `?`).
  - `SbOrder.java`: a `StringBuilder` as a `Comparable` -- made, a JDK's line on the four
    backends, and taken out for its bytes (`SzTreeSet` +385 B).
  - `Bench2.java`: `PriorityQueue` with the draws apart from the timing. The queue is 0.7 us
    an add and a poll on wasm and on the JVM (k60 measured 2.5 us on wasm), a JDK 0.16 us.
  - `Div.java` (253 lines): `Duration.dividedBy` of a long and of a `Duration` under a JDK
    alone -- the words of each refusal and the rounding toward zero, for the member that is
    still refused.
- `sizes/` -- `SzCopyOf` (a record copying its list), `SzListOf`, `SzSetOf`, `SzMapOf`.
