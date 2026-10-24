---
name: lispresen
description: Make presentation slides with lispresen, which turns a deck written as S-expressions ((deck ...), (section ...), (slide ...)) into one self-contained HTML file and chooses font sizes and placement itself. Covers installing lispresen when it is missing (native executable from GitHub Releases, or a Java fallback), starting from `lispresen new`, writing the deck, previewing it with `lispresen serve` (live reload on every save), rendering, and fixing the diagnostics it reports. Use this whenever the user asks for slides, a talk, a deck or a presentation made with lispresen, mentions a lispresen deck or a .lisp file of (deck ...)/(slide ...) forms, or wants a presentation written as S-expressions and delivered as a single HTML page, even if lispresen is not installed yet.
---

# lispresen

lispresen renders a deck file (`talk.lisp`, S-expressions) to `talk.html`, one HTML file
holding the slides and the player. The author writes content only: lispresen picks the
layout and the font sizes, and reports a slide that cannot stay readable instead of
shrinking it. The deck is read, never evaluated.

The work goes in five steps: install, start from the starter deck, write, preview and
fix (`lispresen serve` rebuilds the page on every save), check in a browser and build the
final HTML. Upstream documentation (there is no local checkout to read):

- README (every form and option): https://github.com/making/rontolisp/blob/develop/lispresen/README.md
- DESIGN (layout rules, lint table): https://github.com/making/rontolisp/blob/develop/lispresen/DESIGN.md
- A full deck using every feature: https://github.com/making/rontolisp/blob/develop/lispresen/examples/intro.lisp
  (rendered: https://making.github.io/rontolisp/lispresen/intro.html)

`references/deck-format.md` (next to this file) is the condensed form reference and the
list of mistakes to avoid. Read it before writing or editing a deck.

## 1. Install

First check whether it is already there:

```bash
command -v lispresen && lispresen --version
# {"version": "0.1.0-SNAPSHOT", "gitCommit": "3b9b214"}
```

If not, install the native executable. It needs neither a JVM nor rontolisp. The
prerelease `lispresen-0.1.0-SNAPSHOT` is rebuilt on every change to lispresen, so the same
URL always gives the latest build.

| `uname -s`-`uname -m` | Release asset |
|---|---|
| `Darwin-arm64` (Apple Silicon) | `lispresen-darwin-arm64` |
| `Linux-x86_64` | `lispresen-linux-amd64` |
| `Linux-aarch64` / `Linux-arm64` | `lispresen-linux-arm64` |

```bash
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)               asset=lispresen-darwin-arm64 ;;
  Linux-x86_64)               asset=lispresen-linux-amd64 ;;
  Linux-aarch64|Linux-arm64)  asset=lispresen-linux-arm64 ;;
  *) echo "no native lispresen for $(uname -s)-$(uname -m)"; asset= ;;
esac
tmp=$(mktemp -d)
curl -fL -o "$tmp/lispresen" \
  "https://github.com/making/rontolisp/releases/download/lispresen-0.1.0-SNAPSHOT/$asset"
chmod +x "$tmp/lispresen"
# A directory on PATH that is writable without sudo; otherwise ~/.local/bin.
if [ -w /usr/local/bin ]; then dir=/usr/local/bin; else dir="$HOME/.local/bin"; mkdir -p "$dir"; fi
mv "$tmp/lispresen" "$dir/lispresen"
case ":$PATH:" in *":$dir:"*) ;; *) export PATH="$dir:$PATH" ;; esac
lispresen --version
```

Notes:

- Do not run `sudo` yourself: it waits for a password the session cannot type. If the user
  wants `/usr/local/bin` and it is not writable, give them the `sudo mv` line to run.
- `~/.local/bin` may not be on the user's PATH in new shells. The `export` above covers the
  current session; tell the user to add `export PATH="$HOME/.local/bin:$PATH"` to their
  shell profile instead of editing it without asking.
- macOS quarantines a file downloaded with a browser and kills it at start-up. `curl` and
  `wget` set no quarantine. If the user downloaded it with a browser:
  `xattr -d com.apple.quarantine /path/to/lispresen`.
- The shell tool may reset the environment between calls; if `lispresen` is not found
  later, call it by its full path (`$HOME/.local/bin/lispresen`).

### No native build for this platform (Intel Mac, Windows, others)

Use the JVM: Java 25 or later, the rontolisp jar from the `0.1.0-SNAPSHOT` release and the
lispresen sources. Compile lispresen once to a jar, then use it as `lispresen`:

```bash
java -version                                   # 25 or later
curl -fLO https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT/rontolisp-0.1.0-SNAPSHOT-exec.jar
curl -fsSL https://codeload.github.com/making/rontolisp/tar.gz/refs/heads/develop \
  | tar xz --strip-components=1 rontolisp-develop/lispresen rontolisp-develop/pom.xml
java -jar rontolisp-0.1.0-SNAPSHOT-exec.jar lispresen/cli.lisp -o lispresen.jar
java -jar lispresen.jar --version               # gitCommit is "unknown" outside a git checkout
```

Then run `java -jar lispresen.jar ...` wherever this skill says `lispresen ...`. With SBCL
installed, `sbcl --script lispresen/cli.lisp ...` works too and produces the same output.
On Windows run these in WSL, where the Linux executable also works.

## 2. Start from the starter deck

```bash
lispresen new talk.lisp            # Japanese starter deck
lispresen new talk.lisp --lang en  # English
```

The starter deck renders cleanly and shows the header, sections, a list with notes, a
statement slide, inline forms, runnable code, columns with a callout, a table and a flow,
each with a comment. `new` refuses to overwrite an existing file unless `--force` is given;
never pass `--force` over a deck the user wrote.

Pick the deck language from the user's request: write the slides in the language the
user asks for or writes in. lispresen's line breaking and measurement are tuned for
Japanese (lines break between phrases); English decks work as well.

Replace the starter's placeholder content instead of appending to it, and delete the
comments that no longer describe anything.

## 3. Write the deck

Read `references/deck-format.md` now if it is not already in context. The essentials:

- One `(deck ...)` header, then top-level `(section "...")`, `(slide ...)`, `(agenda)` and
  `(define ...)` forms, each read separately, so every diagnostic has a line number.
- A slide is blocks top to bottom: strings (paragraphs), `ul`/`ol`, `code`, `table`,
  `image`, `video`, `quote`, `column`s, diagrams (`flow`, `cells`), charts (`bars`, `columns`, `lines`, `pie`), `notes`,
  `source`.
- No coordinates or font sizes exist in the format. When content does not fit, the fix is
  less content per slide, not styling.
- Plan the talk as sections of a few slides; one idea per slide, at most 6 list items.
  Put what the speaker says, not what the audience reads, in `(notes ...)`.
- `:agenda t` (or a title string) on the deck adds an agenda after the title slide; put
  `(agenda)` after opening slides that should come before it.

## 4. Preview, lint and fix

The editing loop is `lispresen serve`: it serves the deck's page over HTTP, rebuilds it
whenever the deck (or a file it reads) is saved, and the open page reloads on the slide it
showed. Start it once, in the background, and leave it running while you edit:

```bash
lispresen serve talk.lisp --port 8000 2> serve.log &   # http://127.0.0.1:8000/
serve_pid=$!
```

- It writes nothing to disk. Images and videos are served from beside the deck, so they
  show without `--embed`, and a video can be sought.
- A build happens when the page asks for it (every half second while it is open). Each
  build writes `lispresen: built talk.lisp: E errors, W warnings` and the diagnostics to
  standard error (`serve.log` above), and the page shows them in a panel over the slides.
  Without a page open, `curl -s http://127.0.0.1:8000/__lispresen/version` triggers one.
- `--port` picks another port when 8000 is taken (`cannot serve on ...`, exit 1);
  `--theme` and `--runtime` are the rendering options. It runs on the native executable,
  the JVM jar and SBCL; a WASM build refuses it.
- Stop it when done: `kill $serve_pid`. Never leave a server running when you hand over.

Lint the deck with `--check`, which prints the same diagnostics without a server:

```bash
lispresen talk.lisp --check --strict   # report only; exit 1 on any warning or error
```

Diagnostics go to standard error, one per line:

```
talk.lisp:42: warning: slide 7 "title": a list with 8 items is hard to follow; keep it to 6
talk.lisp:42: error: slide 7 "title": the content does not fit the slide even at the smallest sizes; split the slide
```

The line is the line of the top-level form; the slide number counts the title slide and
the agenda. Exit status: 0 success, 1 errors (or warnings under `--strict`), 2 usage.
A render with an error still writes the HTML, with the broken slide outlined in red, so
always read standard error and the exit status, not only whether `talk.html` appeared.

Loop (edit, save, read the diagnostics, look at the page) until `--check --strict` prints
nothing. Fix warnings too: each one marks something the audience will find hard to read.
`references/deck-format.md` maps the common diagnostics to their fixes.

When the deck is done, build the HTML to hand over:

```bash
lispresen talk.lisp --embed --strict   # writes talk.html (and :page-image, if set); exit 1 on any warning
```

`--embed` inlines images and videos as `data:` URIs, so the file alone is the deck. Without
it (`lispresen talk.lisp`) the HTML refers to images by relative path and must travel with
them, and a missing image is not reported; `--embed` reports it as an error.

## 5. Check in a browser

While editing, the page is the one serve answers: tell the user to open
`http://127.0.0.1:8000/` and keep it open; every save reloads it. The built `talk.html`
opens from disk (on macOS, `open talk.html`). Append `?check` to either URL
(`http://127.0.0.1:8000/?check`): the player then measures each slide with the fonts the
machine really has and shows an overlay in the top-right corner. `runtime check: no
corrections` is the expected result; otherwise it lists each slide it stepped down a size
(`slide 7: body 56px -> 52px`) and outlines it.
A correction is not an error, but a slide corrected on the presenting machine is a slide
near its limit; shorten it.

With browser automation available (Chrome DevTools MCP, Playwright), open
`http://127.0.0.1:8000/?check` (while serving) or `file:///absolute/path/talk.html?check`
in a new page, wait for the fonts, read the text of the element `#chk`, and take
screenshots of the slides you changed (`#N` in the URL opens slide N;
`window.lispresen.show(i)` shows the slide at 0-based index i). A served page reloads
itself after each save, so screenshot after the reload. Close the pages you opened.
Without browser tools, ask the user to open the page with `?check` and tell you what the
overlay says.

Player keys worth telling the user: arrows/space move, `F` full screen, `O` overview, `P`
presenter window (notes, timer), `E` or a click edits runnable code, `Ctrl+Enter` runs it.

## 6. Before handing over

- `lispresen talk.lisp --embed --strict` printed nothing and exited 0, and `talk.html` is
  that build (serve writes no file).
- The `lispresen serve` you started is stopped.
- The browser check reports `no corrections`.
- Every `image` and `video` file exists beside the deck (or the deck was rendered with
  `--embed`), and every one has `:alt`.
- The title slide (`:title`, `:author`, `:date`) and the agenda read as intended.
- Runnable code (`:lang "lisp"`, `"scheme"`, `"clojure"`) loads a 35 MB runtime from the
  network when the deck opens. If the talk does not run code live, use `:run nil` on the
  block or `:runtime :none` on the deck.
- Tell the user the output path, how to present (open in a browser, `F`, `P`), and any
  slide you split or shortened to clear a diagnostic.
