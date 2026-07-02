# Updating the Documentation Site

The user-facing manual is Markdown under `doc/en/**`, rendered to a static HTML
site (`web/dist/docs`) by the standalone generator in `docs-tool/` and published
to GitHub Pages by `.github/workflows/pages.yaml`. The site reuses the browser
playground's WebAssembly runtime, so the Lisp examples are runnable in-page.

**Layout.** `doc/en/nav.yaml` = the sidebar/order (Getting Started, Compiling,
Language Reference, Guides). `doc/assets/docs.css` + `docs.js` = shared theme and
the runnable-cell wiring. `doc/<lang>/` is a language; `en` and `ja` both exist
today, each with its own `nav.yaml`, and docgen renders one site per language
(`/docs/en/`, `/docs/ja/`) with an automatic language switcher in the header
(2+ languages -> switcher appears; `en` is the default and gets the `/docs/`
redirect). Adding another `doc/<lang>/` with a `nav.yaml` auto-creates its site.
Per-operator reference pages live in catalog directories, each with a
`_catalog.yaml` (categories -> ordered `{slug, name}` entries) and a table
"index page": `reference/functions/` (index `reference/functions.md`),
`reference/macros/` (index `reference/macros.md`), `reference/special-forms/`
(index `reference/special-forms.md`). docgen discovers every `_catalog.yaml`,
renders one HTML page per entry with prev/next, and links each operator name in
the index table to its page.

**Code-fence conventions** (parsed by `DocExamplesTest` and the docgen
`RunnableBlockTransformer`):
- ` ```lisp ` = a runnable, self-contained example (becomes a "Run" cell). It is
  executed by `DocExamplesTest` and must not throw. Annotate the final form with
  `; => value` (prin1 form) to show + assert its result; or follow the block with
  a plain ` ``` ` output block to assert stdout (use this for printing examples).
- ` ```console ` = a static transcript or an example that needs stdin/files/network
  or that signals (`read`, `open`, `load`, `with-open-file`, `error`,
  `rontolisp:fetch`). Not executed.
- ` ```bash ` = shell commands. Plain ` ``` ` = expected output.
- Do NOT use dotted-pair literals (`'(a . 1)`) in `lisp` blocks -- the reader
  rejects them; build with `(cons ...)`/`(list ...)`.

**Keep all languages in sync.** Every doc change must be mirrored across BOTH
`doc/en/**` and `doc/ja/**` (and any future `doc/<lang>/`) in the same commit:
adding/removing/renaming a page, a `nav.yaml` entry, or a `_catalog.yaml` entry
must happen in every language tree, and prose edits must be translated. The two
trees must stay structurally identical -- same file set, same heading layout, and
**byte-identical fenced code blocks** (`lisp`/`console`/`bash` and their `; =>`
annotations / output blocks); only prose, headings, link text, `nav.yaml`
`title:`/`lang_name:`, and `_catalog.yaml` category `title:`s are translated
(slugs and operator `name:`s stay identical). Note `DocExamplesTest` only
executes `doc/en` examples, so a broken `doc/ja` code block will NOT be caught by
the build -- this is why ja code fences must be copied verbatim from en.

**Adding/editing pages.** Edit the Markdown; add new top-level pages to
`doc/en/nav.yaml` (and `doc/ja/nav.yaml`). For a new function/macro/special form,
add a per-operator page + a `_catalog.yaml` entry under the matching directory in
each language tree (see CLAUDE.md "Implementation Order" step 5). After editing
examples, normalize the shown results to the real interpreter values and catch
any non-runnable example:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixDetailResults test  # rewrite ; => / output of detail pages
./mvnw -Dtest=DocExamplesTest test                                            # verify every example runs + matches
```

**Build & preview locally** (the docs build is plain Java; the playground build
needs GraalVM + Binaryen and only matters for actually running the cells):

```bash
./mvnw -Pweb -DskipTests package                                  # (optional) refresh web/dist/rontoplayground.js(.wasm)
./mvnw -f docs-tool/pom.xml -DskipTests package                   # build the docgen jar
java -jar docs-tool/target/rontolisp-docgen.jar --source doc --out web/dist/docs
cd web/dist && jwebserver -p 8000                                 # open http://localhost:8000/docs/
```

In CI, `pages.yaml` builds the playground (`-Pweb`) first, then the docs into the
same `web/dist` (never deleting it), then deploys -- so the deployed playground
wasm and the docs come from the same commit (introspection examples like
`rontolisp:list-macros` therefore agree in the deployed site even if a local
`rontoplayground.js.wasm` is stale). The docgen and `DocExamplesTest` are also
exercised by `./mvnw test`; the `-Drontolisp.doc.fix=true` helper is manual-only.
