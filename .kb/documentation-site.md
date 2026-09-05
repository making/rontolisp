# Updating the Documentation Site

The manual is Markdown under `doc/<lang>/**`, rendered to `web/dist/docs` by the standalone
`docs-tool/` generator and published by `.github/workflows/pages.yaml`. It reuses the browser
playground's WebAssembly runtime, so `lisp` examples run in-page.

## Layout
- `doc/<lang>/nav.yaml` = sidebar/order; `doc/assets/docs.css` + `docs.js` = theme and
  runnable-cell wiring. Adding a `doc/<lang>/` with a `nav.yaml` auto-creates its site (`en` is
  default and gets the `/docs/` redirect).
- **The book measure belongs to the prose, not the article** (`docs.css`): `article.markdown`
  fills the column and each flowing child is capped at `--content-max` (68ch), `table` exempt —
  capping the article hides the Result column of `Function | Example | Result` tables. Code in
  a cell wraps (`a.fn-link` stays `nowrap`); `table:has(a.fn-link)` pins 22%/30%.

## Catalogs and subpages
- A catalog directory (`reference/functions/`, `reference/macros/`, `reference/special-forms/`)
  holds a `_catalog.yaml` (categories -> ordered `{slug, name}`) plus a table index page;
  docgen renders one page per entry with prev/next and auto-links each name in the index table.
- A category may override the top-level `index_page:` (`Catalog.Category.indexPage`) — how ONE
  `reference/functions/_catalog.yaml` backs 14 per-package table pages — and may point outside
  the catalog directory; `DocGen.generateLanguage` validates every `index_page` resolves.
- A nav entry may own `subpages:` — rendered like catalog detail pages but absent from the
  sidebar; every descendant highlights its TOP-level ancestor's row (`DocGen.renderSubpages`'s
  `topDocPath`, threaded unchanged, versus `parent`/`backlink`, recomputed per level). A
  subpage is not a catalog entry but CAN be a catalog's `index_page`.

## Code-fence conventions (`DocExamplesTest`, docgen's `RunnableBlockTransformer`)
- ` ```lisp ` = runnable, self-contained "Run" cell; must not throw. Annotate `; => value`
  (prin1 form) to show + assert, or follow with a plain ` ``` ` block to assert stdout.
- **Every** `; =>` is asserted, on every page of every tree. An annotation belongs to a
  TOP-LEVEL form (after it on its line, or on the comment line just below); one written inside
  a form is checked against the whole form's value and will not match.
- A non-reproducible result (build timestamp, live headers, unseeded `random`) must not be an
  arrow — reshape it, comment it, or move to ` ```console `.
- **A live built-in package is not stable either**: a `; =>` listing what a shipped package
  holds goes red when an unrelated change adds a symbol — build the example's OWN packages.
  `do-symbols`' "sorted order" sorts on the OWNER-QUALIFIED spelling
  (`PackageResolver.accessibleSymbols`); `intern` cannot help (no intern table,
  `.kb/symbol-runtime-api.md`) and `defpackage`'s `:intern` is unsupported.
- ` ```console ` = static transcript or anything needing stdin/files/network or that signals
  (`read`, `open`, `load`, `with-open-file`, `error`, `rontolisp:fetch`); not executed.
  ` ```bash ` = shell. Plain ` ``` ` = expected output.
- Do NOT use dotted-pair literals (`'(a . 1)`) in `lisp` blocks — the reader rejects them.

## Keep all languages in sync
Every doc change is mirrored across BOTH trees in the SAME commit: same file set, same heading
layout, **byte-identical fenced code blocks**. Only prose, headings, link text, `nav.yaml`
`title:`/`lang_name:` and `_catalog.yaml` category `title:`s are translated (slugs and operator
`name:`s stay identical). `DocExamplesTest` executes EVERY tree (`DOC_ROOTS`).

**Heading ANCHORS are the reference language's, in every tree.** `DocGen` records the
first-rendered language's heading ids per doc-relative path and replaces every other language's
POSITIONALLY (`alignHeadingIds`), so `[x](page.md#anchor)` is written once with the en slug. A
tree whose page has a different heading COUNT fails the build (`IOException` naming the file);
`DocGenTest.everyAnchorLinkResolvesInEveryLanguage` walks the generated site.

