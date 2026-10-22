# g47: Splice the portable load idiom at compile time

Difficulty: Medium

## The gap

A multi-file program that must run on standard Common Lisp as well as rontolisp
locates its sources relative to the loading file, since standard CL resolves a relative
`load` against `*default-pathname-defaults*` (the working directory):

```lisp
(load (merge-pathnames "src/a.lisp" *load-truename*))
```

The interpreter runs this. The compile path does not: `LoadInliner`
(`.kb/load-inliner.md`) splices only a top-level `load` of a LITERAL path, so this one is
left to the runtime `load`, and the compiled program

- is not self-contained (it reads `src/a.lisp` when it runs), and
- cannot find the file anyway: the entry file is not bracketed with `%begin-file`, so
  `*load-truename*` is nil at run time and the path resolves against the working
  directory. Reproduced 2026-10-22: `app/main.lisp` holding the form above plus a call
  into `src/a.lisp` runs on the interpreter and on SBCL from another directory; the
  `.jar` fails with `LOAD: cannot open file src/a.lisp`, the `.wasm` traps, and both
  compiles warn the called function is undefined. `(or *compile-file-truename*
  *load-truename*)` in place of `*load-truename*` fails the same way.

Nothing in the backends forbids it: the compiler knows every spliced file's path, and
`CompileTimePathnameFolder` (`.kb/asdf.md`, "Compile-time pathname folding") already
folds pathname primitives -- after the inliner, too late for a `load` argument.

rontolisp's own spelling, a literal relative `load` resolved against the loading file,
deviates from CL (where it resolves against the working directory), so a program that
uses it is not portable; `lispresen/` currently carries both spellings behind
`#+rontolisp` / `#-rontolisp` in `lispresen/src/all.lisp`, `lispresen/cli.lisp` and
`lispresen/test/lispresen-test.lisp` for this reason.

## What to do

Fold the load argument before deciding whether to splice: inside a spliced file (and in
the entry file, whose path the compiler also has), `*load-truename*` and
`*load-pathname*` are known constants, so `(merge-pathnames LITERAL <one of them>)`,
`(uiop:merge-pathnames* LITERAL <one of them>)`, `(uiop:subpathname <one of them>
LITERAL)` and `(or *compile-file-truename* <one of them>)` reduce to a literal path and
the `load` splices like a literal one. Decide whether the entry file should get a
`%begin-file` bracket so the variables hold its path at run time as on the interpreter
(a computed `load` the folder cannot reduce would then resolve as it does there); weigh
`LoadContextE2eTest` and the "costs nothing when unread" rule in `.kb/load-inliner.md`.
Same for `require` with a computed path if it falls out naturally.

## Done when

- The reproduction above prints the same on the interpreter, JVM, WASM and the WASM
  component, with `src/` absent at run time (pin it in the load-inliner tests and an E2E
  over all four backends).
- `lispresen/` drops its `#+rontolisp` / `#-rontolisp` load spellings for the portable
  one and `lispresen/check.sh` passes, SBCL leg included.
- `.kb/load-inliner.md` and the user docs on `load` (`doc/en/**` + `doc/ja/**`) describe
  the idiom.
