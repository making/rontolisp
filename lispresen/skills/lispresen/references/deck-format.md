# lispresen deck format

A condensed reference for writing decks. The full description of every form is the README:
https://github.com/making/rontolisp/blob/develop/lispresen/README.md

Contents: file structure, the header, slides, blocks, inline forms, tables, diagrams,
callouts, page advances, code, images, videos, templates, themes, link preview, limits, mistakes
to avoid, diagnostics and their fixes.

## File structure

```lisp
;; Comments are ; and #| ... |#.
(deck :title "lispresen" :author "Name" :date "2026-10-24"
      :agenda "Outline" :kicker :section :progress t :page-numbers t)

(slide :title "Who I am" "An opening slide before the agenda.")
(agenda)                          ; the agenda slide goes here

(section "Why S-expressions")     ; a section divider (the agenda, highlighted, with :agenda)

(slide :title "Slides are data"
  (ul "Diffs review like code"
      (li "Structure is explicit" (ul "No hidden styles")))
  (notes "Shown only in the presenter window."))

(slide "A slide of one short paragraph and no title is shown large.")
```

Top-level forms: one `deck`, then `section`, `slide`, `agenda`, `define`, in any order.
Options are keyword/value pairs at the start of a form; the first argument that is not a
keyword starts the content.

## The header: (deck ...)

| Option | Meaning |
|---|---|
| `:title "t"` | adds a title slide (title, `:author`, `:date`) |
| `:author`, `:date` | lines under the title |
| `:logo (image "logo.svg" :alt "...")` | image above the title |
| `:url "https://..."` | QR code of the URL, with the URL, in a free corner of the title slide |
| `:theme` | `:light` (default), `:dark`, `:high-contrast`, `:midnight`, or `(theme ...)` below |
| `:lang "en"` | the page's `<html lang>` (BCP 47 tag, default `ja`); set it for a non-Japanese deck; declaration only, phrase breaking stays Japanese |
| `:aspect` | `:16x9` (default) or `:4x3` |
| `:agenda t` / `:agenda "Title"` | agenda slide listing the sections; each section divider shows it with that section highlighted |
| `:footer "text"` | one line at the left of the bottom band on every slide but the title slide (a hashtag, a handle, a label); `(slide :footer nil)` drops it |
| `:kicker :section` | each titled slide shows its section title above its title |
| `:progress t` | thin progress bar along the bottom edge |
| `:page-numbers t` | page numbers |
| `:callouts :step` | every callout appears on a page advance |
| `:runtime` | where runnable code loads its runtime: `:hosted` (default, network), `"runtime/"`, `:none` (static code) |
| `:description`, `:page-url`, `:page-image`, `:page-image-font` | link preview (below) |

`(agenda)` places the agenda slide; it lists only the sections after it. It does nothing
without `:agenda` (a warning), and needs at least one section after it.

## Slides: (slide ...)

`(slide [:title "t"] [:kicker "k"] [:footer nil] [:layout name] [:class "c"] [:morph t] block...)`

- The layout comes from the slide's shape: no title and one short paragraph is a
  statement (large, centered); no title and one image without `:caption` is `full` (the
  image covers the slide); title plus one image, code, table or quote gets that
  layout; `column`s give columns; anything else is standard. `:layout` rarely helps,
  except `:image-left` / `:image-right` (Images, below), which shape never chooses.
- `:kicker "text"` is a small label above the title; `:kicker nil` drops the deck's.

## Blocks

