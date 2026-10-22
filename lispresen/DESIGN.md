# lispresen design

lispresen turns a slide deck written as S-expressions into one HTML file. It is written in
rontolisp and runs unchanged on the interpreter, the JVM (`.class` / `.jar`), WASM (WASI
Preview 1 module and WASI 0.3 component) and in a browser (a `--no-wasi` reactor). Two
features set it apart: it decides font sizes and placement itself, so every slide stays
readable from the back of the room, and Lisp, Scheme and Clojure code on a slide can be
edited and run during the talk on rontolisp's own WebAssembly runtime.

Decks are written in Japanese. Line breaking, measurement and fonts are designed for
Japanese text with embedded Latin words and code; English-only decks work but are not
what the tuning targets.

## Portability

lispresen is portable Common Lisp first: it runs on SBCL as well, and produces the same
bytes there. Code that needs a rontolisp feature (a literal `load` the compilers splice,
a WASM export, the playground runtime's hooks) sits behind `#+rontolisp`, with a
`#-rontolisp` spelling for other implementations where one is needed; without a
specific reason, code is written in standard CL. SBCL then serves as the reference the
rontolisp backends are checked against.

The one place this costs code today is loading the sources. Standard CL resolves a
relative `load` against the working directory, so the portable spelling is
`(load (merge-pathnames "x.lisp" *load-truename*))`; rontolisp's compilers splice only a
literal path, resolved against the loading file, and leave the portable spelling to a
run-time `load` that cannot find the file. Until the compile path splices the portable
spelling too, `cli.lisp`, `src/all.lisp` and the test file carry both.

## Goals and non-goals

Goals:

- The author writes content, not geometry. No coordinates, no font sizes in the deck.
- Readability is enforced: text never drops below a floor size, Japanese lines break
  between phrases, and a slide that cannot meet the floor is reported, not silently
  shrunk.
- Byte-identical output for the same deck on every backend.
- One HTML file. Without runnable code it has no network dependency at all.

Non-goals: WYSIWYG editing, animations/transitions, free-form positioning, PowerPoint
export. The one build step is a callout shown by a page advance (Callouts, below); it
appears at once, without a transition.

## Deck format

A deck file is a sequence of top-level forms: one `deck` header, then `slide` and
`section` forms.

```lisp
(deck :title "lispresen"
      :author "Toshiaki Maki"
      :date "2026-10-22"
      :theme :light            ; :light | :dark | :high-contrast | (theme ...), below
      :aspect :16x9            ; :16x9 | :4x3
      :runtime :hosted         ; :hosted | "runtime/" | :none
      :callouts :shown         ; :shown | :step, below
      :page-numbers t)

(section "なぜS式で書くのか")

(slide :title "スライドはデータ"
  (ul "差分をコードと同じようにレビューできる"
      (li "構造が明示される"
          (ul "インデントに意味を持たせない"
              "見えないスタイルがない"))
      "生成したデッキもただのリスト")
  (notes "デッキは read するだけで評価しないことに触れる。"))

(slide "一文だけのスライドは画面いっぱいに表示する。")   ; no title -> statement layout

(slide :title "その場で動かす"
  (code :lang "clojure" "
    (defn fib [n]
      (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
    (map fib (range 10))"))

(slide :title "全体像"
  (image "img/arch.png" :alt "リーダからHTMLまでのパイプライン" :caption "四つの段階"))

(slide :title "インタプリタとコンパイラ" :layout :columns
  (column (p (b "インタプリタ")) (ul "起動が速い" "eval が完全"))
  (column (p (b "コンパイラ")) (ul "実行が速い" "成果物が一つ")))

(slide :title "バックエンドの比較"
  (table :header ("バックエンド" "起動" "成果物" "サイズ (KB)")
         :caption "hello world を出力するプログラム"
    ("インタプリタ" "速い" "なし" "--")
    ("JVM" "普通" (tt ".class") "12")
    ("WASM" "速い" (tt ".wasm") "1,840")))
```

Elements:

| Kind | Forms |
|------|-------|
| Top-level | `deck`, `section`, `slide` (`:title`, `:layout`, `:class`), `define` (below) |
| Block | bare string (= `p`), `p`, `ul`, `ol`, `li`, `code` (`:lang`, and for runnable code `:run`, `:timeout`, `:fresh`, `:stdin`), `image`, `quote` (`:cite`), `column`, `table`, `notes`, `say` around one block (`:at`, `:step`) |
| Inline | string, `b`, `i`, `tt`, `link` (`(link "url" ...)`), `mark`, `say` (`(say "comment" inline...)`, `:step`) |

Rules:

- **The deck is data and is never evaluated.** Compiled `eval` cannot see `defmacro`,
  backquote or CLOS definitions (`doc/en/guides/eval-limitations.md`), so evaluating the
  deck would make the language a deck may use differ per backend. Reading only is
  identical everywhere and makes a deck file safe to render from an untrusted source.
- Tags are matched by `symbol-name`, not by symbol identity, so the package the runtime
  reader interns into does not matter. Options are keyword/value pairs at the start of
  a form; the first argument that is not a keyword starts the content.
- The compiled runtime reader does not support backquote, `#.`, `#+`/`#-`
  (`doc/en/guides/read-load-limitations.md`); the deck format needs none of them. The
  interpreter's and SBCL's readers evaluate `#.`, so the deck is read under `*read-eval*`
  nil, which every backend refuses alike. A form holding a dotted list or more conses
  than characters (shared or circular structure from `#n=`, which SBCL reads) is reported
  and skipped, so the parser and the template expansion only ever see proper trees.
  Characters are code points on every backend (`.kb/string-index-cost.md`), so Japanese
  strings measure and slice identically everywhere.
- Top-level forms instead of one enclosing `(deck ...)`: each form is read separately,
  and the stop index `read-from-string` returns gives its start offset, hence a line
  number for every diagnostic. One enclosing form would leave only "slide 7".
- `code` content is dedented (the common leading whitespace removed, a leading blank
  line dropped) so it can be indented with the surrounding source. A `"` inside code is
  written `\"`.
- A slide with `column`s holds only columns; each column holds blocks, not columns.

### Templates

```lisp
(define side (head &rest points)
  (column (p (b head)) (ul points)))

(slide :title "インタプリタとコンパイラ"
  (side "インタプリタ" "起動が速い" "eval が完全")
  (side "コンパイラ" "実行が速い" "成果物が一つ"))
```

A top-level `(define name (params...) body...)` declares a template; a use `(name
arg...)` anywhere in a later top-level form is replaced by the body with each parameter
replaced by its argument. This is substitution on the read forms (`template.lisp`), not
Lisp macros, for the reason above: nothing is evaluated, so a template cannot compute.

- The parameters are symbols, the last optionally after `&rest`, whose arguments are
  spliced into the list where it appears. A body of several forms is spliced into the
  list holding the use, so one use can stand for several slides, blocks or inlines.
- Names match by `symbol-name`, like tags. Keywords are never substituted, so a
  parameter `title` leaves `:title` alone. A template or parameter cannot take an
  element's name (`slide`, `p`, `b`, ...): a parameter `p` would replace the tag too.
- Uses expand after substitution, so arguments may hold uses, and a body may use a
  template defined after it but before the use. A use with the wrong number of
  arguments is reported and dropped.
- Expansion is bounded, since a deck may come from an untrusted source: uses nest at
  most 32 deep (a template using itself is reported), and a deck's templates produce at
  most 200,000 list elements (a few uses can otherwise double a form each level). A
  top-level form that hits a bound is reported once and dropped.
- Diagnostics inside an expansion carry the line of the top-level form holding the use.

## Pipeline

```
source string
  -> read      top-level forms + start offsets            (reader.lisp)
  -> expand    template uses replaced by their bodies      (template.lisp)
  -> parse     validated model: deck / slide / block / inline structs,
               diagnostics for malformed or unknown forms  (model.lisp)
  -> embed     image sources as data: URIs, with --embed   (embed.lisp)
  -> segment   Japanese phrase boundaries                  (phrase.lisp)
  -> layout    layout selection + fit solver -> per-slide geometry and sizes
                                                           (layout.lisp, metrics.lisp)
  -> lint      readability diagnostics                     (lint.lisp)
  -> emit      HTML + CSS + player JS as one string        (html.lisp, player.lisp)
```

The core is a pure function from a string to a string:

```lisp
(lispresen:render source &key theme runtime embed) ; => (values html diagnostics)
```

It does no I/O, so every host (CLI, browser, a Java caller via `rontolisp:jvm-export`)
is a thin shell over it. `embed` is the one place a host reaches in: a function from an
image path to the file's bytes, which the CLI passes under `--embed`. A diagnostic is
`(severity line slide-number message)`.
Diagnostic messages are in English.

**All layout arithmetic is integer.** Widths are in milli-em, sizes and coordinates in
canvas pixels. Float printing is the one place backends could plausibly differ, and
integer math removes it from the output by construction. The only float computation, the
WCAG contrast ratio, feeds a comparison and is printed as a rounded integer.

## Layout model

### Canvas

Every slide is laid out on a fixed logical canvas, 1920 x 1080 for 16:9 (1440 x 1080 for
4:3). The player scales the canvas to the viewport with a CSS transform and letterboxes
it. Because the canvas is fixed, a font size chosen at build time means the same
fraction of the screen height on any projector, which is what makes static fitting
meaningful.

Safe area: 112 px left/right, 80 px top, 72 px bottom (footer and page number live in the
bottom band). The body box is 1696 px wide.

### Type scale

Sizes in canvas px; the pt column is the equivalent on a 7.5 in (540 pt) high slide,
the unit presentation guidelines use. The last column is full-width characters per line
in the 1696 px body box.

| Role | Default | Steps down to | Floor (warning) | Hard floor (error) | Line height | Chars/line |
|------|---------|---------------|-----------------|--------------------|-------------|------------|
| Title | 80 (40 pt) | 72, 64 | 64 | 56 (28 pt) | 1.3 | 21 |
| Body | 56 (28 pt) | 52, 48, 44 | 48 (24 pt) | 40 (20 pt) | 1.5 | 30 |
| Code | 44 | 40, 36, 32 | 32 | 28 | 1.4 | 64 (half-width) |
| Table | 48 | 44, 40, 36 | 40 (20 pt) | 36 (18 pt) | 1.4 | -- |
| Statement | 120 | 104, 88, 72 | -- | 56 | 1.35 | 14 |
| Callout | 44 | 40, 36 | 40 (20 pt) | 36 (18 pt) | 1.4 | -- |

A callout is never larger than the slide's body text. Sizes only step down from the
default, never up. A sparse slide keeps the default size
rather than inflating, so text size stays consistent across the deck; only the statement,
section and title layouts have their own larger scale. Line heights are those of
Japanese body text (1.5 and up); the Latin-oriented 1.2-1.4 crowds kanji.

### Fonts

System fonts only, so the file needs no network: `"Hiragino Sans", "Noto Sans JP",
"BIZ UDGothic", "Yu Gothic", sans-serif`, and `Menlo, Consolas, "BIZ UDGothic",
monospace` for code. Every Japanese face in the stack sets kana and kanji full-width,
which is what makes the width model exact for Japanese. For that reason proportional
Japanese faces (BIZ UDPGothic, Meiryo UI) are not in the stack, and neither `palt` nor
`text-autospace` is turned on: each changes widths in a way the model would have to
reproduce per font.

### Text measurement

Width of a code point, in milli-em:

| Characters | Width |
|------------|-------|
| Kanji, kana, full-width punctuation and forms, CJK symbols | 1000, exact |
| Printable ASCII | per character, measured on Hiragino Sans W3 (bold: W6), plus 2% |
| Monospace (`code`, `tt`) | 602 (Menlo), plus 2%; full-width 1000 |
| Other proportional characters | 800 (bold 840), plus 2% |

Japanese text is exact under the fonts above. Latin glyphs differ per face, and
Hiragino's are the widest of the stack (its digits are 657, against Noto Sans JP's
572), so the table measured on it over-estimates the other faces rather than under. A
first table of character classes (digits 580, lower case 550) under-estimated Hiragino
and wrapped a table cell the model had set on one line; the per-character table
replaced it. The 2% covers sub-pixel rounding; it applies to proportional characters
only, since a margin on full-width ones puts one character fewer on a line than the
browser does.

