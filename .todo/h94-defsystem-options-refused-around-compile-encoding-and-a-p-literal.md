# h94. `defsystem` options ASDF accepts and rontolisp refuses: `:around-compile`, `:encoding`, `#P` pathnames

Difficulty: Medium

Measured 2026-11-04. `parseDefsystem` hard-errors on anything outside its closed option list.
Release counts are over the 232 fetched releases.

| Option | Releases | First stopped | Treatment wanted |
| --- | --- | --- | --- |
| `:around-compile` | 4 | coalton (6 occurrences), mgl-pax (why its shim exists, `.kb/asdf.md`) | call it around the component's load; a designator string or a lambda |
| `:encoding` | 3 | pngload (`:encoding :utf-8`) | accept `:utf-8` and `:default`; refuse by name anything else if the reader cannot honor it |
| `:entry-point`, `:build-operation`, `:build-pathname` | 3, 2, 2 | not reached in this round | record and ignore: they drive `program-op`, which has no meaning here |
| `:weakly-depends-on` | not counted | not reached in this round | load when available |

The last two rows are refused too: neither is in `AsdfSystems.IGNORED_OPTIONS` or parsed.

A component `:pathname #P"src/"` fails too:
`system clingon: component utils :pathname expects a namestring literal, got #P"src/"`. The `#P`
literal is constant data; read it as the namestring.

`:around-compile` is the one with semantics. Coalton's binds `*features*` to include
`:coalton-lisp-toplevel` and calls the thunk, so it decides how the component's forms READ. On the
interpreter that is a call around `loadFile`. On the compile paths the function has to run in the
build image while the component is read, without reaching the program.

After `:around-compile`, coalton stops on `.todo/h98` (`concrete-syntax-tree`).
