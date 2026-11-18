# j93. A NOTICE, third-party notices, and the notices a user's output needs

Difficulty: Medium

The audit is `.todo/artefacts/j91-license-audit/README.md`; the numbers below are its rows. There is no root `NOTICE` and
`LICENSE` has no third-party section, while permissively licensed code is copied in and
shipped. Rewrites are j90-j92; this item keeps what may stay and says so.

- **Root `NOTICE`** (Apache-2.0 sec. 4) and a third-party notices file with each component's
  licence text: #10 ring / ring-codec, #11 riddley, #12 tiny-routes (BSD-3: clause 1 is
  violated today), #13 ASDF / UIOP, #14 cffi, #15 Maven / Maven Resolver ports (plus the
  sec. 4 "changed" notice in the files), #17 jline (+ jansi and its native libraries), #18
  llama2.c / tinyllamas in `examples/`, #19 the test libraries without licence text,
  #22 starlark-go's `string_format` (or correct the "ported" wording if only behaviour was
  matched -- establish which), #23 ggml's q8_0 reference, #26 the WASI / wasmtime WIT files,
  #27 Unicode-3.0, #30 `coi-serviceworker`, #31 the Kuzushiji-49 weights (CC BY-SA 4.0: decide
  whether SA reaches the weights), #32 the Programming Clojure snippets, and fdlibm's
  permission notice once j91 lands.
- **A header in each copied file** naming its origin and licence.
- **The jars**: the exec jar and the native image carry the notices (`META-INF/`).
- **What a user ships** (#16): every `--native` output statically links wasmtime and 143
  crates (MIT/Apache, BSD-3, ISC, Unicode-3.0, Zlib, CDLA-Permissive-2.0). Generate the notice
  file from `cargo` metadata at build time and give the user a way to get it next to their
  executable; say so in `doc/en/**` + `doc/ja/**`. Likewise the run-time pieces a compiled
  program embeds (#10-#14 when used) and #33: a compiled third-party Quicklisp library carries
  no upstream notice into the output -- document the user's obligation.
- **#25** `clsql-closer-mop.asd` (LLGPL) and `petalisp-native-backend-rontolisp.asd`
  (AGPL-3.0): generate the difference at load time (as `PetalispForms` does) instead of
  shipping near-copies.
- A test (or a `PathCitationTest`-like check) that a file declaring a third-party origin has
  its entry in the notices file, so the next copy cannot land silently.
