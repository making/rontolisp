# The opt-in library E2E suites are red on develop

Difficulty: Medium

Found 2026-09-24 while re-verifying the wasm legs on wasmtime 49.0.0. None of these run in
`./mvnw test` (each is gated by an environment variable), so nothing noticed. Command:

```bash
RONTOLISP_CLACK_E2E=1 RONTOLISP_LACK_E2E=1 RONTOLISP_NINGLE_E2E=1 RONTOLISP_POSTGRES_E2E=1 \
  ./mvnw -Dtest='ClackE2eTest,LackEcosystemWasmE2eTest,LackEcosystemE2eTest,NingleE2eTest,PostmodernE2eTest,MitoE2eTest,ClPostgresE2eTest,Serve*E2eTest' \
  -DfailIfNoTests=false -Drontolisp.binary="$PWD/target/rontolisp" test
```

67 tests, 17 failures, 5 errors. Green: `ClPostgresE2eTest`, `LackEcosystemE2eTest`, both
`Serve*ComponentE2eTest`. The wasm ones below fail identically on wasmtime 47.0.3 (re-run
against the 47 CLI and image), so they are not the bump. The others fail on the
interpreter and JVM too, so wasmtime is not involved.

| test | symptom |
| --- | --- |
| `PostmodernE2eTest` `runtimeSql*` (3) | `UnknownFormatConversionException: Conversion = '"'`, a harness format string, before anything runs |
| `PostmodernE2eTest` other 7 | the native binary's COMPILE dies: `error: stack overflow (--stack <MiB> raises the limit)` |
| `MitoE2eTest` (8 of 9) | stdout carries SQL trace lines (`;; CREATE TABLE ... ()  (102000ms)`, twice) before the expected output; the JVM legs take ~270 s each |
| `LackEcosystemWasmE2eTest` `lackRequestParsesBodies...` (P1 + component) | `wasm trap: call stack exhausted` |
| `ClackE2eTest.tinyRoutesServesOnWasmComponentUnderWasmtimeServe`, `NingleE2eTest.ningleServesOnWasmComponentUnderWasmtimeServe` | `wasmtime serve` answers no bytes (`HTTP/1.1 header parser received no bytes`) |

Re-run after merging upstream (`5977c479e`, which includes the list-spine and CLI-stack
fixes of `951`/`952`): the same 17 failures and 5 errors.

Not yet established: whether the jar driver (no `-Drontolisp.binary`) shows the same, and
which commit turned each one red. Bisect one suite at a time; the stack overflow and
`call stack exhausted` may be one cause (a deeper compile-time or run-time recursion).
