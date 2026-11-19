# How the playground and the documentation site's cells were driven (k10, 2026-11-19)

Build first: `./mvnw -Pweb -DskipTests package`, then
`./mvnw -f docs-tool/pom.xml -DskipTests package` and
`java -jar docs-tool/target/rontolisp-docgen.jar --source doc --out web/dist/docs`.

From node (no browser; node 22 or later):

- `node drive.js web/dist <language> <export> <file>` -- one call of an export
  (`rontoRunProgram`, `rontoEval`, `rontoCompileWasm`), timed.
- `node compile.js web/dist <language> <file> <out.wasm>` -- the Compile to WASM button,
  written to a file (compare with `rontolisp <file> -o x.wasm`: the bytes are the CLI's).
- `node page.js web/dist doc/en/java/index.md` -- every Run cell of a page, in order, as
  `docs.js` runs them; `SKIP=<line>,<line>` leaves cells out.
- `pages.sh web/dist <seconds> <pages...>` -- the cells that hang, fail or differ, a page a
  process; every page of `doc/en` under `xargs -n 12 -P 20` takes about six minutes.

In headless Chrome over CDP (`chrome-headless-shell` of Playwright's cache, or `CHROME=`;
node's own WebSocket, no package): `node browser.js web/dist <scenario.js>` serves the
directory, opens a page and runs the scenario. `ISOLATED=1` sends COOP/COEP.

- `PAGE=/docs/en/java/index.html NEEDLES='text of a cell;;text of another' node browser.js web/dist cell.js`
- `PAGE=/docs/ja/java/index.html node browser.js web/dist allcells.js`
- `PAGE=/docs/en/java/index.html node browser.js web/dist stop.js` -- a cell that never
  ends, the page alive meanwhile, Stop, the next Run.
- `node browser.js web/dist playground.js` -- the playground page's Run source (needs
  `Pool.java` and `Hello.java` beside it: the doc's pool example and a hello program).
