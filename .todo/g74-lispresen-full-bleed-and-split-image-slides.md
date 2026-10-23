# g74: lispresen cannot show an image across the whole slide

Difficulty: Medium

An `(image ...)` is always fitted into the body box inside the safe area
(`lispresen/DESIGN.md`, Layout selection: the `image` layout, `object-fit: contain`), so
a photograph is shown with the 112 / 80 / 72 px margins around it, letterboxed further
when its aspect differs. A talk's photo slides (the venue, a screenshot of a product, a
picture that opens a section) cannot fill the screen, and there is no way to put an
image beside the text running to the slide's edge either.

## Value

- For the user: a full-screen photograph is one of the most common slides in a talk
  that is not all text, and it is what lets a deck breathe between dense slides.
- Design: placement stays lispresen's; the author asks for a full-bleed image the way a
  statement is asked for, by the slide's shape or `:layout`, with no coordinates. Text
  over a photograph is left out, since the contrast lint cannot check a color against an
  image; the image either stands alone or shares the slide side by side with text on the
  theme background.
- Cost: a layout (no fit work for a lone image) and a split variant that takes a side of
  the canvas for the image and lays out the rest of the slide in the remaining width,
  the narrower body the fit solver already handles for columns.

## Shape (to settle in the design)

- `(slide :layout :full (image "photo.jpg" :alt "..."))`, or a slide with no title and
  only an image chosen automatically as `full`: the image covers the canvas
  (`object-fit: cover`, or `contain` on the theme background with `:fit :contain`). The
  page number and progress bar stay on top, or the page number is dropped there; decide
  and document.
- `(slide :title "t" :layout :image-left (image ...) block...)` / `:image-right`: the
  image takes a fixed share of the canvas edge to edge (half, or a `:share` from a short
  list such as 1/3, 1/2, 2/3), the title and body are fitted in the rest.
- `:caption` on a full-bleed image: either refused or set in the bottom band; decide.
- Print, overview and presenter thumbnails show it like any slide; `--embed` and the
  link preview image are unaffected.

## Verification

- Unit test in `lispresen/test/lispresen-test.lisp` (extend `fit-solver`): the body width
  of a split slide and the layout chosen for a lone untitled image.
- `lispresen/check.sh`: byte-identical output on every backend.
- Chrome and Safari: full-bleed and split slides on 16:9 and 4:3 with a portrait and a
  landscape image; the runtime check makes no correction on the split slide's text.
- README.md and DESIGN.md (Layout selection) updated.