Measured with `tools/calibrate.lisp` in Chrome on 2026-10-22 (84 paragraphs: Japanese,
Latin, mixed, bold, inline code, brackets; 40-120 px; 700-1696 px wide): the model never
predicted fewer lines than Chrome rendered, and predicted one more in 9. Safari 26.3, same
day and page: never fewer, one more in 1.

### Line breaking

A Japanese line that breaks inside a word (`プレゼ` / `ンテーション`) is harder to read
aloud from a slide than a line that is slightly shorter, so lispresen breaks between
phrases (文節), not between arbitrary characters.

- Phrase boundaries come from a port of BudouX's Japanese model (Apache-2.0; the weights
  are generated into `src/budoux-ja.lisp` with the license notice). BudouX's scoring is
  a sum of weights over the 1-3 character windows around a position, which is integer
  arithmetic and a few dozen lines of Lisp.
- lispresen emits `<wbr>` at each boundary and styles text with `word-break: keep-all;
  overflow-wrap: anywhere`. The browser then breaks only where the model breaks, in
  every browser, rather than where its own segmenter (`word-break: auto-phrase`, Chrome
  only) would.
- Under `keep-all` the browser still breaks after closing punctuation (`、。」』）！？`)
  and before an opening bracket, which are not letters to it; the model takes those
  opportunities too, or it predicts lines the browser does not need.
