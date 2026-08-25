# 515. Promote the `cocoa` example library's widget rungs into `appkit`

Difficulty: Medium

`examples/macos/cocoa.lisp` (2026-08-25, written for
`examples/browser/minesweeper/minesweeper-macos.lisp`) is a reusable AppKit helper package
built entirely on the `objc:` verbs plus `appkit:`. Question raised when it landed: should
it ship inside the interpreter the way `appkit.lisp` and `linalg.lisp` do, rather than sit
in `examples/`?

**The case for shipping.** `appkit`'s whole promise is that a bare REPL -- and above all the
`rontolisp` NATIVE BINARY, which is what someone actually installs -- opens a window with
nothing required and nothing to copy. A binary user has no `examples/` directory, so today
they cannot get a COLOUR, a filled rounded rectangle, a vertically centred label, a view
that answers a click, or a repeating timer without writing the `objc:send` themselves.
Those are not application logic; they are the rungs immediately above the three widgets
`appkit.lisp` already has. Two of them cannot be reached by an obvious `objc:send` at all:
a centred label needs the font's line height measured and the frame sized to it (an
`NSTextField` draws its string at the top of whatever frame it is given), and a clickable
view needs `objc:define-class` over `NSBox` / `NSTextField` with `mouseDown:` and
`rightMouseDown:` as Lisp functions -- the one place a user has to define an Objective-C
class to get anywhere.

**The case against a `cocoa` PACKAGE.** A second built-in package for one toolkit takes the
name `cocoa` away from user code for good (there is no package shadowing and no
`delete-package`), and it splits the AppKit surface across two prefixes for no reason the
user can see. So the rungs should arrive as `appkit:` functions, not as a `cocoa` package:
`appkit:color`, `appkit:font`, `appkit:panel`, a centred `appkit:text`, `appkit:on-click`,
`appkit:timer`. That keeps one package and one story.

**Where the line goes.** Ship the WIDGET rungs; leave LAYOUT in the example. `cocoa:grid`
is a board-game policy (rows top-down, a box plus a label per cell, both wired to one
handler) and is ten lines over the rungs above -- `examples/jvm/swing.lisp` is the
precedent: `swing:label-grid-window` was written for this same game and stayed an example.
`minesweeper-macos.lisp` would then keep its own `grid` (or a slimmer `cocoa.lisp`) and
call `appkit:` for everything else.

**What shipping costs**, per rung:

- a `PackageRegistry` entry (`appkitFunctionNames`, which `AppKitLibraryTest` asserts the
  library defines EXACTLY);
- a per-operator page under `doc/{en,ja}/reference/functions/appkit-*.md` plus the guide's
  table in both languages (the existing `appkit-window.md` family is the shape);
- growth in what travels: the lazily loaded library, and the blob the JVM backend splices
  into every compiled program (`AppKitLibrary.process`);
- `IndentRules` for anything whose trailing arguments are a body.

What it does NOT cost any more: the native binary's closed table of `objc_msgSend` shapes
already serves every selector `cocoa.lisp` sends -- they were added to
`reachability-metadata.json` and to `ObjcNativeImageForeignConfigTest`'s table when the
example landed (`.kb/objc.md`).

**Recommendation:** do it, for the rungs named above, once the API has survived a second
program. One consumer is a suggestion; the second one tells you which arguments are
really the API. `examples/jvm/life-gui.lisp`'s AppKit counterpart is the obvious second
consumer -- write it against `cocoa.lisp` first, then promote what both used.
