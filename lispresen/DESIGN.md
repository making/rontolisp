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

## Goals and non-goals

Goals:

- The author writes content, not geometry. No coordinates, no font sizes in the deck.
- Readability is enforced: text never drops below a floor size, Japanese lines break
  between phrases, and a slide that cannot meet the floor is reported, not silently
  shrunk.
- Byte-identical output for the same deck on every backend.
- One HTML file. Without runnable code it has no network dependency at all.

Non-goals: WYSIWYG editing, animations/transitions, free-form positioning, PowerPoint
export.

## Deck format

A deck file is a sequence of top-level forms: one `deck` header, then `slide` and
`section` forms.

```lisp
(deck :title "lispresen"
      :author "Toshiaki Maki"
      :date "2026-10-22"
      :theme :light            ; :light | :dark | :high-contrast | (theme ...)
      :aspect :16x9            ; :16x9 | :4x3
      :runtime :hosted         ; :hosted | "runtime/" | :none
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
  (code :lang "clojure" :output-lines 2 "
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
| Slide-level | `deck`, `section`, `slide` (`:title`, `:layout`, `:class`) |
| Block | bare string (= `p`), `p`, `ul`, `ol`, `li`, `code`, `image`, `quote` (`:cite`), `column`, `table`, `notes` |
| Inline | string, `b`, `i`, `tt`, `link` (`(link "url" ...)`), `mark` |

Rules:

- **The deck is data and is never evaluated.** Compiled `eval` cannot see `defmacro`,
  backquote or CLOS definitions (`doc/en/guides/eval-limitations.md`), so evaluating the
  deck would make the language a deck may use differ per backend. Reading only is
  identical everywhere and makes a deck file safe to render from an untrusted source.
- Tags are matched by `symbol-name`, not by symbol identity, so the package the runtime
  reader interns into does not matter. Options are keyword/value pairs at the start of
  a form; the first argument that is not a keyword starts the content.
- The runtime reader does not support backquote, `#.`, `#+`/`#-`
  (`doc/en/guides/read-load-limitations.md`); the deck format needs none of them.
  Characters are code points on every backend (`.kb/string-index-cost.md`), so Japanese
  strings measure and slice identically everywhere.
- Top-level forms instead of one enclosing `(deck ...)`: each form is read separately,
  and the stop index `read-from-string` returns gives its start offset, hence a line
  number for every diagnostic. One enclosing form would leave only "slide 7".
- `code` content is dedented (the common leading whitespace removed, a leading blank
  line dropped) so it can be indented with the surrounding source. A `"` inside code is
  written `\"`.
- Reuse within a deck (phase 3): `(define name (params...) body)` declares a template
  that lispresen expands by substitution. This is lispresen's own expansion, not Lisp
  macros, for the same reason as above.

## Pipeline

```
source string
  -> read      top-level forms + start offsets            (reader.lisp)
  -> parse     validated model: deck / slide / block / inline structs,
               diagnostics for malformed or unknown forms  (model.lisp)
  -> segment   Japanese phrase boundaries                  (phrase.lisp)
  -> layout    layout selection + fit solver -> per-slide geometry and sizes
                                                           (layout.lisp, metrics.lisp)
  -> lint      readability diagnostics                     (lint.lisp)
  -> emit      HTML + CSS + player JS as one string        (html.lisp, player.lisp)
```

The core is a pure function from a string to a string:

```lisp
(lispresen:render source &key theme runtime) ; => (values html diagnostics)
```

It does no I/O, so every host (CLI, browser, a Java caller via `rontolisp:jvm-export`)
is a thin shell over it. A diagnostic is `(severity line slide-number message)`.
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

Sizes only step down from the default, never up. A sparse slide keeps the default size
rather than inflating, so text size stays consistent across the deck; only the statement,
section and title layouts have their own larger scale. Line heights are those of
Japanese body text (1.5 and up); the Latin-oriented 1.2-1.4 crowds kanji.

### Fonts

System fonts only, so the file needs no network: `"Hiragino Sans", "Noto Sans JP",
"BIZ UDGothic", "Yu Gothic", sans-serif`, and a monospace stack ending in `"BIZ
UDGothic"` for code. Every Japanese face in the stack sets kana and kanji full-width,
which is what makes the width model exact for Japanese. For that reason proportional
Japanese faces (BIZ UDPGothic, Meiryo UI) are not in the stack, and neither `palt` nor
`text-autospace` is turned on: each changes widths in a way the model would have to
reproduce per font.

### Text measurement

Width of a code point, in milli-em, by class:

| Class | Width |
|-------|-------|
| Kanji, kana, full-width punctuation and forms | 1000 |
| Wide Latin (`m w M W @`) | 900 |
| Other upper case | 680 |
| Digits | 580 |
| Other lower case and punctuation | 550 |
| Narrow (`i j l . , ; : ' ! \|` and space) | 300 |
| Monospace (`code`, `tt`) | 600; full-width 1000 |