- The model wraps greedily phrase by phrase; Latin words break at spaces. A phrase wider
  than the line falls back to character breaks with kinsoku (no line starts with
  `、。，．）」』】ー・！？` or a small kana, no line ends with `（「『【`), the same rule
  the browser applies under `overflow-wrap: anywhere; line-break: strict`.
- Titles use `text-wrap: balance`, which evens line widths without changing the line
  count the model computed.

### Layout selection

`:layout` overrides; otherwise the slide's shape decides:

| Shape | Layout |
|-------|--------|
| Deck header (generated first slide) | `title` |
| `section` | `section` (large, centered) |
| No title, one short paragraph, no callout | `statement` (centered, statement scale) |
| Title + one `image` | `image` (image fills the body box, `object-fit: contain`) |
| Title + one `code` | `code` |
| Title + one `quote` | `quote` |
| Title + one `table` | `table` |
| Title + `column`s | `columns` |
| Anything else | `standard` (title on top, blocks stacked) |

Placement rules: the title sits at the same position on every content slide, and the
body is top-aligned under it, so the audience's eye does not search for the start of
each slide. Statement, section and title layouts are centered. Images need no intrinsic
size: lispresen computes the box, the browser fits the image inside it.

### Fit solver

For each slide:

1. Fit the title: the largest title step at which it takes at most 2 lines.
2. The body box is what remains of the safe area below the title and a fixed gap.
3. For each region (one for `standard`, one per column for `columns`), compute the
   content height at each body step: lines x line height, plus block gaps, list
   indentation reducing the available width per nesting level, code sized by both its
   longest line and its line count (runnable code too: its output is an overlay,
   below), a table by the column widths and row heights below. Height is monotonic in
   size, so the chosen size is the largest step that fits.
4. Columns share the smallest size any of them needs, and one code size, so
   side-by-side text matches. Each column is `(1696 - 80 (n - 1)) / n` px wide; the 80 px
   gap is fixed so the width is known before the size is.
5. If even the hard floor overflows, the slide is emitted at the hard floor, marked, and
   reported as an error: lispresen does not make text unreadable to make it fit.

A `quote` is its text at the body size, indented by the list indent (an accent rule in
it), and its `:cite` below as `— cite`; the `quote` layout places it as `standard` does.

The result is written per slide as CSS custom properties (`--title`, `--body`,
`--code`, `--table`, `--body-box-height`), so the stylesheet stays fixed and every
computed number is visible in the output.

### Tables

```lisp
(table :header ("項目" "前年" "今年") :align (:left :right :right) :caption "..."
       :row-header t
  ("行1" "8" "10")
  ("行2" (cell :colspan 2 "結合したセル")))
```

- A row is a list of cells. A cell is a string, a number, an inline form (`b`, `tt`,
  ...), or `(cell [:colspan n] inline...)` when it needs options or mixed inlines.
  Cells are inline content only, so inline code (`tt`) is allowed and a `code` block or
  a list is not: neither is readable at slide distance inside a cell. There is no
  `rowspan`; a slide table that needs one is better split.
- Inline code in a cell is set in the monospace face at the table size and measured with
  the monospace widths. It breaks only at its spaces, so an identifier stays on one line
  and counts as one unbreakable unit for the column's minimum width; one wider than the
  table can give falls back to character breaks, as an over-long phrase does.
- `:header` is the column header row (`<th scope="col">`), `:row-header t` makes the
  first column `<th scope="row">`, `:caption` becomes `<caption>`.
- Alignment: a column whose body cells all read as numbers (`1,840`, `12.5%`, `-3`,
  with `--` or empty as a placeholder) is right-aligned with `tabular-nums`; other
  columns are left-aligned. `:align` overrides per column.

