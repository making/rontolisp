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

Non-goals: WYSIWYG editing, slide transitions, free-form positioning, PowerPoint
export. Builds are page advances that reveal list items, bring code lines forward and show
callouts (Page advances, below); each takes effect at once, without a transition. The one
animation is the code morph (below): a slide that rewrites the previous slide's code
shows the change as it happens.

## Deck format

A deck file is a sequence of top-level forms: one `deck` header, then `slide` and
`section` forms.

```lisp
(deck :title "lispresen"
      :author "Toshiaki Maki"
      :date "2026-10-22"
      :theme :light            ; :light | :dark | :high-contrast | :midnight | (theme ...), below
      :lang "ja"               ; the page's <html lang>, a BCP 47 tag; "ja" by default
      :aspect :16x9            ; :16x9 | :4x3
      :runtime :hosted         ; :hosted | "runtime/" | :none
      :callouts :shown         ; :shown | :step, below
      :kicker :section         ; each titled slide's kicker is its section's title
      :agenda t                ; a list of the sections, repeated at each section
      :logo (image "logo.png" :alt "rontolisp")   ; on the title slide
      :url "https://example.com/talk"             ; a QR code of it on the title slide
      :description "S式で書くプレゼンテーション"     ; the page's description
      :page-url "https://example.com/talk.html"   ; where this HTML is published
      :page-image "talk.png"                      ; the title slide, drawn as a PNG
      :page-image-font ("r.ttf" "b.ttf")          ; the TrueType fonts it is drawn in
      :footer "#talk @me"      ; one line at the left of the bottom band, on every slide
      :progress t              ; a bar along the bottom edge, as long as the slide's place
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
  (lead (b "違い") " 起動の速さと " (b (accent "実行の速さ")))
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
| Top-level | `deck`, `section`, `slide` (`:title`, `:kicker`, `:footer`, `:layout`, `:class`, `:morph`), `define` (below) |
| Block | bare string (= `p`), `p`, `lead`, `ul` / `ol` (`:step`), `li`, `code` (`:lang`, `:file`, `:lines`, `:steps`, and for runnable code `:run`, `:timeout`, `:fresh`, `:stdin`), `image` (`:alt`, `:caption`, `:fit`, `:share`), `quote` (`:cite`), `column`, `table`, `flow`, `cells`, `bars` (`:unit`), `notes`, `source`, `say` around one block (`:at`, `:step`) |
| Inline | string, `b`, `i`, `tt`, `link` (`(link "url" ...)`), `mark`, `accent`, `say` (`(say "comment" inline...)`, `:step`) |

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
  and skipped, so the parser and the template expansion only ever see proper trees. The
  one exception is the datum of `(cells ...)` (Diagrams, below), whose dotted pairs are
  what it draws; the template substitution walks a dotted tail, and nothing else reads
  the datum but the drawing.
  Characters are code points on every backend (`.kb/string-index-cost.md`), so Japanese
  strings measure and slice identically everywhere.
- Top-level forms instead of one enclosing `(deck ...)`: each form is read separately,
  and the stop index `read-from-string` returns gives its start offset, hence a line
  number for every diagnostic. One enclosing form would leave only "slide 7".
- `code` content is dedented (the common leading whitespace removed, a leading blank
  line dropped) so it can be indented with the surrounding source. A `"` inside code is
  written `\"`.
- `(code :file "src/fib.lisp" [:lines (4 6)])` takes its text from a UTF-8 file (a
  leading byte order mark dropped) instead, lines FIRST to LAST when `:lines` is given,
  dedented the same way. A relative path resolves against the deck's directory, as an
  image's does (Images, below). `:lang` defaults by extension: `lisp` / `lsp` / `cl`
  lisp, `scm` / `ss` scheme, `clj` / `cljc` clojure, and for the other highlighted
  languages (Runnable code, below) `java`, `kt`, `js` / `mjs`, `ts`, `c` / `h`, `cpp` /
  `cc` / `hpp`, `go`, `rs`, `py`, `sh` / `bash` / `zsh`, `sql`, `json`, `yaml` / `yml`;
  none otherwise. The file is read
  while the deck is parsed, with or without `--embed` (the code is part of the page), so
  `:at` and every lint see its lines. A deck can name any file the host can read, so an
  untrusted deck is rendered where it reads nothing else: the browser host, or WASM with
  only its directory preopened.
- Consecutive `column`s form one row of columns; full-width blocks may stand before,
  between and after rows. A column holds blocks, not columns.
- Text roles are named, never colored: `lead` is a paragraph in the muted color (an
  introduction such as `問題 ... → 解決 ...`), `accent` inline text in the accent color,
  `mark` a highlighter background. Each is a theme color the contrast lint checks.
- A kicker is a one-line label in the accent color above the title, such as the title of
  the enclosing section: `(slide :kicker "text")`, or every titled slide's section title
  with `(deck :kicker :section)` (`:kicker nil` opts a slide out). It sits in the top
  margin, so the title keeps its position. It is a label like the page number, outside
  the body type scale; one longer than a line is cut with an ellipsis and reported.
- `(deck :agenda t)` adds an agenda: a slide after the title slide listing the section
  titles as an ordered list, and in place of each section divider the same list with
  that section highlighted (bold, in the accent color, the others muted), so each
  section opens by showing where the talk is. `:agenda "title"` names the slides
  (default 目次). They are ordinary standard slides, fitted and linted like any list.
  Template uses are expanded before any slide is parsed, so the sections are known when
  the agenda slide takes its number, and slide numbers in diagnostics count it. A deck
  without sections gets no agenda and a warning.
- `(agenda)`, a top-level form of the deck, puts the agenda slide there instead, for a
  talk that opens with slides of its own (who is speaking, why the talk) before saying
  where it goes. The agenda lists the sections after it; a section before it stays a
  section divider. A form in the deck body, not a `:agenda` option, because a position is
  best given where it is: an option would name a slide by number or title, and break when
  the opening is edited. `:agenda` still turns the agenda on and names it, so a deck
  without `(agenda)` is laid out as before. `(agenda)` without `:agenda` is a warning and
  places nothing (the deck might have lost its `:agenda`, or the marker is left over); a
  second `(agenda)` is a warning and ignored; arguments are an error; no section after it
  is the no-sections warning. A template can produce it, like a slide.
