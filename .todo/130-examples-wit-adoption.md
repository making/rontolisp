# Which examples adopt `rontolisp:wit-export` -- and which cannot

**Status:** open, unstarted. Follow-on of `.todo/126` (`wit-export`, DONE 2026-07-14),
raised 2026-07-14 from a survey of every `rontolisp:wasm-export` / `wasm-import` in
`examples/`. This is not a feature todo: it is the **adoption ledger**, so a future
agent does not retrofit `wit-export` blindly onto exports whose ABI or whose export
name a world cannot express. The "blocked" half below is the load-bearing half.

## The survey (every `wasm-export` in `examples/`, counted 2026-07-14)

| Example | Exports | Shape | Verdict |
|---|---|---|---|
| `console/mandelbrot-nogc.lisp:60-63` | 1 | `(f64 f64 f64 f64 s32 s32 s32) -> string` | **adopt now** -- the one clear win (§1) |
| `browser/rainbow/rainbow.lisp:175-177` | 1 | `string -> string` | adopt, thin -- buys a contract check and nothing else (§2) |
| `ml/simd-gemv-nogc.lisp:74` | 1 | `s32 -> s32` | skip -- a `.wit` would restate one integer (§2) |
| `browser/minesweeper/minesweeper-wasm.lisp:86-92` | 7 | **`:s-expr`** in all 7, out in 4 | BLOCKED -- no WIT type maps to `:s-expr` (§3) |
| `browser/hiragana/recognize.lisp:34` | 1 | **`:s-expr`** -> `string` | BLOCKED -- same (§3) |
| `browser/webgl-heat3d/heat3d.lisp:240-242` | 3 (1 aliased) | scalars, `:as "totalHeat"` | BLOCKED -- `:as` vs. kebab label (§4) |
| `browser/webgl-robot-arm/robot-arm.lisp:934-940` | 7 (2 aliased) | scalars, `:as "setSolver"`/`"ikError"` | BLOCKED -- same (§4) |
| `browser/webgl-platformer/platformer.lisp:927-939` | 13 (9 aliased) | scalars, `:as "setKey"`/`"getPx"`/... | BLOCKED -- same (§4) |
| `browser/webgl-cube/cube.lisp:216`, `webgl-galaxy/galaxy.lisp:193-194` | 1 / 2 | `frame(f64)`, `init(s32)` | deferred -- their story is the import side (§5) |
| `browser/webgl-triangle/triangle.lisp` | **0** | 11 imports, no exports | not applicable -- a world with no exports is a compile error (§5) |
| `net/*`, `wasmcloud/*/app.lisp` | -- | `rontolisp:http-handler` | excluded by construction (§6) |
| `count-vowels/count-vowels.lisp`, `wit/world/analyzer.lisp` | 1 / 4 | -- | already on `wit-export` -- nothing to do |

Aliased exports total **12**, over three demos. `:s-expr` exports total **8**, over two.

## 1. Adopt now: `console/mandelbrot-nogc.lisp`

Every type in `:params '(:float :float :float :float :int :int :int) :returns :string`
is inside the boundary subset `WitExportDirective.designator()` accepts
(`WitExportDirective.java:367-390`: `s32`/`s64`/`f64`/`bool`/`string`). Today the
example is built `--no-gc --optimize` (`examples/examples.yaml:329-330`,
`backends: [no-gc]`) and driven by the Node host **written into its own header**
(`examples/console/mandelbrot-nogc.lisp:16-24`, repeated at
`doc/en/compiling/wasm.md:915-923` / `doc/ja/compiling/wasm.md:525`), which reserves
nothing but has to read the returned `(ptr, len)` out of the exported memory by hand:

```js
const [p, n] = ex.mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30);
process.stdout.write(Buffer.from(new Uint8Array(ex.memory.buffer, p, n)).toString());
```

That is exactly the relief `examples/count-vowels/README.md` documents for the other
direction (a `:string` **parameter**): under `--no-gc --component` the canonical ABI
carries the string and `post-return` frees it, so the host writes no memory code at all.
Mandelbrot is the missing `:string`-**result** half of the same story, and it is the only
example in the tree where a world buys something a `.wit`-less build cannot have.