Column widths are decided by lispresen, not by the browser, for the same reason line
breaks are: the browser's automatic table layout depends on the font it has, and the
solver must know each row's height. A table spans its region (the body box, or its
column), like the text around it. Per column, the minimum width is the widest
unbreakable unit (phrase or Latin word) in any of its cells and the maximum is the widest
cell set on one line, both plus horizontal padding. Then:

1. If the maxima sum to at most the region width, no cell wraps, and the table fills
   it: every column gets at least its maximum, and the remaining width raises
   the narrowest columns first to one common width, as far as the remainder reaches.
   The columns end up as even as their content allows, so numbers right-aligned in them
   stand at regular intervals. A first version kept each column at its maximum: a
   short table then sat in the left half of the slide with uneven gaps between its
   columns.
2. Otherwise each column gets its minimum plus a share of the remaining width
   proportional to its maximum minus its minimum (the CSS automatic layout rule), so a
   column of long text wraps before a column of short labels does.
3. If the minima alone exceed the region width, the table steps down a size; at the hard floor
   that is an error.

A row is as tall as its tallest cell (lines x line height plus vertical padding), and
the table height (rows, header, caption) enters the fit solver like any block. The
widths are emitted as `<colgroup>` pixel widths under `table-layout: fixed`, and cells
use the same `<wbr>` phrase breaks as body text, so the browser wraps each cell where
the model did. The header row is bold on a tinted background, and each body row has a
1 px rule drawn as an inset shadow, which takes no layout space.

### Callouts

```lisp
(li "日本語は " (say "BudouX のモデルで区切る" (mark "文節")) " で改行する")

(say "Run で 6765 が出る" :at "(fib 20)" :step t
  (code :lang "lisp" "..."))

(say "四つの段階を順に通る" (image "img/arch.png" :alt "..."))
```

A callout is a short comment in a speech bubble whose tail points at what it is about,
its anchor. The deck names the anchor, never a position; lispresen places the bubble.

- Inline, `(say "comment" inline...)` wraps its anchor: a phrase, a whole list item, a
  table cell's text. It goes wherever inline content goes, inside `b` or `mark` too.
- Around a block, `(say "comment" block)` points at one `p`, `ul`, `ol`, `code`, `image`,
  `table` or `quote` as a whole. In block position a `say` whose content is one such form
  is this kind; anything else is a paragraph holding an inline callout.
- `:at "text"` on a callout around `code` points at the first occurrence of the text,
  which must lie within one line. Code is one string, so the anchor is named by its text
  rather than wrapped; a line number would go stale when a line is added above.
- The comment is a string. Titles, captions and notes take no callouts, and a slide with
  one never takes the statement layout: its centered, balanced lines are not where the
  model puts them.

**Room.** A bubble is an overlay, like the runnable-code output: it takes no room in the
flow, so a comment never re-wraps the text it is about. It is placed in the free space of
the geometry the fit solver computed, in canvas px:

- a text line is a rectangle as wide as its text (the model's widths, trailing spaces
  excluded) and as tall as its line height, offset by its alignment (right or centered
  in table cells, centered in figure captions); a list item's first line reaches left
  over its marker;
- a code line is its monospace width inside the panel's padding, and runnable code adds
  its Run and Reset buttons (320 x 48 px, 12 px inside the top right corner);
- an image is its whole picture area, since its own size is not known at build time;
- a bubble already placed, with its tail.

Backgrounds and rules are not obstacles: a bubble may sit over the empty part of a code
panel or a table. An inline anchor is the part of each line its text takes, one fragment
per line; a block anchor is the block's box (code, table, image) or the rectangle around
its lines (text). The width model over-estimates Latin text, so a static tail can land a
few px to the right of where the browser draws the anchor; the runtime check corrects it.

**The bubble.** Text at the callout size, line height 1.4, padding s/4 by s/2, a 3 px
accent border with a 16 px radius on the `head-bg` color, at most half the content width
wide. The tail is 32 px long on a 36 px base and stops 4 px short of the anchor, so the
bubble's edge is 36 px from it, and its middle stays 34 px (the radius and half the base)
from a corner.

**Candidates**, tried in order:

1. right of the anchor's last fragment, centered on it vertically, then running down
   from it, then up;
2. below the last fragment, centered on it, then running right, then left;
3. above the first fragment, likewise;
4. left of the first fragment, as right.

Each is moved into the body box along its side as long as the tail stays on that side. A
candidate fails when the bubble leaves the body box, when the bubble grown by 12 px on
every side meets an obstacle, or when the box the tail spans (the 36 px reach by its
base) meets one. The anchor's own lines are obstacles, so a bubble never covers its
anchor. Right comes first because it covers no following line: it holds when the anchor
ends its line (a list item, the last phrase of a line) and the lines beside it are
shorter. Below suits the last line of the content, above a line after a short one, and
left a right-aligned table cell, whose neighbors stand at the far ends of their columns.

**Images.** An image fills the room the other blocks leave, so there is no free space
around it. A callout on one reserves a strip on the figure's right, as wide as the bubble
plus its reach, and sets the image against the strip (`align-items: flex-end`). The
middle of the image box's right edge is then on the picture whatever its aspect ratio
(narrower: the picture fills the box's height at its right edge; wider: it fills the
width at mid height), and the bubble sits in the strip, its tail at that point.

**Sizes.** Callouts are placed in source order, each an obstacle for the next, at one size
per slide: the largest callout step at which every one finds room. When none does, the
body steps down too (shorter lines leave more room), down to the hard floor. A callout
that has no room even then is emitted at its centered position below the anchor at the
smallest size, outlined like an overflow, and reported as an error.

