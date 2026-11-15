# j47. Starlark: the conformance corpus on four backends, and the run-time gaps it shows

Difficulty: Medium

bazelbuild/starlark's `test_suite/testdata` (37 files, 430 chunks) has been run twice by
throwaway drivers, each chunk a program of its own, a chunk passing when it prints no
assertion and fails exactly when an error is expected (the MESSAGE not matched): 404 by
the prototype, 416 by the front end of `.kb/starlark-frontend.md` ("Measured"), every
chunk printing the same on the four backends. Make that a test of this repository and
close the gaps.

## Do

- Bring the corpus in (Apache-2.0; keep the notice) and a driver in the shape of the Clojure
  one (`clojure-spec.yaml`, `.kb/clojure-frontend.md`): one program per file, run once per
  backend. Expected errors are matched by MESSAGE (the corpus's `###` patterns, the `java:`
  / `go:` / `rust:` variants any one of which may match). `StarlarkSpecE2eTest` already runs
  `starlark-spec.yaml` on the four backends in process; a chunk needs its own globals
  (`.todo/j49`'s module namespace, or a program per chunk) and has to go on after an error.
  `assert_eq`, `assert_ne` and `assert_` are the corpus's, not the language's: three `def`s
  in Starlark ahead of each chunk were enough.
- With the corpus in the tree, parse every chunk in a test: the reader was compared with
  Python's `ast` over it only once (`.kb/starlark-frontend.md`, "Reader").
- The 14 failing chunks, by cause:
  - missing: `splitlines` (3), `islower` / `isupper` / `istitle` (2), `title` (1), `dir`
    (3); also `getattr`, `hasattr`, `codepoints`, `elem_ords`, `removeprefix` /
    `removesuffix`. `dir` and `getattr` need the methods of a type at run time:
    `StarlarkBuiltins` declares each method behind its types, so generate the table from
    it, as `Scheme.runtimeForms` does.
  - `format`: mixing automatic and manual field numbering is an error (3)
  - a local read before its assignment (2): `.todo/j48`
- `repr`/`str` of floats per the spec (`1e+100`, `inf`, `nan`; today Common Lisp's `1.0e100`
  and `Infinity`), `float` of a string and of an int too large for a float, NaN equal to
  itself, `%` formatting's `%e`/`%f`/`%g`, string escapes in `repr`.
- A function is hashable by identity in the spec and `unhashable type: function` here.
- `s.elems()` answers a list, where the spec has an iterable of its own type.

Done by `.todo/j46` and no longer open: keyword arguments bound by a built-in's signature
(`dict.update(k=v)`), `int` and `list.index` validation as far as the corpus asks, a
built-in's `repr` with its name, bound methods as values, and dict order after `pop` and
re-insert (the host table's, pinned on four backends by `starlark-spec.yaml`).

Record the pass count per backend in `.kb/starlark-frontend.md`.
