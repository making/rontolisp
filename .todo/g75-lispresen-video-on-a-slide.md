# g75: lispresen cannot play a video on a slide

Difficulty: Medium

A deck has `image` (`png`, `jpg`, `gif`, `webp`, `avif`, `svg`) but no moving picture
other than an animated GIF. A demo recorded as `mp4` or `webm`, the usual way to show a
tool working without risking a live demo, cannot be put on a slide; the speaker leaves
the deck to play it.

## Value

- For the user: a recorded demo is the fallback every live-demo talk needs, and GIF is
  a poor substitute (no sound, no pause, files ten times larger).
- Design: a video is an image to the layout: lispresen computes the box and the browser
  fits the video inside it (`object-fit: contain`), so the fit solver treats it as
  `image` and needs no intrinsic size. It is content, not geometry.
- Cost: a block form sharing the image's parsing and layout, the `<video>` element, the
  player's handling of keys and taps over it, and `--embed` of video MIME types.

## Shape

- `(video "demo.mp4" :alt "..." [:caption "t"] [:poster "demo.png"] [:loop t] [:muted t]
  [:autoplay t])`: a block like `image`; `:alt` required as for images (warning when
  missing). `autoplay` means "play when the slide is entered" (the player calls
  `play()` and pauses on leaving), never on page load.
- Controls: a click or tap on the video toggles play, so it does not turn the page
  (touch rule in DESIGN.md, Touch: "something that does something itself"); space and
  arrows keep moving slides.
- `--embed` takes `mp4` / `webm` as `data:` URIs; warn above a size (decide, e.g.
  20 MB), since the HTML must still open.
- Print, overview and presenter thumbnails show the poster (or the first frame).
- A callout on a video points at it as at an image.

## Verification

- Unit test in `lispresen/test/lispresen-test.lisp` (extend `embedding` and the parsing
  test): the element and the MIME type.
- `lispresen/check.sh`: byte-identical output on every backend.
- Chrome and Safari: a slide with a video plays on entry with `:autoplay`, pauses on
  leaving, a click plays and pauses without moving the slide, the presenter window shows
  the poster.
- README.md and DESIGN.md (Deck format, HTML output, Touch).