**Visibility.** A callout is shown with the slide, unless it is a step: `:step t`, or every
callout of a deck with `:callouts :step` (`:step nil` opts one out). The player hides the
step bubbles of a slide when it is entered forward; each page advance (→, ↓, space,
PageDown, Enter, a swipe) shows the next in source order before the next one moves to
the next slide, and going back hides the last one shown before it moves to the previous
slide, which it enters with all its bubbles shown. Every step bubble is placed when the
slide is laid out, so showing one moves nothing. The presenter window and the audience
window share the step with the slide number; the presenter's views show a hidden bubble
faded. The overview, print and the page without JavaScript show every bubble. While a
code block of the slide is edited, its bubbles are hidden, since the edited text may run
under them, and placed again on every change.

### Runtime check

The static estimate cannot see the font the presenting machine actually has. After
`document.fonts.ready`, the player measures each slide: a title taller than the room above
the body steps the title down; a region (the body, or each column) whose content is taller
than it steps the slide's body, code and table sizes down together; a centered text taller
than the safe area steps the statement scale down. Never below the hard floor; a region
still overflowing there is marked like a build-time overflow. Each step is logged with
`console.warn`, and opening the deck with `?check` lists them in an overlay and outlines
the corrected slides. The player derives the line heights, gaps and padding of a step with
the formulas of `layout.lisp`, multiplying before dividing so they round as the integer
arithmetic does; the type scale itself comes from the JSON the emitter writes
(`<script type="application/json" id="lp">`). After the size
corrections, the player places every callout of the slide again, with the candidates
above, on measured geometry: the anchor's client rects (the `:at` text through a
`Range`), the client rects of every text node, the picture areas and the Run and Reset
buttons; the bubble keeps its size. So the tail lands on the anchor the browser drew. A
callout that changes side is listed like a step, and one that finds no room keeps its
static place and is outlined. JavaScript is a correction on top of the static result,
not a replacement for it, so the deck still lays out correctly with JavaScript disabled
and when printed.

## Runnable code

A `code` block whose `:lang` is `"lisp"`, `"scheme"` or `"clojure"` is runnable unless it
says `:run nil` or the deck says `:runtime :none`.

### Runtime

The runtime is the browser playground's build (`rontoplayground.js` and its `.wasm`,
about 35 MB, `web/README.md`): rontolisp's full interpreter with all three front ends,
compiled to WebAssembly by GraalVM Web Image, the same one the documentation site's Run
cells load (`doc/assets/docs.js`). A module compiled by rontolisp itself cannot take its
place: its runtime `eval` has no `defmacro` and no Scheme or Clojure reader.

Where it is loaded from is the deck's `:runtime` (or `--runtime`):

| Value | Source | Needs |
|-------|--------|-------|
| `:hosted` (default) | `https://making.github.io/rontolisp/` | network; tracks `develop` |
| `"dir/"` | a directory beside the deck holding the two runtime files | serving the deck over HTTP (`jwebserver`), since the runtime fetches its `.wasm` and `file://` cannot |
| `:none` | -- | code is static |

The hosted files are served with `Access-Control-Allow-Origin: *` (checked 2026-10-22),
which the cross-origin load below requires. Conference networks are unreliable, so the
README documents the offline setup (`./mvnw -Pweb -DskipTests package`, copy
`web/dist/rontoplayground.js*` into `runtime/`) as the recommended way to present.

### Execution

- The runtime runs in a Web Worker, as in the playground (`web/ronto-worker.js`), so a
  long computation does not freeze slide navigation, and a runaway program can be
  stopped: after `:timeout` (default 10 s) the player terminates the worker and starts a
  new one, and the output says that earlier definitions are gone. A Worker cannot be
  constructed from a cross-origin URL, so the player builds it from a Blob. That worker
  `fetch`es `rontoplayground.js`, imports it from a Blob URL and sets `config.wasm_path`
  to the absolute `.wasm` URL. `importScripts` of the hosted URL itself fails in Chrome
  with a bare `NetworkError` (measured 2026-10-22, Chrome 155; the cross-origin script's
  error is muted, so the cause is not visible), while the same text imported from a Blob
  URL runs; the `fetch` needs the CORS header the hosted files carry.
- One run at a time: Run on another block while one runs queues it, and a run started
  before the runtime is ready waits for it.
- When the deck contains runnable code, the worker starts loading as soon as the deck
  opens. Downloading 35 MB on the first Run click in the middle of a talk would stall
  it. The load state shows in the presenter window and, on the slides, only as the Run
  button's label (Loading / Run / Failed), so it takes no room on the slide; a run
  pressed while loading waits, and Run after a failure loads again.
- Each language has one persistent environment (`rontoSetLanguage`, then `rontoEval`),
  so a function defined on one slide can be called on a later one, as the
  documentation site's Lisp cells share one interpreter. Verified 2026-10-22 on the
  hosted runtime for all three languages: a definition in one `rontoEval` is callable in
  the next. `:fresh t` runs the block as a
  whole program on a new interpreter instead (`rontoRunProgram`, with `:stdin "..."` as
  its standard input). The presenter window has a runtime reset.
- What is shown is what the playground shows: the printed output, then the echoed
  values; a result starting with `ERROR:` is shown in the error style.

### Editing and output

- Code is editable in place: click it, or press `E` on the slide. The editor is a
  transparent `textarea` over the highlighted `pre`, sharing its font, padding and line
  height, so the caret and selection land on the highlighted text, which is re-rendered on
  every input. While editing, keys go to the editor instead of navigation,
  `Ctrl/Cmd+Enter` runs, `Tab` inserts two spaces, `Esc` leaves (see Full screen for
  `Esc` in full screen), and Reset restores the deck's text and closes the output. Run
  and Reset sit in the block's top-right corner; the player adds them, so print and the
  no-script view show none.
