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
same `web/dist` (never deleting it), then the agent skill (below), then deploys --
so the deployed playground wasm and the docs come from the same commit
(introspection examples like `rontolisp:list-macros` therefore agree in the
deployed site even if a local `rontoplayground.js.wasm` is stale).
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

**The invariant: the skill is a VIEW of `doc/`, never a second copy of it.** The
only hand-written text in it is
`docs-tool/src/main/resources/skill/SKILL.template.md` -- frontmatter plus the
"how to walk this bundle" routing. Everything else arrives through
`{{include:PATH}}` (which demotes the page's headings one level and retargets its
links) or a generated table. If you find yourself explaining a language rule in
the template, that rule belongs on a doc page instead; inline it from there.

- The install page (`skill/index.html`) follows the same rule: it is
  `SkillGen.INSTALL_GUIDE` = `doc/<lang>/getting-started/agent-skill.md` rendered (with
  `DocGen.markdownOptions`, the shared dialect) into the chrome of
  `skill/index.html`, so the install instructions someone follows exist once, as
  a manual page. Because that page is served from `/skill/` rather than from
  among the docs, its in-tree links are rewritten to absolute `docs/<lang>/*.html`
  URLs -- which is also why the page's links to the artifacts are written
  absolute in the Markdown: they have to work from both places.

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
  docs-tool>`, computed in `pages.yaml` (whose checkout needs `fetch-depth: 0`):
  it bumps exactly when something that can change the skill changed. Local runs
  default to `SkillGen.DEV_VERSION`. Both archives are written with fixed
  timestamps so an unchanged bundle stays byte-identical.
- Nothing is committed back to the repo -- the bundle is an artifact of the Pages
  deploy, so there is no generated file in git to drift.
