# The JVM's arms of the bulk primitives: the tools and the measurements (2026-11-16)

The numbers of `.kb/starlark-frontend.md`, "The JVM's arms", and of
`.kb/bulk-primitives.md`, "JVM". The eleven programs and `time.sh` are
`.todo/artefacts/j54-starlark-faster-than-cpython/`.

## The profile

JFR's `jdk.ExecutionSample` every 1 ms over a compiled program. The generated class
has no line numbers and its method names hold `-` and `$`, which `jfr summary` and a
`\w+` pattern both lose:

```bash
java -jar $JAR sorting.star -o Prog.class --class-name Prog
java -Xss64m -XX:StartFlightRecording=filename=p.jfr,settings=profile,jdk.ExecutionSample#period=1ms Prog
python3 jfrtop.py p.jfr                    # self and inclusive samples per method
python3 jfrcallers.py p.jfr _cpidx 6       # the frames around the first frame that matches
```

A sample is the main thread in Java code: the compiler's threads, a collection and
time in the VM are not in it (`/usr/bin/time` gives the process's CPU beside its wall
time; `-Xlog:gc` the pauses).

## The programs

- `fuzz.star` -- 424 lines: `sorted` both ways and by a key (with the places of equal
  keys), `join`, `split`, `count`, `find`, `rfind`, `replace` and `in` over words of six
  alphabets (ASCII, Latin-1 with NUL-like and quote characters, characters either side
  of 256 with two outside the BMP, kana, one letter, the code points around the
  surrogates) at 0 to 700 words of up to 1 to 14 characters; ints of seven ranges at 0
  to 5,000, a tuple's vector, bools. The interpreter (the callers' loops), the JVM and
  wasm print the same; Go Starlark differs where a string holds a character of several
  bytes (its `len` and `find` count bytes).
- `sortrounds.star` -- `sorted` of 50,000 ints and of 50,000 strings `ROUNDS` times
  (replace the word): 24 rounds 0.59 s, 96 1.40 s, 400 2.96 s on the JVM against
  CPython's 0.57, 1.96 and 7.63.
