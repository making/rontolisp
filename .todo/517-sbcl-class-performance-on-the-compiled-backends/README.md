# Spike sources for `.todo/517`

Every number in `.todo/517` and its children comes from these files. Two
spellings of each benchmark, `-toplevel.lisp` and `-defun.lisp`, because the
difference between them is `.todo/518`/`.todo/519`.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
D=.todo/517-sbcl-class-performance-on-the-compiled-backends

sbcl --script $D/loopsum-toplevel.lisp                       # the oracle
java -jar $JAR $D/loopsum-toplevel.lisp                      # interpreter
java -jar $JAR $D/loopsum-toplevel.lisp -o P.class --class-name P && java -cp . P
java -jar $JAR $D/loopsum-toplevel.lisp -o p.wasm && wasmtime run -W gc p.wasm
```

`dce-nth.lisp` / `dce-random.lisp` are the pair that shows the benchmark note's
premise is a dead-code artifact: SBCL runs both in the same 0.693 s because it
deletes the `nth` call. Run them under SBCL only -- they are the claim, not a
rontolisp measurement.

`-XX:+PrintCompilation` on the `nth-*` classes is how `.todo/520` was found:
look for `COMPILE SKIPPED: stack not empty at OSR entry point`.
