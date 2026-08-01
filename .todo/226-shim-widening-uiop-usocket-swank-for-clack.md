# Shim widening for clack: uiop symbol-call + uiop/image, usocket host resolution, swank stub

Difficulty: 低 (three independent small edits to existing shim machinery;
each has a direct precedent)

Part of the Clack milestone `.todo/223`.

## 1. uiop: exported, real `symbol-call`; `uiop/image` package

- `uiop:symbol-call` — lack/util's `load-with-quicklisp` references it at READ
  time (single colon), so the whole `util.lisp` fails to load. The symbol
  exists but is INTERNAL today ("not external in the UIOP package" — spike).
  Make it a real exported member: `(apply (intern (string name) (find-package
  (string pkg))) args)` with a package-designator-tolerant lookup. postmodern's
  json-encoder probe wants it too, same spelling.
- `uiop/image` package with exported `print-condition-backtrace` —
  `lack-middleware-backtrace` does `(:import-from :uiop/image
  :print-condition-backtrace)`, which dies when the package is absent. No
  backend has a Lisp-level backtrace, so the honest lite version prints the
  CONDITION to `:stream` (`(format stream "~A~%" condition)` shape — the spike
  used exactly this and the middleware worked). Real uiop houses it in the
  `uiop/image` package that `uiop` also re-exports; register the package
  properly in `PackageRegistry` seeding so both spellings resolve.

## 2. usocket: `host-to-hostname`, `get-host-by-name`

`clack.handler:run` calls both when `:address` is given (it always normalizes
the address before passing it to the backend handler). Missing from the shim
(spike hit "HOST-TO-HOSTNAME is not external"). Interpreter/JVM: resolve via
`InetAddress` (`get-host-by-name` -> address, `host-to-hostname` -> dotted
string). WASM: follow the existing shim policy — address accessors return
nil/identity (the shim source records the splice constraint; `.kb/tcp-sockets.md`).

## 3. swank: stub built-in system

`clack.asd` hard-depends on `"swank"`, whose real `.asd` (slime) is a program
we can never parse — without intervention `ql:quickload` downloads the slime
tarball and dies. Add a `BuiltinSystems` stub system "swank" whose one shim
source defines package `swank` exporting `create-server` / `stop-server`:
`create-server` signals "swank is not supported on rontolisp" (clack only
reaches it when `:swank-port` is passed), `stop-server` is a nil no-op.
Remember the native-image `resource-config.json` entry (`.kb/asdf.md` gotcha).

## Test

- uiop/usocket: units beside the existing shim tests; the E2E lives in
  `.todo/228`'s ClackE2eTest (loads the UNPATCHED cached sources).
- swank: `AsdfSystemsTest` — `:depends-on ("swank")` resolves without network;
  calling `swank:create-server` signals.
