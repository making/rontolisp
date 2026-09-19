# The runtime package API: `unuse-package`, `shadowing-import`, `delete-package` and friends

Difficulty: Medium (`make-package` runtime-tier design already exists; these add the
runtime mutation functions and the `set-up-packages` aux on top of it)

Split out of `.todo/715` (2026-09-19). The `in-package`/`defpackage`/`make-package`
read/compile-time tier is done; this is the RUNTIME mutation half of `.todo/741`,
which closed 2026-09-09 covering only part. Re-filing before quoting.

## What fails (2026-09-19, interpreter, suite `ca06bd9`, test-level)

| operator | count | reason |
|---|---:|---|
| `unuse-package` | 25 | `The function UNUSE-PACKAGE is undefined` |
| `shadowing-import` | 13 | `The function SHADOWING-IMPORT is undefined` |
| `delete-package` | 12 + `no such package` | undefined / failure |
| `set-up-packages` | 56 | `The function SET-UP-PACKAGES is undefined` (an aux defun the packages chapter relies on, not a standard operator) |
| `package-name` wrong forms | 8 | answers a bare string instead of `(values name)` / `%read-eval` spelling |
| `export` / `intern` / `unintern` / `import` designator | ~20 | wrong error/designator handling |
| `use-package` / `find-package` | ~10 | designator and package-name answers |

## Notes

- `set-up-packages` is the suite's own aux defun (loaded by every chapter), not an
  operator. It is a LOST FORM, so fixing the package tier starts with making that
  form evaluate. It is the same shape as the `class-precedence-list-foo` and the
  old `*CLASSSES*`/`*METHODS*` cascade -- but unlike those it is a package-list
  helper, so it is not decided against.
- The runtime-tier mechanics are `.kb/packages.md`, "Runtime tier": `LispPackage`
  carries an `externals` set; the runtime tier is a live table on every backend
  (`.todo/741`'s design decision). The `cl` external list is the standard's 978
  names (`.kb/packages.md`, "The `cl` external list is the STANDARD's list").
- `delete-package`/`unuse-package`/`shadowing-import` are built-ins in `CL_FUNCTIONS`
  that currently signal `undefined`; check `.kb/adding-primitives.md` per surface.
- Measure the effect as a DIFF of failing test NAMES (`unuse-package`, `packages`),
  and report fixed AND regressed.
