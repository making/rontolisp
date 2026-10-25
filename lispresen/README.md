# lispresen

A presentation tool written in rontolisp: a slide deck written as S-expressions becomes
one HTML file, with font sizes and placement chosen by lispresen so every slide stays
readable. The same program runs on the interpreter, the JVM, WASM and SBCL, and
produces the same bytes on each. Decks are written in Japanese; lines break between phrases.

The design is in [DESIGN.md](DESIGN.md).

The deck introducing lispresen, [examples/intro.lisp](examples/intro.lisp), is published at
https://making.github.io/rontolisp/lispresen/intro.html with every push that changes it.

## A deck

`lispresen new talk.lisp` writes a starter deck to begin from: the header, sections and
a few slides showing the main forms, each with a comment on how it is written.

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
| `(deck :title :author :date :logo :url :theme :aspect :runtime :callouts :kicker :agenda :progress :page-numbers :footer :description :page-url :page-image :page-image-font :lang)` | the header; a `:title` adds a title slide, with `:logo` (an image path or `(image "logo.png" :alt "...")`) above the title and, for `:url`, a QR code of the URL with the URL under it in a corner, placed where it covers no text. `:theme` is `:light`, `:dark`, `:high-contrast`, `:midnight` or `(theme :base :dark :accent "#ff9f43" ...)` (below), `:aspect` `:16x9` or `:4x3`, `:runtime` where runnable code loads its runtime from (below), `:callouts :step` shows every callout on a page advance (below), `:kicker :section` puts each titled slide's section title above its title, `:agenda t` (or a title string) adds a slide listing the sections, after the title slide or at `(agenda)`, and shows each section divider as that list with its section highlighted, `:progress t` draws a thin bar along the bottom edge whose length is the current slide's place in the deck, `:footer "text"` (or `("text " (link ...))`) sets one line at the left of the bottom band on every slide but the title slide, a hashtag, a handle or a confidentiality label; `:lang "en"` is the page's `<html lang>` tag (a BCP 47 tag, default `ja`; it changes the declaration only, phrase breaking stays Japanese); `:description`, `:page-url`, `:page-image` and `:page-image-font` describe the published page to a link preview (below) |
| `(section "title")` | a section divider |
| `(agenda)` | where the agenda slide of `:agenda` stands, for slides that open the talk before it; it lists only the sections after it |
| `(slide [:title "t"] [:kicker "k"] [:layout name] [:class "c"] [:morph t] block...)` | a slide; the kicker is a small accent label above the title (`:kicker nil` drops the deck's; `:footer nil` drops the deck's footer). `:morph t` animates its first code block from the previous slide's (below). `:layout :image-left` or `:image-right` sets the slide's image edge to edge on that side of the canvas and fits the title and the other blocks in the rest (Layout, below) |
| `"text"`, `(p [:step t] inline...)` | a paragraph; `:step t` on it or on any block below but a list reveals the whole block on a page advance (below) |
| `(lead inline...)` | a paragraph in the muted color, e.g. an introduction above columns |
| `(ul [:step t] item...)`, `(ol ...)` | a list; an item is a string, an inline form or `(li inline... [list])`, and a list right after an item nests under it. `:step t` reveals one item per page advance (below) |
| `(code [:lang "lisp"] "text")` | a code block; the common indentation is removed. `lisp`, `scheme` and `clojure` are highlighted and runnable (below); `console` is a shell transcript: `$ ` lines are commands, the other lines output, which wraps at the panel's edge; `java`, `kotlin`, `javascript` (`js`), `typescript` (`ts`), `c`, `cpp`, `go`, `rust`, `python` (`py`), `bash` (`sh`, `shell`, `zsh`), `sql`, `json` and `yaml` (`yml`) are highlighted (comments, strings, keywords, numbers and constants; JSON and YAML keys) and never run; any other `:lang` is plain |
| `(code :file "src/fib.lisp" [:lines (4 6)] [:lang ...])` | a code block whose text is a UTF-8 file beside the deck, or its lines 4 to 6; `:lang` defaults by extension (`.lisp`, `.scm`, `.clj` and their variants, and `.java`, `.kt`, `.js`, `.ts`, `.c`, `.h`, `.cpp`, `.go`, `.rs`, `.py`, `.sh`, `.sql`, `.json`, `.yaml`, `.yml` and the like) |
| `(code :steps ("(defun" (3 4) ("x" "y")) ...)` | brings lines forward one page advance at a time and fades the rest: a string names the first line holding it, `(3 4)` lines 3 to 4 of the block as shown, a list several of them |
| `(quote [:cite "who"] inline...)` | a quotation |
| `(column block...)` | a column; consecutive columns stand side by side at one text size, each as wide as its content needs, and blocks before or after them span the slide |
| `(image "path" :alt "text" [:caption "text"] [:fit :contain] [:share 1/3])` | an image, fitted into the remaining space; alone on a slide without a title, it covers the whole slide. `:fit :contain` shows a full-bleed or split image whole instead of cropping it, `:share` (`1/3`, `1/2`, `2/3`) is the width a split image takes |
| `(video "demo.mp4" :alt "text" [:poster "demo.png"] [:autoplay t] [:muted t] [:loop t] ...)` | a video (`mp4`, `webm`), laid out as an image and taking the same options. `:autoplay t` plays it when its slide is entered, never on page load; leaving the slide pauses it. A click or tap on it plays or pauses it. Print, the overview and the presenter window show the `:poster` image (or the first frame) |
| `(frame "https://..." :alt "name" [:zoom 2] ...)` | a live web page, laid out as an image and taking the same options but `:fit`. It is shown `:zoom` times enlarged (1 to 4 in quarters, 2 by default) and loads when its slide is first shown; the overview and the presenter window show its name instead (below) |
| `(flow [:direction :down] label...)`, `(flow (-> step...)...)` | boxes joined by arrows (below): a chain, or paths that branch and merge; a label is a string, an inline form or a list of them |
| `(cells '(1 (2 3)))` | the box-and-pointer drawing of a list (below) |
| `(bars [:series (name...)] [:stack t] [:axis t] [:unit "s"] [:step :series] (label value...)...)` | a horizontal bar chart (below) |
| `(columns ...)` | a column chart, the same options as `bars` (below); side-by-side blocks are `column` |
| `(lines [:series (name...)] [:unit "s"] [:step :series] (label value...)...)` | a line chart (below) |
| `(pie [:donut t] [:unit "s"] (label value)...)` | a pie or donut chart (below) |
| `(math expr...)` | a formula laid out from its S-expression (below): a display formula as a block, an inline one inside a paragraph, a list item or a table cell |
| `(table [:header (cell...)] [:align (:left ...)] [:caption "t"] [:row-header t] (cell...)...)` | a table; a cell is inline content or `(cell :colspan n inline...)` |
| `(notes "text")` | speaker notes |
| `(source inline...)` | a line in the muted color at the bottom of the slide naming where its content comes from; one per slide |
| `(b ...)`, `(i ...)`, `(tt "code")`, `(mark ...)`, `(accent ...)`, `(link "url" ...)` | inline content; `accent` is text in the accent color |
| `(say "comment" [:step t] inline...)`, `(say "comment" [:at "text"] [:step t] block)` | a callout: the comment in a speech bubble pointing at the content it wraps (below) |
| `(define name (param... [&rest rest]) body...)` | a template (below) |

