# lispresen

A presentation tool written in rontolisp: a slide deck written as S-expressions becomes
one HTML file, with font sizes and placement chosen by lispresen so every slide stays
readable. The same program runs on the interpreter, the JVM, WASM and SBCL, and
produces the same bytes on each. Decks are written in Japanese; lines break between phrases.

The design is in [DESIGN.md](DESIGN.md).

## A deck

```lisp
(deck :title "lispresen" :author "rontolisp" :date "2026-10-22" :page-numbers t)

(section "なぜS式で書くのか")

(slide :title "スライドはデータ"
  (ul "差分をコードと同じようにレビューできる"
      (li "構造が明示される" (ul "見えないスタイルがない")))
  (notes "話者ノートは表示されない。"))

(slide "一文だけのスライドは画面いっぱいに表示する。")

(slide :title "コード"
  (p "フォームを " (tt "read-from-string") " で読む。")
  (code :lang "lisp" "
    (read-from-string source t nil :start pos)"))

(slide :title "表"
  (table :header ("項目" "値") :caption "数値の列は右に揃う"
    ("スライド数" "31")
    ("警告" "--")))
```

| Form | Meaning |
|------|---------|
| `(deck :title :author :date :logo :url :theme :aspect :runtime :callouts :kicker :agenda :page-numbers)` | the header; a `:title` adds a title slide, with `:logo` (an image path or `(image "logo.png" :alt "...")`) above the title and, for `:url`, a QR code of the URL with the URL under it in a corner, placed where it covers no text. `:theme` is `:light`, `:dark`, `:high-contrast`, `:midnight` or `(theme :base :dark :accent "#ff9f43" ...)` (below), `:aspect` `:16x9` or `:4x3`, `:runtime` where runnable code loads its runtime from (below), `:callouts :step` shows every callout on a page advance (below), `:kicker :section` puts each titled slide's section title above its title, `:agenda t` (or a title string) adds a slide listing the sections and shows each section divider as that list with its section highlighted |
| `(section "title")` | a section divider |
| `(slide [:title "t"] [:kicker "k"] [:layout name] [:class "c"] block...)` | a slide; the kicker is a small accent label above the title (`:kicker nil` drops the deck's) |
| `"text"`, `(p inline...)` | a paragraph |
| `(lead inline...)` | a paragraph in the muted color, e.g. an introduction above columns |
| `(ul [:step t] item...)`, `(ol ...)` | a list; an item is a string, an inline form or `(li inline... [list])`, and a list right after an item nests under it. `:step t` reveals one item per page advance (below) |
| `(code [:lang "lisp"] "text")` | a code block; the common indentation is removed. `lisp`, `scheme` and `clojure` are highlighted and runnable (below); `console` is a shell transcript: `$ ` lines are commands, the other lines output, which wraps at the panel's edge |
| `(code :file "src/fib.lisp" [:lines (4 6)] [:lang ...])` | a code block whose text is a UTF-8 file beside the deck, or its lines 4 to 6; `:lang` defaults by extension (`.lisp`, `.scm`, `.clj` and their variants) |
| `(code :steps ("(defun" (3 4) ("x" "y")) ...)` | brings lines forward one page advance at a time and fades the rest: a string names the first line holding it, `(3 4)` lines 3 to 4 of the block as shown, a list several of them |
| `(quote [:cite "who"] inline...)` | a quotation |
| `(column block...)` | a column; consecutive columns stand side by side at one text size, each as wide as its content needs, and blocks before or after them span the slide |
| `(image "path" :alt "text" [:caption "text"])` | an image, fitted into the remaining space |
| `(table [:header (cell...)] [:align (:left ...)] [:caption "t"] [:row-header t] (cell...)...)` | a table; a cell is inline content or `(cell :colspan n inline...)` |
| `(notes "text")` | speaker notes |
| `(b ...)`, `(i ...)`, `(tt "code")`, `(mark ...)`, `(accent ...)`, `(link "url" ...)` | inline content; `accent` is text in the accent color |
| `(say "comment" [:step t] inline...)`, `(say "comment" [:at "text"] [:step t] block)` | a callout: the comment in a speech bubble pointing at the content it wraps (below) |
| `(define name (param... [&rest rest]) body...)` | a template (below) |

The deck is read, never evaluated: no macros, no backquote, no reader conditionals, no
`#.`.

A template is reused by substitution: a later `(name arg...)` anywhere in the deck is
replaced by the body, each parameter by its argument, the `&rest` arguments spliced where
the rest parameter appears. A body of several forms stands for several slides, blocks or
inlines.

```lisp
(define side (head &rest points)
  (column (p (b head)) (ul points)))

(slide :title "インタプリタとコンパイラ"
  (side "インタプリタ" "起動が速い" "eval が完全")
  (side "コンパイラ" "実行が速い" "成果物が一つ"))
```

Keywords are not substituted (a parameter `title` leaves `:title` alone), and a template
or parameter cannot be named after an element (`slide`, `p`, `b`, ...).

A theme is the page's colors and the code's (`code-bg`, `code-fg`, `code-muted` and the
highlight colors). `(theme :base :light :code :dark)` sets code on dark panels in a light
deck; any color can be replaced by name, and every text color is checked for contrast.
`:midnight` is a dark indigo with gold: thin arcs frame the title, section and statement
slides, and its tables, code and lists are drawn in fine lines.

```lisp
(slide :title "列の幅は内容で決まる"
  (lead (b "問題") " 等幅の列ではコードが収まらない → " (b (accent "解決")) " 列ごとに幅を測る")
  (column
    (code :lang "lisp" :run nil "(row-widths row k c width)")
    (code :lang "console" "
      $ lispresen/check.sh
      ok   interpreter"))
  (column (ul "コードの列は最長の行の幅を取る" "残りを文章の列が使う")))
```

## Callouts

`say` puts a short comment in a speech bubble whose tail points at what it wraps: a
phrase, a list item, a table cell, a whole block, or, with `:at`, a piece of a code
block named by its text.

```lisp
(li "日本語は " (say "BudouX のモデルで区切る" (mark "文節")) " で改行する")

(say "Run で 6765 が出る" :at "(fib 20)"
  (code :lang "lisp" "
    (defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
    (fib 20)"))
```

lispresen places the bubble beside its target where it covers no text: right of it,
below, above or left, in that order. A bubble takes no room from the slide; when there is
no free space for it, the bubble text and then the body text get smaller, and a bubble
that still finds none is outlined in red and reported as an error. A bubble on an image
gets a strip on the image's right.

A bubble is shown with its slide. With `:step t` (or `(deck :callouts :step)` for every
bubble, `:step nil` to opt one out) it appears on a page advance instead.

## Page advances

A slide can reveal itself over several page advances: the items of a `(ul :step t ...)`,
the lines of a `(code :steps ...)`, and step bubbles. Each advance takes the next step of
the slide in the order they are written, a bubble after the steps of what it wraps,
before moving on to the next slide; going back undoes them one at a time.

```lisp
(slide :title "その場で動かす"
  (say "Run で 6765 が出る" :at "(fib 20)" :step t
    (code :file "fib.lisp" :lines (4 6) :steps ((1 2) "(fib 20)"))))
```

Here the first advance brings the definition forward, the second the call, the third the
bubble. Nothing moves when a step is revealed: hidden items keep their room. Print and the
overview show the whole slide; the presenter window shows what is still to come faded.

## Running

From the repository root, with the executable jar built (`./mvnw package`):

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar

# interpreter
java -jar $JAR lispresen/cli.lisp -- talk.lisp                  # writes talk.html

# JVM
java -jar $JAR lispresen/cli.lisp -o lispresen.jar
java -jar lispresen.jar talk.lisp

# WASM (a WASI Preview 1 module, or --component for a WASI 0.3 component)
java -jar $JAR lispresen/cli.lisp -o lispresen.wasm
wasmtime run --dir . lispresen.wasm talk.lisp

# native executable: no JVM or wasmtime needed to run it
java -jar $JAR lispresen/cli.lisp --native -o lispresen
./lispresen talk.lisp

# any Common Lisp, e.g. SBCL
sbcl --script lispresen/cli.lisp talk.lisp
```

```
lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|hosted|none] [--embed] [--check] [--strict]
```

`DECK` and `OUT` accept `-` for standard input and output; `OUT` defaults to `DECK` with
`.html`. Code files are read into the HTML. Images are referenced by their path, relative to the deck; `--embed` puts them in
the HTML file as `data:` URIs (`png`, `jpg`, `gif`, `webp`, `avif`, `svg`), so the file
alone is the whole deck. A WASM build reads code files and images only from a directory
`wasmtime run --dir` opens. Diagnostics go to standard error as `talk.lisp:42: slide 7 "title":
warning: ...`. `--check` only reports, `--strict` fails on warnings. Exit status: 0
success, 1 errors (or warnings under `--strict`), 2 usage.

## Presenting

Open the HTML file in a browser. Arrow keys, space, PageUp/PageDown (clickers),
Home/End and swiping move between slides; `#N` in the URL opens slide N. `F`, a double
click or the button in the bottom-right corner toggles full screen. `O` shows all slides.
Printing to PDF puts one slide on each page.

