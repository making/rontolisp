# 536. The documentation site has no search

Difficulty: Medium (it is one generator pass plus one `docs.js` module -- no
cross-backend semantics -- but the index format and the two-tier split are the
design decisions worth getting right the first time)

## The gap

`web/dist/docs` is 877 pages per language and the only way in is the sidebar
tree and the operator index tables. There is no way to ask "which page mentions
`with-open-file`" short of the browser's per-page find. The agent skill has
`rontolisp-full.md` for exactly that need; a human reader has nothing.

## What the site gives us to work with

Measured on 2026-08-26 (`doc/en`, `doc/ja`):

| | pages | markdown raw | gzip | H1-H3 |
| --- | --- | --- | --- | --- |
| en | 877 | 1.5 MB | 507 KB | 1384 |
| ja | 877 | 1.9 MB | 590 KB | 1384 |

Fenced code is 222 KB of each tree, so stripping code buys almost nothing and
loses the examples, which for the operator pages are the most searchable part.

Three constraints follow from the site as it stands:

- **Static hosting, no server** (GitHub Pages, `.github/workflows/pages.yaml`),
  so the search runs in the browser or it runs on someone else's machine.
- **The site has no external JS dependency today** -- `doc/assets/docs.js` is
  9 KB of hand-written code -- and it already establishes the lazy-load pattern
  this feature needs: the 16 MB playground runtime loads on the first Run click,
  never on page view.
- **Japanese is a first-class tree**, so word-boundary tokenization is the
  central problem, not an afterthought. Every `doc/<lang>/` gets its own index;
  a search never crosses language trees.

`DocGen` already computes heading ids and the TOC (`TocBuilder`, and
`alignHeadingIds` which makes en's ids the ids in every tree), so the index can
be built per SECTION and a hit can link to `page.html#anchor` rather than to the
top of an 877-page site's page.

## Options considered

1. **Own index + own JS, substring matching.** `DocGen` emits
   `docs/<lang>/search-index.json`; `docs.js` fetches it lazily and scans with
   `indexOf`. Zero dependencies, nothing added to CI, and one code path for both
   languages -- substring matching needs no segmenter, which is what CJK search
   wants anyway, and in English a substring covers most of what stemming would
   (`compil` matches both `compile` and `compiling`). A linear scan over 1.5 MB
   is single-digit milliseconds. A bigram inverted index was rejected: at this
   corpus size the postings are LARGER than the text they index.
2. **Pagefind.** Consumes the built HTML, shards the index, ships a UI. Good
   CJK handling and near-zero implementation cost, but it puts a Node or Rust
   binary into `pages.yaml` and its UI has to be re-skinned to the site theme.
   The fallback if option 1's payload turns out to be a problem in practice.
3. **lunr.js / MiniSearch / FlexSearch.** Needs a separate Japanese tokenizer
   (TinySegmenter) and loads the whole index at once, which lands in the
   megabytes here. Strictly worse than 2. Rejected.
4. **Algolia DocSearch.** Best quality and zero operations, but it is an
   external service: it does not work under `jwebserver` locally or offline, and
   it ends the site's self-containment. Rejected on that ground, not on quality.

## What to build

Option 1, in two tiers, so that page view stays free and the common case is
instant:

- **Tier 1 -- inline with the page, tens of KB gzipped.** Page titles, all H1-H3
  headings, and every catalog entry's operator name and signature. This is the
  `Ctrl+K` jump-to-page index. With 700+ per-operator reference pages, "I know
  the name, take me there" is the dominant query, and it must answer without a
  network round trip.
- **Tier 2 -- fetched on the first keystroke, ~500-600 KB gzipped.** Section
  bodies, keyed to their heading anchor. Picks up everything Tier 1 misses.

Ranking, cheapest signal first: exact operator name > title > heading > body;
then earlier offset, then hit count. A result renders as page title, section
heading, and a snippet around the first hit with the match marked.

The UI is a `docs.js` module and a topbar control: `Ctrl+K` and `/` to open,
arrow keys and Enter to move and select, Escape to close. Theme lives in
`doc/assets/docs.css` with the rest.

## Tests

`docs-tool` is outside the root reactor, so this is
`./mvnw -f docs-tool/pom.xml test`. `DocGenTest` gains the anti-rot gate: every
rendered page appears in its language's index, every index entry's
`page.html#anchor` resolves against the generated site (the same walk
`everyAnchorLinkResolvesInEveryLanguage` already does), and the two language
indexes have the same entry count -- the trees are structurally identical by
rule, so a divergence is a doc bug the docs build should catch.

The agent skill is unaffected: the index is a docgen output, not a doc page, and
must not reach `SkillGen`'s bundle.