The deck is read by lispresen's own reader, never evaluated: no macros, no backquote, no
reader conditionals, no `#.`. Symbols keep the case they are written with (`N` and `n`
are different letters in a formula; tags and options match whatever their case), `|a b|`
and `\` escape characters in a symbol, and a decimal such as `2.5` is refused wherever it
would be printed, since float printing differs between implementations: write it as a
string.

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
A chart's series take `accent` and `chart-2` to `chart-6`, checked at 3:1 on `bg`.
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

## Diagrams

A diagram is drawn from an S-expression as SVG in the theme's colors, so it follows the
theme, reads as text in a review, and its labels are measured, wrapped between phrases
and sized like the rest of the slide.

```lisp
(flow "read" "parse" (b "segment") ("layout " (tt "fit-slide")) "lint" "emit")

(flow (-> "deck" "interpreter" "HTML")
      (-> "deck" "compile" "JVM" "HTML")
      (-> "compile" (label "or") "WASM" "HTML"))

(cells '((a . 1) (b . 2)))

(bars :unit "s" ("interpreter" "2.1") ("jvm" "0.4") ("wasm" "0.6"))

(columns :series ("2025" "2026") ("Q1" 3 5) ("Q2" 4 8) ("Q3" 2 6))

(lines :series ("p50" "p99") :unit " ms" ("Jan" 12 40) ("Feb" 11 38) ("Mar" 10 55))

(pie :donut t :unit "%" ("Chrome" 64) ("Safari" 19) ("Other" 17))
```

`flow` with labels alone is a chain: its boxes left to right, joined by arrows, one
height for all; when they do not fit one row it takes as few rows as fit, as even as it
can, and the arrow turns back below each row. A flow that branches or merges is written
as paths, each `(-> step...)` a chain of arrows:

- A label names its box by its text, so a label written in two paths is one box, drawn
  where it is first written (an inline callout belongs there). Two boxes of one text are
  told apart with `(node NAME label...)`, which declares a box named by the symbol `NAME`;
  a path then writes `NAME` for it, before or after the declaration.
- `(label inline...)` between two steps labels the arrow between them. An arrow written
  twice is drawn once; it takes one label at most.
- `:direction :down` draws the flow top to bottom (a chain too); the default is `:right`.
- A cycle is an error naming it: a flow reads one way.

A flow of paths is laid out in layers, each box one layer past every box before it, with
few crossings, each box level with the boxes it joins where room allows; an arrow that
skips a layer runs straight through it. Arrows leaving one box share a line that splits,
as do arrows into one box, and a dot marks where lines join, so lines that only cross
read as crossing. More than 8 boxes is a warning. `cells` draws a list as cons cells: the cdr chain on one row, each car
below its cell (an atom as its label, a list as its own row), `nil` as a slash and a dotted
pair's cdr to its right. The datum is quoted or not; a dotted pair is deck syntax only
inside `cells`, and a template use inside it is drawn, not expanded. It holds at most 32
conses; atoms are symbols (lower case unless written otherwise), integers, ratios,
strings and characters.

Four forms draw charts, one per question a chart answers: `bars` (categories compared,
long labels), `columns` (categories compared, or a few periods), `lines` (a change over
ordered points) and `pie` (the parts of a whole). A row is `(label value...)`: the label
inline content (wrapped at its phrases when long), then one value per series. A value is
an integer or a decimal written as a string (`"2.1"`, `"-0.5"`, `"1,840"`); a float is
refused, since its printing differs between implementations. `:unit` follows every value
as written (`:unit " KB"` gives `297 KB`).

- `:series ("2025" "2026")` names the series, at most 6: each value of a row belongs to
  the series in its place, a legend names them above the chart, and each takes a color of
  the theme in order, the accent first. More than 4 is a warning.
- `bars` draws a row per category, a bar per series side by side, as long as its value's
  share of the scale from zero, and the value at its end; a negative value runs left of
  zero. `columns` draws the same upright, the categories along the bottom.
- `:stack t` stacks a row's values into one bar, negative ones below zero, with the
  row's total at its end. `:axis t` draws a scale with gridlines instead of the values.
- `lines` draws a point per value joined by a line per series, along a scale on the
  left; each series has its own marker shape as well as its color. The scale starts at
  zero unless the values lie far from it.
- `pie` draws a slice per row clockwise from the top, at most 6, beside a legend with
  each value; `:donut t` cuts a hole in it. A value is 0 or more.
- A scale steps by 1, 2 or 5 times a power of ten, computed in exact rationals, its ticks
  written with the unit and the step's decimals.

More than 7 rows of `bars` (12 of `columns` or `lines`, 5 slices of a pie) is a warning,
as for a table's rows. A chart reads as its values to a screen reader.

Labels start at 48 px and step down to 36 px with the slide; a diagram wider than its
room at 36 px is an error. A diagram is centered in its column or the body; a bar,
column or line chart spans it. A chart grows into the height the other blocks leave, its
labels at the same size: a pie as wide as its column allows, a column or line chart's
plot up to two thirds of its width, a bar chart's bars up to twice as thick. It leaves
room for its callouts, and on a slide with an image the image takes the height.

## Formulas

A formula is written as an S-expression, prefix notation being a formula's tree already,
and laid out by lispresen from the same width table as the text: no TeX, no script
library, no image. In block position `(math ...)` is a display formula centered in its
column or the body, set at the body size; inside a paragraph, a list item, a table cell
or a flow label it is an inline formula of the text's size, fractions and limits set
compactly so it stays within its line. A `say` around a block formula points at it.

```lisp
(p "計算量は " (math (O (* n (log n)))) " で済む")
(math (= x (/ (pm (- b) (sqrt (- (^ b 2) (* 4 a c)))) (* 2 a))))
(math (= (fib n) (cases (n (< n 2)) ((+ (fib (- n 1)) (fib (- n 2))) "otherwise"))))
(math (= (sum (= i 1) n (^ i 2)) (/ (* n (+ n 1) (+ (* 2 n) 1)) 6)))
```

| Form | Sets |
|------|------|
| `x`, `N`, `#\N`, `alpha`, `Gamma`, `inf`, `partial`, `cdots`, `reals`, ... | a symbol: one letter in italic, a longer name upright, a Greek letter or symbol by name (`infty`, `nabla`, `emptyset`, `forall`, `exists`, `ell`, `hbar`, `deg`, `dots`, `vdots`, `naturals`, `integers`, `rationals`, `complex`) |
| `42`, `1/2`, `"if"` | a number; a ratio as a fraction; a string as text (a decimal is written as a string: `"0.5"`) |
| `(+ a b)`, `(- a b)`, `(- a)`, `(* a b)`, `(pm a b)`, `(times a b)`, `(cdot a b)`, `(div a b)`, `(mod a b)` | sums and products; `*` is juxtaposition (`2x`, a dot between two numbers), a sum used as a factor or after a minus sign gets parentheses |
| `(= a b c)`, `<`, `>`, `<=`, `>=`, `/=`, `approx`, `equiv`, `sim`, `propto`, `in`, `notin`, `subset`, `subseteq`, `->`, `=>`, `<=>`, `mapsto`, `ll`, `gg` | relations, chained |
| `(/ a b)`, `(binom n k)`, `(deriv y x)`, `(partial f x)` | a fraction, a binomial coefficient, dy/dx, ∂f/∂x |
| `(^ x 2)`, `(_ x i)`, `(^ (_ x i) 2)` | a superscript, a subscript, both |
| `(sqrt x)`, `(root 3 x)`, `(abs x)`, `(norm v)`, `(floor x)`, `(ceil x)`, `(paren ...)`, `(bracket ...)`, `(brace ...)`, `(set a b)`, `(tuple a b)`, `(angle a b)` | radicals and delimiters, which grow with their content; several arguments are separated by commas |
| `(sum lo hi body)`, `(prod ...)`, `(int ...)`, `(bigcup ...)`, `(bigcap ...)`, `(lim (-> n inf) body)` | large operators with their limits (either may be left out); below and above in a display formula, beside in an inline one |
| `(sin x)`, `log`, `ln`, `exp`, `max`, `min`, `det`, `gcd`, ... | a function set upright, its argument in parentheses unless it is simple |
| `(f x y)` | any other head is a function applied to its arguments: f(x, y) |
| `(matrix (a b) (c d))`, `bmatrix`, `vmatrix`, `(cases (value condition) ...)` | a matrix in parentheses, brackets or bars; a case analysis behind a brace |
| `(bar x)`, `(hat x)`, `(vec x)`, `(dot x)`, `(bold w)`, `(prime f)`, `(! n)`, `(not p)`, `(text "and")` | accents, bold, f′, n!, ¬p, words |

Every formula carries a spoken form as its `aria-label` (`x equals (minus b plus or
minus the square root of b squared minus 4 a c) over (2 a)`). A display formula wider
than its room at the smallest body size is an error; an inline formula taller than its
line (a matrix, a display-sized sum) is a warning that it belongs in a block.

## Callouts

`say` puts a short comment in a speech bubble whose tail points at what it wraps: a
phrase, a list item, a table cell, a whole block, or, with `:at`, a piece of a code
block named by its text, a box of a `flow`, a category of a chart (on a chart of one series
its bar, column, point or slice, the tail at the value written there; else its label), a series by its legend name, a slice of a `pie`,
or an atom of `cells` named by its label (the first equal to the text, else the first
holding it). A `say` inside a label of a `flow` or a chart points where `:at` naming that
label would; one around a chart's value, `("Q2" 4 (say "peak" 8))`, points at the value
written at its bar or column, or at its point.

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
any block with `:step t`, the lines of a `(code :steps ...)`, and step bubbles. Each
advance takes the next step of the slide in the order they are written, a bubble after
the steps of what it wraps, before moving on to the next slide; going back undoes them
one at a time.

```lisp
(slide :title "その場で動かす"
  (say "Run で 6765 が出る" :at "(fib 20)" :step t
    (code :file "fib.lisp" :lines (4 6) :steps ((1 2) "(fib 20)"))))
```

Here the first advance brings the definition forward, the second the call, the third the
bubble. Nothing moves when a step is revealed: hidden items keep their room. Print and the
overview show the whole slide; the presenter window shows what is still to come faded.

```lisp
(slide :title "結果"
  (p "測った値を見せてから、結論を出す。")
  (bars :step t :unit " KB" ("JVM (jar)" 297) ("WASM" 788))
  (lead :step t "配るなら jar が一番小さい"))
```

`:step t` reveals a whole block: a paragraph (`p`, `lead`), a quote, an image, a video, a
frame, a table, a diagram, a display formula, a code block (its `:steps` then walk its lines) or a
column. A table's rows and a diagram's boxes or bars come with their block; only a list
reveals its parts one by one, and a chart's series with `:step :series` (`bars`,
`columns`, `lines`): one advance per series, its legend name, marks and values together,
a bubble around one of its values with it. A bubble that is no step but points at a stepped block, or
stands in one, appears with it. A video with `:autoplay t` that is a step plays when it
is revealed. `:step` on `notes`, on `source` or on the only block of an untitled slide
(which would open empty) is an error.

