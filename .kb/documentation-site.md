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

A category may override the file's top-level `index_page:` with its own
(`Catalog.Category.indexPage`, falling back to the file's default when absent).
This is how `reference/functions/_catalog.yaml` -- ONE catalog, one directory of
808 detail pages, no file moved -- backs 14 per-package table pages
(`reference/functions/cl.md`, `reference/functions/rontolisp.md`, ...,
2026-08-30) instead of one page listing every package: each category routes to
its own package's page, so a detail page's "back" link and its name's
auto-linked table row both go to the right package, and the previous/next chain
still runs across every entry in the whole catalog regardless of category. A
category whose real content lives on a page outside the catalog's own directory
can point there too -- the `uiop Package` category's `index_page` is
`reference/uiop.md` itself (the prose page, not a `reference/functions/uiop.md`
stub -- that stub was deleted 2026-08-30 once the category pointed straight at
the real page), so `uiop:file-exists-p` and friends became auto-linked for free
on a page that already documented them, and `DocGen.generateLanguage` validates
every `index_page` resolves to a real `nav.yaml` page or subpage before
rendering anything, rather than letting a typo silently fall back to a bare
"Back" link.

**A nav entry may own `subpages:`** -- a nested `{file, title}` list, rendered
like the catalog detail pages (previous/next chained among themselves, a back
link to the immediate parent) but absent from the sidebar itself; every
descendant highlights its TOP-level ancestor's sidebar row regardless of
nesting depth (`DocGen.renderSubpages`'s `topDocPath` parameter, threaded
through the recursion unchanged, versus `parent`/`backlink`, recomputed at each
level for the "back" arrow and the previous/next start). Use `subpages:` when a
page's sub-pages are a breakdown of one topic rather than sibling topics: the
sidebar carries one row per topic, and `reference/uiop/*.md` (four sub-package
pages, 2026-08-16) is reachable only through `reference/uiop.md` -- which is
itself, since 2026-08-30, not a sidebar row of its own but a subpage of
`reference/functions.md` alongside the 14 per-package pages, nested two levels
deep (`reference/uiop/os.md`'s back link goes to `reference/uiop.md`, but its
sidebar highlight lands on "Functions", not on "The uiop Package", because that
row no longer exists). A subpage is not itself a catalog entry -- a catalog
entry is an OPERATOR, and `SkillGen` turns every catalog entry into a row of
the skill bundle's `operators.md`, where a sub-package page does not belong --
but a subpage CAN be a catalog's `index_page` (the function package pages
above are subpages of `reference/functions.md`), in which case
`DocGen` applies the same table auto-linking to it that a top-level index page
gets.

**Code-fence conventions** (parsed by `DocExamplesTest` and the docgen
`RunnableBlockTransformer`):
- ` ```lisp ` = a runnable, self-contained example (becomes a "Run" cell). It is
  executed by `DocExamplesTest` and must not throw. Annotate a form with
  `; => value` (prin1 form) to show + assert its result; or follow the block with
  a plain ` ``` ` output block to assert stdout (use this for printing examples).
  **Every** `; =>` is asserted, on every page of every language tree, not only
  the block's last one: the test splits a block into its top-level forms and
  checks each form's own annotation -- written after the form on its line, or on
  the comment line just below it (how a long result is written). An annotation
  therefore belongs to a TOP-LEVEL form; one written inside a form (on an inner
  `print`, say) is checked against the whole form's value and will not match.
  A result that is not reproducible -- a build timestamp, a live server's
  response headers, an unseeded `random` -- must not be an arrow at all: reshape
  the example so its value is stable (`(getf res :status)`, a seeded generator),
  leave the line an ordinary comment, or move the block to ` ```console `.
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
(slugs and operator `name:`s stay identical). `DocExamplesTest` executes EVERY
language tree (`DOC_ROOTS`), so a broken or stale `doc/ja` block fails the build
on its own -- it was `doc/en`-only until 2026-08-16, and the ja tree had by then
accumulated ~60 pages of hand-written results the interpreter never answered
(lowercased symbols, listings several releases old).

**Heading ANCHORS are the reference language's, in every tree (2026-08-12).** A
translated heading keeps its translated TEXT and takes en's `id`: `DocGen`
records the heading ids of the first-rendered (default) language per doc-relative
path and, for every other language, replaces that page's generated ids
positionally (`alignHeadingIds`). Before this, flexmark slugged each tree's ids
from its own heading text, so `#no-wasi-reactor-mode` existed only in `en/` --
94 of the site's anchor links were dead, all of them in `ja/`, whichever spelling
the link used: an en-slug link found no such id, and a ja-slug link was the same
anchor written twice, which the "mirror the link verbatim" rule above forbids.
So an `[x](page.md#anchor)` is written ONCE, with the en slug, and copied into
every tree. The positional match makes "same heading layout" load-bearing rather
than advisory: a tree whose page has a different heading COUNT fails the build
(`IOException` naming the file), and `DocGenTest`'s
`everyAnchorLinkResolvesInEveryLanguage` walks the generated site and fails on
any `#fragment` with no matching `id` -- the anchor half of the page-link check
`SkillGenTest` already did.

