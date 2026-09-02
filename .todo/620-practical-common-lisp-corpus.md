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

## Where it stands (2026-09-02, second pass, measured against SBCL 2.2.9.debian
## with the `rontolisp` executable JAR, interpreter backend only)

Each system was loaded and exercised, and the output diffed against SBCL's
byte for byte. The first pass is the row set; this pass re-ran all of it after
`.todo/623`, `.todo/626`, `.todo/391` and `.todo/041`'s printer-variable half
landed.

| system | chapters | verdict |
| --- | --- | --- |
| `simple-database` | 3 | identical except `.todo/041` line wrapping |
| `macro-utilities` | 8 | `ppme` RUNS now (`.todo/041`'s `write` keywords landed). Two residues: `.todo/041` line wrapping, and `.todo/156`'s uppercase-`G` gensym prefix -- ours is lowercase `g`, so `:case :downcase` must `|...|`-escape it (`(let ((\|g3\| 0) ...))` against SBCL's `(let ((g119 0) ...))`) |
| `test-framework` | 9 | identical except `.todo/041` line wrapping |
| `pathnames` | 15 | identical except `.todo/041` line wrapping, under `--feature sbcl` |
| `spam` | 23 | **identical** (needs the quicklisp cl-ppcre; the bundled 1.2.3 is `.todo/602`) |
| `binary-data` | 24 | **identical** |
| `id3v2` | 25 | **identical** (ID3v2.3, ISO-8859-1 and UCS-2 frames, `--feature sbcl`) |
| `url-function` | 26 | blocked: needs `net.aserve` (decided below -- not ours to reach) |
| `mp3-database` | 27 | **identical**, `delete-rows` and `delete-all-rows` included (`.todo/623` landed) |
| `shoutcast` | 28 | blocked: needs `net.aserve` |
| `mp3-browser` | 29 | blocked: needs `net.aserve` |
| `html` | 30, 31 | **identical** |
| `profiler` (ch.32) | 32 | `with-timing` is identical; `compile-timing-data` / `show-timing-data` die on `The function FIFTH is undefined` -- `fifth`..`tenth` do not exist (`first`..`fourth` do), which `.todo/338` already ranks |

Five rows are byte-identical. Of the rest, three are `.todo/041`'s missing
right margin ALONE, one (`macro-utilities`) is that plus `.todo/156`'s gensym
prefix, one (`profiler`) is `.todo/338`'s missing ordinal accessors, and three
are the `net.aserve` chapters, decided below. No row is blocked on anything
this item still owns.

## The blockers this corpus found, in the order they bite

1. `.todo/621` -- the reader cannot read `.4`. **Fixed 2026-09-01.**
2. `.todo/625` -- a `.asd` that defines its own component class. **Fixed
   2026-09-01**, and it was only the FIRST of portableaserve's `.asd` obstacles.
3. `.todo/623` -- `delete` / `delete-if` / `nsubstitute` are silent no-ops on a
   vector, and `sort` drops a vector's fill pointer. **Fixed 2026-09-02** --
   chapter 27's `delete-rows` is byte-identical now.
4. `.todo/602` -- `nreverse` answers nil for a vector. This is what makes the
   BUNDLED cl-ppcre 1.2.3 (`libraries/`, which SBCL loads unchanged) fail:
   its parser accumulates a literal run into an adjustable string and
   `parse-string` finishes with `(nreverse parse-tree)`, so every
   multi-character literal in a regex becomes nil and `create-scanner` dies with
   `Unknown token NIL in parse-tree` while api.lisp is still loading. The
   quicklisp cl-ppcre works, and chapter 23 gives byte-identical answers on it.
5. `.todo/622` -- a `case` clause whose key designator is the atom `nil`.
   **Fixed 2026-09-02.**
6. `.todo/624` -- `read` on a stream is line-oriented. **Fixed 2026-09-02.**
7. `.todo/391` (the printer never dropped the package qualifier) and `.todo/626`
   (it never abbreviated `(quote x)` / `(function x)` as `'x` / `#'x`, and never
   `|...|`-escaped a symbol whose name needs it). **Both fixed 2026-09-02.**
8. `.todo/041` -- `write`'s keyword arguments **(fixed 2026-09-02)** and the
   pretty printer's RIGHT MARGIN (still open: every wrapped list in the diffs
   above is this, and it is the single remaining cause in four of the rows).
9. `.todo/338` -- `fifth` through `tenth` do not exist. Chapter 32's
   `compile-timing-data` sorts `:key #'fifth`, so the chapter's own entry point
   `show-timing-data` cannot run. Already ranked there (~600 ansi-test cases).
10. `.todo/156` -- `gensym`'s default prefix is lowercase `g` where CL specifies
    `G`. Invisible until something prints a gensym with `*print-case*`
    `:downcase`, which is exactly what chapter 8's `ppme` does: a name of `"g3"`
    must be escaped to read back, a name of `"G3"` must not. Already listed
    there as part of the case model.

## The gap that was a DESIGN question -- decided and closed (2026-09-02)

Chapter 15's `list-directory` and `file-exists-p` are written as a chain of
`#+sbcl` / `#+openmcl` / `#+allegro` / `#+clisp` branches ending in
`#-(or ...) (error "list-directory not implemented")`. Every primitive the
sbcl branch uses already answers exactly as SBCL does -- the only blocker was
that no `#+` in that chain names us.

**`--feature NAME` shipped** (comma-separated, repeatable): it widens whatever
set the target already has and seeds the run-time `*features*` with the same
names. It refuses `rontolisp` and every `rontolisp-*` name -- those describe the
build, which `-o` and the flags beside it decide -- and it reaches only the
source the user brought (the entry file, everything it loads, every `.asd` and
component under it), never the sources rontolisp ships. Mechanics and the two
boundaries: `.kb/reader-features.md`; user documentation:
`doc/{en,ja}/reference/data-types.md`.

The lie stays in the user's hands, and so does its cost: the same claim also
selects the branches that really do call SBCL's internals, which is why no
`#+sbcl` is announced by default. Chapters 15, 25 and 27 need it; nothing else
in the corpus does.

## The three `net.aserve` chapters (26, 28, 29) -- decided, do not widen

Measured against `portableaserve-20190813-git`. The full argument and the
numbers are in `.kb/asdf.md`, "The `.asd`-as-data boundary is not what stops
portableaserve". The short form: the `.asd` parser is where we stop, but
widening it buys nothing, because behind it lie `:puri` and `:cl-fad` (absent
even from this machine's quicklisp cache), `#+sbcl :sb-bsd-sockets` and
`#+sbcl :sb-posix` (SBCL contribs, which cannot exist here), and then
`acl-compat/packages.lisp` -- component #1, a plain `(:file ...)`, no custom
class involved -- whose `#+sbcl (:use #:sb-bsd-sockets #:sb-ext #:sb-gray)`
needs those packages to be real. SBCL on this machine cannot load
portableaserve either (`Component :PURI not found, required by acl-compat`).
`acl-compat` is a per-implementation compatibility layer; its rontolisp branch
does not exist and only we could write it. That is `.todo/147`'s shape (shim
an `acl-compat`/`net.aserve` surface over our own HTTP server,
`.kb/http-server.md`), not an ASDF-evaluator question.

## Reproducing

There is no fixture in the repo -- the tarball is BSD-licensed but not ours to
vendor, and the corpus is a measurement, not a test. Load the systems by hand
in dependency order (`macro-utilities` -> `pathnames` -> `binary-data` ->
`id3v2` -> the rest) with `--system-path`, and diff against `sbcl --script` on
the same driver body. Two driver traps, both ours and not the corpus's:
suppress SBCL's compile chatter by binding `*standard-output*` around
`asdf:load-system` only -- chapter 31's `(defvar *html-output* *standard-output*)`
captures whatever is current at LOAD time, so reset it afterwards -- and reach
`combine-results` (ch.9) and `emit-css` (ch.31) by `::`, since neither is
exported. Chapter 25 needs an mp3; a synthetic ID3v2.3 tag written with
`struct.pack` is enough and exercises both the ISO-8859-1 and the UCS-2 frame
paths. Chapters 15, 25 and 27 need `--feature sbcl`, and nothing else.