## Code morph

When a talk rewrites a function step by step, `:morph t` on the slide with the new
version shows the change: on the page advance into it, the tokens the two versions share
move to their new place, removed ones fade out and added ones fade in. Going back plays
it in reverse.

```lisp
(slide :title "素朴な再帰"
  (code :lang "lisp" "(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))"))

(slide :title "ループで" :morph t
  (code :lang "lisp" "
    (defun fib (n)
      (loop repeat n
            for a = 0 then b and b = 1 then (+ a b)
            finally (return a)))"))
```

The morph pairs the first code block of each slide; which tokens pair is decided when the
deck is rendered, so it is the same on every backend. A jump (`#N`, the overview),
`prefers-reduced-motion` and a block edited in the player switch at once; print shows each
slide as it is.

## Install

A native executable, which needs neither a JVM nor rontolisp, is on the
[lispresen-0.1.0-SNAPSHOT release](https://github.com/making/rontolisp/releases/tag/lispresen-0.1.0-SNAPSHOT)
(macOS arm64, Linux amd64/arm64), rebuilt with every push that changes lispresen. For example,
on macOS (Apple Silicon):

```bash
wget https://github.com/making/rontolisp/releases/download/lispresen-0.1.0-SNAPSHOT/lispresen-darwin-arm64
chmod +x lispresen-darwin-arm64
sudo mv lispresen-darwin-arm64 /usr/local/bin/lispresen
lispresen new talk.lisp                                         # a starter deck
lispresen serve talk.lisp                                       # http://127.0.0.1:8000/, reloaded as you edit
lispresen talk.lisp                                             # writes talk.html
```

On Linux the file is `lispresen-linux-amd64` or `lispresen-linux-arm64`. On macOS, a file
downloaded with a browser is quarantined and killed at start-up;
`xattr -d com.apple.quarantine lispresen-darwin-arm64` lifts that (`wget` and `curl` set no
quarantine).

## A skill for AI agents

[skills/lispresen](skills/lispresen/SKILL.md) is a skill for Claude Code (and other agents
that read `SKILL.md`): it installs lispresen where it is missing, starts from `lispresen
new`, writes and renders the deck, fixes what the diagnostics report and checks the result
in a browser. It is published as a plugin in the rontolisp marketplace:

```bash
claude plugin marketplace add https://making.github.io/rontolisp/skill/marketplace.json
claude plugin install lispresen@rontolisp
```

Inside a session the same steps are `/plugin marketplace add ...` and `/plugin install
lispresen@rontolisp`; `claude plugin update lispresen@rontolisp` takes a newer version.
Claude Code uses the skill when asked for slides made with lispresen, or on
`/lispresen:lispresen`.

Without the plugin system, copy the directory into `~/.claude/skills/` (every project) or
a project's `.claude/skills/`; nothing updates that copy:

```bash
dir=~/.claude/skills/lispresen
base=https://raw.githubusercontent.com/making/rontolisp/develop/lispresen/skills/lispresen
mkdir -p $dir/references
curl -fsSL -o $dir/SKILL.md $base/SKILL.md
curl -fsSL -o $dir/references/deck-format.md $base/references/deck-format.md
```

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
lispresen new FILE [--lang ja|en] [--force]
lispresen serve DECK [--port N] [--host ADDR] [--theme NAME] [--runtime URL|hosted|none]
lispresen --version
```

`DECK` and `OUT` accept `-` for standard input and output; `OUT` defaults to `DECK` with
`.html`. Code files are read into the HTML. Images and videos are referenced by their path, relative to the deck; `--embed` puts them in
the HTML file as `data:` URIs (`png`, `jpg`, `gif`, `webp`, `avif`, `svg`; `mp4`, `webm`,
a video over 20 MB reported, since the page then opens slowly), so the file
alone is the whole deck, the page image (Link preview, below) aside. A WASM build reads and writes files
only in a directory `wasmtime run --dir` opens. Diagnostics go to standard error as `talk.lisp:42: warning: slide 7
"title": ...`, the severity right after the line, red for an error and yellow for a warning
when standard error is a terminal (not under `NO_COLOR` or `TERM=dumb`). `--check` only reports, `--strict` fails on warnings. Exit status: 0
success, 1 errors (or warnings under `--strict`), 2 usage.

`new` writes the starter deck to `FILE` (`-` for standard output): Japanese, or English
with `--lang en`, dated today in the local time zone. An existing `FILE` is left alone and reported unless
`--force` is given. A deck file named `new` is rendered as `./new`.

`serve` serves the deck's page at `http://127.0.0.1:8000/` until it is stopped (Ctrl-C)
and keeps it current while you edit: open the URL in a browser, save the deck, and the
page reloads with the new build on the slide it showed. `--port` and `--host` choose
where it listens (only this machine by default; `--host 0.0.0.0` lets a phone on the same
network open it), `--theme` and `--runtime` are the rendering options above.

- The page is the HTML `lispresen DECK` writes, plus a script that asks the server every
  half second whether the build changed. Each ask compares the deck's text and the dates
  of the files the build read (the code files `(code :file ...)` names) with what they
  were and rebuilds on a difference; an image or other file the page loaded that
  changed reloads the page without a rebuild. Nothing is rebuilt while no browser asks.
- The files beside the deck are served at their relative paths, so images need no
  `--embed`, and a `--runtime` directory beside the deck loads over HTTP. A request for
  one byte range (`Range: bytes=...`) gets just those bytes, so a video can be sought.
  Nothing is written to disk: no HTML, no page image.
- Each build's diagnostics go to standard error as above. In the browser, a build with
  any shows them in a panel over the slides, errors red and warnings amber (a click hides
  it); the next build without any removes it. A deck that stops reading (an unbalanced parenthesis) still shows the
  slides before the error, and the page returns to the slide you were on when it is fixed.

`serve` runs on the native executable, the interpreter, the JVM and SBCL. A WASM module
(Preview 1 or a component) cannot accept a connection, so there it exits with status 1
saying so.

`--version` prints the version and the commit lispresen was built from, in the shape of
`rontolisp --version`:

```
{"version": "0.1.0-SNAPSHOT", "gitCommit": "42ee82c"}
```

Both are read when lispresen is built: the version from the repository's `pom.xml` (the
release `lispresen-<version>` carries the same), the commit from its `.git`. Run on the
interpreter or SBCL, that is when `cli.lisp` is loaded; a build outside a repository
reports `unknown`.