| Form | Meaning |
|---|---|
| `"text"` or `(p inline...)` | paragraph |
| `(lead inline...)` | paragraph in the muted color, e.g. above columns |
| `(ul item...)`, `(ol item...)` | list; an item is a string, an inline form or `(li inline... [nested list])`; `:step t` reveals one item per advance |
| `(code [:lang "lisp"] "text")` | code block; common indentation removed. `lisp`, `scheme`, `clojure` are highlighted and runnable; `console` is a shell transcript (`$ ` lines are commands); `java`, `kotlin`, `javascript`/`js`, `typescript`/`ts`, `c`, `cpp`, `go`, `rust`, `python`/`py`, `bash`/`sh`, `sql`, `json`, `yaml`/`yml` are highlighted, never run; any other `:lang` is plain |
| `(code :file "fib.lisp" [:lines (4 6)])` | code from a UTF-8 file beside the deck; `:lang` defaults by extension (`.lisp`, `.java`, `.py`, `.sh`, `.json`, ...) |
| `(quote [:cite "who"] inline...)` | quotation |
| `(column block...)` | consecutive columns stand side by side; widths come from content |
| `(image "path" :alt "text" [:caption "text"] [:fit :contain] [:share 1/3])` | image fitted into the remaining space; full-bleed or split (Images, below) |
| `(video "demo.mp4" :alt "text" [:poster "demo.png"] [:autoplay t] [:muted t] [:loop t])` | video laid out as an image, same options (Videos, below) |
| `(table ...)` | table (below) |
| `(flow ...)`, `(bars ...)`, `(cells ...)` | diagrams (below) |
| `(notes "text")` | speaker notes, presenter window only |
| `(source inline...)` | one muted line at the bottom naming a source; one per slide |
| `(say "comment" ... block)` | callout (below) |

Inline forms: `(b ...)`, `(i ...)`, `(tt "code")`, `(mark ...)` (highlighter),
`(accent ...)` (accent color), `(link "url" inline...)`. They nest:
`(b (accent "solution"))`. Mix strings and forms inside `p`, `li`, table cells:
`(p "Read with " (tt "read-from-string") ".")`.

## Tables

```lisp
(table :header ("Backend" "Start" "Size (KB)")
       :caption "hello world" :row-header t :align (:left :left :right)
  ("JVM" "normal" "12")
  ("WASM" "fast" "1,840")
  ((cell :colspan 2 "Total") "1,852"))
```

Every row has as many cells as the header (a `:colspan` cell counts its span). Cells are
inline content only: no lists or code blocks. Numeric columns right-align automatically.
At most 7 body rows and 5 columns.

## Diagrams

```lisp
(flow "read" "parse" (b "layout") ("emit " (tt "html")))       ; boxes joined by arrows
(bars :unit " KB" ("JVM" 297) ("WASM" 788) ("native" "6,700"))  ; horizontal bar chart
(cells '(a (b c)))                                             ; box-and-pointer drawing
```

`bars` values are integers or decimals written as strings (`"2.1"`, `"1,840"`); a float
(`2.1`) is an error. `cells` takes one quoted list (this is the one place `'` belongs).
At most 8 flow boxes and 7 bars.

## Callouts

```lisp
(li "Lines break " (say "BudouX's model" (mark "between phrases")))
(say "returns n below 2" :at "(< n 2)"
  (code :lang "lisp" "(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))"))
```

`say` wraps what it points at; the comment is a string of at most 2 lines. `:at "text"`
names a piece of a code block (within one line) or a diagram label. lispresen places the
bubble; one with no room is an error. `:step t` shows it on a page advance.

## Page advances and morph

- `(ul :step t ...)` reveals items one per advance.
- `(code :steps ((1 2) "(fib 20)") ...)` brings lines forward: `(1 2)` is lines 1 to 2 of the
  block as shown, a string the first line holding it.
- `(slide ... :morph t (code ...))` animates its first code block from the previous
  slide's first code block.

## Runnable code

`lisp`, `scheme`, `clojure` blocks run in the deck (click or `E` to edit, `Ctrl+Enter` to
run). Blocks of one language share definitions across slides. Options: `:run nil` (show
only), `:timeout 5`, `:fresh t` (new interpreter), `:stdin "3 4"` (with `:fresh`). The
runtime (about 35 MB) loads from the network when the deck opens; `:runtime :none` or
`--runtime none` makes all code static.

## Images

