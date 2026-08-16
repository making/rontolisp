# 402. Missing CL functions: the namestring, `nstring-*` and environment-enquiry families

Difficulty: Low

Three unrelated CL families the dexador spike (`.todo/396`) walked into. All
three are small, and all three are ordinary CL that any library may call.

## 1. `file-namestring` / `directory-namestring` / `host-namestring`

Absent entirely -- `(file-namestring #P"/a/b/c.txt")` signals
`The function FILE-NAMESTRING is undefined`. dexador calls it in two places
(`src/body.lisp`, `src/backend/usocket.lisp`) to name a pathname content part
in a multipart upload.

The rest of the family IS implemented, but only as operator-position cases:
`namestring`, `pathname-name`, `pathname-type`, `pathname-directory`,
`enough-namestring` all answer correctly when called directly and are all
`(fboundp 'x)` => NIL. That is `.todo/394`'s sweep (a CL FUNCTION with no
`Environment` definition and no `BuiltinFunctionWrappers` entry), not this
item -- but the new names must land WITH the wrapper, not repeat the pattern.
`.kb/pathnames.md` for the value model; the accessors already decompose a
namestring, so `file-namestring` is the last path segment and
`directory-namestring` everything before it.

## 2. `nstring-upcase` / `nstring-downcase` / `nstring-capitalize`

Absent. chunga's `util.lisp` takes `#'nstring-upcase` as a first-class value,
so the interpreter survives until the function is called while **the compile
path fails outright**:

```
error: while compiling defun CHUNGA::MAKE-KEYWORD: Cannot compile: CHUNGA::NSTRING-UPCASE
```

The destructive spelling is the point (it returns the SAME string, modified),
so it must actually mutate -- `string-upcase` under another name would be a
silent divergence for any caller that relies on the aliasing. Strings are
already mutable here (`copy-seq` + `setf aref`), so this is the existing
`string-upcase` walk writing in place. First-class value support is required
(chunga's use IS `#'`), so `Environment` + `BuiltinFunctionWrappers` both.

## 3. The environment-enquiry family

`lisp-implementation-type`, `lisp-implementation-version`, `software-type`,
`software-version`, `machine-type`, `machine-version`, `machine-instance`,
`short-site-name`, `long-site-name` -- all absent. dexador's default
User-Agent is built from the first four.

`.kb/time-environment-builtins.md` is the home. Each answers a string or NIL,
and the ANSI-blessed answer for anything unknown is NIL, so the only design
question is what the four knowable ones say and whether they agree across
backends. Proposal: `lisp-implementation-type` = `"rontolisp"` and
`-version` = the `Version` resource on every backend (identical output is the
rule -- `.kb/emitted-output-determinism.md`); `software-type` / `machine-type`
from the host on interpreter and JVM, and the WASM target's own name on the
two WASM backends; the site names NIL. Write the choice into the `.kb` file --
a caller printing a User-Agent must not get a different string per backend by
accident.

`room`, `ed` and `dribble` are absent too and are deliberately out of scope
(no consumer, and two of them are interactive).

## Pinning

One test per family across all four backends, plus a `ci-spec.yaml` case for
the User-Agent-shaped string, and the per-operator doc pages the workflow in
`CLAUDE.md` requires.
