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

## Where it stands (2026-09-02, measured against SBCL 2.2.9 with the
## `rontolisp` executable JAR, interpreter backend only)

Each system was loaded and exercised, and the output diffed against SBCL's
byte for byte.

| system | chapters | verdict |
| --- | --- | --- |
| `simple-database` | 3 | **identical** (only `.todo/041` line-wrapping and `.todo/391` printer noise) |
| `macro-utilities` | 8 | `ppme` dies: `write` rejects `:length` / `:level` / `:gensym` / `:case` (`.todo/041`) |
| `test-framework` | 9 | **identical** |
| `pathnames` | 15 | **identical** under `--feature sbcl` (2026-09-02); without it every call lands in the `#-(or ...)` `(error "list-directory not implemented")` |
| `spam` | 23 | **identical** (re-measured 2026-09-01 after `.todo/621` was fixed -- `.4` and friends read right) |
| `binary-data` | 24 | **identical** |
| `id3v2` | 25 | **identical** (an ID3v2.3 tag with ISO-8859-1 and UCS-2 frames reads back the same) |
| `url-function` | 26 | blocked: needs `net.aserve` (see `.todo/625`) |
| `mp3-database` | 27 | **identical** under `--feature sbcl` (2026-09-02: insert/select/matching/`in`/order-by/distinct/projection/`with-column-values`/`sort-rows`/`map-rows`) -- except `delete-rows`, which dies in `.todo/623` |
| `shoutcast` | 28 | blocked: needs `net.aserve` |
| `mp3-browser` | 29 | blocked: needs `net.aserve` |
| `html` | 30, 31 | **identical** (`emit-html`, `html`, `with-html-output :pretty t`, `define-html-macro`, `:print`/`:format`, attribute escaping) |
| `profiler` (ch.32) | 32 | **identical** |

So nine of the twelve agree with SBCL, and the chapter 15 blocker -- the last
one this item itself owned -- is gone. What is left is `macro-utilities`'
`ppme` (`.todo/041`), `mp3-database`'s `delete-rows` (`.todo/623`) and the
three `net.aserve` chapters (`.todo/625`); every one of them has an owner
elsewhere, so this item is now a standing measurement to re-run as they land.

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

## The gap that was a DESIGN question -- decided and closed (2026-09-02)

Chapter 15's `list-directory` and `file-exists-p` are written as a chain of
`#+sbcl` / `#+openmcl` / `#+allegro` / `#+clisp` branches ending in
`#-(or ...) (error "list-directory not implemented")`. Every primitive the
sbcl branch uses (`directory` over a `:wild` pathname, `probe-file`,
`wild-pathname-p`, `file-namestring`, `pathname-directory`, `make-pathname`)
already answers exactly as SBCL does -- the only blocker was that no `#+` in
that chain names us.

The three options were (a) leave it; (b) let the USER widen the read-time set
from the command line; (c) a curated per-library announcement, like
`trivial-features` does for `:unix`/`:darwin`/`:arm64`. **(b) shipped**:
`--feature NAME` (comma-separated, repeatable) widens whatever set the target
already has and seeds the run-time `*features*` with the same names. It
refuses `rontolisp` and every `rontolisp-*` name -- those describe the build,
which `-o` and the flags beside it decide -- and it reaches only the source the
user brought (the entry file, everything it loads, every `.asd` and component
under it), never the sources rontolisp ships. Mechanics and the two boundaries:
`.kb/reader-features.md`; user documentation: `doc/{en,ja}/reference/data-types.md`.

The lie stays in the user's hands, and so does its cost: the same claim also
selects the branches that really do call SBCL's internals, which is why no
`#+sbcl` is announced by default.

## Reproducing

There is no fixture in the repo -- the tarball is BSD-licensed but not ours to
vendor, and the corpus is a measurement, not a test. Load the systems by hand
in dependency order (`macro-utilities` -> `pathnames` -> `binary-data` ->
`id3v2` -> the rest), and diff against `sbcl --script` on the same driver.
Chapter 25 needs an mp3; a synthetic ID3v2.3 tag written with `struct.pack` is
enough and exercises both the ISO-8859-1 and the UCS-2 frame paths. Chapters 15
and 27 need `--feature sbcl` on the command line, and nothing else.
