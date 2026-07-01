# Minesweeper (Lisp -> WebAssembly, playable in the browser)

A complete Minesweeper whose rules are written in rontolisp, compiled ahead of
time to a WebAssembly reactor, and played in a plain HTML page. All the
interesting work -- the flood-fill reveal, win/lose detection, and even the
board HTML -- happens in Lisp; the page is just glue.

| File | Role |
| --- | --- |
| [`minesweeper.lisp`](minesweeper.lisp) | The whole game as a pure state machine (see below) |
| [`minesweeper.html`](minesweeper.html) | The page: grid, mouse/touch input, mine placement, timer |
| [`minesweeper.wasm`](minesweeper.wasm) | Prebuilt module (regenerate with `build.sh`) |
| [`build.sh`](build.sh) | Recompile the `.lisp` to `.wasm` |

## Play it

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
a reactor has no entropy source, the mine layout is generated in JavaScript and
handed to the Lisp `new-game` -- which also lets the page keep the mines away
from the very first click.

The game is a **pure state machine**. The state is a nested list of integers
`(status w h mines revealed flags)`; the page treats it as an opaque string that
round-trips through the WebAssembly `:sexpr` ABI, so the JavaScript never parses
Lisp. Each interaction is one export call:

| Export | Signature | Purpose |
| --- | --- | --- |
| `new-game` | `(:int :int :sexpr) -> :sexpr` | Build a fresh state from width, height, and a mine bit-list |
| `reveal` | `(:sexpr :int) -> :sexpr` | Open a cell; flood-fill blanks; detect win/loss |
| `toggle-flag` | `(:sexpr :int) -> :sexpr` | Flag / unflag a covered cell |
| `render` | `(:sexpr) -> :string` | The board as a run of `<div class='cell ...'>` elements |
| `game-status` | `(:sexpr) -> :int` | 0 playing, 1 won, 2 lost |
| `mines-remaining` | `(:sexpr) -> :int` | Mines minus flags placed |

The `:string` / `:sexpr` boundary is a `(ptr, len)` into the module's linear
memory. The page writes UTF-8 via the exported `__ronto_alloc` bump allocator,
passes the pointer and length, and reads the returned `(ptr, len)` back out --
the same pattern as [`../rainbow.html`](../rainbow.html). See the top of
`minesweeper.lisp` for the state layout and the `CLAUDE.md` notes on
`rontolisp:wasm-export` and `--no-wasi` for the ABI details.