**Prototyped end to end before writing this todo** (scratchpad only, nothing committed;
jar = `./mvnw -q -Dmaven.test.skip=true package`, wasmtime 46.0.1):

- a 5-line `mandelbrot.wit` (`export mandelbrot: func(x0: f64, ..., max-iter: s32) -> string;`)
  plus `(rontolisp:wit-export "mandelbrot.wit")` replacing lines 60-63;
- `--no-gc --optimize` output is **byte-identical** (`cmp`) to the hand-written
  `wasm-export` build -- the migration cannot regress the existing artifact;
- `--no-gc --component --optimize --emit-wit` -> a **1285-byte** component, and
  `wasmtime run --invoke 'mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30)' comp.wasm`
  prints the ASCII art **with zero runtime flags**;
- `jco transpile` + `import { mandelbrot } from "./dist/comp.js"` returns the string in
  Node: the `Uint8Array`/`ptr`/`len` dance above **disappears from the host**;
- the wasm-GC `--component` leg also compiles and runs (110,806 bytes,
  `-W gc=y`; the export is sync-lifted because it returns a string rather than
  printing, so no `:async t` and no jco stackful-async gap -- `.todo/112`).

Copy `examples/count-vowels/` verbatim as the model (a checked-in `.wit`, a README with
the two-build table, `backends: [no-gc, wasm-component]`). One naming detail: if the
checked-in world is to survive an `--emit-wit` round trip **byte-exact** it must be
spelled `package root:component; world root;` -- a component's type is always
`root:component`/`root` (`.kb/wit.md`, "Two deliberate differences"). And be as honest
as count-vowels' README already is: that diff is a regression check on OUR type mapping,
not a check on the program.

## 2. Adopt, thin (do only alongside a real reason)

**`browser/rainbow/rainbow.lisp:175-177`** -- `rainbow-html`, `string -> string`, already
a valid component-model label, no `:as`. Verified: after the migration the `--no-wasi`
core module is byte-identical (106,505 bytes, `cmp`). What it buys: the `wit-export`
contract check, which runs on **every** backend (arity/type drift becomes a compile error
naming the WIT line, even under a plain `rontolisp rainbow.lisp`). What it does **not**
buy: bindings (`--emit-wit` requires `--component` -- `RontoLispCli.java:224-229`; the
page loads a raw core module), and CI coverage (browser demos are absent from
`examples/examples.yaml` by construction -- see the comment at
`examples/examples.yaml:41-43`; they cannot run headless). `rainbow.html:100-118` keeps
its hand-written `__ronto_alloc` + `(ptr,len)` glue either way. So: a one-line migration
whose `.wit` sits next to a page that cannot consume it. Defer it until the browser demos
get a component story; it is not wrong, it is just not yet worth a second file.

**`ml/simd-gemv-nogc.lisp:74`** -- `fingerprint(s32) -> s32`, already callable with
`wasmtime --invoke` and no bindings, no memory in the picture. A `.wit` would restate one
integer. Skip.

## 3. BLOCKED: the `:s-expr` ABI has no WIT spelling (a genuine hole in the mapping)

`minesweeper-wasm.lisp:86-92` -- all **seven** exports take an `:s-expr` parameter (the
game state) and **four** return one; `hiragana/recognize.lisp:34` --
`recognize(:s-expr) -> :string` (the 576-pixel bitmap arrives as a list). `:s-expr` is
s-expression **text** crossing as `(ptr, len)` and parsed by the emitted runtime reader
(`WasmExportCompiler.java:37`, `:377`, `:404`).

There is no WIT type that lowers to it. `designator()` maps WIT `string` to `:string` --
a *different* wire format (an ordinary Lisp string; nothing parses it). So a blind
retrofit does not fail: it **compiles**, and the page breaks at runtime.

And it is worse than "unsupported", because the two already collide in the emitted WIT:
`WasmExportCompiler.componentValType()` (`:484-491`) lifts **both** `:string` and
`:s-expr` as component `string`. Verified -- an `:s-expr` in / `:s-expr` out export under
`--component --emit-wit` prints