## Presenting

Open the HTML file in a browser. Arrow keys, space, PageUp/PageDown (clickers),
Home/End and swiping move between slides; on a touch screen, a tap on the left third goes
back and anywhere else goes forward. A click or tap on a video plays or pauses it, and
leaves the slide where it is; one on a frame's page goes to the page, as do the keys after
it, until the slide outside the page is clicked. `#N` in the URL opens slide N. `F`, a double click
or the button in the bottom-right corner toggles full screen. `O` shows all slides.
While the deck is in full screen, and while the presenter window is open, the player holds a
screen wake lock so the display does not dim or lock mid-talk (browsers that lack the API, or
refuse it, are left as they are).
Printing to PDF puts one slide on each page. With `?notes` in the URL it prints a handout:
each page holds a slide and its notes under it (a slide without notes prints alone), on a page
as wide as the canvas and twice as tall; notes longer than the area under the slide are cut.

`P` opens the presenter window: the current and next slide, the notes, a timer (click it
to reset) and Start, which puts the slides in full screen on the other display (Chrome or
Edge, the deck served over HTTP; the first Start asks for permission to use the
displays). Moving in either window moves the other, and a click on a video in the presenter window
plays or pauses it in the slides. Print with notes opens the handout print (`?notes&print`).

If the fonts on the presenting machine set text wider than lispresen measured, the deck
steps the affected slides down a size when it opens; `?check` in the URL lists what it
changed.

