# The `--no-wasi` load-path warning reports `clackup`'s pathname branch, which a reactor never takes

Difficulty: High

Since `a8091825` (`compiler/NoWasiLoadPathRefusals`), every `--no-wasi` build of a
clack program prints one warning nobody can act on:

```console
$ examples/cloudflare-workers/hello-clack/build.sh
compiling worker.lisp -> src/worker.wasm
.../clack-20250622-git/src/clack.lisp:21:3: warning: WITH-OPEN-FILE is reachable from a
top-level form of this --no-wasi module (a top-level form -> CLACK:CLACKUP ->
CLACK:EVAL-FILE -> CLACK::%LOAD-FILE), so it can run while the module LOADS -- ...
```

The line is TRUE statically and false dynamically. `clackup` dispatches on what it
was handed:

```lisp
(let* ((app (typecase app
              ((or pathname string) (eval-file app))   ; (clackup "app.lisp")
              (otherwise app))))                       ; (clackup #'app)
  ...)
```

Every Worker example calls `(clack:clackup #'app ...)`, so the first clause cannot
match: `#'app` is a function. The module loads and serves correctly -- verified by
instantiating `examples/cloudflare-workers/hello-clack/src/worker.wasm` under node
(`__ronto_seed_random` + `__ronto_set_time` + `_initialize`, then `handle-request`),
which answers `{"status":200,...}`.

`.kb/wasm-export-no-wasi.md` already records it as a "known standing line,
deliberately". That is the problem: it is not one program's line, it is EVERY clack
/ ningle / tiny-routes program's line (`grep -rl clackup examples/**/*.lisp` --
all five `examples/cloudflare-workers/*` workers, plus the `examples/net` and
`examples/asdf` ones once built `--no-wasi`). A warning class whose only routine
instance is a false positive teaches the reader to skip the class, which is exactly
what the pass exists to prevent -- todo-308 landed it because four real blockers
had cost a node run each to find.

## The same blindness costs bytes

The tree-shaker is name-reachability too, so it keeps the branch as well.
`-Drontolisp.debug.functable` on the hello-clack build lists `CLACK::%LOAD-FILE`,
`CLACK:EVAL-FILE` and `PROBE-FILE` in the emitted module, and the
`NoWasiFilesystemStubs` string `"WITH-OPEN-FILE requires WASI; a --no-wasi module
has no filesystem"` is in `src/worker.wasm`. Dead weight in a bundle with a size
limit, in every Worker built from clack.

One cause, two symptoms: neither analysis can see that a `typecase` clause whose
type the argument cannot have is dead.

## Work

The pass runs on `LispMacroExpander.flattenTopLevel`'s output
(`WasmLispCompiler` ~1633-1642), BEFORE per-form macro expansion, so `typecase` is
still literal in the AST -- no expansion archaeology needed.

- Give the walk argument sensitivity. `report`'s call edges are bare names today
  (`Scan.called`); carry the SHAPES of the actual arguments across an edge, bind
  them to the callee's parameters (`collectDefinitions` keeps bodies only -- it
  needs the lambda list too, `&optional`/`&rest`/`&key` included), and prune a
  `typecase`/`etypecase`/`typep` branch whose type a known shape cannot satisfy.
  A shape lattice of three points is enough for this: FUNCTION (`#'f`,
  `(lambda ...)`), a literal's type, and UNKNOWN.
- Stay conservative in the direction that matters. A wrong prune is a MISSED
  refusal -- the failure mode this pass was built to end -- so prune only on a
  shape the call site states syntactically, and treat everything else as UNKNOWN.
  Note the memoization: `visited` currently keys on name + guarded; it must key on
  the shapes too, or the first call edge's shapes silently answer for the second.
- Then do the tree-shaker (`.kb/optimize-dead-code-elimination.md`). Sharing the
  narrowing with `--optimize` is what turns the fix from "one warning quieter"
  into "every clack Worker drops its dead file loader". If the two analyses cannot
  share code, at least share the predicate.
- Update `.kb/wasm-export-no-wasi.md`: the "known standing line, deliberately"
  sentence is the re-evaluation trigger this retires, and the new approximation
  (what a shape is, and why an UNKNOWN one never prunes) takes its place.

## Done when

`examples/cloudflare-workers/hello-clack/build.sh` prints no warning; the other
four `examples/cloudflare-workers/*` workers print only lines that are true;
`NoWasiLoadPathRefusalsTest` gains a case pinning both directions (a function
argument prunes the pathname clause; a `(clackup "app.lisp")` call still reports
it); and each affected worker still answers under node, byte-compared where the
module is otherwise unchanged.
