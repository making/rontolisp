# g59: the native legs of FetchSpecE2eTest fail on macOS

Difficulty: Medium

A local full `./mvnw test` on macOS aarch64 (2026-10-23, develop d3c15ef0c; the
`macos-aarch64` native precompile shim in `target/classes` dated 2026-10-22 20:36, so the
native leg ran) fails 21 NATIVE cases of `FetchSpecE2eTest`, while the CI e2e job passes:

- `e2e()[3][8]`, case `an-octet-vector-body-is-sent-as-it-is` (`fetch-spec.yaml`): the
  6-octet `(unsigned-byte 8)` body arrives empty (`application/octet-stream|` instead of
  `|fffe4100807f`); the empty vector and the `"é"` string bodies are right.
- `clojureHttpClient()[3][1]` and `[3][19]`..`[3][37]` (`clojure-http-spec.yaml`): from
  `[3][19]` on, each case's actual output is ANOTHER case's output (e.g. case
  `the-verbs-are-values-and-the-request-rides-the-response` printed the gzip case's lines),
  so the native leg's per-case results are shifted or mixed -- likely a harness or runner
  pairing problem that one failing case sets off, not 19 separate bugs.

First establish whether a fresh shim (`-Drontolisp.native.build=true`) still fails, and
whether the failure predates today (bisect against an older develop if needed).

## Goal

Every native case of `FetchSpecE2eTest` passes on macOS with a fresh shim, the octet-vector
body is sent intact by the native runner, and one case's failure can no longer misattribute
other cases' output.