Bold adds 5% to Latin, and the total carries a 4% safety margin. Japanese text is exact
under the fonts above; the Latin rows are a conservative estimate, calibrated with a
calibration deck (see Verification) rather than derived from font files, which lispresen
does not ship.

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
| No title, one short paragraph | `statement` (centered, statement scale) |
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
   longest line and its line count plus the output box of runnable code, a table by the
   column widths and row heights below. Height is monotonic in size, so the chosen size
   is the largest step that fits.
4. Columns share the smallest size any of them needs, so side-by-side text matches.
5. If even the hard floor overflows, the slide is emitted at the hard floor, marked, and
   reported as an error: lispresen does not make text unreadable to make it fit.

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
solver must know each row's height. Per column, the minimum width is the widest
unbreakable unit (phrase or Latin word) in any of its cells and the maximum is the widest
cell set on one line, both plus horizontal padding. Then:

1. If the maxima sum to at most the body box width, every column gets its maximum: no
   cell wraps, and the table keeps its natural width.
2. Otherwise each column gets its minimum plus a share of the remaining width
   proportional to its maximum minus its minimum (the CSS automatic layout rule), so a
   column of long text wraps before a column of short labels does.
3. If the minima alone exceed the box, the table steps down a size; at the hard floor
   that is an error.

A row is as tall as its tallest cell (lines x line height plus vertical padding), and
the table height (rows, header, caption) enters the fit solver like any block. The
widths are emitted as `<colgroup>` pixel widths under `table-layout: fixed`, and cells
use the same `<wbr>` phrase breaks as body text, so the browser wraps each cell where
the model did. The header row is bold on a tinted background; rows are striped when
there are more than 4.

### Runtime check

The static estimate cannot see the font the presenting machine actually has. After
`document.fonts.ready`, the player measures each region; on overflow it moves the region
one step down the same scale (never below the hard floor) and logs a warning. Opening
the deck with `?check` shows these corrections as an overlay. JavaScript is a correction
on top of the static result, not a replacement for it, so the deck still lays out
correctly with JavaScript disabled and when printed.

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
  constructed from a cross-origin URL, so the player builds it from a Blob whose script
  `importScripts` the runtime by absolute URL and sets `config.wasm_path` to the
  absolute `.wasm` URL.
- When the deck contains runnable code, the worker starts loading as soon as the deck
  opens. Downloading 35 MB on the first Run click in the middle of a talk would stall
  it. The load state (loading / ready / failed) shows in the presenter window and as a
  small marker on code slides.
- Each language has one persistent environment (`rontoSetLanguage`, then `rontoEval`),
  so a function defined on one slide can be called on a later one, as the
  documentation site's Lisp cells share one interpreter. `:fresh t` runs the block as a
  whole program on a new interpreter instead (`rontoRunProgram`, with `:stdin "..."` as
  its standard input). The presenter window has a runtime reset.
- What is shown is what the playground shows: the printed output, then the echoed
  values; a result starting with `ERROR:` is shown in the error style.

### Editing and output

- Code is editable in place: click it, or press `E` on the slide. While editing, keys go
  to the editor instead of navigation, `Ctrl/Cmd+Enter` runs, `Esc` leaves (see Full
  screen for `Esc` in full screen), and a reset button restores the deck's text.
- The fit solver reserves an output box below the code, `:output-lines` lines (default
  3) at the code size, so running never pushes content off the slide; longer output
  scrolls inside the box. `:output "..."` pre-fills the box, which is also what print
  and PDF show.
- Runs execute in the audience window. Edits and outputs are broadcast to the presenter
  window over the same `BroadcastChannel` as navigation, and Run pressed in the
  presenter window is forwarded.
- Highlighting uses one tokenizer for the three languages (strings, comments,
  keywords, `[]`/`{}`/`#{}`, `#t`/`#f`), applied at build time; the editor re-highlights
  on input with the same rules ported to the player script.

## Readability lint

| Severity | Condition |
|----------|-----------|
| error | malformed or unknown form; overflow at the hard floor; a table row whose cell count (with `:colspan`) differs from the header's or the first row's |
| warning | a size below its warning floor; more than 6 items in one list; list nesting deeper than 2; an item longer than 3 lines; a title longer than 2 lines; code longer than 15 lines; a table with more than 7 body rows or 5 columns; full-width alphanumerics (`ＡＢＣ１２３`); `image` without `:alt`; theme contrast below 4.5:1 for body text or 3:1 for titles |

Diagnostics print as `talk.lisp:42: slide 7 "その場で動かす": warning: body text at
44px (floor 48px); consider splitting the slide`.

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
  the runtime check, a presenter window (`P`: current and next slide, notes, timer,
  runtime state, synchronized over `BroadcastChannel`), and the runnable-code worker,
  included only when the deck has runnable code.