**Search is two JSON files docgen writes per language tree** (2026-08-26). The site is
static, so the search runs in the browser: `SearchIndex` emits
`<lang>/search-index.json` (page paths, titles, operator signatures, every H1-H3
heading -- ~28 KB gzipped) and `<lang>/search-body.json` (the section bodies, ~450 KB
gzipped). `docs.js` prefetches the first on idle, so `Ctrl+K` / `/` answers the dominant
"I know the operator name, take me there" query with no round trip, and fetches the
second on the first keystroke. Matching is plain **substring** -- deliberately, not a
weakness: it needs no segmenter, which is what the Japanese tree wants, and in English a
substring covers most of what stemming would (`compil` finds `compile` and `compiling`).
A bigram inverted index was rejected because at this corpus size its postings are larger
than the text they index; Pagefind is the fallback if the payload ever becomes a problem,
and Algolia was rejected for ending the site's self-containment (it cannot work under
`jwebserver` or offline). Rank, cheapest signal first: operator name > page title >
signature > heading > body; then earlier offset.

- Each tree gets its own pair and **a search never crosses languages** -- `<body>` carries
  `data-search-base`, the relative path to this page's language root, and every result
  link is resolved against it.
- **Both files are keyed by POSITION** -- a hit is `pages[i]` and `pages[i].h[j]` -- so
  EVERY heading is indexed, the `<h1>` that repeats the page title included. Skipping it
  when it matched the title made the two trees' indexes diverge, because a `nav.yaml`
  title is translated and an anchor is not; `docs.js` folds an `<h1>` hit into the page's
  own result instead. `DocGenTest` pins the shape: every rendered page is in its
  language's index, every entry's `page.html#anchor` resolves in the generated site, and
  the two trees' indexes agree page for page and anchor for anchor.
- Because the anchors come from `alignHeadingIds`, a `ja` hit links to the en anchor,
  exactly like a `ja` cross-page link.
- The index is a docgen OUTPUT, not a doc page: `SkillGen` reads `doc/`, so nothing of
  this reaches the agent skill's bundle.
- `fetch` needs a real origin -- previewing the search over `file://` gets you the dialog
  and no results. Use the `jwebserver` recipe below.

**Adding/editing pages.** Edit the Markdown; add new top-level pages to
`doc/en/nav.yaml` (and `doc/ja/nav.yaml`). For a new function/macro/special form,
add a per-operator page + a `_catalog.yaml` entry under the matching directory in
each language tree (see CLAUDE.md "Implementation Order" step 5). After editing
examples, normalize the shown results to the real interpreter values and catch
any non-runnable example:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixShownResults test   # rewrite every page's ; => / output blocks, both trees
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
same `web/dist` (never deleting it), then the agent skill (below), then deploys --
so the deployed playground wasm and the docs come from the same commit
(examples therefore agree in the deployed site even if a local
`rontoplayground.js.wasm` is stale).
`DocExamplesTest` is exercised by `./mvnw test`; `docs-tool` is NOT in the root
reactor, so ITS tests run only in `pages.yaml` (which builds it WITHOUT
`-DskipTests` on purpose) or when you run `./mvnw -f docs-tool/pom.xml test`
yourself. The `-Drontolisp.doc.fix=true` helper is manual-only.

## The agent skill (`docs-tool` `skill` mode)

`java -jar docs-tool/target/rontolisp-docgen.jar skill --source doc --out
web/dist/skill` writes an agent skill to `https://making.github.io/rontolisp/skill/`:
`rontolisp/SKILL.md` + `rontolisp/references/**` (every `doc/en/**` page verbatim,
plus a generated `contents.md` and `operators.md`), a `.tar.gz` and a `.skill` zip
of that tree, `rontolisp-full.md` (all of it as one file), `VERSION` /
`version.json`, and an install page.

