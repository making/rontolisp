# `uiop/stream` part 2: temporary files, encodings and the standard streams

Difficulty: Medium

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **3 / 66 for `uiop/stream` as a whole (`read-file-string`, `default-temporary-directory`, `with-temporary-file`)**.

Depends on `.todo/353`, `.todo/354`, `.todo/357`. The other half of
`.todo/359`.

The **28** remaining `uiop/stream` externals -- everything that is not "read
this file":

```
CALL-WITH-TEMPORARY-FILE  TMPIZE-PATHNAME  ADD-PATHNAME-SUFFIX
TEMPORARY-DIRECTORY  *TEMPORARY-DIRECTORY*  SETUP-TEMPORARY-DIRECTORY
CALL-WITH-STAGING-PATHNAME  WITH-STAGING-PATHNAME
NULL-DEVICE-PATHNAME  CALL-WITH-NULL-INPUT  WITH-NULL-INPUT
CALL-WITH-NULL-OUTPUT  WITH-NULL-OUTPUT
*DEFAULT-ENCODING*  DEFAULT-ENCODING-EXTERNAL-FORMAT  ALWAYS-DEFAULT-ENCODING
*DEFAULT-STREAM-ELEMENT-TYPE*  DETECT-ENCODING  *ENCODING-DETECTION-HOOK*
ENCODING-EXTERNAL-FORMAT  *ENCODING-EXTERNAL-FORMAT-HOOK*
*UTF-8-EXTERNAL-FORMAT*
*STDIN*  *STDOUT*  *STDERR*  SETUP-STDIN  SETUP-STDOUT  SETUP-STDERR
```

## Notes

- **The temporary-file family already half exists.** `with-temporary-file` is a
  macro with a real expansion and `default-temporary-directory` /
  `%temp-file-name` are prelude Lisp (`.kb/lack.md` -- smart-buffer's disk-spill
  path is why). This item makes `call-with-temporary-file` the real function and
  the macro its wrapper, which is upstream's own layering and removes the
  duplicate uniqueness rule. Do not leave two mechanisms.
- **Encodings are a single decision, not eight.** Every backend reads and writes
  UTF-8 and there is no external-format surface; `read-file-string` already
  documents that it accepts and ignores `:external-format`. So `*default-encoding*`
  is `:utf-8`, `*utf-8-external-format*` is that, the two hooks are the identity
  functions upstream installs, and `detect-encoding` answers `:utf-8` without
  reading the file. Write it in `.kb/uiop.md` as ONE lite decision with its
  reason; the alternative (eight independent approximations) is unauditable.
- **`*stdin*` / `*stdout*` / `*stderr*` and their `setup-` functions** are the
  interesting part. They are the raw streams, distinct from
  `*standard-output*` -- and rontolisp redirects standard output per-backend
  (`.kb/standard-output-redirect.md`), while stdin is a whole dispatch of its own
  (`stdin-dispatch.lisp`, `stdin-stub.lisp`, `stdin.wit`). Bind them to the real
  underlying streams and make `setup-*` re-derive them, so a program that
  captures `*standard-output*` and still wants the console has the same escape
  hatch it has in SBCL.
- **`null-device-pathname`** is `/dev/null` on unix; on WASM it is only
  openable if the host preopened it. `with-null-output` should therefore be
  implemented over a discarding stream rather than over the device, which is
  both faster and portable -- upstream's shape allows it.
- **`with-staging-pathname`** (write to a temp file, rename over the target on
  success) needs the rename primitive `.todo/358` discusses; inherit whatever
  that item decides rather than adding a second answer.

## Gate

`UiopCoverageTest` reports `uiop/stream 66/66`, closing the sub-package.
`ci-spec.yaml` gains a case that writes through `with-staging-pathname`, reads
the result back, and prints to `*stderr*` -- the component backend uses a
different I/O adapter, so all four must be compared.
