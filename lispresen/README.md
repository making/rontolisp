# lispresen

A presentation tool written in rontolisp: a slide deck written as S-expressions becomes
one HTML file, with font sizes and placement chosen by lispresen so every slide stays
readable. The same program runs on the interpreter, the JVM, WASM and SBCL, and
produces the same bytes on each. Decks are written in Japanese; lines break between phrases.

The design, and what is still to come, is in [DESIGN.md](DESIGN.md).

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
| `(deck :title :author :date :theme :aspect :page-numbers)` | the header; a `:title` adds a title slide. `:theme` is `:light`, `:dark` or `:high-contrast`, `:aspect` `:16x9` or `:4x3` |
| `(section "title")` | a section divider |
| `(slide [:title "t"] [:layout name] [:class "c"] block...)` | a slide |
| `"text"`, `(p inline...)` | a paragraph |
| `(ul item...)`, `(ol item...)` | a list; an item is a string, an inline form or `(li inline... [list])`, and a list right after an item nests under it |
| `(code [:lang "lisp"] "text")` | a code block; the common indentation is removed |
| `(image "path" :alt "text" [:caption "text"])` | an image, fitted into the remaining space |
| `(table [:header (cell...)] [:align (:left ...)] [:caption "t"] [:row-header t] (cell...)...)` | a table; a cell is inline content or `(cell :colspan n inline...)` |
| `(notes "text")` | speaker notes |
| `(b ...)`, `(i ...)`, `(tt "code")`, `(mark ...)`, `(link "url" ...)` | inline content |

The deck is read, never evaluated: no macros, no backquote, no reader conditionals.

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
lispresen DECK [-o OUT] [--theme NAME] [--check] [--strict]
```

`DECK` and `OUT` accept `-` for standard input and output; `OUT` defaults to `DECK` with
`.html`. Diagnostics go to standard error as `talk.lisp:42: slide 7 "title": warning:
...`. `--check` only reports, `--strict` fails on warnings. Exit status: 0 success, 1
errors (or warnings under `--strict`), 2 usage.

## Presenting

Open the HTML file in a browser. Arrow keys, space, PageUp/PageDown (clickers),
Home/End and swiping move between slides; `#N` in the URL opens slide N. `F`, a double
click or the button in the bottom-right corner toggles full screen. Printing to PDF puts
one slide on each page.

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

`tools/calibrate.lisp` writes a page that compares the width model with the browser it
is opened in. `tools/gen-budoux.py` regenerates `src/budoux-ja.lisp` from BudouX's
model (Apache License 2.0, `tools/BUDOUX-LICENSE`).
