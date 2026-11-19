# k49. doc/java: the JDK classes on a page of their own, and the two "library" sections told apart

Difficulty: Medium

Asked by the user on 2026-11-20.

1. **The table is too long.** The table in the "ライブラリ" section of `doc/ja/java/index.md`
   (line 452 on, about 290 lines, up to "スレッド") has grown too large. Move it to a page
   of its own. `doc/en/java/index.md`, "The library", has the same table.
2. **The headings are confusing.**
   - "The library" / "ライブラリ" means the JDK classes the subset provides.
   - "Using a library" / "ライブラリを使う" means a third-party library read from the
     source path.
   - The same word names two different things, so give the sections names that tell
     them apart.

## To do

- Add a new page in both languages, for example `doc/en/java/classes.md` and
  `doc/ja/java/classes.md`.
  - Its title says what it is: the JDK classes and members the subset has. For example,
    "The JDK classes" and "使える JDK のクラス".
  - It holds the table and the paragraphs that explain it.
  - Keep a short section in `index.md` in its place: what the subset provides, and a
    link to the page.
- Rename "Using a library" / "ライブラリを使う" so the reader sees it is about a
  third-party library and the source path.
  - For example, "Using a third-party library" and "外部のライブラリを使う".
  - Keep its anchor `#using-a-library` if other pages and docs link to it. If the anchor
    changes, update every link to it.
- Find every link to the moved section and the renamed one, and update each:
  - `doc/**`, including `deviations.md` and `guides/`
  - `examples/**` READMEs
  - `.kb/**`
  - `docs-tool/` templates (`SKILL.template.md`)
  - source comments
  - test resources
- Add the new page to the site's navigation or index, if the site has one. See
  `.kb/documentation-site.md` for how pages are listed and built.
- Make en and ja mirror each other in the same commit (`.kb/documentation-site.md`).
- Do not change the wording of the table itself beyond what the move needs.
- Run the tests that check docs: `DocExamplesTest`, `PathCitationTest`,
  `CatalogIndexPageTest`, and any docs-site or link check the kb names.
- Choose the final names with this goal: a reader who sees both headings knows which
  one is the JDK's classes and which is someone else's library.