- The output takes no room on the slide. A first version reserved an output box below
  the code in the fit solver; on a full slide that stepped the code down a size or two,
  which costs more legibility than an output shown only while it matters. The output is
  a panel over the slide, at the code size, as wide as the body: directly below the code
  when at least three lines fit there, otherwise anchored to the bottom of the safe area
  (at most 45% of the canvas high) over whatever it covers. It opens when a run starts
  and closes with its × button or `Esc` (when not editing); it stays open across
  navigation. Output longer than the panel scrolls: by wheel or drag on an always-visible
  scrollbar (macOS hides overlay scrollbars, which would hide that there is more), and
  by `↑` / `↓`, three lines at a time, which move between slides again once the panel
  is at its end; `←` / `→`, space and the clicker keys always move between slides. The panel belongs to the slide, so it
  scales with the canvas and is cloned into the presenter window; print shows no output.
- Runs execute in the audience window. Edits and outputs are broadcast to the presenter
  window over the same `BroadcastChannel` as navigation, and Run pressed in the
  presenter window is forwarded.
- Highlighting uses one tokenizer for the three languages (`highlight.lisp`): comments
  (`;`, `#|...|#`), strings and characters (`"..."`, `#\x`, Clojure `\x`), keywords
  (`:name`), numbers and constants (`nil`, `t`, `true`, `false`, `#t`, `#f`), and the head
  of a defining or binding form (`def...`, `let`, `lambda`, `fn`, `if`, ...). `[]`, `{}`
  and `#{}` are delimiters like `()`. It runs at build time on every `lisp` / `scheme` /
  `clojure` block, runnable or not; the editor re-highlights on input with the same rules
  ported to the player script, which produce the same markup (checked on a sample of all
  three languages, 2026-10-22). The colors are theme variables.

## Readability lint

| Severity | Condition |
|----------|-----------|
| error | malformed or unknown form; a malformed template or use, or one past the expansion bounds; overflow at the hard floor; a callout without room at the smallest sizes, or whose `:at` text is not on a line of its code; a table row whose cell count (with `:colspan`) differs from the header's or the first row's; under `--embed`, an image that cannot be read or has an unknown extension |
| warning | a size below its warning floor; more than 6 items in one list; list nesting deeper than 2; an item longer than 3 lines; a title longer than 2 lines; code longer than 15 lines; a table with more than 7 body rows or 5 columns; full-width alphanumerics (`ＡＢＣ１２３`); `image` without `:alt`; under `--embed`, an image given as a URL; a theme text color below 4.5:1 against its background; callout text below its warning floor; a callout longer than 2 lines |

Diagnostics print as `talk.lisp:42: slide 7 "その場で動かす": warning: body text at
44px (floor 48px); consider splitting the slide`.

The contrast lint checks every text color against the background it is set on: `fg`,
`muted` and `accent` on `bg`, `fg` on `head-bg` (table headers and callouts) and `mark`, and `fg`, `muted` and the four
highlight colors on `code-bg`. Every one of them carries body-size text somewhere (the
accent colors links, the muted color captions and code comments), so each needs WCAG AA
for normal text, 4.5:1; a separate 3:1 for large titles would only matter for a title
color, and titles use `fg`.

## Themes

A theme is twelve colors: `bg`, `fg`, `muted`, `accent`, `code-bg`, `head-bg`, `rule`,
`mark` and the highlight colors `hl-str`, `hl-kw`, `hl-def`, `hl-const`, written as
custom properties of the same names. `:theme` names a built-in theme (`:light`, `:dark`,
`:high-contrast`) or overrides one:

```lisp
(deck :theme (theme :base :dark :accent "#ff9f43" :mark "#4a3b00"))
```

`:base` defaults to light; a color is `#rgb` or `#rrggbb`. `--theme` on the command line
names a built-in theme and replaces the deck's choice.

## HTML output

One file containing:

- `<html lang="ja">` and the slides as `<section class="slide layout-standard"
  style="--body:52px ...">` elements with semantic markup (`h1`, `ul`, `pre><code`,
  `figure`, `blockquote`).
- The theme stylesheet: colors as custom properties (`--fg`, `--bg`, `--accent`,
  `--muted`), font stacks, the layout classes, and print CSS (`@page` sized to the
  canvas, one slide per page) for PDF export through the browser's print dialog.
- The player script: canvas scaling, navigation (arrow keys, space, PageUp/PageDown for
  clickers, Home/End, swipe, `#N` in the URL), overview grid (`O`), full screen (`F`,
  below),
  the runtime check, a presenter window (`P`, below), and the runnable-code worker,
  included only when the deck has runnable code.
- Callouts as `<div class="say" role="note" data-side="below" style="left:...;top:...">`
  after the body, each named by its anchor's `aria-describedby` (an inline anchor is a
  `<span>`), which is also how the player finds the anchor. The tail is two `clip-path`
  triangles of the border and the fill color, and the bubble prints with its colors
  (`print-color-adjust: exact`).
- Speaker notes in `<aside class="notes">`, hidden in the audience view.

### Overview and presenter window

`O` shows every slide as a grid of scaled copies; arrow keys move the selection, `Enter`
or a click opens a slide, `O` or `Esc` closes it. The copies are made when it opens, so
edited code and outputs appear as they are.

`P` opens the presenter window: the same file with `?presenter`, showing the current slide,
the next one, the notes, a timer (click to reset), Start, and with runnable code the runtime
state and a runtime reset. Navigation in either window moves the other; the windows talk
over a `BroadcastChannel` named after the file's path. The presenter's copy of the current
slide shows the audience window's edits and outputs, and its Run and Reset act in the
audience window, where the code runs.

### Full screen

Presenting is done in full screen; the windowed view is for preparation.

- `F`, double-click on the slide, or the full-screen button of the toolbar (shown on
  mouse movement, hidden after 2 s) calls `requestFullscreen` on the stage element. The
  Fullscreen API needs a user gesture, so the deck cannot open in full screen by itself.