## Runnable code

A `code` block in `lisp`, `scheme` or `clojure` runs in the deck: click it (or press `E`)
to edit, `Ctrl+Enter` or Run to run, Reset to restore it. The output opens in a panel over
the slide, so running code takes no room from it; long output scrolls (wheel, or `↑` /
`↓`), and × or `Esc` closes the panel. Run and Reset sit in the panel's top-right corner;
when the first lines would reach them, the code starts below them. Blocks of
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
network; the deck may be opened as a file or over HTTP. To present offline, build it and
put it beside the deck:

```bash
./mvnw -Pweb -DskipTests package                 # needs GraalVM with Web Image (web/README.md)
mkdir -p talk/runtime && cp web/dist/rontoplayground.js* talk/runtime/
java -jar $JAR lispresen/cli.lisp -- talk/talk.lisp --runtime runtime/
jwebserver -d "$PWD/talk"                        # the runtime is fetched, so not from file://
```

Opened as a file, such a deck does not run its code: browsers do not fetch local files,
and Run says so.

`--runtime none` (or `:runtime :none`) renders the code as static text.

## Link preview

A deck published on the web can say what a link preview shows when its URL is shared:

```lisp
(deck :title "lispresen: S式で書くプレゼンテーション"
      :description "S式で書いたデッキから、文字の大きさと配置を決めた HTML を一つ作るプレゼンテーションツール"
      :page-url "https://making.github.io/rontolisp/lispresen/intro.html"
      :page-image "intro.png"
      :page-image-font ("fonts/BIZUDPGothic-Regular.ttf" "fonts/BIZUDPGothic-Bold.ttf"))
```

