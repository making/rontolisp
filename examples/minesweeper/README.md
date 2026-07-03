# Minesweeper (one Lisp rulebook, two front-ends)

A complete Minesweeper whose rules are written once in rontolisp and played two
ways: in the **browser** (the rules compiled ahead of time to a WebAssembly
reactor) and on the **desktop** (the same rules run on the interpreter behind a
Java/Swing window). All the interesting work -- the flood-fill reveal and
win/lose detection -- happens in Lisp; only the drawing differs between the two.

| File | Role |
| --- | --- |
| [`minesweeper-core.lisp`](minesweeper-core.lisp) | The shared rules: a pure state machine, no rendering (see below) |
| [`minesweeper-wasm.lisp`](minesweeper-wasm.lisp) | Browser front-end: loads the core, adds HTML rendering + WASM exports |
| [`minesweeper.html`](minesweeper.html) | The page: grid, mouse/touch input, mine placement, timer |
| [`minesweeper.wasm`](minesweeper.wasm) | Prebuilt module (regenerate with `build.sh`) |
| [`build.sh`](build.sh) | Recompile `minesweeper-wasm.lisp` to `minesweeper.wasm` |
| [`minesweeper-swing.lisp`](minesweeper-swing.lisp) | Desktop front-end: loads the core, paints a Swing grid |

The two front-ends share `minesweeper-core.lisp` verbatim -- the browser build
inlines it at compile time (a top-level literal `load`), and the Swing build
loads it at run time. Swapping the rendering layer is all it takes to move the
same game between WebAssembly and the JVM.

## Play it on the desktop (Java / Swing)

```bash
# from the repo root, after ./mvnw package
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/minesweeper/minesweeper-swing.lisp
```

Left-click opens a cell, right-click flags it, and any click after the game ends
starts a fresh board. The Swing front-end runs on the JVM -- interpret it, or
compile it to a `.class` (`-o Minesweeper.class`; WASM cannot lower a Java
object) -- and needs a display. Unlike the
entropy-free browser reactor, this build has `random`, so it lays
its own mines -- keeping them off the first click. The rendering layer reuses the
[`../swing.lisp`](../swing.lisp) `swing` package (spliced in with
`(require :swing "../swing.lisp")`), whose clickable, text-capable label grid
(`swing:label-grid-window`) was built for this game.

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
`place-mines` the Swing front-end calls with an interpreter-shuffled ordering. So
entropy stays host-side while the rule stays in Lisp, and the two front-ends
place mines identically.

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
the same pattern as [`../rainbow.html`](../rainbow.html). See the top of
`minesweeper-core.lisp` for the state layout and the `CLAUDE.md` notes on
`rontolisp:wasm-export` and `--no-wasi` for the ABI details.
