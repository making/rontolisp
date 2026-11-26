# appkit Package Functions

The `appkit` package is a Cocoa widget layer written in rontolisp over `objc`,
loaded on first use like `linalg`; it needs a macOS display, and runs on the
interpreter and in a program compiled to a `.class` or a `.jar` (never a
`.wasm`). It is **not part of Common Lisp**; reference its functions
with the `appkit:` qualifier. See the [macOS GUI guide](../../guides/objc-appkit.md).

| Function | Example | Result |
|----------|---------|--------|
| `appkit:window` | `(appkit:window "title" :width 480 :height 300)` | a shown, centered `NSWindow` |
| `appkit:label` | `(appkit:label win "text" :x 20 :y 120 :width 380)` | an `NSTextField` label, its string centred in the rectangle |
| `appkit:button` | `(appkit:button win "Click" :x 20 :y 40 :on-click (lambda () ...))` | an `NSButton` whose action runs the closure |
| `appkit:panel` | `(appkit:panel win :x 20 :y 20 :width 34 :height 34 :fill c :radius 7)` | a filled, rounded `NSBox` in the window |
| `appkit:color` | `(appkit:color 90 200 250)` | an `NSColor` from 0-255 components |
| `appkit:font` | `(appkit:font 19 :bold t)` | the system `NSFont` at that size |
| `appkit:set-text` | `(appkit:set-text label "new text")` | the text, now shown |
| `appkit:set-color` | `(appkit:set-color tile (appkit:color 214 69 65))` | the colour: a panel's fill, any other control's text colour |
| `appkit:text` | `(appkit:text label)` | the control's text as a string |
| `appkit:on-click` | `(appkit:on-click tile (lambda (button) ...))` | the view; the closure runs on a click (1 left, 3 right) |
| `appkit:click` | `(appkit:click button)` | `nil`; the action ran as a click would |
| `appkit:timer` | `(appkit:timer 0.12 (lambda () ...))` | a repeating `NSTimer`; a `nil` answer stops it |
| `appkit:menu` | `(appkit:menu (list (list "Quit" #'appkit:quit "q")))` | an `NSMenu` whose items are Lisp closures; `:separator` is a dividing line |
| `appkit:status-item` | `(appkit:status-item "λ" :menu m :dock nil)` | an `NSStatusItem` in the system menu bar; `:dock nil` hides the Dock icon |
| `appkit:quit` | `(appkit:quit)` | never answers: the application ends, as Cmd-Q ends it |
| `appkit:close` | `(appkit:close win)` | `nil`; the window is closed (hidden) |
| `appkit:visible-p` | `(appkit:visible-p win)` | whether the window is on screen |
| `appkit:wait` | `(appkit:wait win)` | `nil`, once the window has been closed; with no window, until the application ends |

