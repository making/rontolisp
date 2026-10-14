# g14: local `./mvnw test` silently tests a stale native runner

2026-10-14: `FetchSpecE2eTest` NATIVE leg failed 21/259 deterministically on this
box (full suite and solo alike), green on CI. Root cause was NOT the suite: the
`rlrun-net` under test was built 2026-10-08, predating the 2026-10-09 `octets`
request key (`HostFetchLibrary`: octet bodies cross as one string; the runner
reads it since the same commit), so every octet request body went out empty
(`Content-Length: 0`, observed server-side). Rebuilding via
`rontolisp-native/build.sh` turned the suite 259/259 green with no other change.

Why it misleads: plain `./mvnw test` never rebuilds the runner
(`rontolisp.native.build=false` default) and just copies whatever sits in
`rontolisp-native/target/resources`. After any runner-source change the NATIVE
leg tests stale code. The 3 divergent octet cases cascade through positional
slicing into 19 slice failures plus the count, and the binary exits 0 with
short output and empty stderr, so it reads as a flaky hang/crash. CI stays
green because a fresh checkout has no stub (leg skips) and the native-image job
rebuilds before running.

Decide in the item how a local run stops testing stale artifacts: e.g. skip or
fail the NATIVE leg loudly when the stub predates the runner sources (mtime is
fragile under git; prefer a source-sha fingerprint embedded at build and
checked at test time -- `NativeToolchain` already pairs shim and stub by
fingerprint), or rebuild when cargo is present. Note the cost: a rebuild is
~30 s+ even with cache.

Acceptance:
- the chosen gate in place, pinned (a test asserting the leg skips/refuses
  loudly on a stale pair, plus the green FetchSpec run again).
- `.kb` note where the NATIVE leg's prerequisites live (`fetch-http.md`
  runner section and/or `native-output.md`).
- full suite green (merge `origin/develop` right before the run, one maven run).