```wit
export head: func(p0: string) -> string;
```

which is a perfectly valid world that, fed back through `wit-export`, produces `:string`
designators and a silently different ABI. `.kb/wit.md:314` already notes in passing that
"an `:s-expr` export has no WIT spelling"; this todo is where that becomes a decision:

- **(a) Never.** `:s-expr` is a rontolisp-private wire format; these two demos stay on
  hand-written `wasm-export` permanently, and `.kb/wit.md` says so under its own heading
  instead of in a subclause. Cost: two demos outside the WIT story forever.
- **(b) Rewrite the data model, after `.todo/128`.** Minesweeper's state is a plist of
  ints and its cells are ints; the hiragana bitmap is 576 numbers. Both are expressible
  as a WIT `record` / `list<u8>` once marshalling exists (`WitTypeMapper` already names
  their house representation). That is a **rewrite of the demos**, not a retrofit, and it
  is the honest long-term home.
- **(c) Annotate a WIT `string`** as "s-expression text" (a custom attribute). Rejected on
  sight if it makes our WIT unportable to `wasm-tools` / `wit-bindgen` -- which it does.

Recommendation: **(a) now, (b) as the eventual home, never (c)** -- but take the decision
explicitly and record it; do not let a retrofit PR make it by accident.

## 4. BLOCKED: `:as` camelCase vs. the component-model kebab label (12 exports)

`heat3d.lisp:242` (`:as "totalHeat"`), `robot-arm.lisp:939-940` (`"setSolver"`,
`"ikError"`), `platformer.lisp:928,932-939` (`"setKey"`, `"getCoins"`, `"coinTotal"`,
`"getDeaths"`, `"getState"`, `"getTime"`, `"getPx"`, `"getPy"`, `"getPz"`) publish 12
exports under camelCase aliases their pages call **by literal name**:
`webgl-heat3d/index.html:387,408`, `webgl-robot-arm/index.html:473,484,513`,
`webgl-platformer/index.html:415,422,427`.

`wit-export` emits no `:as`: `WitExportDirective.exportForm()` (`:305-358`) builds the
lowered `wasm-export` from the world's label, and `LABEL` (`:70`) *enforces*
lower-kebab-case. That is deliberate -- `.kb/wit.md:247` calls `:as` "the ad-hoc fix-up"
the WIT replaces, and for a **component** it is simply right (jco maps `count-vowels` ->
`countVowels`). But these are raw core modules loaded with `WebAssembly.instantiate`,
where the export name is literal: adopting `wit-export` renames `setKey` to `set-key` and
breaks every `lisp.setKey(...)` call site.

The open design question, stated plainly and **not decided here**:

- **(i)** `wit-export` grows an alias mechanism (a `:as`-carrying option, or a WIT-level
  annotation) -- which re-introduces exactly the two-places drift the directive was built
  to kill, on the one field the component model says must be canonical.
- **(ii)** Core-module demos that want camelCase JS names stay on hand-written
  `wasm-export` forever, and `wit-export` is documented as **component-facing**.

Measure the cheap third path before deciding either: **a kebab-case export is already
callable from JS** as `exports["set-key"](...)`, and the tree has the precedent --
`rainbow.html:109` does exactly `exports["rainbow-html"](ptr, len)`. If the pages accept
that, the "collision" collapses into a ~12-line `index.html` edit and needs no new
mechanism at all. It is only a blocker if we insist the JS side keep camelCase.

## 5. Deferred to `.todo/127`: the WebGL family is an IMPORT story

Verified counts (`rontolisp:wasm-import` / `wasm-export` per file):

| File | imports | exports |
|---|---|---|
| `browser/webgl-common/gl.lisp` | **31** | 0 |
| `webgl-triangle/triangle.lisp` | 11 | **0** |
| `webgl-cube/cube.lisp` | 7 | 1 |
| `webgl-galaxy/galaxy.lisp` | 8 | 2 |
| `webgl-heat3d/heat3d.lisp` | 7 | 3 |
| `webgl-robot-arm/robot-arm.lisp` | 14 | 7 |
| `webgl-platformer/platformer.lisp` | 10 | 13 |