- `(source inline...)` on a slide names where its content comes from (a quotation's
  book, a measurement's date): one line in the muted color at the bottom of the safe
  area, above the page number row, 32 px on a 40 px line, wrapped in the content width.
  Its height and a 24 px gap come out of the body (or the statement's centered box)
  before the slide is fitted, so the content never runs under it. It is inline content,
  so a link in it is a link; like a title or a caption it takes no callout. A second
  source on a slide is an error, a source longer than 2 lines a warning. It is a label
  like the page number, outside the body type scale.
- `(deck :footer inline...)` sets one line on every slide but the title slide, at the left
  of the bottom band at the page number's size and color: a hashtag, a handle, a
  confidentiality label. The value is a string, an inline form or a list of inlines
  (`("#talk " (link "https://e.org" "@me"))`; a link takes the footer's color). It is
  a label like the page number, outside the body type scale, and takes no room from the
  body. Its width is the content width less the widest page number and a 48 px gap; one
  longer than a line is cut with an ellipsis and reported once. `(slide :footer nil)`
  drops it from a slide. A source stays above the band. It is part of the slide, so print
  and the overview show it.
- `(deck :progress t)` draws a 6 px bar in the accent color along the bottom edge of the
  canvas, as wide as the current slide's place in the deck (slide 3 of 20: 15%). The
  player sets its width on every move, so it is no slide's element: the overview and the
  presenter window's thumbnails, which copy slides, never show it, the presenter
  window hides the stage it is on, and print and the page without JavaScript hide it.

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
  template defined after it but before the use. The datum of `(cells ...)` is data: a
  list in it whose head names a template is drawn, not expanded, while a parameter in it
  is substituted, which is how a template passes a list to draw. A use with the wrong number of
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
               code files read, :morph tokens paired,
               diagnostics for malformed or unknown forms  (model.lisp, embed.lisp,
                                                            morph.lisp)
  -> embed     image sources as data: URIs, with --embed   (embed.lisp)
  -> segment   Japanese phrase boundaries                  (phrase.lisp)
  -> layout    layout selection + fit solver -> per-slide geometry and sizes
                                                           (layout.lisp, diagram.lisp,
                                                            metrics.lisp)
  -> lint      readability diagnostics                     (lint.lisp)
  -> emit      HTML + CSS + player JS as one string        (html.lisp, player.lisp)
  -> draw      the title slide as a PNG, with :page-image  (page-image.lisp, raster.lisp,
                                                            truetype.lisp, png.lisp)
```

The core is a pure function from a string to a string:

```lisp
(lispresen:render source &key theme runtime read-file embed page-image)
;; => (values html diagnostics image), IMAGE (path octets) or nil
```

It does no I/O, so every host (CLI, browser, a Java caller via `rontolisp:jvm-export`)
is a thin shell over it. `read-file` is the one place a host reaches in: a function from a
path the deck names to the file's bytes, nil when it cannot be read. Code files are read
with it, and under `embed` (the CLI's `--embed`) images too, and under `page-image` the
font and the logo of the page image (Link preview, below). The browser host passes none,
so a `:file` there is an error. A diagnostic is
`(severity line slide-number message)`.
Diagnostic messages are in English.

**All layout arithmetic is integer.** Widths are in milli-em, sizes and coordinates in
canvas pixels; the page image's in sixteenths of its pixels. Float printing is the one place backends could plausibly differ, and
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
| Diagram label | 48 | 44, 40, 36 | 40 (20 pt) | 36 (18 pt) | 1.4 | -- |
| Kicker | 32 | -- | -- | -- | 40 px | -- |

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
| Printable ASCII | per character, the wider of Chrome and Safari on Hiragino Sans at weight 400 (bold: 700), plus 2% |
| Monospace (`code`, `tt`) | 602 (Menlo), plus 2%; full-width 1000 |
| Other proportional characters | 800 (bold 840), plus 2% |
| Inline code (`tt`) and a marker (`mark`) | 125 more on the element's first character and on its last, its padding |

Inline code and a marker carry `padding: 0 .125em` (with a `.2em` corner), so their
background does not end flush with the first and last glyph, while a slide gives up an
eighth of an em on each side and no more. The browser slices the box at a line break
(`box-decoration-break: slice`, the default), so the padding stays at the element's two
ends wherever the lines break, and the model adds it to those two characters. `clone`
would put it on every line fragment, which makes a width depend on where the line broke;
an identifier breaks only at its spaces anyway. Adjacent elements pad each other, a
quarter em between them, and nested ones (`(mark (tt "x"))`) add both. The amount is
an exact binary fraction, so no browser rounds it differently from the model. Measured
on 2026-10-23 with the calibration page, which pads its inline code and has a marker
sample: never fewer lines than Chrome or Safari, one more in 18 and 11; the golden deck
needs no runtime correction in either browser in any built-in theme.

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

That table was measured in Chrome alone, and Safari sets Hiragino's Latin wider at every
size: 2.7% on average at weight 400, 4.4% at 700, single characters up to 15% (measured
2026-10-22, Safari 26.3 against Chrome 155, each character set twenty times). The 2%
margin hid it in the paragraphs, which meet a line boundary only by chance, until a bold
title the model set in two lines took three in Safari, 0.05 px over its width. The table
now holds the wider of the two browsers per character, and the calibration page also sets
every ASCII character in both weights and reports any wider than the model, which does
not depend on chance. Same day, 96 paragraphs (a bold title among them): never fewer
lines in either browser, one more in 18 (Chrome) and 11 (Safari); no character wider
than the model in either.

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
  only) would. A boundary where an element starts gets its `<wbr>` before the element's
  tag, not inside it, so an element's start padding moves to the next line with its text
  rather than ending the line, as the model puts it on the first character.
- Under `keep-all` the browser still breaks after closing punctuation (`、。」』）！？`)
  and before an opening bracket, which are not letters to it; the model takes those
  opportunities too, or it predicts lines the browser does not need.
- A number is one unit with what a reader takes as part of it. A half-width number keeps
  the word after its space (`1 時間`, `2 営業日`, `211 件`; not another number, an
  opening bracket or code), written as a no-break space, and a digit run joined by `-` `/` `.` `:` `,` (`2026-10-22`, `1.2.3`,
  `12:30`, `1,000`) never breaks inside.
- Chrome breaks after a dash (`-` `‐` `–`) before a letter, a digit or a bracket even
  under `keep-all` (`2026-` / `10-22`, `read-` / `from-string`), and Safari does not
  (measured 2026-10-23, Chrome 155 and Safari 26.3). Where the model does not break after
  a dash, the dash and the character after it are wrapped in a `white-space: nowrap`
  span. A character (a word joiner) would do it too, but it would be copied with the
  text, into a pasted symbol as well.
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
| Deck header (generated first slide) | `title` (below) |
| `section` | `section` (large, centered); with `:agenda`, `standard` (the agenda) |
| No title, one short `p`, no callout | `statement` (centered, statement scale) |
| No title, one `image` without `:caption`, no source or callout | `full` (the image covers the canvas, below) |
| Title + one `image` | `image` (image fills the body box, `object-fit: contain`) |
| Title + one `code` | `code` |
| Title + one `quote` | `quote` |
| Title + one `table` | `table` |
| Title + `column`s, with or without full-width blocks | `columns` |
| Anything else | `standard` (title on top, blocks stacked) |

The title slide centers the deck's `:logo` (160 px high, the image's own width), its
title at the statement scale (at most 2 lines) and the author and date in the safe area,
the title stepping down when the logo and those lines leave it too little height. A
deck's `:url` adds a QR code of the URL with the URL under it in a corner, so the slide's
address can be scanned or typed. The corner never moves the text: the text is laid out as
without the URL, and the QR code takes the largest of 320, 280, 240 and 200 px and the
first corner of bottom right, bottom left, top right and top left where it and the URL
under it, 24 px clear, cover none of the text. The logo counts as the full width (its own
width is unknown) and each title line as wide as the widest, since `text-wrap: balance`
evens the lines. When no corner is free, the smallest size goes to the bottom right and
the slide is reported. A first version narrowed the text column to make room for the code
on the right, which moved the title.

Placement rules: the title sits at the same position on every content slide, and the
body is top-aligned under it, so the audience's eye does not search for the start of
each slide. Statement, section and title layouts are centered. Images need no intrinsic
size: lispresen computes the box, the browser fits the image inside it.

#### Full-bleed and split images

An image in the body box keeps the safe area's margins around it, so a photograph cannot
fill the screen, nor run to the slide's edge beside text. Two layouts take an image out of
the body and give it a part of the canvas, edge to edge:

```lisp
(slide (image "venue.jpg" :alt "会場"))                       ; full, by its shape
(slide :layout :full (image "chart.png" :alt "..." :fit :contain))
(slide :title "t" :layout :image-left (image "p.jpg" :alt "...") (ul ...))
(slide :title "t" :layout :image-right (ul ...) (image "p.jpg" :alt "..." :share 1/3))
```

- `full`: the image covers the whole canvas (`object-fit: cover`, centered, cut at the
  edges its aspect does not match), or with `:fit :contain` is shown whole on the theme
  background. A slide with no title, one `image` without `:caption` and no source or
  callout takes it automatically; `:layout :full` on any other slide is a warning and
  the slide keeps its own layout.
- `image-left` / `image-right`: the slide's one `image` outside columns takes the left or
  right `:share` of the canvas width (`1/3`, `1/2` or `2/3`; half by default), top to
  bottom, covering it or, with `:fit :contain`, shown whole in it. The kicker, the title,
  the body, the source, the footer and the page number are laid out in the rest less the
  112 px side margins, as on any slide in a narrower content width: 736 px at half of
  16:9, 1056 at a third, 416 at two thirds (496, 736 and 256 on 4:3, where two thirds
  leave room for a word or two). The fit solver sees nothing new, the narrower body it
  handles for columns. The slide writes the region as `--left`, `--width` and `--right`
  on its element, which the stylesheet places those elements and a run's output overlay
  by. A slide without exactly one such image, or whose image has a `:caption` or a
  callout, is a warning and keeps its own layout. A split is never chosen by shape: a
  title and an image with other blocks stay a `standard` slide whose image shares the
  body.
- **No text on a picture.** The contrast lint cannot check a color against an image, so
  text never stands on one. A full slide has no title, caption, source or callout by the
  rule above, and drops the page number and the footer as the title slide does. The
  progress bar stays along the bottom edge: it is a 6 px bar in the accent color, no
  text. A caption on a full-bleed or split image is refused rather than set on the
  picture or in a band under it, which would take the edge back; a credit goes in the
  notes or in a split slide's source line.
- `:fit` on an image in the body box, or `:share` anywhere but a split slide, is a
  warning: an image in the body box is always shown whole.
- Print, the overview and the presenter thumbnails show these slides like any other: the
  image is part of the slide. The page image draws the title slide and is unaffected, and
  so is `--embed`, which inlines the image as it does in the body.

### Fit solver

For each slide:

1. Fit the title: the largest title step at which it takes at most 2 lines.
2. The body box is what remains of the safe area below the title and a fixed gap, and
   above the slide's source line and its gap.
3. The body is a vertical stack of full-width blocks and rows of columns. Compute its
   height at each body step: lines x line height, plus block gaps, list indentation
   reducing the available width per nesting level, code sized by both its longest line
   and its line count (runnable code too: its output is an overlay, below, and its Run
   and Reset buttons take a band above the rows only where a row would reach them,
   Runnable code below), a table by
   the column widths and row heights below, a row of columns as its tallest column at
   the widths below. Height is monotonic in size, so the chosen size is the largest step
   that fits.
4. Columns share the slide's body and code size, so side-by-side text matches. The code
   size at a body step is that step's, or a smaller one when a code line does not fit
   its block (full width, or its column's minimum below).
5. If even the hard floor overflows, the slide is emitted at the hard floor, marked, and
   reported as an error: lispresen does not make text unreadable to make it fit.

A `quote` is its text at the body size, indented by the list indent (an accent rule in
it), and its `:cite` below as `— cite`; the `quote` layout places it as `standard` does.

An image takes a share of the height the other blocks leave (`flex: 1 1 0`), and a row
of columns holding an image takes one too; every other block and row is as tall as its
content.

### Columns

Column widths come from content, as table widths do, and for the same reason: a code
column needs the width of its longest line, which an even split does not give it. At a
body step and code size, each column has a minimum, the widest of its blocks' minima,
and a maximum, the widest of their unwrapped widths:

| Block | Minimum | Maximum |
|-------|---------|---------|
| text, list, quote | the widest unbreakable unit, plus the indent | the text set on one line, plus the indent |
| code | the longest line, plus padding; runnable code at least its buttons, 320 + 2 x 12 px | the same |
| console transcript | the longest command line, plus padding | the longest line, plus padding |
| table | the sum of its columns' minima | the sum of their maxima |
| image | its caption's widest unit | none: it takes what it gets |
| flow | its widest box, labels wrapped at their widest unit | its boxes on one row |
| cells | its drawing | the same |

With 80 px between columns, `1696 - 80 (n - 1)` px are shared:

1. If the maxima fit, every column gets at least its maximum and the rest is spread
   evenly, the narrowest raised first, as in a table: columns of short text stay an even
   split.
2. Otherwise a column whose maximum is below an even share takes only its maximum, a
   column whose minimum is above it takes its minimum, and the others share the rest
   evenly. A code column keeps its lines on one line, and the text beside it takes what
   is left, which wraps it.
3. If the minima do not fit, the code steps down, then the body; at the hard floor the
   columns are set at their minima and the slide is an error.

Both cases are one function, `level-widths`: the largest level L such that each width
`clamp(L, floor, cap)` sums to at most the room, the 1 px remainders to the leftmost
columns at L. Tables use it for their first case.

There is no author hint for widths. Every case met so far (code beside a list, a short
column beside a long one, an image beside text) comes out of the content; a hint would
be geometry in the deck, the first thing the goals rule out.

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

### Diagrams

```lisp
(flow "read" "parse" (b "segment") ("layout " (tt "fit-slide")) "lint" "emit")
(cells '(slide :title "t" (p "x")))
(cells '((a . 1) (b . 2)))
(bars :unit "s" ("interpreter" "2.1") ("jvm" "0.4") ("wasm" "0.6"))
```

A picture of a structure belongs in the deck as text for the reasons the rest of the
deck does: an image file does not follow the theme, cannot be reviewed as a diff, and its
labels are neither phrase-wrapped nor sized with the slide. A diagram is drawn by
lispresen from an S-expression, as an inline SVG, its labels HTML over it.

- **Shapes.** Every shape is a filled area in integer canvas px: a border is a rectangle
  under a face 4 px smaller on each side, a line a 4 px rectangle, an arrowhead a
  triangle. No stroke means no half-pixel coordinate and no stroke to leave out of a
  bounding box, so the box the browser reports for a shape (`getBoundingClientRect`) is
  the box the static layout used, and the callout placement agrees on both sides. Borders
  are `--accent`, faces `--head-bg`, arrows `--fg`, labels `--fg` on `--head-bg` or `--bg`,
  pairs the contrast lint already checks, so every theme draws them.
- **Size.** Every measure derives from the label size s, which starts at 48 px and steps
  down with the body, further while the drawing is wider than its region, as a table's
  does: line height 1.4 s, box padding 0.6 s by s/3, 1.5 s between boxes, 2 s between rows,
  arrowheads 0.4 s long. Below 40 px is a warning, and a drawing wider than its region at
  36 px is an error naming its width, besides the slide's overflow. A diagram is
  centered in its region (`align-self: center`); it is fixed geometry, so the runtime
  check leaves it as written.
- **flow.** A label is inline content (`b`, `tt`, an inline callout ...): a string, an
  inline form, or a list of them for mixed content. It is set on one line, or wrapped at
  its phrases when wider than a third of the flow (less the gaps), so long labels do not
  push the row into one box per row. Every box has the height of the tallest label and is
  no narrower than it is tall. Boxes fill as few rows as the region holds, and then the
  narrowest row limit that needs no more rows (a binary search over the greedy fill)
  evens them: six equal boxes of which four fit a row go three and three, not four and
  two. Rows are left-aligned; the arrow from a row's last box drops below it, runs back
  along the gap and drops into the next row's first box, so the reading order stays left
  to right. More than 8 boxes is a warning. The boxes are an `<ol>` of `<li>`s, each as
  large as its box, its label's top padding the half of the height its lines leave.
- **cells.** The classic box-and-pointer drawing. The cdr chain of a list stands in one
  row of cells (a square half for the car, one for the cdr, a divider between); each
  car's drawing hangs below its cell, an atom as its label (monospace, the `tt` widths)
  and a list as its own row; `nil` is a slash across its half; a dotted pair's cdr points
  right to its atom. A pointer is a dot and an arrow. A cell moves right past the one
  before it, and past the drawings below the cells before it by s (each drawing taken as
  its bounding box, not its outline per row: simpler, and a slide-sized list does not
  need the packing). The datum is the one argument, quoted or not, a list of at most 32
  conses, its atoms symbols (lower case unless written with lower case, keywords with
  their colon), integers, ratios, strings and characters. A float is refused because its
  printing is the one thing the backends may not share. The figure is `role="img"` named by
  the list as Lisp prints it, the atoms as spans.
- **bars.** A horizontal bar chart: a row per `(label value)`, the label on the left,
  the bar (an accent rectangle, `dg-b`) as long as the value's share of the largest, the
  value at its end followed by `:unit` as written. It is sized like a table: the label
  size steps with the body, and further while the chart does not fit its region; a row is
  its label's lines at the label line height plus s/4 above and below, the bar as tall as
  one line, centered on the label. The label column is as wide as its widest label set on
  one line, or wrapped at its phrases when that is wider than a third of the chart; the
  value column as wide as its widest value; 0.6 s separates them from the bars. The
  longest bar takes the rest of the region, which must be at least 4 s, else the chart is
  too wide (the error above); in a row of columns its minimum is that, and it takes
  whatever width it is given, as an image does. A bar of a positive value is at least
  4 px, a zero draws none. Values are integers or decimals written as strings, read into
  rationals, so the lengths are integer arithmetic and no float is printed; the value is
  shown as written. More than 7 bars is a warning, as a table's rows are. Labels and
  values are `<p>`s over the SVG (`dg-t`, `dg-v`), read in row order.
- **Callouts.** A `say` around a diagram points at its whole drawing; with `:at` at the
  first label equal to the text, else the first holding it: a box of a flow, a bar of a
  bar chart (the tail at its value), an atom of cells (in the order Lisp prints them). Every shape and label is an obstacle, a flow box
  whole, and the runtime placement takes the SVG shapes' boxes as obstacles too.

No diagram has steps of its own yet; a step callout on one works as on any block.

### Page advances

```lisp
(ul :step t "一つ目" "二つ目")
(code :file "fib.lisp" :lines (4 6) :steps ((1 2) "(fib 20)"))
```

A slide's steps are what its page advances reveal: each item of a `(ul :step t ...)` or
`(ol :step t ...)` (a nested list comes with its item, unless it has `:step` of its own),
each step of a code block's `:steps`, and each step callout. They are numbered in the
order they are written, a callout after the steps of what it wraps, since a comment
follows what it is about; the parser records them as it reads (`number-steps`).

- `:steps` lists steps of a code block; a step is a string, naming the first line that
  holds it as `:at` does, `(FIRST LAST)`, lines of the block as shown counted from 1, or
  a list of those. A step brings its lines forward and fades the others to 35%; the last
  step reached stays until the slide is left. Lines are named by text first for the same
  reason `:at` is: a line added above does not shift them.
- The HTML carries the numbers: `data-steps` on the slide, `data-step` on an item or a
  bubble, `data-focus` on a stepped code block and, on each of its lines (a `<span
  class="ln">`, the highlight spans closed and opened again at a line end), `data-hot`
  with the steps that bring it forward.
- The player hides every step of a slide entered forward; each page advance (→, ↓,
  space, PageDown, Enter, a tap, a swipe) reveals the next, the slide's last moves on, and going
  back undoes the last one revealed before it moves to the previous slide, which it
  enters with every step taken. Hidden items keep their room (`visibility: hidden`), so
  nothing moves.
- The presenter window and the audience window share the step with the slide number;
  the presenter's views show what is still hidden faded. The overview, print and the
  page without JavaScript show every step and no faded line. Editing a block ends its
  fading: its text is highlighted anew, without line spans, until Reset restores the
  deck's markup.

### Code morph

```lisp
(slide :title "v1" (code :lang "lisp" "(defun f (x) (* x x))"))
(slide :title "v2" :morph t (code :lang "lisp" "(defun f (x)
  (* x x x))"))
```

`(slide :morph t ...)` animates its first code block from the first code block of the
slide before it: on the page advance into the slide, tokens the two share move from their
old place to their new one, tokens only the old code has fade out, tokens only the new
code has fade in, and the code panel grows or shrinks to its new box, all in one 600 ms.
Going back plays it in reverse. The option is on the slide rather than on the block
because it names a relation between two slides; the block is the slide's first, as for
the previous slide.

- **The pairing is computed at render time** (`morph.lisp`), in portable Lisp, so every
  backend emits the same one and `check.sh` compares it. Tokens are the highlighter's
  segments split at blanks (a plain block is one segment), so no token holds a blank or a
  line end: whitespace and indentation are not tokens, and a re-indented line moves. The
  pairing keeps the order of both blocks, the way a patience diff does: the shared start
  and end, then the texts found once on each side as anchors (their longest run in the
  same order), each gap between anchors again, and a gap without anchors by a longest
  common subsequence. A first pass compares a bracket together with the token inside it
  next to it, so the `(` of `(defun` is not taken by the `(` of a `(defvar` added above
  it; the gaps between those pairs are then paired by text alone. Last, a text left
  exactly once among the unpaired tokens of both sides is paired too, so a form that
  moved past others moves instead of fading out and in. A gap of more than 10^6 cells
  (tokens times tokens) is reported and the slide switches without the morph, since an
  untrusted deck could otherwise make the table arbitrarily large.
- **The HTML carries it**: both blocks' tokens are `<span class="tk">` (with the
  highlight class), a token of the new block names the old token it continues by index
  in `data-m`, and the new block's `pre` has `data-morph`. A block can be the new code of
  one morph and the old code of the next. Tokens are `inline-block`, so they can be
  transformed; in a `pre` this changes no width or height. A token never spans a line
  end, so the line spans of `:steps` wrap whole tokens.
- **The player only plays it** (Web Animations): it measures the old tokens and panel
  before the switch and the new ones after, moves each paired token from its old box
  (translate and scale, the code size may differ) with the opacity of a faded line
  carried over, fades unpaired new tokens in over the second half, puts a copy of each
  unpaired old token at its old place fading out over the first half, and draws the panel
  as a box animated from the old rectangle to the new one while the new `pre` shows no
  background and does not clip. Everything it added is removed when the animation ends
  or anything else changes the slide.
- It plays on a page advance (keys, a tap, a swipe, or the presenter window's advance, which
  the message to the audience window carries), between adjacent slides only. A jump
  (`#N`, the overview, Home/End) switches at once, as does the presenter window itself,
  whose views are thumbnails, and `prefers-reduced-motion: reduce`.
- Runnable code: the morph is between the deck's texts. A block edited in the player
  holds no token spans, so a morph from or to it switches at once until Reset restores
  the deck's markup.
- Print, the overview and the page without JavaScript show each slide as it is.
- Warnings: a `:morph` slide without a code block, one whose previous slide has none,
  and a console transcript on either side (its output lines wrap between any two
  characters, which tokens as boxes would change).

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
  `table`, `quote`, `flow`, `cells` or `bars` as a whole. In block position a `say` whose content is one such form
  is this kind; anything else is a paragraph holding an inline callout.
- `:at "text"` on a callout around `code` points at the first occurrence of the text,
  which must lie within one line. Code is one string, so the anchor is named by its text
  rather than wrapped; a line number would go stale when a line is added above. On a
  diagram, `:at` names a label instead (Diagrams, above).
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
- a code line is its monospace width inside the panel's padding (below the buttons'
  band when it has one), and runnable code adds its Run and Reset buttons (320 x 48 px,
  12 px inside the top right corner);
- an image is its whole picture area, since its own size is not known at build time;
- a bubble already placed, with its tail.

Backgrounds and rules are not obstacles: a bubble may sit over the empty part of a code
panel or a table. An inline anchor is the part of each line its text takes, after the
text before it with its spaces, one fragment per line; a block anchor is the block's box
(code, table, image) or the rectangle around its lines (text). The width model over-estimates Latin text, so a static tail can land a
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
callout of a deck with `:callouts :step` (`:step nil` opts one out), revealed by a page
advance (Page advances, above). Every step bubble is placed when the slide is laid out,
so showing one moves nothing. While a
code block of the slide is edited, its bubbles are hidden, since the edited text may run
under them, and placed again on every change. An open output panel moves below the bubbles of its code. Of
any other bubble and the panel, the one shown last is on top: a bubble shown by an
advance covers the panel, and a run brings the panel back over it.

### Runtime check

The static estimate cannot see the font the presenting machine actually has. After
`document.fonts.ready`, the player measures each slide: a title taller than the room above
the body steps the title down; a region (the body and each column) whose content is taller
than it steps the slide's body, code and table sizes down together; a centered text taller
than the safe area steps the statement scale down. Column widths stay as written: a
smaller size only needs less of them. Never below the hard floor; a region
still overflowing there is marked like a build-time overflow. Each step is logged with
`console.warn`, and opening the deck with `?check` lists them in an overlay and outlines
the corrected slides. The player derives the line heights, gaps and padding of a step with
the formulas of `layout.lisp`, multiplying before dividing so they round as the integer
arithmetic does; the type scale itself comes from the JSON the emitter writes
(`<script type="application/json" id="lp">`). After the size
corrections, the player places every callout of the slide again, with the candidates
above, on measured geometry: the anchor's client rects (the `:at` text through a
`Range`), the client rects of every text node, the picture areas and the Run and Reset
buttons, each rounded to the nearest canvas px (the client rects carry float noise:
Safari 26.3 at a 0.42 scale reports an image edge at 1043.00005, which rounding outward
made 1044 and left a bubble written flush against the body's edge no room, 2026-10-23);
the bubble keeps its size. So the tail lands on the anchor the browser drew. A
callout that changes side is listed like a step, and one that finds no room keeps its
static place and is outlined. JavaScript is a correction on top of the static result,
not a replacement for it, so the deck still lays out correctly with JavaScript disabled
and when printed.

### QR codes

`qr.lisp` encodes the URL itself, so the code cannot drift from the URL and no tool or
service is needed: ISO/IEC 18004 byte mode (the URL's UTF-8), error correction level M,
versions 1 to 10, up to 213 bytes (a longer URL is an error and gets no code). Reed-Solomon
over GF(256) runs on log tables and the mask is the lowest penalty of the eight, all
integer arithmetic, so every backend writes the same modules. The code is an SVG path of
the dark modules, black on a white square with the four-module quiet zone whatever the
theme, since many scanners do not read an inverted code. Checked 2026-10-22: the
Reed-Solomon codewords, format and version bits equal the standard's examples (unit
tests), and Chrome's `BarcodeDetector` reads back the text of a code of every version 1
to 10, and of a Japanese text.

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
| `"dir/"` | a directory beside the deck holding the two runtime files | serving the deck over HTTP (`jwebserver`): a page opened from `file://` cannot `fetch` local files (Chrome 155 and Safari 26.3), so the player reports that in the output instead of running |
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
  constructed from a cross-origin URL, so the player builds it from a Blob: the page
  `fetch`es `rontoplayground.js` and puts its text, followed by the worker's own code, in
  one Blob, and the worker sets `config.wasm_path` to the absolute `.wasm` URL. The script
  cannot be loaded into the worker by URL: `importScripts` of the hosted URL fails in
  Chrome with a bare `NetworkError` (measured 2026-10-22, Chrome 155; the cross-origin
  script's error is muted), and `importScripts` of a Blob URL made inside the worker fails
  the same way in Safari when the deck is opened from `file://` (measured 2026-10-23,
  Safari 26.3: the page's origin is `null`, so is the worker's, and its nested Blob URL
  does not load). An indirect `eval` of the text is no way out either, since the script
  is `'use strict'` and its `var GraalVM` would stay local to the `eval`. The `fetch`es
  need the CORS header the hosted files carry. A failure is reported with its cause in
  the output of a waiting run, the Run button's tooltip and the presenter window; for a
  runtime directory and a deck opened from `file://` it says to serve the deck over HTTP.
- `rontolisp:fetch` takes the runtime's async path when the page is cross-origin
  isolated: the worker posts each request (`{type:'ronto-fetch'}` with a
  `SharedArrayBuffer`) and blocks in `Atomics.wait` until the page's `fetch` writes the
  response into the buffer. The runner is that page side (`broker`, the playground's
  `brokerFetch` protocol, `.kb/fetch-http.md`), and the worker declares it with
  `self.rontoFetchBroker = true`; without isolation the runtime uses a synchronous XHR in
  the worker. A deck published under `https://making.github.io/rontolisp/` is isolated
  whenever the browser has opened the playground, whose COOP/COEP service worker has
  scope `/rontolisp/`. Before the runner brokered, a fetch there blocked the worker until
  the timeout (measured 2026-10-23, Chrome, the intro deck on Pages).
- Messages from the worker carry a `type` (`ready`, `fail`, `result`, `ronto-fetch`).
  The runner drops anything from a worker it has since replaced, and a result for a run
  other than the current one.
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
  `Esc` in full screen), and Reset restores the deck's markup and closes the output. Run
  and Reset sit in the block's top-right corner; the player adds them, so print and the
  no-script view show none.
- The buttons take room only where they would cover code. The fit solver counts them as
  a 320 x 48 px box 12 px inside the corner (`*tools-box*`; the widest label, Loading,
  makes them 290 px wide in Chrome 155 and 298 px in Safari 26.3, measured 2026-10-23).
  A runnable block is never narrower than the box and its insets (344 px), and when a row
  beside the box would come within the panel's padding of it, at the width the block is
  laid out at, the rows start below the box: the panel's top padding becomes the inset,
  the box's height and the padding (the block's `band` class), and its height counts it.
  The band costs a full-width block nothing unless its first line is long; it is what
  lets a narrow column hold runnable code at all. Reserving width instead would widen
  every column whose first line is its longest by the buttons, which the golden deck's
  Clojure and Scheme columns cannot afford at any code size. The editor's `textarea` takes the same padding.
- The output takes no room on the slide. A first version reserved an output box below
  the code in the fit solver; on a full slide that stepped the code down a size or two,
  which costs more legibility than an output shown only while it matters. The output is
  a panel over the slide, at the code size and in the code colors, as wide as the body:
  directly below the code and the code's shown bubbles (it moves as a step shows or
  hides one) when at least three lines fit there, otherwise anchored to the bottom of the safe area
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
- Code in another language a talk shows next to Lisp is highlighted by a second
  tokenizer driven by a table of rules per language: `java`, `kotlin` (`kt`),
  `javascript` (`js`), `typescript` (`ts`), `c`, `cpp` (`c++`), `go`, `rust` (`rs`),
  `python` (`py`), `bash` (`sh`, `shell`, `zsh`), `sql`, `json` and `yaml` (`yml`). A
  language's rules are its line and block comment delimiters, its string delimiters with
  their escapes (`\`, or SQL's and YAML's doubled quote) and whether they span lines
  (Python's triple quotes, Java and Kotlin text blocks, template literals and Go's raw
  strings, all set as plain strings), `'x'` character literals (C family; a `'` that
  starts none, a Rust lifetime, is punctuation), its keywords (`hl-k`, compared ignoring
  case in SQL) and constants (`true`, `null`, `None`; `hl-n`, as numbers are). In JSON and
  YAML a key is `hl-k`. In shell and YAML a `#` comment opens only at the start of a word
  (`${#a}`, `a#b`), in YAML a quote too (`it's`), and a string left open ends at the end of
  its line, so a stray quote colors one line at most. It is no grammar: enough to set
  comments, strings and keywords apart from the back of the room. Only colors change, so
  the widths, the fit solver and the layout are the monospace table's as for any code;
  `:steps`, `:at` and `:morph` work on these blocks as on Lisp. They are never run, whatever
  `:run` says, and not editable; any other `:lang` is plain `code-fg` text. Checked
  2026-10-24 in Chrome: Java, Python, shell, SQL, JSON and YAML blocks in each built-in
  theme, the runtime check making no correction.
- A `console` block is a shell transcript: a line starting with `$ ` is a command, its
  `$` in the keyword color, and every other line is output, in the comment color. It is
  never runnable. A command line is set on one line like code; an output line longer
  than the panel wraps at the panel's edge between any two characters, as a terminal
  wraps it (`white-space: pre-wrap; line-break: anywhere`), instead of stepping every
  code block of the slide down. The model wraps it greedily with the monospace widths,
  which over-estimate Menlo by 2%, so the browser never needs more rows than counted.

## Readability lint

| Severity | Condition |
|----------|-----------|
| error | malformed or unknown form; a `:page-url` that is not an absolute http(s) URL; a drawn `:page-image` not named `.png`, or without a `:page-image-font`; a page image font that cannot be read or is not TrueType; a malformed template or use, or one past the expansion bounds; overflow at the hard floor; a `:url` longer than a QR code holds; a callout without room at the smallest sizes, or whose `:at` text is not on a line of its code or a label of its diagram; a `flow` without boxes; a `bars` without bars, a bar that is not `(label value)`, a value that is not an integer or a decimal string of 0 or more (a float among them), an option after the bars; a `cells` without one list, with more than 32 conses, or with an atom other than a symbol, integer, ratio, string or character; a diagram wider than its region at the smallest size; a table row whose cell count (with `:colspan`) differs from the header's or the first row's; a second `source` on a slide, a callout in one, one outside a slide; a code `:file` that cannot be read or is not UTF-8, both `:file` and text, `:lines` without `:file`, not two ascending line numbers, or past the file's end; an image `:fit` other than `:cover` or `:contain`, a `:share` other than `1/3`, `1/2` or `2/3`; under `--embed`, an image that cannot be read or has an unknown extension |
| warning | `:agenda` in a deck without sections (after its `(agenda)`); `(agenda)` without `:agenda`, or after the first; `:logo` or `:url` without a `:title`; `:page-image` without `:page-url`, or drawn without a `:title`; `:page-image-font` without `:page-image`; a character the page image font has no glyph for; a logo the page image leaves out (not SVG, unreadable, or SVG it does not draw); a QR code with no free corner; a size below its warning floor; more than 6 items in one list; list nesting deeper than 2; an item longer than 3 lines; a title longer than 2 lines; code longer than 15 lines; a table with more than 7 body rows or 5 columns; a flow of more than 8 boxes; a bar chart of more than 7 bars; a source longer than 2 lines; full-width alphanumerics (`ＡＢＣ１２３`); `image` without `:alt`; a `:full`, `:image-left` or `:image-right` layout the slide's shape does not allow; `:fit` on an image in the body box, `:share` off a split slide; under `--embed`, an image given as a URL; a theme text color below 4.5:1 against its background; callout text below its warning floor; a callout longer than 2 lines; a kicker on a slide without a title, or longer than one line; a footer longer than one line beside the page number |

Diagnostics print as `talk.lisp:42: slide 7 "その場で動かす": warning: body text at
44px (floor 48px); consider splitting the slide`.

The contrast lint checks every text color against the background it is set on: `fg`,
`muted` and `accent` on `bg`, `fg` on `head-bg` (table headers and callouts) and `mark`,
and `code-fg`, `code-muted` and the four highlight colors on `code-bg` (code panels,
inline code and the output panel). Every one of them carries body-size text somewhere
(the accent colors links, kickers and accented text, the muted color captions and leads,
the code muted color comments and console output), so each needs WCAG AA
for normal text, 4.5:1; a separate 3:1 for large titles would only matter for a title
color, and titles use `fg`.

## Themes

A theme is fourteen colors: the page's `bg`, `fg`, `muted`, `accent`, `head-bg`, `rule`
and `mark`, and the code's `code-bg`, `code-fg`, `code-muted` and highlight colors
`hl-str`, `hl-kw`, `hl-def`, `hl-const`, written as custom properties of the same names.
`:theme` names a built-in theme (`:light`, `:dark`, `:high-contrast`, `:midnight`) or
overrides one:

```lisp
(deck :theme (theme :base :dark :accent "#ff9f43" :mark "#4a3b00"))
(deck :theme (theme :base :light :code :dark))   ; dark code panels on a light page
```

`:base` defaults to light; `:code` takes the seven code colors from another built-in
theme, then the named colors replace theirs. The code colors are a group of their own
because a code panel is a background of its own: a first theme set code text in `fg` and
tuned the highlight colors to `bg`, so a dark `code-bg` on a light theme made every code
pair unreadable. Code panels, inline `tt`, the editor's caret and the output panel all
use the code group. A color is `#rgb` or `#rrggbb`. `--theme` on the command line
names a built-in theme and replaces the deck's choice.

A built-in theme may also add stylesheet rules, written after the fixed stylesheet; an
override keeps the rules of its base, and they take their colors from the variables.
None of them may move what the fit solver placed, so they draw outside the text or set
it narrower than it was measured, never wider. `:midnight` uses this:

- the title, section and statement slides stand between two 3 px arcs in the accent,
  the edges of 1000 x 2400 px ellipses centered 540 px in from the canvas edge. An arc
  runs 40 px from the edge at mid-height and 88 px at the top and bottom, always inside
  the 112 px side margin, so it meets no text, QR code or page number;
- display text takes proportional kana (`palt`) and -0.01 em tracking, and the kicker
  the regular weight;
- table headers are accent text over a hairline, code panels and callouts nearly
  square with a hairline ring, inline code on `head-bg`, list markers accent dots, and
  the quote rule 3 px, its padding widened by what the rule lost.

Checked 2026-10-22 against the other directions tried (a light deck with full-bleed
indigo slides and heavy vermilion parentheses; black and white with a signal yellow):
the dark indigo with fine gold lines was chosen. Hiragino stays the face, since a
display face would need its own width table.

## HTML output

One file containing:

- `<html lang="ja">` (the deck's `:lang`, `ja` by default; a tag of letters, digits and `-`, else an error) and the slides as `<section class="slide layout-standard"
  style="--body:52px ...">` elements with semantic markup (`h1`, `ul`, `pre><code`,
  `figure`, `blockquote`). A kicker is a `<p class="kicker">` before the `h1`, a row of
  columns a `<div class="columns">` in the body holding `<div class="column"
  style="width:...px">`s, and `grow` when it takes a share of the height.
- After the `<title>`, the description and link preview tags of a deck that sets them
  (Link preview, below).
- The theme stylesheet: colors as custom properties (`--fg`, `--bg`, `--accent`,
  `--muted`), font stacks, the layout classes, and print CSS (`@page` sized to the
  canvas, one slide per page) for PDF export through the browser's print dialog.
- The player script: canvas scaling, navigation (arrow keys, space, PageUp/PageDown for
  clickers, Home/End, tap, swipe, `#N` in the URL), overview grid (`O`), full screen (`F`,
  below),
  the runtime check, a presenter window (`P`, below), and the runnable-code worker,
  included only when the deck has runnable code.
- Callouts as `<div class="say" role="note" data-side="below" style="left:...;top:...">`
  after the body, each named by its anchor's `aria-describedby` (an inline anchor is a
  `<span>`), which is also how the player finds the anchor. The tail is two `clip-path`
  triangles of the border and the fill color, and the bubble prints with its colors
  (`print-color-adjust: exact`).
- A diagram as `<figure class="dg" style="width:...;height:...;--dg:48px ...">` holding
  `<svg class="dg-art">` (`rect`, `polygon` and `circle` elements, classed `dg-b`, `dg-f`
  and `dg-e` for their colors) and, over it, its labels at the places the solver gave
  them: an `<ol class="dg-flow">` of `<li class="dg-l">` for a flow, a `<p class="dg-l
  dg-t">` label and a `<p class="dg-l dg-v">` value per bar of a bar chart, `<span
  class="dg-l">` atoms for cells.
- A footer as `<p class="footer">` after the source, placed by the stylesheet at the left
  of the bottom band.
- A source as `<p class="source">` after the body, placed by the stylesheet at the bottom
  of the safe area; with `:progress`, a `<div id="progress">` after the slides in the
  canvas.
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

### Touch

A phone shows the canvas letterboxed as on any screen, held upright too: the slide is the
same picture, so there is no second, scrolling layout to keep in step with it.

- A tap (a touch that moves under 10 px and lifts within 500 ms) on the left third of the
  stage goes back, anywhere else goes forward, a step at a time like the keys. Forward is
  most of the taps, so it gets the larger part; the band covers the whole stage height, so
  the empty space above and below an upright slide works too. A tap that moves cancels
  the touch's default action (`preventDefault`), so no click or `dblclick` follows it.
- A tap on something that does something itself is left to it: links, buttons, form
  fields, `[tabindex]`, `[contenteditable]`, a runnable code block (a tap edits it) and its
  output. A tap while text is selected clears the selection, and one while the editor has
  focus leaves the editor, instead of moving.
- A horizontal swipe over 50 px moves as before; a touch is a swipe or a tap, never both.
  Two fingers, a pinch-zoomed page (`visualViewport.scale` above 1), or the editor with
  focus move nothing, so panning a zoomed slide does not turn the page.
- A mouse click moves nothing, so the desktop works as before.
- `touch-action: manipulation` on the stage turns off the double-tap zoom, so two quick
  taps are two page moves. A `dblclick` within 1 s of a touch is ignored, so a double tap
  on a link or a code block does not toggle full screen.

### Full screen

Presenting is done in full screen; the windowed view is for preparation.

- `F`, a mouse double-click on the slide, or the full-screen button of the toolbar (shown
  on mouse movement or a tap, hidden after 2 s) calls `requestFullscreen` on the stage element. The
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

## Link preview

A deck published on the web says what a link preview shows: `<meta>` tags in the head and,
when lispresen draws it, a PNG of the title slide beside the HTML.

### Options and tags

| Option | Tags |
|--------|------|
| `:description "text"` | `<meta name="description">`, and `og:description` with a page URL |
| `:page-url "https://..."` | `og:type` (`website`), `og:url`, `og:title` (the deck title, else `lispresen`), `twitter:card` (`summary_large_image` with an image, else `summary`) |
| `:page-image "intro.png"` | `og:image`, the path resolved against the page URL; for an image lispresen draws, `og:image:width` and `og:image:height` |
| `:page-image-font "f.ttf"` or `("regular.ttf" "bold.ttf")` | none: the fonts the image is drawn in |

A deck that sets none of them gets no tags, so its output is unchanged. `:page-url` is
separate from `:url`, neither derived from the other: `:url` is where the title slide's QR
code sends the audience (the intro deck's goes to the source tree), the page URL is where
this HTML file is published, and the two are different addresses as often as not. Nor is
the image derived from the page URL (`.html` to `.png`): a tag naming an image nobody
wrote is a broken preview, so the deck names it. A relative `:page-image` is drawn by
lispresen and written at that path beside the HTML; an absolute URL (or a path from the
root, `/i.png`) is an image hosted elsewhere, named as given and not drawn. X falls back to
the Open Graph tags for the title, description and image, so only `twitter:card` is
written.

`render` returns the drawn image as a third value, `(path octets)`, so it stays a pure
function; the CLI writes it at the path relative to the HTML file (the working directory
when the HTML goes to standard output), and `--check` writes nothing. Under `--embed` the
HTML stays self-contained except for the PNG: a crawler fetches `og:image` by URL and takes
no `data:` URI. The browser editor does not ask for the image, so it reads no font and its
HTML is the same.

### The page image

1200 x 675 px, the canvas at 5/8. 1200 px is the width Facebook and X ask of a large
preview; 16:9 is the slide's own shape, so the slide fills the image without bands, and a
platform that crops to 1.91:1 (1200 x 630) takes 22 px off the top and bottom, inside the
slide's 80 and 72 px margins (50 and 45 px here). The 5/8 scale makes a canvas px exactly 10
sub-pixels, so every coordinate stays an integer. A 4:3 deck stands in the middle of the
same frame (900 px wide), the background filling the sides.

What is drawn follows the title slide as the browser draws it: the background; the
midnight arcs (the outer half of the ring between each ellipse and one 3 px narrower); the
logo; each title line and each line under it at the rows `title-rows` gives (the function
the QR placement uses), broken where the fit solver broke them, centered on its own
advance width and set on the baseline the font's ascender and descender center in the line
box, as CSS does; the QR code in the solver's corner, each module edge on a pixel edge as
`crispEdges` puts it, and its URL under it, underlined at the font's `post` underline
position. The midnight title's `letter-spacing: -.01em` is applied; its `palt` is not, a
GPOS feature, and neither is kerning: text is set on the `hmtx` advances.

The logo is drawn when it is an SVG of the subset a mark uses: `svg`, `g`, `rect` (`rx`),
`circle`, `ellipse`, `line`, `polyline`, `polygon` and `path` with every command but arcs,
`fill` and `stroke` in `#rgb`, `#rrggbb`, `black` or `white`, `stroke-width` and
`stroke-linecap`, joins drawn round. An SVG with anything else (a transform, a style, a
gradient, text, an arc) or a raster logo is left out with a warning rather than drawn
wrong; decoding PNG or JPEG would take an inflate or a DCT and color conversion for a case
the intro deck does not have.

### Fonts

Text needs outlines, and lispresen has no font of its own: a Japanese font is megabytes,
and vendoring one would put them in every compiled artifact for a feature most renders do
not use. The deck names the font file, relative to the deck as its images are, read
through the `read-file` the host passes: one file for every line, or a regular and a bold
one, the title being bold as the slide's is. Only TrueType outlines (`glyf`, quadratic)
are read; an OpenType font with CFF outlines (`OTTO`, cubic charstrings with their own
subroutine interpreter) or a collection is an error. `truetype.lisp` reads `head`, `hhea`,
`hmtx`, `maxp`, `loca`, `cmap` (formats 4 and 12), `post` and `glyf`, composite glyphs
included; hinting is not run, as a browser on macOS does not run it either.

The intro deck's fonts are BIZ UDPGothic Regular and Bold (Morisawa, SIL Open Font
License 1.1 with no Reserved Font Name, `examples/fonts/OFL.txt`), from Google Fonts'
`ofl/bizudpgothic`: TrueType, of the family in the deck's font stack (`BIZ UDGothic` is
its fixed-pitch sibling), and proportional like Hiragino Sans, which the slides are
measured on. Each is cut down with `pyftsubset` to ASCII and the characters of the title,
11 KB instead of 4.6 MB, hints and layout tables dropped (README, Link preview). A character
the font lacks is drawn as its missing glyph (a box) and reported, so a title edited past
the subset fails `check.sh` until the subset is cut again.

### Rasterizer and PNG

`raster.lisp` fills polygons in sub-pixels, 16 to a pixel, by the nonzero rule: on four
sample rows a pixel, each edge's crossing exact to a sixteenth, the covered length summed
per pixel (0 to 64) and blended onto it in 17 levels. A glyph's quadratic curves, and the
logo's quadratic and cubic ones, are flattened into lines to within an eighth of a pixel
(the segment count from the control polygon's second difference); a stroke is the union
of a quadrilateral per segment and a disc per vertex, each turned the same way so the
nonzero rule unites them; ellipses and rounded corners come from a 65-entry sine table.
Integers throughout, `floor` for every division, so every backend draws the same pixels.

The image is an 8-bit palette PNG: each blended color is added to the palette when first
met (60 colors for the intro deck), the nearest one taken past 256, so a pixel is one byte
and the encoder handles a third of the bytes RGB would. `png.lisp` writes IHDR, PLTE, one
IDAT and IEND with their CRC-32, and the zlib stream (Adler-32) as one fixed-Huffman
deflate block whose matches are the two a drawn slide repeats: the byte before (distance
1, a run of background) and the row above (distance one row), chosen greedily. The intro
deck's image is 25.8 KB. Drawing and encoding add about 5 s to a render on the
interpreter (2026-10-23: 2.4 s to 7.2 s, the encoder 2.1 s of it); the compiled backends
take a fraction of that.

Checked 2026-10-23 against headless Chrome's screenshot of the title slide at 1200 x 675
(Chrome 155, Hiragino Sans): the intro deck and a midnight 4:3 deck with a two-line title
match row for row, the logo, the arcs and the QR code in place; the lines differ only by
the fonts' widths (BIZ UDPGothic's Latin is wider than Hiragino's), the URL under the QR
code ending where the browser's ends.

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
lispresen new FILE [--lang ja|en] [--force]
lispresen --version
```

- `DECK` and `OUT` accept `-` for stdin / stdout. `OUT` defaults to `DECK` with `.html`.
- A drawn `:page-image` is written relative to `OUT` (Link preview, above).
- `--embed` inlines images (HTML output, above).
- `--check` lints without writing output. `--strict` makes warnings fail.
- Exit status: 0 success, 1 errors (or warnings under `--strict`), 2 usage.
- Diagnostics go to `*error-output*`.
- `new` and `--version` are below.

### Build information

`--version` prints `{"version": "0.1.0-SNAPSHOT", "gitCommit": "42ee82c"}`, the shape and
key names of `rontolisp --version`. It leaves out rontolisp's `buildTimestamp` and
`gitBranch`: a timestamp would make two builds of one commit differ, and every leg of
`check.sh` prints the same line.

- **The version is the repository's `pom.xml`.** lispresen has no release cycle of its
  own: the workflow builds it with the rontolisp release of the pom version and publishes
  it as `lispresen-<pom version>`, so a second version number in lispresen could only
  disagree with the tag.
- **Both are read when lispresen is built**, by the macro `build-info`
  (`src/build.lisp`), which expands into a literal: on the compilers when a jar, module or
  executable is made, on the interpreter and SBCL when the source is loaded, which is
  when they "build" it. No backend can run a program while it compiles
  (`uiop:run-program` is not implemented on rontolisp, and WASM has no processes), so
  the commit is read from `.git` as files: `HEAD`, the loose ref it names or its line in
  `packed-refs`, through the `.git` file and `commondir` of a linked work tree. A shallow
  checkout has all of these. An environment variable set by the workflow would need
  setting by hand for every local build. `#.` would read the same files; a macro keeps
  the reading in ordinary functions.
- The root is found from the file being compiled: `*load-truename*`, bound in a file a
  literal `load` splices (relative to the compiler's working directory on the compile
  path, absolute elsewhere), never in the top-level file. So a use sits in a top-level
  form of a loaded file; the interpreter expands a function body's macros when it first
  runs, outside the load.
- Outside a repository (a copy of `lispresen/` alone) each is `unknown`, and the build
  succeeds.

### Starter deck

`lispresen new FILE` writes a deck to begin from. A subcommand, as `cargo new` is, since
it takes none of the rendering options; a deck file named `new` is rendered as `./new`.

- The decks are `starter/ja.lisp` and `starter/en.lisp`, read into lispresen when it is
  built (`build-text`, as above), so they are decks that render and review as written,
  with no string escaping. `:date "YYYY-MM-DD"` is replaced by the date.
- Japanese by default, as the tuning targets Japanese decks; `--lang en` for English.
  The comments are English in both, as the README they point to is. The English deck
  sets `:lang "en"`. `:lang` changes the declaration only: phrase segmentation stays
  BudouX's Japanese model. An inline `(lang ...)` form for a phrase in another language
  is not added; the page-level tag is what assistive technology needs first.
- Each shows the header (`:description`; `:theme`, `:url`, `:page-url` and `:page-image`
  as comments), sections, a list with notes, a statement, inline forms, runnable code,
  columns with a callout, a table and a flow. An image is a comment: the deck would name
  a file `new` does not write.
- The date is today in UTC. rontolisp decodes time as GMT on every backend (WASI has no
  time zone), so SBCL is asked for UTC too, and the deck is the same on every backend.
- An existing file is never replaced without `--force`: `open` with `:if-exists nil`,
  so nothing can come between the check and the write. `-` writes to standard output.

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
  cli.lisp               CLI host: arguments, file I/O, exit status, new, --version
  src/
    all.lisp             loads the sources below in order, but build and starter
    package.lisp         defpackage lispresen
    build.lisp           what the build reads: version, commit, files as text (CLI only)
    reader.lisp          top-level forms with offsets and line numbers
    template.lisp        define templates: substitution on the read forms
    model.lisp           structs and parsing with diagnostics
    embed.lisp           code files as text (UTF-8), images as data: URIs (base64)
    qr.lisp              QR codes of the deck's URL as SVG
    diagram.lisp         the drawings of flow, bars and cells
    budoux-ja.lisp       generated BudouX weights (Apache-2.0 notice)
    phrase.lisp          phrase segmentation
    metrics.lisp         code point widths, line breaking
    layout.lisp          layout selection, type scale, fit solver
    lint.lisp            readability checks, contrast ratio
    theme.lisp           built-in themes, theme overrides
    html.lisp            HTML escaping and emission, stylesheet, preview tags
    png.lisp             CRC-32, Adler-32, deflate, the PNG file
    truetype.lisp        glyph outlines and advances from a .ttf
    raster.lisp          polygon filling, strokes, text, the SVG subset of a logo
    page-image.lisp      the title slide drawn as the page image
    player.lisp          the player script as a string constant
    highlight.lisp       tokenizers: Lisp / Scheme / Clojure, console, other languages
    morph.lisp           the token pairing of a :morph slide's code
    lispresen.lisp       render
    starter.lisp         the starter deck lispresen new writes (CLI only)
  starter/
    ja.lisp, en.lisp     the starter decks, read in when lispresen is built
  skills/lispresen/
    SKILL.md             the agent skill: install, write, render, check (README)
    references/          its condensed deck format and diagnostics
  web/
    lispresen-web.lisp   browser reactor
    index.html           editor page
    build.sh             compiles the reactor to lispresen.wasm
  test/
    lispresen-test.lisp  rove: segmentation, metrics, solver, parser diagnostics
  examples/
    intro.lisp           a talk introducing lispresen
    intro.html           its golden output, rendered with --embed
    intro.png            its golden page image
    fonts/               the page image's fonts: BIZ UDPGothic subsets, OFL.txt
    pipeline.png         the image intro.lisp shows (its flow, drawn as a PNG)
    photo.svg            the picture of its full-bleed and split slides
    logo.svg             its title slide's logo
    fib.lisp             the code file one of its slides shows lines of
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
  `examples/`, so its files resolve; each leg in its own copy of it, where the page image
  is written), and requires each output to equal `examples/intro.html` and each page image
  `examples/intro.png` byte for byte. Each leg must also print the `--version` line of
  the pom version and the commit checked out, and write a starter deck that renders with
  no diagnostic under `--strict` and equals the first leg's but for its date (the
  workflow runs the same check, `--exe`, on each published executable). The golden deck is a talk introducing lispresen
  that shows each feature twice under one title, as the deck code on one slide (kicker
  `書き方`) and as the result on the next (`表示例`); a player feature gets its
  instructions (`使い方`) and a slide to try it on (`試す`). It has runnable Lisp, Scheme
  (`:fresh`, `:stdin`) and Clojure (`:timeout`) blocks, a columns slide built from a
  `&rest` template and a template the samples of deck code go through, a lead above a row
  of a code column and a list column, console transcripts, Java, Python and shell
  blocks, dark code panels
  (`:code :dark`), section kickers (replaced on the paired slides, dropped on one), a
  named agenda placed by `(agenda)` after an opening slide, a title slide with an
  embedded SVG logo and a QR code (drawn as the page image too, with the description and
  page URL in the head), a statement, a
  code morph, a quote with a source, tables (a row header, `:align`, a `:colspan` cell,
  numeric columns), every inline form, an embedded PNG, a full-bleed slide and a split one
  (`:image-right`, `:share 1/3`, the ratio read on every backend), ordered and unordered lists, one
  revealed by steps, a flow, two cells drawings in columns (one with dotted pairs), a bar
  chart with a source line under it, a progress bar, `:layout` and
  `:class`, and eight callouts (two inline ones, one of them a step; by `:at`, a code
  line, a flow box, a cells atom and a bar; one on an image; and a step one named by
  `:at` in runnable code read from a file by `:lines`, which `:steps` brings forward line
  by line), so the highlighter, the emitted player, template expansion, diagram drawing,
  callout placement and the binary read, UTF-8 decoding and base64 are in the
  comparison, and in the page image the TrueType reader, the rasterizer, the logo's SVG
  (a rounded rectangle, quadratic paths and round-capped strokes) and the PNG encoder.
- **Unit tests** (rove, `test/lispresen-test.lisp`, run by `check.sh` on the
  interpreter; on SBCL rove needs bordeaux-threads, which the repository does not
  vendor): phrase boundaries checked against BudouX's own output, measurement and
  line breaking at their exact boundaries, step selection, the hard-floor error, the layout a lone untitled image takes and
  the text region of a split slide, the
  three table-width cases, column widths beside a full-width block, a wrapping console,
  the QR encoder against the standard's examples and its version boundaries, the title
  slide's logo and QR code, the preview tags (none without the options, every one with
  them, an image lispresen does not draw), URL resolution and the preview diagnostics
  (a font that cannot be read or is not TrueType, a missing glyph, a logo left out),
  numeric column detection, parser diagnostics with line
  numbers (`#.` and dotted lists among them), the balanced rows of a flow, a cells datum
  with dotted pairs and a list that looks like a template use, the callout sides on a flow
  box, a bar and a cells atom, bar values and lengths, the diagram diagnostics, the room
  a source takes and its diagnostics, the agenda, its place at `(agenda)` and their
  slide numbers and diagnostics, template
  expansion and its diagnostics and
  bounds, the side each kind of callout anchor gets, an unplaceable callout, the
  order of a slide's page advances (items, code lines, a bubble after what it wraps) and
  a highlight string across a line end, the morph pairing (a moved form, a string
  token split at a line end inside `:steps` lines) and its diagnostics, the callout and
  `:steps` diagnostics, the kicker diagnostics, base64 padding, the CRC-32 and Adler-32
  check values, deflate and a whole PNG against what zlib reads, the
  embedding diagnostics, code files (UTF-8 up to four bytes, `:lines`, the language by
  extension, their diagnostics), the highlighting of every language from a table of
  (language, source, spans) rows (a comment marker in a string, a string in a comment), and both starter decks rendering with no diagnostic. The golden deck covers
  emission, so HTML is not asserted piecewise.
- **Calibration and runnable code** (manual, in Chrome and Safari, when the width table,
  the font stacks or the player change): the page `tools/calibrate.lisp` writes must
  report no paragraph where the model predicts fewer lines than the browser; a deck with
  Lisp, Scheme and Clojure blocks must run each on the hosted and on a local runtime,
  share definitions across slides, and recover from an infinite loop by timeout; the
  presenter window must follow navigation both ways, every kind of step included, show edits
  and outputs, and forward Run; a callout must sit beside its anchor after the runtime
  check; a deck of three versions of a function must morph stepping forward and back,
  driven from either window, and switch at once on a jump, under reduced motion and
  after an edit; Start must put the audience window in full screen on the other display,
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

Columns beside blocks (2026-10-22, Chrome 155 through DevTools and Safari 26.3 through
`safaridriver`): the golden deck and a deck with a lead above a code and console column
and a list column, a callout on a list item and one with `:at` on a wrapped console row,
are laid out with the runtime check making no correction in either browser; every column
has the width written, and each console panel is as tall as the rows the model counted
(the output wrapped at the same character in both browsers).

Diagrams (2026-10-22, Chrome 155 through DevTools and Safari 26.3 through
`safaridriver`): the golden deck's flow and cells slides, and a deck with a flow wrapped
into two rows, labels in bold and inline code, an alist, a list deeper than the slide is
wide and diagrams in columns, render as laid out in all four built-in themes; the runtime
check makes no correction, and the bubble naming a flow box stays on the side the static
layout chose, 1 px off (the half pixel of centering).

Code morph (2026-10-23, Chrome 155 through DevTools and Safari 26.3 through
`safaridriver`): a deck of three versions of a function (a memo table added, then a loop,
the last with `:steps`) morphs stepping forward and back, also when driven from the
presenter window; a jump (`#N`, the overview, Home), `prefers-reduced-motion` (emulated
in Chrome, `matchMedia` answering reduce in Safari) and an edited block switch at once,
and Reset brings the morph back. Tokens as `inline-block` leave every panel's size as it
was. In Safari the screen was locked, which freezes a page's animation timeline, so the
playback was checked by seeking the animations: a paired token at 0, 300 and 600 ms sits
at its old place, halfway (ease-in-out) and its new place, the panel's box likewise, and
finishing removes everything the morph added. A copy of a removed token rests at opacity
0, so one the cleanup has not reached yet shows nothing.

Bar charts, sources and the progress bar (2026-10-23, Chrome 155 through DevTools and
Safari 26.3 through `safaridriver`): the golden deck's bar chart and a deck with a chart
in a column (a zero value among the bars) and a statement with a source lay out with the
runtime check making no correction; every label sets on the lines counted and every
value in its box in both browsers, and the bubble naming a bar stays below its value, 1 px
off. The progress bar follows navigation and is absent from the overview.

Run buttons (2026-10-23, Chrome 155 through DevTools and Safari 26.3 through
`safaridriver`): the golden deck's Clojure and Scheme columns and a deck with runnable
code in a 344 px column, in a column whose first line is its longest at 28 px, in three
code columns and at full width with a long first line: with each label (Loading, Failed,
Run) no code text meets the buttons, every panel is as tall as the model counted, the
editor's padding equals the panel's, and the runtime check makes no correction.

Runtime from `file://` (2026-10-23, Chrome 155 through DevTools and Safari 26.3 through
`safaridriver`): a deck with Lisp, Scheme and Clojure blocks and an infinite loop, on the
hosted runtime and on a runtime directory, each opened over HTTP and as a file. Three of
the four run every block, share definitions and recover from the timeout in both
browsers; the runtime directory opened as a file fails in both, with the message to serve
the deck over HTTP in the output, the Run button's tooltip and the presenter window.
Before the Blob carried the script's text, the hosted runtime opened as a file failed in
Safari only, at the worker's `importScripts`.

Checked by hand only, since browser automation refuses full screen: Start placing the
audience window on another display, and `Esc` under the Keyboard Lock.
