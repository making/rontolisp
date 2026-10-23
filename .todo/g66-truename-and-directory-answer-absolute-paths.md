# g66: truename, probe-file and directory answer absolute paths, like SBCL

Difficulty: Medium

`probe-file`, `truename` and `directory` resolve links and answer a directory in directory
form on every backend (`.kb/pathnames.md`, "Remaining lite edges"). Two differences from
SBCL remain:

- Nothing is made absolute. SBCL answers `(probe-file "a.txt")` as
  `#P"/abs/cwd/a.txt"` (merged with `*default-pathname-defaults*`, then resolved); ours
  answers `#P"a.txt"`. The same holds for `truename` and each `directory` entry.
- With `:resolve-symlinks nil`, a `..` in `directory`'s pathspec stays as spelled; SBCL
  takes it out.

## Goal

The three operators answer absolute truenames on the interpreter, JVM and both WASM
backends, matching SBCL 2.6.x, with `..` removed in both `:resolve-symlinks` modes. On
WASM decide what "absolute" means against the preopens (the module may know no host
cwd) and document it. Check the callers that rely on a relative answer (uiop's
`directory-files`/`subdirectories`/`directory*`, ASDF's source-directory answers,
`enough-namestring` users, ci-spec cases printing these answers) against SBCL/UIOP.
Update the tests, `.kb/pathnames.md`, `.kb/directory-listing.md` and the `doc/en` + `doc/ja`
pages of the three operators.