`:description` is the page's description. `:page-url`, the absolute URL the HTML is
published at, adds the Open Graph and Twitter Card tags: `og:url`, the title, the
description and, with `:page-image`, the image as a large card. `:page-url` is where this
file is; `:url` is where the title slide's QR code sends the audience, often elsewhere. A
deck that sets none of them gets no tags.

lispresen draws a `:page-image` given as a path relative to the page: the title slide as a
1200 x 675 PNG, written at that path beside the HTML (with `-o -`, relative to the working
directory), with the logo (an SVG of simple shapes), the title broken where the slide
breaks it, the lines under it, the QR code and the theme's colors. Its text is set in the
TrueType fonts (`.ttf`, glyf outlines; not CFF `.otf`) `:page-image-font` names, relative
to the deck: one file, or a regular and a bold one for the title. An absolute URL is named
as given and nothing is drawn. The browser editor draws no image.

`examples/fonts` holds BIZ UDPGothic (SIL Open Font License 1.1, `examples/fonts/OFL.txt`)
cut down to ASCII and the characters of the intro deck's title. A character the font lacks
is drawn as a box and reported; to cut a font for another title:

```bash
pyftsubset BIZUDPGothic-Bold.ttf --unicodes=U+20-7E --text="式で書くプレゼンテーション" \
  --no-hinting --layout-features= --drop-tables+=DSIG,meta,vhea,vmtx,gasp \
  --output-file=fonts/BIZUDPGothic-Bold.ttf
```