- The canvas scale is recomputed on `resize` and `fullscreenchange`, so entering full
  screen changes only the scale, never the layout the solver computed. The letterbox
  around a canvas whose aspect differs from the screen is the theme background.
- In full screen the cursor and toolbar hide after 2 s without mouse movement.
- `Esc` exits full screen in every browser, and a page cannot stop that, which conflicts
  with `Esc` leaving the code editor. Where the Keyboard Lock API exists (Chromium), the
  player locks `Escape` while in full screen, so `Esc` leaves the editor and a held `Esc`
  exits full screen (the browser's own behavior under the lock). Elsewhere `Esc` does
  both; clicking outside the editor also leaves it.
- From the presenter window, Start puts the audience window in full screen on the other
  display through the Window Management API (`getScreenDetails`,
  `requestFullscreen({screen})`, Chromium). The click is in the presenter window, and full
  screen needs a user gesture in the window that enters it, so the presenter delegates its
  activation with `postMessage(..., {targetOrigin, delegate: 'fullscreen'})` (Capability
  Delegation). Delegation is refused for `targetOrigin: '*'`, so it needs a real origin:
  a deck opened from `file://` gets no delegation, and `F` is pressed in
  the audience window. The first Start asks for the window-management permission and
  does not wait for the answer, so it goes full screen on the audience window's own
  display; once the permission is granted, Start picks the other display. Without the
  API the audience window is moved to the projector by hand. Measured 2026-10-22 in
  Chrome 155: the delegated message arrives with `navigator.userActivation.isActive`
  true; full screen itself is refused under browser automation (as `F` is there), so it
  is checked by hand.

Images are referenced by their relative path, so the output is written next to the deck
by default and the paths stay valid. `--embed` inlines them as `data:` URIs instead, so
the HTML file stands alone:

- A relative path resolves against the deck's directory (the working directory when the
  deck is standard input). The CLI reads the bytes with `:element-type '(unsigned-byte
  8)`, `file-length` and `read-sequence`, which behave alike on every backend (checked
  2026-10-22 on the interpreter, JVM, both WASM backends and SBCL, with bytes 0, 128 and
  255); the WASM backends need the directory preopened (`wasmtime run --dir .`).
- The MIME type comes from the extension: `png`, `jpg` / `jpeg`, `gif`, `webp`, `avif`,
  `svg`. Another extension, or a file that cannot be read, is an error and leaves the
  path in place.
- The base64 is computed in `embed.lisp` with integer arithmetic, not by a host library,
  so every backend writes the same characters.
- A source with a URL scheme stays as it is; anything but `data:` gets a warning, since
  the file then does not stand alone.

## Hosts

| Host | Entry | Build and run |
|------|-------|---------------|
| Interpreter | `cli.lisp` | `rontolisp lispresen/cli.lisp -- talk.lisp` |
| JVM | `cli.lisp` | `rontolisp lispresen/cli.lisp -o lispresen.jar && java -jar lispresen.jar talk.lisp` |
| WASM (WASI P1) | `cli.lisp` | `rontolisp lispresen/cli.lisp -o lispresen.wasm && wasmtime run --dir . lispresen.wasm talk.lisp` |
| WASM component | `cli.lisp` | `rontolisp lispresen/cli.lisp --component -o lispresen.wasm && wasmtime run --dir . lispresen.wasm talk.lisp` |
| Native executable | `cli.lisp` | `rontolisp lispresen/cli.lisp --native -o lispresen && ./lispresen talk.lisp` |
| SBCL | `cli.lisp` | `sbcl --script lispresen/cli.lisp talk.lisp` |
| Browser | `web/lispresen-web.lisp` | `web/build.sh` (`--no-wasi --optimize`): a reactor exporting `render` (`:params '(:string) :returns :string`) |

CLI:

```
lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|hosted|none] [--embed] [--check] [--strict]
```

- `DECK` and `OUT` accept `-` for stdin / stdout. `OUT` defaults to `DECK` with `.html`.
- `--embed` inlines images (HTML output, above).
- `--check` lints without writing output. `--strict` makes warnings fail.
- Exit status: 0 success, 1 errors (or warnings under `--strict`), 2 usage.
- Diagnostics go to `*error-output*`.

The browser host is an editor page: the deck source on the left, the rendered deck in an
`iframe srcdoc` on the right, re-rendered on a short debounce, diagnostics listed below,
and a download button for the HTML. The reactor returns the HTML and the diagnostics in
one string (a fixed separator line), since a reactor export returns a single value. The
lispresen reactor (887 KB) renders; the preview's runnable code loads the playground
runtime like any other deck. The preview keeps its slide across re-renders through
`window.lispresen` (`show`, `current`), which the player defines. The reactor's
arguments and results are allocated by a bump allocator that is never reset (about 15 KB
per render of the golden deck), so the page instantiates the compiled module again once
its memory passes 64 MB. `lispresen.wasm` is built, not committed.

## Directory layout

```
lispresen/
  DESIGN.md              this file
  README.md              usage, offline runtime setup
  check.sh               cross-backend golden check + unit tests
  cli.lisp               CLI host: arguments, file I/O, exit status
  src/
    all.lisp             loads the sources below in order
    package.lisp         defpackage lispresen
    reader.lisp          top-level forms with offsets and line numbers
    template.lisp        define templates: substitution on the read forms
    model.lisp           structs and parsing with diagnostics
    embed.lisp           images as data: URIs, base64
    budoux-ja.lisp       generated BudouX weights (Apache-2.0 notice)
    phrase.lisp          phrase segmentation
    metrics.lisp         code point widths, line breaking
    layout.lisp          layout selection, type scale, fit solver
    lint.lisp            readability checks, contrast ratio
    theme.lisp           built-in themes, theme overrides
    html.lisp            HTML escaping and emission, stylesheet
    player.lisp          the player script as a string constant
    highlight.lisp       tokenizer for Lisp / Scheme / Clojure code
    lispresen.lisp       render
  web/
    lispresen-web.lisp   browser reactor
    index.html           editor page
    build.sh             compiles the reactor to lispresen.wasm
  test/
    lispresen-test.lisp  rove: segmentation, metrics, solver, parser diagnostics
  examples/
    intro.lisp           a deck presenting lispresen itself
    intro.html           its golden output, rendered with --embed
    pipeline.png         the image intro.lisp shows
  tools/
    gen-budoux.py        regenerates src/budoux-ja.lisp from BudouX's model
    BUDOUX-LICENSE       BudouX's Apache License 2.0
    calibrate.lisp       writes the page comparing the width model with a browser
```

