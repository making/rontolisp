# g62: truename's directory form and `directory`'s truenames, like SBCL

Difficulty: Medium

`probe-file` and `truename` resolve symbolic links on every backend (`.kb/pathnames.md`),
but two SBCL behaviors of the truename family are still missing (checked against SBCL 2.6.5
on macOS, 2026-10-23):

- **Directory form.** SBCL answers a directory in directory form whatever the argument's
  spelling: `(probe-file "/tmp")` and `(truename "/tmp")` are `#P"/private/tmp/"`, and with
  `ln` a link to the directory `d`, `(probe-file "ln")` is `#P".../d/"`. Ours answers
  `#P"d"` -- a trailing `/` only when the argument has one. Telling a directory from a file
  needs a per-backend question no primitive asks yet: `%probe-file` says only "exists", and
  probing `"x/"` does not tell them apart on the JVM or the interpreter (`java.io.File` and
  `Path.of` drop the trailing separator). Something like a file-type answer from the stat
  each backend already does (`path_filestat_get` on WASM P1, `stat-at` on the component,
  `Files.isDirectory` on the JVM / `SourceLoader`) would serve `probe-file`, `truename`,
  `uiop:directory-exists-p` and `uiop:directory-pathname-p` callers alike.
- **`directory` answers truenames.** SBCL's `(directory "ln/*.txt")` is
  `(#P".../d/a.txt")` and `(directory "e95-links/*")` lists each target once
  (`a.txt`, `d/`), links resolved and deduplicated; `:resolve-symlinks nil` keeps the link
  names. Ours answers the matched spellings (`ln/a.txt`; `d/`, `dl/`, `in`, `up/`) and
  rejects the `:resolve-symlinks` keyword (`Function expects 1 argument, got 3`).

## Goal

- `probe-file` / `truename` answer a directory in directory form on the interpreter, JVM and
  both WASM backends; `directory` resolves its matches through `%real-path` and
  deduplicates them, and accepts `:resolve-symlinks` (nil keeps the matched spellings).
- On WASM, the same preopen rule as `probe-file`: only links the module can read resolve,
  so every answer stays openable.
- Expectations that pin the current spellings move (ci-spec `directory-listing-and-uiop-walkers`,
  `wild-pathnames`, the `directory*` tests), `.kb/pathnames.md` "Still SBCL gaps" and
  `.kb/directory-listing.md` updated, and the `doc/en` + `doc/ja` pages for `probe-file`,
  `truename` and `directory`.