## Layout

Slides are laid out on a fixed 1920 x 1080 canvas (1440 x 1080 for 4:3) that the page
scales to the window. Body text starts at 56 px and steps down to 40 px only when the
slide needs it; a slide that does not fit at 40 px is outlined in red and reported as an
error instead of being shrunk further. Text widths are computed from a table measured on
Hiragino Sans, the widest of the font stack, and phrase boundaries come from a port of
[BudouX](https://github.com/google/budoux)'s Japanese model, so the browser breaks lines
where lispresen measured them. A number stays on one line with its counter (`1 時間`) and
never breaks inside (`2026-10-22`, `1,000`). An emoji is measured as one em and a
sequence (a flag, `👨‍💻`) as one glyph that no line breaks inside.

A photograph can fill the slide or one side of it:

```lisp
(slide (image "venue.jpg" :alt "会場"))           ; no title, one image: covers the slide
(slide :title "会場" :layout :image-right         ; :image-left puts it on the left
  (ul "駅から徒歩 5 分" "定員 200 人")
  (image "venue.jpg" :alt "会場" :share 1/3))     ; a third of the width, half by default
```

The image is cropped to its area (`:fit :contain` shows it whole on the background), and
the title and body of a split slide are fitted in the width the image leaves. Text is
never set on a picture, since its contrast cannot be checked: a full-bleed image takes no
title, caption, source or callout and shows no page number or footer, and an image beside
text takes no caption.

A recorded demo is a `video`, laid out as an image: in the body, over the whole slide or
beside the text.

```lisp
(slide :title "デモ"
  (video "demo.mp4" :alt "fib を評価する" :poster "demo.png" :autoplay t :muted t))
```

`:autoplay t` plays it when the slide is entered and leaving the slide pauses it. A
browser plays sound only in a window that has had a key press or click, so when moving
from the presenter window, press `F` in the slides window first; a `:muted t` video
always plays.

A live demo of a web application is a `frame`, also laid out as an image:

```lisp
(slide :title "エディタ"
  (frame "https://making.github.io/rontolisp/lispresen/editor/" :alt "lispresen のエディタ"))
```

- The page is laid out in its box divided by `:zoom` and scaled up, so a page set at
  16 px reads as 32 px on the 1920 x 1080 slide at the default 2.
- It loads when its slide is first shown and keeps its state after that. The overview and
  the presenter window show its name, not a second copy of the page.
- Once the page is clicked, keys go to it; click the slide outside it to move between
  slides again. A frame that fills the whole slide leaves nothing to click, so give an
  interactive page a title.
- A page is served, never embedded: under `--embed` a relative URL is reported.

The intro deck frames lispresen's own editor (`web/`), which the Pages workflow publishes
at `https://making.github.io/rontolisp/lispresen/editor/`.

## Development

```bash
lispresen/check.sh            # every backend against the golden deck and image, --version,
                              # the starter deck and serve, then the unit tests
lispresen/check.sh --update   # regenerate examples/intro.html and intro.png first
```

`web/` is a browser editor: `web/build.sh` compiles lispresen to a WebAssembly reactor,
and `web/index.html`, served over HTTP, re-renders the deck as you type and colors its
source as a Lisp code block on a slide is colored. `web/build.sh
DIR` puts both files in `DIR` instead, which is how the Pages workflow publishes the
editor at `lispresen/editor/`. Framed in another page, the editor starts from its sample
and leaves the draft of the editor opened alone untouched.

`tools/calibrate.lisp` writes a page that compares the width model with the browser it
is opened in: the line counts of sample paragraphs (formulas among them), every ASCII
character, emoji, symbol and Greek letter in both weights, and the ink heights the
formula layout assumes. `tools/gen-budoux.py` regenerates `src/budoux-ja.lisp` from BudouX's
model (Apache License 2.0, `tools/BUDOUX-LICENSE`).
