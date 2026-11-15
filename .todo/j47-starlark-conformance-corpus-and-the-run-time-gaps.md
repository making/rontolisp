# j47. Starlark: the conformance corpus on four backends, and the run-time gaps it shows

Difficulty: Medium

After `.todo/j46`. The prototype ran bazelbuild/starlark's `test_suite/testdata` (37 files,
430 chunks) through a throwaway Python driver: 404 pass, every file identical on the four
backends (`.todo/artefacts/j45-starlark-front-end-prototype/`, `README.md`). Make that a test of this repository and close the gaps.

## Do

- Bring the corpus in (Apache-2.0; keep the notice) and a driver in the shape of the Clojure
  one (`clojure-spec.yaml`, `.kb/clojure-frontend.md`): one program per file, run once per
  backend. Expected errors are matched by MESSAGE (the corpus's `###` patterns, the `java:`
  / `go:` / `rust:` variants any one of which may match); the prototype only checked that an
  error occurred.
- The 26 failing chunks, by cause:
  - missing: `splitlines`, `title`, `islower`, `isupper`, `istitle`, `dir`, also
    `getattr`, `hasattr`, `codepoints`, `elem_ords`, `removeprefix`/`removesuffix`,
    bound methods as values (`x.append` uncalled)
  - keyword arguments to methods and to built-ins that lack the keyword (`dict.update(k=v)`)
  - argument validation in `int` and `list.index`
  - `format`: mixing automatic and manual field numbering is an error
  - a built-in's `repr` carries its name
- `repr`/`str` of floats per the spec (`1e+100`, `inf`, `nan`), `%` formatting's
  `%e`/`%f`/`%g`, string escapes in `repr`.
- Dict insertion order after `pop` and re-insert: the prototype relied on the host hash
  table's order; pin it on the four backends or keep the order in the dict itself.

Record the pass count per backend in the Starlark `.kb` file `.todo/j46` creates.