- Speaker notes in `<aside class="notes">`, hidden in the audience view.

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
- From the presenter window (phase 2), "Start" puts the audience window in full screen
  on the external display through the Window Management API (`getScreenDetails`,
  `requestFullscreen({screen})`, Chromium); without it the audience window is moved to
  the projector by hand and `F` pressed there.

Images are referenced by their relative path, so the output is written next to the deck
by default and the paths stay valid. Embedding them as data URIs (`--embed`) needs a
binary read and base64 and is phase 3.

## Hosts

| Host | Entry | Build and run |
|------|-------|---------------|
| Interpreter | `cli.lisp` | `rontolisp lispresen/cli.lisp -- talk.lisp` |
| JVM | `cli.lisp` | `rontolisp lispresen/cli.lisp -o lispresen.jar && java -jar lispresen.jar talk.lisp` |
| WASM (WASI P1) | `cli.lisp` | `rontolisp lispresen/cli.lisp -o lispresen.wasm && wasmtime run --dir . lispresen.wasm talk.lisp` |
| WASM component | `cli.lisp` | `rontolisp lispresen/cli.lisp --component -o lispresen.wasm && wasmtime run --dir . lispresen.wasm talk.lisp` |
| Browser | `web/lispresen-web.lisp` | `--no-wasi` reactor exporting `render` (`:params '(:string) :returns :string`) |

CLI:

```
lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|none] [--check] [--strict]
```

- `DECK` and `OUT` accept `-` for stdin / stdout. `OUT` defaults to `DECK` with `.html`.
- `--check` lints without writing output. `--strict` makes warnings fail.
- Exit status: 0 success, 1 errors (or warnings under `--strict`), 2 usage.
- Diagnostics go to `*error-output*`.

The browser host is an editor page: the deck source on the left, the rendered deck in an
`iframe srcdoc` on the right, re-rendered on a short debounce, diagnostics listed below,
and a download button for the HTML. The reactor returns the HTML and the diagnostics in
one string (a fixed separator line), since a reactor export returns a single value. The
lispresen reactor (a few hundred KB) renders; the preview's runnable code loads the
playground runtime like any other deck.

## Directory layout

```
lispresen/
  DESIGN.md              this file
  README.md              usage, offline runtime setup
  check.sh               cross-backend golden check + unit tests
  cli.lisp               CLI host: arguments, file I/O, exit status
  src/
    package.lisp         defpackage lispresen
    reader.lisp          top-level forms with offsets and line numbers
    model.lisp           structs and parsing with diagnostics
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
    build.sh
  test/
    lispresen-test.lisp  rove: segmentation, metrics, solver, parser diagnostics
  examples/
    intro.lisp           a deck presenting lispresen itself
    intro.html           its golden output
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
  WASM and the WASM component, renders `examples/intro.lisp` on each through stdin and
  stdout, and requires each output to equal `examples/intro.html` byte for byte.
- **Unit tests** (rove, `test/lispresen-test.lisp`, run by `check.sh` on the
  interpreter): phrase boundaries on a few sentences checked against BudouX's own
  output, measurement and line breaking (Latin, Japanese, kinsoku fallback), step
  selection at the boundaries, the hard-floor error, the three table-width cases,
  numeric column detection, parser diagnostics with line numbers. The golden deck covers emission, so HTML is not asserted piecewise.
- **Calibration and runnable code** (manual, in Chrome and Safari, when the width table,
  the font stacks or the player change): a calibration deck of long Latin and Japanese
  lines at each body step opened with `?check` must show no correction; a deck with
  Lisp, Scheme and Clojure blocks must run each on the hosted and on a local runtime,
  share definitions across slides, and recover from an infinite loop by timeout. The
  documentation site's Run cells are verified the same way; no committed test drives a
  browser.

## Phases

1. Reader, model, phrase segmentation, metrics, fit solver, layouts `title` / `section`
   / `statement` / `standard` / `code` / `image` / `table`, HTML and navigation, full
   screen, CLI, `check.sh`.
2. Runnable code, presenter window, `columns`, `quote`, overview, print CSS,
   runtime check, highlighting, themes and the contrast lint, browser editor.
3. `define` templates, `--embed` images.

## Open points to verify first

- Literal `load` of files that contain `defpackage` / `in-package`, nested two levels
  (`cli.lisp` -> `src/*.lisp`), inlines correctly on all compiled backends.
- The runtime reader of the compiled backends reads deck text with Japanese strings and
  keyword options identically to the interpreter (the golden check answers this).
- The playground runtime loads in a Blob worker from the hosted origin, and
  `rontoSetLanguage("clojure")` followed by `rontoEval` keeps Clojure definitions across
  calls as it does for Lisp (`PlaygroundRepl.eval` keeps one session per language).
- The BudouX Japanese model's size as Lisp data, and the compiled WASM module size it
  adds; if it dominates the reactor, the weights move to a compact string encoding
  decoded at startup.