`cli.lisp` and `lispresen-web.lisp` pull the sources in with top-level literal `load`s,
which the compilers inline at compile time (as `examples/browser/minesweeper` does), so
each compiled artifact is self-contained. lispresen has no dependency outside the
built-in language and `uiop`; the HTML emitter is a few dozen lines, which is cheaper
than putting a vendored library on every host's `--system-path`.

## Verification

lispresen is not an `ExamplesE2eTest` target; `check.sh` is its verification and is run
before committing a change under `lispresen/`.

- **Cross-backend identity**: `check.sh` builds the CLI for the interpreter, the JVM,
  WASM, the WASM component and a native executable (skipped when the jar carries no
  precompiler for the platform), runs it on SBCL as well (skipped without `sbcl`),
  renders `examples/intro.lisp` with `--embed` on each through stdin and stdout (from
  `examples/`, so its image resolves), and requires each output to equal
  `examples/intro.html` byte for byte. The golden deck has runnable Lisp and Clojure
  blocks, a columns slide built from a `&rest` template, a quote, an embedded PNG and two
  callouts (an inline one, and a step one named by `:at` in runnable code), so the
  highlighter, the emitted player, template expansion, callout placement and the binary
  read and base64 are in the comparison.
- **Unit tests** (rove, `test/lispresen-test.lisp`, run by `check.sh` on the
  interpreter; on SBCL rove needs bordeaux-threads, which the repository does not
  vendor): phrase boundaries checked against BudouX's own output, measurement and
  line breaking at their exact boundaries, step selection, the hard-floor error, the
  three table-width cases, numeric column detection, parser diagnostics with line
  numbers (`#.` and dotted lists among them), template expansion and its diagnostics and
  bounds, the side each kind of callout anchor gets, an unplaceable callout, step
  callouts and the callout diagnostics, base64 padding, and the embedding diagnostics. The golden deck covers
  emission, so HTML is not asserted piecewise.
- **Calibration and runnable code** (manual, in Chrome and Safari, when the width table,
  the font stacks or the player change): the page `tools/calibrate.lisp` writes must
  report no paragraph where the model predicts fewer lines than the browser; a deck with
  Lisp, Scheme and Clojure blocks must run each on the hosted and on a local runtime,
  share definitions across slides, and recover from an infinite loop by timeout; the
  presenter window must follow navigation both ways, step callouts included, show edits
  and outputs, and forward Run; a callout must sit beside its anchor after the runtime
  check; Start must put the audience window in full screen on the other display,
  and `Esc` in the editor in full screen must leave the editor only (Chromium). The
  documentation site's Run cells are verified the same way; no committed test drives a
  browser.

## Phases

1. Reader, model, phrase segmentation, metrics, fit solver, layouts `title` / `section`
   / `statement` / `standard` / `code` / `image` / `table`, the built-in themes, HTML,
   navigation, full screen, print CSS, CLI, `check.sh`.
2. Runnable code, presenter window, `columns`, `quote`, overview, runtime check,
   highlighting, theme overrides and the contrast lint, browser editor.
3. `define` templates, `--embed` images.

All three phases are implemented, and callouts after them. The compiled CLI is 297 KB as a jar, 788 KB as a WASM
module, 796 KB as a component and 6.7 MB as a native executable (macOS arm64, measured
2026-10-22 after phase 3); the BudouX weights are 17 KB of source.

## Open points

Verified in phase 1: a literal `load` of files holding `defpackage` / `in-package`,
nested two levels (`cli.lisp` -> `src/all.lisp` -> `src/*.lisp`), inlines on every
compiled backend, and the compiled runtime reader reads Japanese decks as the interpreter
does (the golden check). Verified in phase 2 (Chrome 155, 2026-10-22): the hosted runtime
loads in a Blob worker (through `fetch`, above) in about 2 s from the HTTP cache, and keeps
Lisp, Scheme and Clojure definitions across `rontoEval` calls. Safari 26.3 (2026-10-22,
through `safaridriver`): the calibration page reports 0 paragraphs under the browser and
1 over; the golden deck loads the hosted runtime, runs, shares definitions, recovers from
a timeout, and the editor keys, overview and presenter window behave as in Chrome.

Callouts (2026-10-22, Chrome 155 through DevTools and Safari 26.3 through `safaridriver`):
the golden deck and a deck with a callout on an image, a right-aligned table cell, a
column, a list item and a long paragraph place every bubble on the side the static layout
chose, the runtime check moving it only so the tail meets the measured anchor (by up to
18 px: the glyph box against the line box, and the Latin over-estimate), with the same
coordinates in both browsers. A step bubble shows on an advance, hides on going back, and
is shown when its slide is entered backward; the presenter window follows steps both ways
(over HTTP, as `BroadcastChannel` needs an origin) and shows the hidden ones faded; the
overview and print (headless Chrome `--print-to-pdf`) show all; editing the code hides
the bubble, and the next placement follows the moved `:at` text.

Checked by hand only, since browser automation refuses full screen: Start placing the
audience window on another display, and `Esc` under the Keyboard Lock.
