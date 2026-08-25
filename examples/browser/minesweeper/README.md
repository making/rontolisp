# Minesweeper (one Lisp rulebook, three front-ends)

A complete Minesweeper whose rules are written once in rontolisp and played three
ways: in the **browser** (the rules compiled ahead of time to a WebAssembly
reactor), on the **desktop through Java/Swing**, and on the **desktop as a native
macOS window** (both of those run the same rules on the interpreter). All the
interesting work -- the flood-fill reveal and win/lose detection -- happens in
Lisp; only the drawing differs between the three.

| File | Role |
| --- | --- |
| [`minesweeper-core.lisp`](minesweeper-core.lisp) | The shared rules: a pure state machine, no rendering (see below) |
| [`minesweeper-wasm.lisp`](minesweeper-wasm.lisp) | Browser front-end: loads the core, adds HTML rendering + WASM exports |
| [`minesweeper.html`](minesweeper.html) | The page: grid, mouse/touch input, mine placement, timer |
| [`minesweeper.wasm`](minesweeper.wasm) | Prebuilt module (regenerate with `build.sh`) |
| [`build.sh`](build.sh) | Recompile `minesweeper-wasm.lisp` to `minesweeper.wasm` |
| [`minesweeper-swing.lisp`](minesweeper-swing.lisp) | Desktop front-end: loads the core, paints a Swing grid |
| [`minesweeper-macos.lisp`](minesweeper-macos.lisp) | Desktop front-end: loads the core, builds a native Cocoa window |
| [`minesweeper-core-test.lisp`](minesweeper-core-test.lisp) | The rules, checked with [rove](../../../doc/en/guides/testing.md) -- no front-end can be run head-less, the core can |

The three front-ends share `minesweeper-core.lisp` verbatim -- the browser build
inlines it at compile time (a top-level literal `load`), and the two desktop
builds load it at run time. Swapping the rendering layer is all it takes to move
the same game between WebAssembly, the JVM and AppKit.

Because the core touches neither the screen nor entropy, it is also the part
that can be TESTED, and `minesweeper-core-test.lisp` does — the flood fill, the
win and loss transitions, flagging, and the placement rule that keeps the first
click safe. rove is vendored in this repository, so its three directories go on
`--system-path`, and the run's exit code is the verdict:

```bash
# from the repo root, after ./mvnw package
SP=src/test/resources/rove:src/test/resources/dissect:src/test/resources/cl-ppcre
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
  examples/browser/minesweeper/minesweeper-core-test.lisp --system-path $SP
```

## Play it on the desktop (Java / Swing)

```bash
# from the repo root, after ./mvnw package
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/browser/minesweeper/minesweeper-swing.lisp
```

Left-click opens a cell, right-click flags it, and any click after the game ends
starts a fresh board. The Swing front-end runs on the JVM -- interpret it, or
compile it to a `.class` (`-o Minesweeper.class`; WASM cannot lower a Java
object) -- and needs a display. Unlike the
entropy-free browser reactor, this build has `random`, so it lays
its own mines -- keeping them off the first click. The rendering layer reuses the
[`../../jvm/swing.lisp`](../../jvm/swing.lisp) `swing` package (spliced in with
`(require :swing "../../jvm/swing.lisp")`), whose clickable, text-capable label grid
(`swing:label-grid-window`) was built for this game.

## Play it on the desktop (native macOS)

```bash
# from the repo root, after ./mvnw package
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/browser/minesweeper/minesweeper-macos.lisp

# or, after ./mvnw -Pnative package -- no JVM in sight
./target/rontolisp examples/browser/minesweeper/minesweeper-macos.lisp

# or compiled, like the Swing build (the binding travels inside the class)
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
  examples/browser/minesweeper/minesweeper-macos.lisp -o Minesweeper.class
java Minesweeper
```

Left-click opens a cell, right-click (or Ctrl-click) flags it, and the face
starts a fresh board; a mine counter and a running clock sit either side of it.
This front-end draws a **real Cocoa window** -- rounded tiles, a dark title bar,
emoji glyphs -- out of the built-in `objc` / `appkit` packages, which bind AppKit
through the foreign function API. Two consequences: it needs macOS with a
display, and unlike the Swing build it also runs in the **`rontolisp` native
binary**, where `java:` interop cannot be interpreted at all ([the macOS GUI
guide](../../../doc/en/guides/objc-appkit.md) says why). Compiling it to a
`.class` works exactly like the Swing build's, the binding travelling inside the
class; only WASM refuses, having no foreign function API. Its rendering layer is
the reusable [`../../macos/cocoa.lisp`](../../macos/cocoa.lisp) `cocoa` package
-- the AppKit counterpart of `swing.lisp`, spliced in the same way with
`(require :cocoa "../../macos/cocoa.lisp")`.

## Play it in the browser (WebAssembly)

```bash
./build.sh                      # (optional) rebuild minesweeper.wasm
jwebserver -p 8000              # or: python3 -m http.server 8000
# open http://localhost:8000/minesweeper.html
```

It must be served over `http://` (a `file://` page cannot `fetch` the `.wasm`)
and needs a browser with WebAssembly GC: Chrome 119+, Firefox 120+, or
Safari 18.2+.

Left-click opens a cell, right-click (or long-press on touch) flags it, and the
smiley starts a new game.

## How it works

The module is built with `--no-wasi`, which produces a **reactor**: it has no
WASI imports (so `WebAssembly.instantiate(bytes, {})` needs no import object) and
exposes an `_initialize` export the page runs once after instantiation. Because
a reactor has no entropy source, the page cannot ask Lisp to roll the mines; but
it does not reimplement the placement rule either. Instead the page only shuffles
a random ordering of the cell indices (its sole job) and hands it to the shared
Lisp `place-mines`, which applies the first-click-safe placement rule -- the same
`place-mines` the two desktop front-ends call with an interpreter-shuffled
ordering. So
entropy stays host-side while the rule stays in Lisp, and every front-end places
mines identically.

The game is a **pure state machine**. The state is a nested list of integers
`(status w h mines revealed flags)`; the page treats it as an opaque string that
round-trips through the WebAssembly `:s-expr` ABI, so the JavaScript never parses
Lisp. Each interaction is one export call:

| Export | Signature | Purpose |
| --- | --- | --- |
| `place-mines` | `(:int :int :int :int :s-expr) -> :s-expr` | The shared first-click-safe placement rule: pick mines from a host-supplied random ordering |
| `new-game` | `(:int :int :s-expr) -> :s-expr` | Build a fresh state from width, height, and a mine bit-list |
| `reveal` | `(:s-expr :int) -> :s-expr` | Open a cell; flood-fill blanks; detect win/loss |
| `toggle-flag` | `(:s-expr :int) -> :s-expr` | Flag / unflag a covered cell |
| `render` | `(:s-expr) -> :string` | The board as a run of `<div class='cell ...'>` elements |
| `game-status` | `(:s-expr) -> :int` | 0 playing, 1 won, 2 lost |
| `mines-remaining` | `(:s-expr) -> :int` | Mines minus flags placed |

The `:string` / `:s-expr` boundary is a `(ptr, len)` into the module's linear
memory. The page writes UTF-8 via the exported `__ronto_alloc` bump allocator,
passes the pointer and length, and reads the returned `(ptr, len)` back out --
the same pattern as [`../rainbow/rainbow.html`](../rainbow/rainbow.html). See the top of
`minesweeper-core.lisp` for the state layout and the `CLAUDE.md` notes on
`rontolisp:wasm-export` and `--no-wasi` for the ABI details.