(`.todo/124:26` and `.todo/127:81-84` both say "34 hand-declared imports" for `gl.lisp`;
the file declares **31** today. Fix the number when the spike happens.)

Triangle has **zero** exports and a world with no exports is a compile error
(`WitExportDirective.java:252-254`), so `wit-export` is not even applicable to it. For the
others a world could describe only their 1-3 `frame`/`init` exports -- while the whole
interest of these demos is the ~40-import GL surface, which `wit-export` never looks at
(a world's `import` items are ignored today; `.kb/wit.md`). The artifact worth having is
`local:webgl/gl.wit` **plus a generated JS import object** -- and that is already the
spike in `.todo/127`'s Definition of Done (`.todo/127:81-84`) and the "follow-on prize" in
`.todo/124:143-147`. Cross-reference it; do not duplicate it, and do not migrate these
demos' export side ahead of it (§4 blocks three of them anyway).

## 6. Excluded by construction

`examples/net/*` and `examples/wasmcloud/*/app.lisp` end in `rontolisp:http-handler`, and
`wit-export` + `http-handler` is a hard compile error (`RontoLispCli.java:304-307`: a
serve-mode component's only export is `wasi:http/incoming-handler`, so a world of function
exports could not be honored). They acquire a world when `http-handler` *becomes* "a
program implementing the `wasi:http/incoming-handler` world" -- `.todo/124`'s absorption
list -- not before.

## Definition of done

- **`examples/console/mandelbrot-nogc.lisp` migrated**: a checked-in `.wit` next to it
  (`package root:component; world root;` if the `--emit-wit` round trip is to be
  byte-exact), `(rontolisp:wit-export "...")` replacing lines 60-63, the header rewritten
  to show **both** builds -- the `--no-gc` core module with its memory-reading host, and
  the `--no-gc --component` route where the host is `mandelbrot(...)` and nothing else.
  The `--no-gc` core module stays **byte-identical** to today's (assert with `cmp`; it
  already is).
- `examples/examples.yaml:329-330` gains a `wasm-component` leg next to `no-gc` (the
  `count-vowels` pattern: the component legs only compile in the harness), and
  `examples/README.md:35`'s row mentions the world. `doc/en|ja/compiling/wasm.md`'s
  mandelbrot snippet **stays** (it documents the `--no-gc` string ABI, which is still the
  truth for a core module) but cross-links the component route -- mirrored in both
  languages in the same commit.
- **The `:s-expr` hole is a written decision** in `.kb/wit.md` (§3), not a subclause: which
  of (a)/(b), and why `wasm-export` remains the escape hatch. "Not supported" is an
  acceptable record; silence is not.
- **The `:as`/kebab collision is a written decision** (§4), after checking whether the three
  pages can call `exports["set-key"]` (`rainbow.html:109` says they can). If they can, say
  the demos MAY migrate and leave them alone anyway; if we choose an alias mechanism, that
  is its own todo, not a line in this one.
- The WebGL family is explicitly deferred to `.todo/127` (and its `gl.lisp` import count
  corrected there). No `gl.lisp` work in this todo.
- `rainbow` / `simd-gemv-nogc` left alone (or migrated with a one-line note); `minesweeper`
  / `hiragana` NOT migrated.
- `./mvnw test` + `ExamplesE2eTest` for the new leg; native `CiSpecE2eTest` only if
  `ci-spec.yaml` moves (it should not).

## References

`.kb/wit.md` (the contract check, the `--emit-wit` fixpoint, the `:as` note at :247 and
the `:s-expr` note at :314), `.kb/wasi-component.md`, `.kb/wasm-export-no-wasi.md`,
`.kb/no-gc-scalar-wasm.md`, `examples/count-vowels/README.md` (the model to copy),
`.todo/124` (the anchor), `.todo/127` (the import side + the `gl.lisp` spike),
`.todo/128` (marshalling -- the gate on §3 option (b)), `.todo/112` (jco cannot call a
stackful-async GC export; irrelevant here only because mandelbrot's export is sync).
