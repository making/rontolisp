# Run the _Practical Common Lisp_ book corpus and match SBCL

Difficulty: Medium (the corpus is the MEASUREMENT; each gap it names is its own
item, and most of them already have owners)

`practicals-1.0.3` -- Peter Seibel's own code for _Practical Common Lisp_,
twelve ASDF systems over eleven chapters -- is a corpus of ordinary,
idiomatic, 2005-vintage Common Lisp written by someone who was not thinking
about us. That is what makes it worth pinning: it exercises the reader, the
macro system, CLOS, the condition system, binary streams and `defpackage` the
way a book teaches them, not the way our test suite happens to.

Get it from <http://www.gigamonkeys.com/book/practicals-1.0.3.tar.gz>.

## Where it stands (2026-09-01, measured against SBCL 2.6.5 with the installed
## `rontolisp` native binary, interpreter backend only)

Each system was loaded and exercised, and the output diffed against SBCL's
byte for byte.

| system | chapters | verdict |
| --- | --- | --- |
| `simple-database` | 3 | **identical** (only `.todo/041` line-wrapping and `.todo/391` printer noise) |
| `macro-utilities` | 8 | `ppme` dies: `write` rejects `:length` / `:level` / `:gensym` / `:case` (`.todo/041`) |
| `test-framework` | 9 | **identical** |
| `pathnames` | 15 | blocked at READ time -- `#+(or sbcl cmu lispworks openmcl allegro clisp)`; with the branch forced, every pathname primitive it uses is **identical** |
| `spam` | 23 | **identical** (re-measured 2026-09-01 after `.todo/621` was fixed -- `.4` and friends read right) |
| `binary-data` | 24 | **identical** |
| `id3v2` | 25 | **identical** (an ID3v2.3 tag with ISO-8859-1 and UCS-2 frames reads back the same) |
| `url-function` | 26 | blocked: needs `net.aserve` (see `.todo/625`) |
| `mp3-database` | 27 | **identical** once ch.15 is unblocked -- except `delete-rows`, which dies in `.todo/623` |
| `shoutcast` | 28 | blocked: needs `net.aserve` |
| `mp3-browser` | 29 | blocked: needs `net.aserve` |
| `html` | 30, 31 | **identical** (`emit-html`, `html`, `with-html-output :pretty t`, `define-html-macro`, `:print`/`:format`, attribute escaping) |
| `profiler` (ch.32) | 32 | **identical** |

So ten of the twelve already agree with SBCL, two of them only after a gap
listed below is closed.

## The blockers this corpus found, in the order they bite

1. `.todo/621` -- the reader cannot read `.4`. One literal, and chapter 23 does
   not load. **Fixed 2026-09-01** -- chapter 23 is now byte-identical.
2. `.todo/625` -- a `.asd` that defines its own component class. Three chapters.
3. `.todo/623` -- `delete` / `delete-if` / `nsubstitute` are silent no-ops on a
   vector, and `sort` drops a vector's fill pointer.
4. `.todo/602` -- `nreverse` answers nil for a vector. This is what makes the
   BUNDLED cl-ppcre 1.2.3 (`libraries/`, which SBCL loads unchanged) fail:
   its parser accumulates a literal run into an adjustable string and
   `parse-string` finishes with `(nreverse parse-tree)`, so every
   multi-character literal in a regex becomes nil and `create-scanner` dies with
   `Unknown token NIL in parse-tree` while api.lisp is still loading. The
   quicklisp cl-ppcre works, and chapter 23 gives byte-identical answers on it.
5. `.todo/622` -- a `case` clause whose key designator is the atom `nil`.
6. `.todo/624` -- `read` on a stream is line-oriented.
7. `.todo/041` -- `write`'s keyword arguments, and the pretty printer's right
   margin (every wrapped list in the diffs above is this).
8. `.todo/391` (the printer never drops the package qualifier) and
   `.todo/626` (it never abbreviates `(quote x)` / `(function x)` as
   `'x` / `#'x`, and never `|...|`-escapes a symbol whose name needs it).
   Chapter 8's `ppme` and chapter 3's `macroexpand-1` demo both print this.

## The one gap that is a DESIGN question, not a bug

Chapter 15's `list-directory` and `file-exists-p` are written as a chain of
`#+sbcl` / `#+openmcl` / `#+allegro` / `#+clisp` branches ending in
`#-(or ...) (error "list-directory not implemented")`. Every primitive the
sbcl branch uses (`directory` over a `:wild` pathname, `probe-file`,
`wild-pathname-p`, `file-namestring`, `pathname-directory`, `make-pathname`)
already answers exactly as SBCL does -- forcing the branch makes chapters 15
and 27 pass. The blocker is only that no `#+` in that chain names us.

This is the shape every portable 2000s library has, and it has no clean
answer: claiming `:sbcl` is a lie, and `:rontolisp` is in no upstream's
`#+` chain. Options, none free -- (a) leave it, and accept that a library
whose portability layer predates us falls into its `#-` else-branch;
(b) let the USER widen the read-time set from the command line (a
`--feature sbcl` flag, the cross-file channel the ASDF `:rontolisp-features`
option already provides per system, `.kb/reader-features.md`); (c) a curated
per-library announcement, like the `trivial-features` system already does for
`:unix`/`:darwin`/`:arm64`. (b) is the one that costs nothing and keeps the
lie in the user's hands rather than ours. Decide before adding a fourth
mechanism.

## Reproducing

There is no fixture in the repo -- the tarball is BSD-licensed but not ours to
vendor, and the corpus is a measurement, not a test. Load the systems by hand
in dependency order (`macro-utilities` -> `pathnames` -> `binary-data` ->
`id3v2` -> the rest), and diff against `sbcl --script` on the same driver.
Chapter 25 needs an mp3; a synthetic ID3v2.3 tag written with `struct.pack` is
enough and exercises both the ISO-8859-1 and the UCS-2 frame paths.