`(image "img/arch.png" :alt "..." :caption "...")`. The path is relative to the deck.
Without `--embed` the HTML refers to the file by that path. `--embed` inlines `png`,
`jpg`, `gif`, `webp`, `avif`, `svg` and reports a missing file as an error. An SVG or PNG
does not follow the theme's colors; prefer a `flow`, `bars` or `cells` diagram where one
fits.

A photograph can run to the slide's edges:

```lisp
(slide (image "venue.jpg" :alt "the venue"))              ; full: covers the whole slide
(slide :title "Venue" :layout :image-left                 ; or :image-right
  (image "venue.jpg" :alt "the venue" :share 1/3)          ; 1/3, 1/2 (default) or 2/3
  (ul "5 minutes from the station" "200 seats"))
```

- The image is cropped to its area; `:fit :contain` shows it whole on the background.
- A split slide fits its title and other blocks in the width the image leaves (736 px of
  1920 at half). Keep that text short; `2/3` leaves room for a word or two.
- No text on a picture: a full slide has no title, caption, source or callout and shows
  no page number or footer; a split image takes no caption or callout. Put a photo
  credit in a split slide's `source` or in `notes`.


## Videos

A recorded demo, the fallback for a live one:

```lisp
(slide :title "Demo"
  (video "demo.mp4" :alt "fib evaluated in the REPL"
         :poster "demo.png" :autoplay t :muted t :loop t))
```

- A video is laid out as an image: in the body, `full` alone on an untitled slide, or
  `:image-left` / `:image-right`, with the same `:caption`, `:fit`, `:share` and callouts.
- `:autoplay t` plays it when its slide is entered (never on page load) and leaving the
  slide pauses it. A click or tap plays or pauses it; keys still move slides. Sound plays
  only after a key press or click in that window, so use `:muted t` when the demo has
  none.
- Print, the overview and the presenter window show `:poster` (or the first frame); set
  it, since Safari may show no frame before playing.
- `--embed` inlines `mp4` and `webm` (and the poster); above 20 MB it warns.

## Templates

```lisp
(define side (head &rest points)
  (column (p (b head)) (ul points)))

(slide :title "Interpreter and compiler"
  (side "Interpreter" "fast start" "full eval")
  (side "Compiler" "fast run" "one artifact"))
```

Pure substitution, nothing computed: a later `(side ...)` is replaced by the body, each
parameter by its argument, the `&rest` arguments spliced in. A template or parameter
cannot be named after an element (`slide`, `p`, `b`, ...); keywords are never
substituted, so a parameter `title` leaves `:title` alone.

## Themes

```lisp
(deck :theme :midnight)
(deck :theme (theme :base :dark :accent "#ff9f43"))
(deck :theme (theme :base :light :code :dark))   ; dark code panels on a light page
```

Colors are `#rgb` or `#rrggbb`; every text color is checked for 4.5:1 contrast.
`--theme NAME` on the command line overrides the deck's theme.

## Link preview

```lisp
(deck :title "..." :description "One sentence."
      :page-url "https://example.com/talk.html"   ; where the HTML is published
      :page-image "talk.png"                      ; title slide drawn as a 1200x675 PNG
      :page-image-font ("Regular.ttf" "Bold.ttf")); TrueType (glyf) fonts, relative to the deck
```

A relative `:page-image` is drawn and needs `:page-image-font` (TrueType `.ttf`, not CFF
`.otf`); without one it is an error. An absolute image URL is used as given and not
drawn. Leave all of these out for a deck that is not published.

## Limits (warnings unless noted)

| Thing | Limit |
|---|---|
| list items | 6 per list; nesting 2 levels; an item 3 lines |
| title | 2 lines |
| code block | 15 lines |
| table | 7 body rows, 5 columns |
| flow / bars | 8 boxes / 7 bars |
| source, callout | 2 lines |
| body text | starts at 56 px and steps down to fit; below 48 px is a warning, and a slide that does not fit at 40 px is an error |

## Mistakes to avoid