`P` opens the presenter window: the current and next slide, the notes, a timer (click it
to reset) and Start, which puts the slides in full screen on the other display (Chrome or
Edge, the deck served over HTTP; the first Start asks for permission to use the
displays). Moving in either window moves the other.

If the fonts on the presenting machine set text wider than lispresen measured, the deck
steps the affected slides down a size when it opens; `?check` in the URL lists what it
changed.

## Runnable code

A `code` block in `lisp`, `scheme` or `clojure` runs in the deck: click it (or press `E`)
to edit, `Ctrl+Enter` or Run to run, Reset to restore it. The output opens in a panel over
the slide, so running code takes no room from it; long output scrolls (wheel, or `↑` /
`↓`), and × or `Esc` closes the panel. Blocks of
one language share definitions, so a function defined on one slide can be called on the
next.

```lisp
(code :lang "clojure" :timeout 5 "
  (defn square [x] (* x x))
  (map square (range 10))")
```

| Option | Meaning |
|--------|---------|
| `:run nil` | show the code without running it |
| `:timeout s` | stop a run after s seconds (default 10); the runtime restarts and earlier definitions are gone |
| `:fresh t` | run as a whole program on a new interpreter |
| `:stdin "text"` | standard input of a `:fresh` run |

The runtime is rontolisp's browser build, about 35 MB. By default it loads from
`https://making.github.io/rontolisp/` when the deck opens, so presenting needs the
network. To present offline, build it and put it beside the deck:

