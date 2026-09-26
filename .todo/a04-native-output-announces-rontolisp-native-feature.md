# A `--native` output announces `:rontolisp-native` in `*features*`

Difficulty: Low

Measured 2026-09-26 (linux-x86_64): a `--native` output's `*features*` is the Preview 1 set,
`(:RONTOLISP :RONTOLISP-WASM :UNICODE)`, because the module inside is the P1 module and
`Features.WASM` is used as is. Nothing reads wrong, but the runner adds capabilities P1 lacks --
`rontolisp:fetch` (rlrun-net), and objc:/appkit:/metal:/scene: on `macos-aarch64` -- and no
`#+` can tell the two apart, so one source cannot say "fetch on native, fall back on P1".

Plan: in `CompileFrontend`, when `options.runnerHosted()`, add `:rontolisp-native` (additive,
named for what the target has, `.kb/reader-features.md`). Do NOT add OS/architecture features:
they would have to come from `--native-target`, never the compile host's `os.name` (a cross build
would lie), and they flip `#+unix`/`#+darwin` branches in shipped libraries (`.kb/uiop.md`).

Pin it: a native-output test that `#+rontolisp-native` is true there and false on P1 and
`--component`; document it in `doc/{en,ja}` beside the other feature names and in
`.kb/reader-features.md`.
