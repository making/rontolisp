# `scene:add` accepts a non-solid, and the draw callback pays for it

Difficulty: Medium

## The report

```lisp
(defvar *v* (scene:viewer :title "Demo" :width 900 :height 640))
(defvar *triad* (geom:triad :at #f(0 0 0)))
(scene:add *v* *triad*)
```

Clicking the window prints

```
objc: error in a callback: No applicable method: GEOM:USER-DATA on CONS
```

on `java -jar`, and CRASHES the native binary.

## What is wrong

Two separate defects, and both should be fixed.

1. **`scene:add` does not accept a list.** `geom:triad` answers a LIST of three
   `geom:arrow` solids by design (`.kb/geom.md`, "The arrow, and where the origin
   indicator lives"), so `(scene:add *v* (geom:triad))` is the spelling a caller
   reaches for first, and every doc page has to spell `dolist` around it instead.
   `scene:add` is `(v &rest solids)` and conses whatever it is handed into
   `scene::%contents`. Making it splice a list argument is the essential fix: the
   two constructors that answer one solid and the one that answers three then
   compose the same way. Decide, and record, whether `scene:drop` / `scene:clear`
   need the matching shape.

2. **Whatever gets in, `scene:add` must refuse a non-solid THERE**, with a message
   naming the argument -- not defer it to a draw callback, where the report is a
   `geom:user-data` method-dispatch failure that names nothing the caller wrote.

3. **The native binary must not crash.** `ObjcBridge` / `JvmObjcTemplate` already
   install `ObjcClasses.onError` to PRINT and continue precisely so an error never
   unwinds into native frames (`.kb/objc.md`). On `java -jar` that guard clearly
   works -- the message above is its output. Find out why the native image dies
   anyway: whether the error escapes before the guard, whether the guard's own path
   throws under native-image, or whether the crash is downstream of continuing with
   a half-built frame. **The finding is the deliverable** -- if the guard cannot
   hold for a whole class of errors under native-image, say so in `.kb/objc.md`
   with what was measured.

## Closing conditions

- A failing test first, per the repo's bug-fix rule. The draw path has an
  offscreen harness (`SceneOffscreenRenderTest`), so this is testable without a
  window; the `scene:add` half is a plain `GeomLibraryTest`/`SceneLibraryTest`-shape
  case.
- The reported three lines work verbatim, hand-checked on `java -jar` AND the
  native binary, plus the two compiled carriers the GUI rule names
  (`-o X.class`, `-o x.jar`) -- CLAUDE.md, "After Task Completion".
- Docs mirrored en/ja if the spelling changes (`geom:triad`, `scene:add`,
  `doc/*/guides/solid-modeling.md`, `examples/macos/scene-solids.lisp`, and the
  browser twin's README if its text says `dolist`).
- `.kb/geom.md` records what `scene:add` accepts, and `.kb/objc.md` whatever the
  native crash turns out to be.