```bash
./mvnw -Pweb -DskipTests package                 # needs GraalVM with Web Image (web/README.md)
mkdir -p talk/runtime && cp web/dist/rontoplayground.js* talk/runtime/
java -jar $JAR lispresen/cli.lisp -- talk/talk.lisp --runtime runtime/
jwebserver -d "$PWD/talk"                        # the runtime is fetched, so not from file://
```

`--runtime none` (or `:runtime :none`) renders the code as static text.

## Layout

Slides are laid out on a fixed 1920 x 1080 canvas (1440 x 1080 for 4:3) that the page
scales to the window. Body text starts at 56 px and steps down to 40 px only when the
slide needs it; a slide that does not fit at 40 px is outlined in red and reported as an
error instead of being shrunk further. Text widths are computed from a table measured on
Hiragino Sans, the widest of the font stack, and phrase boundaries come from a port of
[BudouX](https://github.com/google/budoux)'s Japanese model, so the browser breaks lines
where lispresen measured them.

## Development

```bash
lispresen/check.sh            # every backend against the golden deck, then the unit tests
lispresen/check.sh --update   # regenerate examples/intro.html first
```

`web/` is a browser editor: `web/build.sh` compiles lispresen to a WebAssembly reactor,
and `web/index.html`, served over HTTP, re-renders the deck as you type.

`tools/calibrate.lisp` writes a page that compares the width model with the browser it
is opened in. `tools/gen-budoux.py` regenerates `src/budoux-ja.lisp` from BudouX's
model (Apache License 2.0, `tools/BUDOUX-LICENSE`).
