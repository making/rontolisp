# e95. No backend resolves a symbolic link

Difficulty: Medium

`truename` and `probe-file` answer the argument's namestring (`LispNames.PROBE_FILE`), and
Clojure's `.getCanonicalPath` / `.getCanonicalFile` read the canonical path off the spelling
(`%clojure-io-canonical-path`). So `ring.util.response/file-response` and `resource-response`
cannot do what `:allow-symlinks?` is for: measured 2026-10-09 against clj 1.12.6 +
ring-core 1.15.5, a link below `:root` leading outside it is `nil` there and served here on
the interpreter and the JVM (both wasm backends did not follow that link, `../x` inside the
preopen, so there it happened to be `nil`). Documented in `doc/*/clojure/reference/ring-util.md` ("Differences")
and `.kb/clojure-frontend.md` ("Ring util namespaces").

## Plan

1. A primitive answering the real path of an existing file on all four backends: the
   interpreter and the JVM through `Path.toRealPath`; Preview 1 by walking the components
   with `path_readlink` (a new import, the `path_filestat_get` precedent of `file-write-date`);
   the component through `readlink-at` in `adapter.wat`; the serve bridge's stub.
2. `truename` over it (CL), `.getCanonicalPath` over it (Clojure), and
   `%clojure-ring-canonical-path` over it where the file exists.
3. Pin with a link in `ClojureRingFileResponseTest`'s tree on all four backends, and the CL
   side in `ci-spec.yaml`.
