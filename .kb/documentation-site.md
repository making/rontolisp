# Updating the Documentation Site

The manual is Markdown under `doc/<lang>/**`, rendered to a static site (`web/dist/docs`) by
the standalone generator in `docs-tool/` and published by `.github/workflows/pages.yaml`. The
site reuses the browser playground's WebAssembly runtime, so `lisp` examples run in-page.

## Layout
- `doc/<lang>/nav.yaml` = sidebar/order (Getting Started, Compiling, Language Reference,
  Guides). `doc/assets/docs.css` + `docs.js` = shared theme and runnable-cell wiring.
- `en` and `ja` exist; docgen renders one site per language (`/docs/en/`, `/docs/ja/`) with an
  automatic header language switcher (2+ languages -> switcher; `en` is default and gets the
  `/docs/` redirect). Adding another `doc/<lang>/` with a `nav.yaml` auto-creates its site.
- **The book measure belongs to the prose, not the article** (`docs.css`): `article.markdown`
  fills the content column and each flowing child is capped at `--content-max` (68ch); `table`
  is exempt. Capping the article instead hides the Result column of `Function | Example |
  Result` tables. Two rules keep those honest: code in a cell wraps (`white-space: normal`,
  with `a.fn-link` still `nowrap`), and `table:has(a.fn-link)` pins 22%/30% for name/example
  (some rows list nine names at once and auto layout otherwise hands them the table).

## Catalogs (per-operator reference pages)
Catalog directories each hold a `_catalog.yaml` (categories -> ordered `{slug, name}`) and a
table "index page": `reference/functions/` (index `reference/functions.md`),
`reference/macros/`, `reference/special-forms/`. docgen discovers every `_catalog.yaml`,
renders one HTML page per entry with prev/next, and auto-links each operator name in the index
table.

A category may override the file's top-level `index_page:` with its own
(`Catalog.Category.indexPage`). That is how ONE `reference/functions/_catalog.yaml` (one
directory of 808 detail pages) backs 14 per-package table pages (`reference/functions/cl.md`,
`.../rontolisp.md`, ...): each category routes to its own package page, so a detail page's
"back" link and its auto-linked row go to the right package, while previous/next still runs
across the whole catalog regardless of category. A category may point at a page outside the
catalog directory -- the `uiop Package` category's `index_page` is `reference/uiop.md` itself.
`DocGen.generateLanguage` validates every `index_page` resolves to a real `nav.yaml` page or
subpage before rendering.

## `subpages:`
A nav entry may own `subpages:` -- a nested `{file, title}` list rendered like catalog detail
pages (prev/next among themselves, back link to the immediate parent) but absent from the
sidebar; every descendant highlights its TOP-level ancestor's sidebar row regardless of depth
(`DocGen.renderSubpages`'s `topDocPath`, threaded unchanged through the recursion, versus
`parent`/`backlink`, recomputed per level). Use it when sub-pages are a breakdown of one topic
rather than sibling topics. A subpage is not itself a catalog entry (a catalog entry is an
OPERATOR, and `SkillGen` turns every entry into a row of `operators.md`), but a subpage CAN be
a catalog's `index_page`, in which case `DocGen` applies the same table auto-linking a
top-level index page gets.

## Code-fence conventions (parsed by `DocExamplesTest` and docgen's `RunnableBlockTransformer`)
- ` ```lisp ` = runnable, self-contained (becomes a "Run" cell). Executed by
  `DocExamplesTest`; must not throw. Annotate with `; => value` (prin1 form) to show + assert
  a result, or follow the block with a plain ` ``` ` output block to assert stdout.
- **Every** `; =>` is asserted, on every page of every language tree -- the test splits a block
  into top-level forms and checks each form's annotation (after the form on its line, or on the
  comment line just below). An annotation belongs to a TOP-LEVEL form; one written inside a
  form is checked against the whole form's value and will not match.
- A non-reproducible result (build timestamp, live response headers, unseeded `random`) must
  not be an arrow: reshape so the value is stable (`(getf res :status)`, a seeded generator),
  leave an ordinary comment, or move the block to ` ```console `.
- **A live built-in package is not stable either.** A `; =>` counting or listing what a shipped
  package holds (`(do-symbols (s :rontolisp) ...)`, the `cl` externals) goes red when an
  unrelated change adds a symbol. Build the example's OWN packages instead: `defpackage` two,
  export three names, walk those. Note: `do-symbols`' "sorted order" is a sort on each symbol's
  OWNER-QUALIFIED spelling (`PackageResolver.accessibleSymbols`), so an inherited name sorts
  under the package it came from and can precede an alphabetically earlier local name.
  `intern` cannot help build one: there is no intern table, so an interned name never joins the
  accessible set (`.kb/symbol-runtime-api.md`), and `defpackage`'s `:intern` clause is not
  supported -- for a user package, accessible == exports + inherited.
- ` ```console ` = static transcript, or an example needing stdin/files/network or that signals
  (`read`, `open`, `load`, `with-open-file`, `error`, `rontolisp:fetch`). Not executed.
