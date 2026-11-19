# k05: the probes

Java programs printing a transcript, run under JDK 25 and under the four backends
(`compare.sh File.java all`; `JAR` is the exec jar). Written here; a JDK's output is
what each was compared with and is not kept. The numbers are in `.kb/java-frontend.md`
("The library", "java.net.http") and `.kb/fetch-http.md` ("Timeouts").

| Probe | What it asks | Lines | Differs from the JDK |
|---|---|---|---|
| `Dur.java` | `java.time.Duration`: the factories over 22 seconds and 14 nanosecond adjustments, the arithmetic over 17 durations, 103 texts through `parse`, `Thread.sleep` of one | 3,321 | nothing |
| `Members.java` | `Math`'s exact quotients, `String.codePointCount` / `offsetByCodePoints` / `indexOf` within a range, `Character.toChars` into an array, `codePointAt` of an array, `reverseBytes`, `OptionalLong`, `firstValueAsLong`, `System.getenv` (run with `K05_PROBE=set`; a `.wasm` with `wasmtime run --env K05_PROBE=set`) | 866 | nothing (a `NullPointerException`'s message is left out of the transcript) |
| `Limits.java` | the HTTP client's time limits against `Origin.java` (`java Origin.java 18431 600`) and a blackholed address (`10.255.255.1:81`) | 14 | nothing, on the interpreter, the JVM, a `--native` output and a component (`wasmtime run -S http=y`) |
| `Hang.java` | a request timeout against an address that never answers the connect | 1 | nothing |
| `timeouts.lisp` | `rontolisp:fetch`'s two options on each transport: the words of each failure | 5 | the words are the transport's |

`size/`: the programs whose sizes are quoted (`Get.java`, `GetTimed.java`, `u.lisp`,
`timed.lisp`), compared against a jar built from `23ce3b245`.