**Two install paths, both served from `/skill/`.** The plugin one is primary:
`marketplace.json` + `rontolisp-plugin.zip` (`.claude-plugin/plugin.json` +
`skills/rontolisp/**`), added with
`claude plugin marketplace add https://making.github.io/rontolisp/skill/marketplace.json`.
A marketplace added by URL downloads NOTHING but that JSON, so its plugin entry
cannot use a relative `./path` source -- it is an absolute `archive` URL, and the
archive URL is deliberately unversioned so a client holding a stale
`marketplace.json` still resolves (the version it installs is the one in the
archive's `plugin.json`). No `sha256` for the same reason: pinning it would
break every client whose copy of the marketplace predates the current archive.
This is also why nothing has to be committed to the repo -- there is no
`.claude-plugin/marketplace.json` in git, and no generated file in the tree. The
second path is the bare `.tar.gz` into a `skills/` directory, which nothing
updates for you. `claude plugin` rejects an archive URL that is not https or
that points at a loopback host, so the plugin install can only be exercised
against the deployed site, not a local server -- what a local run CAN check is
that `claude plugin marketplace add` accepts the generated JSON.

**The invariant: the skill is a VIEW of `doc/`, never a second copy of it.** The
only hand-written text in it is
`docs-tool/src/main/resources/skill/SKILL.template.md` -- frontmatter plus the
"how to walk this bundle" routing. Everything else arrives through
`{{include:PATH}}` (which demotes the page's headings one level and retargets its
links) or a generated table. If you find yourself explaining a language rule in
the template, that rule belongs on a doc page instead; inline it from there.

- The install page (`skill/index.html`) follows the same rule: it is
  `SkillGen.INSTALL_GUIDE` = `doc/<lang>/getting-started/agent-skill.md` rendered (with
  `Markdown.options`, the shared dialect) into the chrome of
  `skill/index.html`, so the install instructions someone follows exist once, as
  a manual page. Because that page is served from `/skill/` rather than from
  among the docs, its in-tree links are rewritten to absolute `docs/<lang>/*.html`
  URLs -- which is also why the page's links to the artifacts are written
  absolute in the Markdown: they have to work from both places.

- The bundle also mirrors `examples/` (`--examples`, default `examples`) into
  `references/examples/` with a generated `references/examples.md`. Inclusion is an
  ALLOWLIST of text extensions (`SkillGen.EXAMPLE_TEXT_EXTENSIONS`) plus a skip
  list of build directories, so a `.wasm` or a `.bin` of weights cannot get in --
  the bundle is text an agent reads, and the generator writes strings, so binary
  would be mangled rather than merely large. A README link to one of those
  excluded files is rewritten to `--repo-base` (the GitHub blob URL) instead of
  being left dangling. `examples/**` is therefore both a `pages.yaml` path trigger
  and part of the version's commit count.
- `SkillGen.CONTENTS_PAGE` is `contents.md`, not `index.md`: `doc/en/index.md`
  mirrors to `references/index.md`, and a case-insensitive filesystem would let a
  generated `INDEX.md` silently eat it.
- Links are rewritten with a code mask (`SkillGen.codeMask`), because these pages
  quote code containing `](...)`. A link that resolves OUT of the language tree
  (`../../playground.html`) has no bundled counterpart and becomes the absolute
  site URL it means.
- `SkillGenTest` is the anti-rot gate: it regenerates from the real `doc/` tree and
  fails when any bundled link no longer resolves, when the frontmatter or a
  `{{placeholder}}` is broken, when `SKILL.md` grows past 500 lines, or when a
  catalog entry is missing from `operators.md`. A doc rename that breaks the site
  therefore breaks this build too.
- The version is `<project major.minor>.<git rev-list --count HEAD -- doc
  docs-tool examples>` -- but that is only a CANDIDATE. `pages.yaml` curls the
  published `version.json` and passes `--previous-version` / `--previous-hash`;
  `SkillGen` digests the whole bundle minus the version itself
  (`contentDigest`, published as `contentHash`) and keeps the previous version
  when the digest matches. A commit count alone would bump on a `doc/ja`-only
  edit, a docs-tool test change or a rebuilt `.wasm` the bundle excludes, and
  every installed copy would re-download 2MB that is byte-identical. The
  candidate still has to be monotonic, which is why it stays a commit count.
  `pages.yaml`'s checkout needs `fetch-depth: 0` for it. Local runs default to
  `SkillGen.DEV_VERSION`. Both archives are written with fixed timestamps so an
  unchanged bundle stays byte-identical.
- Nothing is committed back to the repo -- the bundle is an artifact of the Pages
  deploy, so there is no generated file in git to drift.
