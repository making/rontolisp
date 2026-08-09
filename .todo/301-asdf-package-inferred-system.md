# ASDF: `:class :package-inferred-system`

Difficulty: Medium

`(defsystem "x" :class :package-inferred-system ...)` is a hard error today:

```console
$ echo '(ql:quickload "ningle")' > s.lisp && rontolisp s.lisp
ASDF:DEFSYSTEM ningle: unsupported option :CLASS (supported: :name :description
  :long-description :version :author :maintainer :license :depends-on :serial
  :components :pathname :rontolisp-features)
```

It is the FIRST thing that stops ningle (`.todo/300`), and it is not a
ningle-shaped gap: of the systems already sitting in `~/.rontolisp/quicklisp`,
`ningle`, `rove` (the modern test framework the whole fukamachi ecosystem has
moved its test systems onto) and `array-operations` all declare it. This is the
system style that has replaced hand-listed `:components` upstream, so the subset
either grows it or freezes at the 2015 half of Quicklisp.

## What the class means

There is no `:components` clause. A sub-system name is a FILE PATH under the
primary system's directory, and its dependencies are read out of the file's own
package definition:

- system `ningle/app` -> `app.lisp` under `ningle.asd`'s directory. Generally:
  strip the primary system's name and the `/`, append `.lisp` to the rest
  (`x/a/b` -> `a/b.lisp`).
- the file's FIRST form is a `defpackage` / `uiop:define-package`; every package
  named in `:use`, `:mix`, `:reexport`, `:use-reexport`, `:mix-reexport` and the
  first argument of each `:import-from` / `:shadowing-import-from` is a
  dependency. `:nicknames`/`:shadow`/`:export`/`:intern`/`:documentation`
  contribute nothing.
- a dependency package name becomes a SYSTEM name: `cl`/`common-lisp` (and the
  implementation's own packages) drop out, `x/...` names are sub-systems of this
  same primary system, and anything else is the downcased package name — unless
  `register-system-packages` said otherwise.

That last clause is why **`register-system-packages` has to stop being skipped**
(`.kb/asdf.md` records the skip and its reason — nothing consulted such a map
*then*). ningle.asd's three lines are load-bearing:

```lisp
(register-system-packages "lack-component" '(#:lack.component))
(register-system-packages "lack-request"   '(#:lack.request))
(register-system-packages "lack-response"  '(#:lack.response))
```

Without the map, `app.lisp`'s `(:import-from #:lack.request ...)` asks for a
system called `lack.request` and `locate` looks for `lack.request.asd`, which
does not exist. The map is per-file data, exactly like the `defparameter`
environment `parseAsdSource` already keeps.

Note what the class does NOT need: ningle's own `:depends-on ("ningle/main")`
never mentions myway or alexandria — those reach the graph only through
`app.lisp`'s defpackage. So reading the files is not an optimization, it is the
whole dependency graph.

## Where it has to work

Both `.asd` consumers, or the compile backends diverge from the interpreter:

- `eval/LispEvaluator.loadSystem` (the interpreter / `ql:quickload`)
- `cli/LoadInliner.spliceSystem` (the JVM and both WASM backends)

`AsdfSystems.parseAsdSource` is pure data-parsing today and never reads a second
file; a package-inferred system has to. It already takes the `.asd` path, so the
`SourceLoader` is the seam to thread through (the browser playground has an
in-memory one and no filesystem — resolution must go through it, never
`Files`). Read only the first form of each component file: `LispReader` on the
whole file would evaluate `#+`/`#-` and cost a full parse of every source in the
system.

Watch the cycle rule: a package-inferred system's dependency graph can name a
sibling that names it back, which real ASDF reports as a circular dependency;
`loadSystem` already has that check for `:depends-on`, so route these edges into
the same one rather than recursing until the stack ends.

## Rejected alternative

`AsdOverrides.replacementSource` (the ironclad mechanism: substitute a
hand-written `.asd`) would make ningle load in an afternoon. It is the wrong
tool here — ironclad's `.asd` is *executable* and cannot be parsed as data at
all, while ningle's parses fine and merely declares a class we do not implement.
An override would freeze ningle's file list into this repository, and would do
nothing for rove or array-operations.

## Work

- Accept `:class` and implement the `package-inferred-system` value; any other
  class stays a hard error naming the clause.
- Record `register-system-packages` into a package -> system map (per parsed
  `.asd`, merged into the loader's map), and use it when translating a defpackage
  dependency into a system name.
- Resolve a sub-system name to its file, read that file's first form, derive
  dependencies, recurse.
- Both consumers above; `AsdfSystemsTest` for the parse half (including the
  verbatim `ningle.asd`), plus a four-backend load of a package-inferred system
  from disk.
- `.kb/asdf.md`: a section for the class, and rewrite the
  `register-system-packages` sentence — it now says the form is skipped
  *because nothing consults such a map*, which stops being true here.

## Done when

`(ql:quickload "ningle")` reaches ningle's own sources on all four backends
without a patched `.asd`, and a `rove`-style `:class :package-inferred-system`
system with nested sub-system names (`x/a/b`) loads the same way.