## Search: two JSON files per language tree
`SearchIndex` emits `<lang>/search-index.json` (paths, titles, signatures, every H1-H3 heading)
and `<lang>/search-body.json` (section bodies); `docs.js` prefetches the first on idle for
`Ctrl+K` / `/` and fetches the second on the first keystroke. Matching is plain **substring** —
deliberate (no segmenter for Japanese). Rank, cheapest first: operator name > page title >
signature > heading > body, then earlier offset.
- A search never crosses languages: `<body>` carries `data-search-base`.
- **Both files are keyed by POSITION** (`pages[i]`, `pages[i].h[j]`), so EVERY heading is
  indexed including the `<h1>` repeating the title — skipping it made the trees' indexes
  diverge. Anchors come from `alignHeadingIds`. `DocGenTest` pins the shape. `fetch` needs a
  real origin — preview over `jwebserver`, not `file://`.

## Build and preview
```bash
./mvnw -f docs-tool/pom.xml -DskipTests package
java -jar docs-tool/target/rontolisp-docgen.jar --source doc --out web/dist/docs
cd web/dist && jwebserver -p 8000                                 # http://localhost:8000/docs/
```
`-Pweb -DskipTests package` refreshes the playground wasm (only needed to run cells).
`-Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixShownResults` rewrites `; =>` / output
blocks in both trees (manual-only). `docs-tool` is NOT in the root reactor, so its own tests
run only in `pages.yaml` (which builds it WITHOUT `-DskipTests` on purpose) or via
`./mvnw -f docs-tool/pom.xml test`. In CI `pages.yaml` builds playground, then docs into the
same `web/dist` (never deleting it), then the skill, then deploys.

## The agent skill (`docs-tool` `skill` mode)
`rontolisp-docgen.jar skill --source doc --out web/dist/skill` writes `rontolisp/SKILL.md` +
`rontolisp/references/**`, a `.tar.gz`, a `.skill` zip, `rontolisp-full.md`,
`VERSION`/`version.json` and an install page to `https://making.github.io/rontolisp/skill/`.

**Invariant: the skill is a VIEW of `doc/`, never a second copy.** The only hand-written text
is `docs-tool/src/main/resources/skill/SKILL.template.md`; everything else arrives through
`{{include:PATH}}` or a generated table. A language rule explained in the template belongs on a
doc page instead. Nothing is committed back to the repo.
- **Two install paths, both from `/skill/`.** Primary `marketplace.json` +
  `rontolisp-plugin.zip`: a marketplace added by URL downloads NOTHING but that JSON, so its
  plugin entry is an absolute `archive` URL, deliberately unversioned and without `sha256`.
  `claude plugin` rejects a non-https or loopback archive URL, so the install can only be
  exercised deployed. Secondary: the bare `.tar.gz`, which nothing updates for you.
- Install page = `SkillGen.INSTALL_GUIDE` (`doc/<lang>/getting-started/agent-skill.md`)
  rendered into the `skill/index.html` chrome; its artifact links are absolute in the Markdown
  because they must work from both places.
- `examples/` is mirrored (`--examples`) under an ALLOWLIST
  (`SkillGen.EXAMPLE_TEXT_EXTENSIONS`) plus a build-directory skip list, so a `.wasm` cannot
  get in; a link to an excluded file is rewritten to `--repo-base`.
- `SkillGen.CONTENTS_PAGE` is `contents.md`, not `index.md`: a case-insensitive filesystem
  would let a generated `INDEX.md` eat the mirrored `doc/en/index.md`. Links are rewritten
  with a code mask (`SkillGen.codeMask`) because pages quote code containing `](...)`.
- Version = `<project major.minor>.<git rev-list --count HEAD -- doc docs-tool examples>`, a
  CANDIDATE only: `SkillGen` keeps `--previous-version` when `contentDigest` (published as
  `contentHash`) matches. Must stay monotonic, hence the commit count and `fetch-depth: 0`.
  Local runs use `SkillGen.DEV_VERSION`; both archives use fixed timestamps.
- `SkillGenTest` is the anti-rot gate: fails when a bundled link no longer resolves, the
  frontmatter or a `{{placeholder}}` is broken, `SKILL.md` grows past 500 lines, or a catalog
  entry is missing from `operators.md`.