- ` ```bash ` = shell commands. Plain ` ``` ` = expected output.
- Do NOT use dotted-pair literals (`'(a . 1)`) in `lisp` blocks -- the reader rejects them;
  build with `(cons ...)`/`(list ...)`.

## Keep all languages in sync
Every doc change is mirrored across BOTH `doc/en/**` and `doc/ja/**` (and any future
`doc/<lang>/`) in the SAME commit: adding/removing/renaming a page, a `nav.yaml` entry, or a
`_catalog.yaml` entry happens in every tree, and prose is translated. The trees stay
structurally identical -- same file set, same heading layout, **byte-identical fenced code
blocks** (incl. `; =>` annotations and output blocks). Only prose, headings, link text,
`nav.yaml` `title:`/`lang_name:`, and `_catalog.yaml` category `title:`s are translated (slugs
and operator `name:`s stay identical). `DocExamplesTest` executes EVERY tree (`DOC_ROOTS`), so
a broken or stale `doc/ja` block fails the build on its own.

**Heading ANCHORS are the reference language's, in every tree.** A translated heading keeps its
translated TEXT and takes en's `id`: `DocGen` records the heading ids of the first-rendered
(default) language per doc-relative path and replaces every other language's generated ids
POSITIONALLY (`alignHeadingIds`). So an `[x](page.md#anchor)` is written ONCE with the en slug
and copied verbatim into every tree. The positional match makes "same heading layout"
load-bearing: a tree whose page has a different heading COUNT fails the build (`IOException`
naming the file), and `DocGenTest.everyAnchorLinkResolvesInEveryLanguage` walks the generated
site and fails on any `#fragment` with no matching `id`.

## Search: two JSON files per language tree
Static site, so search runs in the browser. `SearchIndex` emits `<lang>/search-index.json`
(page paths, titles, operator signatures, every H1-H3 heading -- ~28 KB gzipped) and
`<lang>/search-body.json` (section bodies, ~450 KB gzipped). `docs.js` prefetches the first on
idle so `Ctrl+K` / `/` answers "I know the operator name" with no round trip, and fetches the
second on the first keystroke. Matching is plain **substring** -- deliberate: no segmenter
needed (what the Japanese tree wants), and in English it covers most of stemming (`compil`
finds `compile`/`compiling`). Pagefind is the fallback if the payload becomes a problem;
Algolia is rejected (it ends self-containment under `jwebserver`/offline). Rank, cheapest
first: operator name > page title > signature > heading > body; then earlier offset.
- Each tree gets its own pair and **a search never crosses languages** -- `<body>` carries
  `data-search-base`, the relative path to this page's language root; every result link
  resolves against it.
- **Both files are keyed by POSITION** (`pages[i]`, `pages[i].h[j]`), so EVERY heading is
  indexed, including the `<h1>` repeating the page title -- skipping it when it matched the
  title made the trees' indexes diverge (a `nav.yaml` title is translated, an anchor is not);
  `docs.js` folds an `<h1>` hit into the page's own result. `DocGenTest` pins the shape: every
  rendered page is in its language's index, every `page.html#anchor` resolves, and the two
  trees agree page for page and anchor for anchor.
- Anchors come from `alignHeadingIds`, so a `ja` hit links to the en anchor.
- The index is a docgen OUTPUT, not a doc page: `SkillGen` reads `doc/`, so none of it reaches
  the skill bundle.
- `fetch` needs a real origin -- previewing search over `file://` gives the dialog and no
  results; use `jwebserver`.

## Adding/editing pages
Edit the Markdown; add new top-level pages to every `nav.yaml`. For a new operator, add a
per-operator page + `_catalog.yaml` entry under the matching directory in EACH language tree.
Then:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixShownResults test   # rewrite ; => / output blocks, both trees
./mvnw -Dtest=DocExamplesTest test                                            # verify
```

## Build and preview

```bash
./mvnw -Pweb -DskipTests package                                  # (optional) refresh web/dist/rontoplayground.js(.wasm)
./mvnw -f docs-tool/pom.xml -DskipTests package                   # build the docgen jar
java -jar docs-tool/target/rontolisp-docgen.jar --source doc --out web/dist/docs
cd web/dist && jwebserver -p 8000                                 # http://localhost:8000/docs/
```

The docs build is plain Java; the playground build needs GraalVM + Binaryen and only matters
for actually running cells. In CI, `pages.yaml` builds the playground (`-Pweb`) first, then the
docs into the same `web/dist` (never deleting it), then the skill, then deploys -- so the
deployed wasm and docs come from one commit. `DocExamplesTest` runs under `./mvnw test`;
`docs-tool` is NOT in the root reactor, so its own tests run only in `pages.yaml` (which builds
it WITHOUT `-DskipTests` on purpose) or via `./mvnw -f docs-tool/pom.xml test`.
`-Drontolisp.doc.fix=true` is manual-only.

## The agent skill (`docs-tool` `skill` mode)
`java -jar docs-tool/target/rontolisp-docgen.jar skill --source doc --out web/dist/skill`
writes a skill to `https://making.github.io/rontolisp/skill/`: `rontolisp/SKILL.md` +
`rontolisp/references/**` (every `doc/en/**` page verbatim, plus generated `contents.md` and
`operators.md`), a `.tar.gz` and a `.skill` zip, `rontolisp-full.md`, `VERSION` /
`version.json`, and an install page.