- **Quotes in strings.** A `"` inside a string is `\"`. An unescaped one ends the string
  early and the rest is read as symbols: `"He said "hi""` gives
  `error: unexpected HI on a slide`. Inside `code` text the same applies.
- **Backslashes.** Only `\"` and `\\` are escapes; `"\n"` reads as `n`. Write real line
  breaks inside the string; the common indentation of a code block is removed, so indent
  it with the surrounding source:
  ```lisp
  (code :lang "python" "
    def f():
        return 1")
  ```
- **Nothing is evaluated.** No `format`, `concatenate`, `let`, backquote, `#.` or `#+`:
  `(format nil ...)` is `error: unknown element format`. Use `define` templates for
  repetition.
- **`'` is the quote element.** `'x` reads as `(quote x)`, a quotation block. Do not quote
  lists in a deck, except the datum of `cells`.
- **Options come first.** `(code "text" :lang "lisp")` is `error: (code ...) takes strings
  only`; write `(code :lang "lisp" "text")`.
- **Lists are blocks.** A `ul` inside `p` is `error: unknown inline element ul`. A nested
  list goes in `(li "item" (ul ...))` or right after an item in the same list.
- **Unknown names are errors**, e.g. `(h2 ...)`, `(div ...)`, `(img ...)`. There are no
  font sizes, colors per element, positions or HTML.
- **Parentheses.** An unbalanced form is `error: cannot read this form (unbalanced
  parentheses or bad syntax)`, reported at the line where the form starts.
- **Full-width letters and digits** (`ＡＢＣ１２３`) are a warning; write half-width.
- **Every image and video needs `:alt`.**
- **Do not shrink to fit.** There is no option for it; split the slide.

## Diagnostics and their fixes

| Diagnostic (abridged) | Fix |
|---|---|
| `the content does not fit the slide even at the smallest sizes; split the slide` | split into two slides, move detail to `notes`, shorten sentences, or turn a long paragraph into a short list |
| `body text at 44px (floor 48px); consider splitting the slide` | the slide fits only below the readable size: remove content until it goes |
| `code at 28px (floor 32px); shorten the lines or the block` | shorten long lines, cut the block to the lines that matter (`:file` with `:lines`), or give the code its own slide |
| `a code block of N lines is hard to read; keep it to 15` | show fewer lines; use `:steps` to walk through them |
| `a list with N items is hard to follow; keep it to 6` | split the list or the slide |
| `lists nested deeper than 2 levels are hard to follow` | flatten |
| `a table row has N cells where the table has M` | add the missing cells or a `:colspan` |
| `a bar's value is a float; write it as a string, such as "2.1"` | quote the number |
| `the :at text "..." is not on a line of the code` | copy the text exactly from one line of the block |
| `the callout "..." has no room beside its anchor; shorten it or the slide` | shorten the comment, point at a different anchor, or remove content around it |
| `a callout takes N lines; keep each to 2` | shorten the comment |
| `(agenda) places the agenda of (deck :agenda t), which this deck lacks` | add `:agenda t` to the deck, or remove `(agenda)` |
| `:agenda lists the sections, and this deck has none` | add `section`s or drop `:agenda` |
| `image X has no :alt`, `video X has no :alt` | add `:alt "what it shows"` |
| `the full layout needs an (image ...) or (video ...) without :caption as the only block, ...` | drop the title, caption, source and callouts, or drop `:layout :full` |
| `the image-left layout needs one (image ...) or (video ...) outside columns, ...` | keep one image or video, outside `column`, without `:caption` or a callout on it |
| `image X takes :fit only on a :full, :image-left or :image-right slide` | remove `:fit`; an image in the body is always shown whole |
| `cannot embed image X: cannot read it` | fix the path (relative to the deck) |
| `video X is N MB, which makes the embedded HTML slow to open; ...` | shorten or re-encode the video, or render without `--embed` and ship it beside the HTML |
| `:page-image is drawn in the :page-image-font, which this deck lacks` | add a TrueType font, use an absolute image URL, or remove `:page-image` |
