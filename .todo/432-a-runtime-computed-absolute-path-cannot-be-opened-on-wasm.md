# 432. A runtime-computed absolute path cannot be opened on WASM

Difficulty: Medium

```lisp
(defun rd (p) (with-open-file (s p) (read-line s)))
(rd (concatenate 'string "/tmp/" "xx.txt"))   ; interpreter/JVM: "hello"   WASM: error
(rd (concatenate 'string "x" ".txt"))         ; all four: "hello"
(probe-file (concatenate 'string "/tmp/" "xx.txt"))
;; interpreter/JVM: #P"/tmp/xx.txt"           WASM: NIL
```

Both WASM backends, under every preopen spelling tried (`--dir /`,
`--dir /tmp`, `--dir . --dir /`). `open` answers `open: cannot open file`, and
`probe-file` -- which cannot signal by contract -- answers NIL for a file that
exists. A pathname OBJECT behaves exactly like the equivalent string, so this
is about the PATH, not about `.kb/pathnames.md`'s value model.

`WasmIoRuntimeBuilder`'s `_open` opens relative to **fd 3, the first preopened
directory**, and never consults `fd_prestat_dir_name` to learn what that
directory is CALLED. So a path that starts with `/` is handed to `path_open` as
`/tmp/xx.txt` relative to that fd, which WASI rejects -- even when the host
mapped `/` and `tmp/xx.txt` under it is exactly the file meant. The
"resolve against the first preopen" rule is recorded in
`.kb/read-load-streams.md`; what is NOT recorded is that it silently makes
every absolute path unreachable.

**A literal path is not a counterexample.** `(with-open-file (s "/tmp/xx.txt")
...)` appears to work on WASM with no `--dir` at all -- because
`CompileTimePathnameFolder` bundles the file's compile-time CONTENTS into the
artifact as a `with-input-from-string` (`.kb/asdf.md`), so nothing is opened.
Editing the file after compiling does not change the output. Every genuine
runtime open of an absolute path fails.

## Why it matters

Found by the cl-mustache spike (`.todo/425`): `mustache:compile-template` on a
`pathname`, and `mustache:*load-path*` partial lookup, both build the path at
run time, so `t/test-api.lisp` cannot run at all on either WASM backend while
it passes 20/20 on the interpreter and the JVM.

The shape is general and it is the common one. `asdf:system-relative-pathname`,
a runtime `uiop:merge-pathnames*`, `(merge-pathnames name *load-truename*)`, a
path read from a config file or an environment variable -- all of them produce
an absolute namestring, and all of them are dead on WASM today. The failure
mode for `probe-file` is the worse half: not an error, just "the file is not
there".

## The fix

Resolve a path against the preopen table instead of assuming fd 3 is the root
of everything: walk `fd_prestat_dir_name` for the preopened fds, pick the
longest prefix match for an absolute path, and pass `path_open` the remainder
relative to that fd. A relative path keeps today's behavior (first preopen), so
nothing that works now moves. With no matching preopen the answer is the
ordinary "cannot open" errno -- an ERRNO, not a trap, which
`.kb/wasi-component.md` already establishes as the policy.

Both WASM backends together: `--component`'s `adapter.wat` reaches the same
`$path_open` over `wasi:filesystem@0.3.0` and caches a single preopen
descriptor today, so the same widening is needed there.

## Definition of done

`open` / `with-open-file` / `probe-file` / `load` / `file-write-date` /
`delete-file` accept an absolute runtime path on both WASM backends whenever a
preopen covers it, and answer identically to the interpreter and the JVM;
relative paths keep their current resolution byte for byte. Pinned in
`WasmLispCompilerIntegrationTest` (both the module and the `--component` leg,
with a `--dir` that is NOT the file's own directory, so the prefix match is
what is under test) and a `ci-spec.yaml` case, with the resolution rule written
into `.kb/read-load-streams.md` beside the fd-3 sentence it replaces, and the
compile-time bundling caveat above noted there so the next visitor does not
"verify" this with a literal path.