**Invariant: the skill is a VIEW of `doc/`, never a second copy.** The only hand-written text
is `docs-tool/src/main/resources/skill/SKILL.template.md` (frontmatter + "how to walk this
bundle" routing); everything else arrives through `{{include:PATH}}` (which demotes headings one
level and retargets links) or a generated table. A language rule explained in the template
belongs on a doc page instead.

**Two install paths, both served from `/skill/`.** Primary: `marketplace.json` +
`rontolisp-plugin.zip` (`.claude-plugin/plugin.json` + `skills/rontolisp/**`), added with
`claude plugin marketplace add https://making.github.io/rontolisp/skill/marketplace.json`. A
marketplace added by URL downloads NOTHING but that JSON, so its plugin entry cannot use a
relative `./path` source -- it is an absolute `archive` URL, deliberately unversioned so a
stale `marketplace.json` still resolves (the installed version is the one in the archive's
`plugin.json`). No `sha256`, for the same reason. Nothing is committed to the repo (no
`.claude-plugin/marketplace.json` in git, no generated file in the tree). Secondary: the bare
`.tar.gz` into a `skills/` directory, which nothing updates for you. `claude plugin` rejects an
archive URL that is not https or points at a loopback host, so the plugin install can only be
exercised against the deployed site; locally you can only check that
`claude plugin marketplace add` accepts the generated JSON.

- The install page (`skill/index.html`) is `SkillGen.INSTALL_GUIDE` =
  `doc/<lang>/getting-started/agent-skill.md` rendered (with `Markdown.options`) into the
  `skill/index.html` chrome, so the instructions exist once as a manual page. Served from
  `/skill/`, so its in-tree links are rewritten to absolute `docs/<lang>/*.html` URLs -- which
  is why its links to the artifacts are written absolute in the Markdown (they must work from
  both places).
- The bundle mirrors `examples/` (`--examples`, default `examples`) into `references/examples/`
  with a generated `references/examples.md`. Inclusion is an ALLOWLIST of text extensions
  (`SkillGen.EXAMPLE_TEXT_EXTENSIONS`) plus a skip list of build directories, so a `.wasm` or a
  weights `.bin` cannot get in (the generator writes strings; binary would be mangled). A README
  link to an excluded file is rewritten to `--repo-base` (the GitHub blob URL). `examples/**` is
  both a `pages.yaml` path trigger and part of the version's commit count.
- `SkillGen.CONTENTS_PAGE` is `contents.md`, not `index.md`: `doc/en/index.md` mirrors to
  `references/index.md`, and a case-insensitive filesystem would let a generated `INDEX.md` eat
  it.
- Links are rewritten with a code mask (`SkillGen.codeMask`) because pages quote code containing
  `](...)`. A link resolving OUT of the language tree (`../../playground.html`) becomes the
  absolute site URL.
- `SkillGenTest` is the anti-rot gate: regenerates from the real `doc/` tree and fails when a
  bundled link no longer resolves, the frontmatter or a `{{placeholder}}` is broken, `SKILL.md`
  grows past 500 lines, or a catalog entry is missing from `operators.md`.
- Version = `<project major.minor>.<git rev-list --count HEAD -- doc docs-tool examples>` --
  but only a CANDIDATE. `pages.yaml` curls the published `version.json` and passes
  `--previous-version` / `--previous-hash`; `SkillGen` digests the whole bundle minus the
  version (`contentDigest`, published as `contentHash`) and keeps the previous version when the
  digest matches, so a `doc/ja`-only edit or a rebuilt excluded `.wasm` does not make every
  installed copy re-download 2MB that is byte-identical. The candidate must stay monotonic,
  hence the commit count; `pages.yaml`'s checkout needs `fetch-depth: 0`. Local runs default to
  `SkillGen.DEV_VERSION`. Both archives use fixed timestamps so an unchanged bundle stays
  byte-identical.
- Nothing is committed back to the repo -- the bundle is an artifact of the Pages deploy.
