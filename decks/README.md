# decks

Slide decks about rontolisp, written with [lispresen](../lispresen). Each deck is a
lispresen source and the HTML it renders to; the HTML is committed, so a deck can be
opened without building anything.

## Layout

One directory per deck, named after it:

```
decks/
  build.sh
  <name>/
    <name>.lisp     the source
    <name>.html     generated: the whole deck, images embedded
    <name>.png      generated: the link-preview image of the title slide
    fonts/          the TrueType fonts the preview image is drawn in, if any
```

A deck may keep other files it uses (images, code it shows) beside its source.

## Building

From the repository root, with the executable jar built (`./mvnw package`):

```bash
decks/build.sh                     # every deck
decks/build.sh rontolisp-intro     # the named decks only
```

Each deck is rendered with `--embed --strict`: images go into the HTML, and a warning
fails the build as an error does. `RONTOLISP_JAR` overrides the jar's path. Commit the
regenerated `.html` and `.png` with the source.

A deck's `:page-image-font` must cover every character its title slide draws (title,
author, date, URL): a missing glyph is a warning. lispresen's
[Link preview](../lispresen/README.md#link-preview) section shows how to cut a font
down to those characters with `pyftsubset`; keep the font's license file beside it.

## Publishing

`.github/workflows/pages.yaml` copies every `decks/<name>/*.html` and `*.png` to
`https://making.github.io/rontolisp/decks/<name>/` on each push to `develop` that changes
`decks/`. A deck's `:page-url` is therefore
`https://making.github.io/rontolisp/decks/<name>/<name>.html`, and its runnable code
loads the runtime from the same site.
