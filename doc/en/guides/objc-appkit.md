# macOS GUI (objc / appkit)

Two built-in packages open a real Cocoa window from a rontolisp REPL with nothing
installed: `objc` binds the Objective-C runtime and AppKit through the JVM's
foreign function API (no JNI, no bundled native library, no reflection), and
`appkit` is a small widget layer written in rontolisp on top of it — a window, a
label, a button whose action is a Lisp closure, a coloured panel, a click, a
repeating timer and a menu bar item.

> **macOS only; interpreter and JVM class.** Both packages work under
> `java -jar rontolisp.jar`, in the `rontolisp` native binary — the binding needs no
> reflection, which is what `java:` interop lacks there — and in a program compiled
> to a `.class` or `.jar`, which carries the binding inside it. Neither WASM backend
> has a foreign function API, so compiling such a program to a `.wasm` is a
> `Cannot compile: appkit:window ...` error. On Linux, or on a JVM that denies native
> access (`--illegal-native-access=deny`), every `objc:` function signals an ordinary
> `error` whose message starts with the function's name and says why.

## A window from the REPL

```console
CL-USER> (defvar *win* (appkit:window "counter" :width 420 :height 200))
CL-USER> (defvar *label* (appkit:label *win* "no clicks yet" :x 20 :y 120 :width 380))
CL-USER> (defvar *n* 0)
CL-USER> (appkit:button *win* "Click me" :x 20 :y 40
    :on-click (lambda ()
                (setq *n* (+ *n* 1))
                (appkit:set-text *label* (format nil "clicked ~a time(s)" *n*))))
```

The window appears, centered and in front; clicking the button runs the closure,
which updates the label. The REPL stays yours the whole time — the window lives on
the process's first thread, not on the one reading your input — and closing the
window does not end the REPL. `examples/macos/counter.lisp` is the same program as a
script; it ends with `(appkit:wait *win*)`, which blocks until the window is closed,
because a script's process exits when its last form returns.

Anything larger is built the same way, in Lisp:
`examples/browser/minesweeper/minesweeper-macos.lisp` plays a full Minesweeper in a
Cocoa window and `examples/macos/life-macos.lisp` runs Conway's Life in one, both
out of the widgets below. What the two share above them is a board:
`examples/macos/cocoa.lisp`, a small `cocoa` package holding the grid of clickable
tiles they happen to want — board-game policy, which is why it stays an example.

`examples/macos/listener.lisp` puts the language itself in the window: a transcript in
an `NSTextView`, an editable `NSTextField` whose Return key is a Lisp closure, and
`eval` on what it reads — printed output captured, an error shown as a line instead of
ending the process. The window and the evaluator are the same image, so a form typed
into it can open the next window.

A program does not need a window at all. `appkit:status-item` puts a title in the system
menu bar and `appkit:menu` hangs a menu off it whose entries are Lisp closures; with
`:dock nil` the process has no Dock icon and no app switcher entry, which is what a menu
bar program looks like, and `appkit:quit` is then the way out. `appkit:wait` with no
argument blocks until that happens.

```console
CL-USER> (defvar *n* 0)
CL-USER> (defvar *item*
    (appkit:status-item "λ" :dock nil
                        :menu (appkit:menu
                               (list (list "Count" (lambda ()
                                                     (setq *n* (+ *n* 1))
                                                     (appkit:set-text *item*
                                                                      (format nil "λ ~a" *n*))))
                                     :separator
                                     (list "Quit" #'appkit:quit "q")))))
```

`examples/macos/menubar.lisp` is that program with a clock in it: an `appkit:timer`
rewrites the title once a second, and one of its menu entries opens a window — the same
proof `listener.lisp` gives, from the menu bar instead.

| Function | Purpose |
|----------|---------|
| `appkit:window` | `(appkit:window title &key (width 480) (height 300) background dark)` — a shown, centered `NSWindow` |
| `appkit:label` | `(appkit:label window text &key x y width height (size 13) color (align :left) bold)` — an `NSTextField` label, its string centred in the rectangle |
| `appkit:button` | `(appkit:button window title &key x y (width 120) (height 32) on-click)` — an `NSButton`; `on-click` is a zero-argument function |
| `appkit:panel` | `(appkit:panel window &key x y width height fill (radius 0) (border 0) border-color)` — a filled, rounded `NSBox` |
| `appkit:color` | `(appkit:color r g b &optional (alpha 1.0))` — an `NSColor` from 0-255 components |
| `appkit:font` | `(appkit:font size &key bold)` — the system font at that size |
| `appkit:set-text` | `(appkit:set-text view text)` — a button's title, any other control's string value |
| `appkit:set-color` | `(appkit:set-color view color)` — a panel's fill colour, any other control's text colour |
| `appkit:text` | `(appkit:text view)` — the title or string value, as a Lisp string |
| `appkit:on-click` | `(appkit:on-click view handler)` — the handler takes the button number: 1 left, 3 right |
| `appkit:click` | `(appkit:click button)` — performs the action as a click would |
| `appkit:timer` | `(appkit:timer seconds fn)` — a repeating `NSTimer`; `fn` answering `nil` stops it |
| `appkit:menu` | `(appkit:menu items)` — an `NSMenu`; an item is `(title handler)` plus an optional key equivalent, `:separator` a dividing line |
| `appkit:status-item` | `(appkit:status-item title &key menu (dock t))` — an `NSStatusItem` in the system menu bar; `:dock nil` is the accessory policy |
| `appkit:quit` | `(appkit:quit)` — ends the application, as Cmd-Q does |
| `appkit:close` | `(appkit:close window)` — closes (hides) the window; the value stays valid |
| `appkit:visible-p` | `(appkit:visible-p window)` — whether it is on screen |
| `appkit:wait` | `(appkit:wait &optional window)` — blocks the calling thread until the window is closed, or until the application ends |

Coordinates are AppKit's: the origin is the window's bottom-left corner. A label is
centred vertically in the rectangle it is given, which is what puts a digit in the
middle of a tile; a panel is the tile itself, and both answer a click:

```console
CL-USER> (defvar *board* (appkit:window "tiles" :width 200 :height 200
                                 :background (appkit:color 26 29 38) :dark t))
CL-USER> (defvar *tile* (appkit:panel *board* :x 20 :y 20 :width 34 :height 34
                               :fill (appkit:color 104 116 146) :radius 7))
CL-USER> (defvar *digit* (appkit:label *board* "3" :x 20 :y 20 :width 34 :height 34
                                :size 19 :align :center :bold t))
CL-USER> (appkit:on-click *tile*
    (lambda (button) (appkit:set-color *tile* (appkit:color 230 233 241))))
#<objc RontoLispAppKitPanel>
CL-USER> (appkit:timer 1 (lambda () (appkit:set-text *digit* "4") nil))
#<objc __NSCFTimer>
```

Every widget is a plain Objective-C object, so anything the layer lacks is one
`objc:send` away:

```console
CL-USER> (objc:send *win* "setBackgroundColor:"
    (objc:send "NSColor" "colorWithRed:green:blue:alpha:" 0.9 0.95 1.0 1.0))
CL-USER> (objc:send *win* "frame")
(690.0 676.0 420.0 228.0)
```

## The objc package

`objc` is the exact analogue of `java`: a package named after the foreign system,
with a handful of generic verbs.

| Function | Purpose |
|----------|---------|
| `objc:class` | `(objc:class "NSWindow")` — a class by name |
| `objc:send` | `(objc:send receiver "selector:with:" arg1 arg2)` — sends a message; the receiver is an object, a class, or a class name as a string |
| `objc:define-class` | `(objc:define-class "Name" "NSObject" methods &optional protocols)` — a class whose methods are Lisp functions |
| `objc:on-main` | `(objc:on-main (lambda () ...))` — runs the function on the main thread and answers its value |
| `objc:string` | `(objc:string "text")` — an `NSString` |
| `objc:data` | `(objc:data buffer)` — an `NSMutableData` holding a packed buffer's bytes |
| `objc:bytes` | `(objc:bytes data)` — an `NSData`'s bytes as a packed `(unsigned-byte 8)` vector |
| `objc:address` | `(objc:address object)` — the object's address, an integer |
| `objc:objectp` | `(objc:objectp x)` — whether `x` is an Objective-C object |

```console
CL-USER> (objc:send (objc:string "hello world") "length")
11
CL-USER> (objc:send (objc:send (objc:string "hello") "uppercaseString") "UTF8String")
"HELLO"
CL-USER> (objc:send (objc:string "hello world") "rangeOfString:" "world")
(6 5)
CL-USER> (objc:send "NSNumber" "numberWithDouble:" 2.5)
#<objc __NSCFNumber>
```

### The runtime is something you can ask

Everything Objective-C settles at the moment it happens is also readable at that moment:
whether a receiver answers to a name, which class it really is, what types a method
declares, what sits under a key.

```console
CL-USER> (objc:send (objc:string "hi") "respondsToSelector:" "uppercaseString")
T
CL-USER> (objc:send (objc:send (objc:send (objc:string "hi") "class") "description") "UTF8String")
"NSTaggedPointerString"
CL-USER> (objc:send (objc:send (objc:string "hi") "methodSignatureForSelector:" "hasPrefix:") "methodReturnType")
"B"
CL-USER> (objc:send (objc:send (objc:string "hello") "valueForKey:" "length") "doubleValue")
5.0
```

The second line catches a class cluster in the act — `objc:string` asked for an `NSString`
and got a private subclass chosen for the value. `examples/macos/objc-runtime.lisp` is this
whole side of the package in one runnable file: selectors carried around as strings and
guarded by `respondsToSelector:`, class hierarchies walked, a method's own type encoding
read back, key-value coding and a sort by a text key, a class defined at run time whose
`isEqual:` is a Lisp closure that `containsObject:` calls, and an `NSNotificationCenter`
observer. It opens no window.

### AppKit is not the boundary

Every framework on the machine speaks the Objective-C runtime, and one that is not linked
into this process is a single message away: `NSBundle` maps it and registers its classes,
so from the next form on the class name resolves.

```console
CL-USER> (objc:send (objc:send "NSBundle" "bundleWithPath:"
    (objc:string "/System/Library/Frameworks/NaturalLanguage.framework")) "load")
T
CL-USER> (objc:send (objc:send "NLLanguageRecognizer" "dominantLanguageForString:"
    (objc:string "これは日本語の文章です")) "UTF8String")
"ja"
```

That is the whole of dependency management here: no manifest, no classpath, no download.
`examples/macos/system-frameworks.lisp` is the surface it opens, in one runnable file —
Vision, NaturalLanguage, Core Image and the speech synthesizer, none of them wrapped for
Lisp by anybody first. Its centre is a round trip: a Lisp string is drawn into an image by
Core Image and read back out of it by Vision, and `equal` decides whether the machine read
what it was given. It opens no window either, and it is silent, because the speech is
synthesized to an AIFF file instead of the speakers.

### Typed by the selector's own encoding

`objc:send` never guesses a signature. The Objective-C runtime describes every
method completely (`method_getTypeEncoding` answers, for example,
`@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64` for
`initWithContentRect:styleMask:backing:defer:`), and each argument and the result
are marshalled by that declaration:

| Declared type | Lisp argument | Lisp result |
|---------------|---------------|-------------|
| object (`@`) | an object, `nil`, or a string (sent as an `NSString`) | an object, or `nil` |
| class (`#`) | an object or a class name | an object |
| selector (`:`) | the selector name as a string | the name |
| C string (`*`) | a string | a string |
| `BOOL` | `t` / `nil` | `t` / `nil` |
| integer kinds | an integer | an integer |
| `float` / `double` | a number | a float |
| struct (`{...}`) | a list of numbers, the struct's scalar fields in order (`(x y w h)` for an `NSRect`) | a list of numbers |
| any other pointer (`^`) | an object, an integer address, or `nil` | an integer address |

A selector the receiver does not respond to, a wrong argument count, or an argument
that does not fit its declared type is an `error`, never a crash. The answer of a
`performSelector...` message is discarded (its type is the target method's, which the
binding cannot see). Blocks, unions and bitfields are outside this first cut: a
selector that takes one is refused by name.

### Bytes, and the `:error` out-parameter

Two things a generic message send cannot express on its own are a block of MEMORY and an
out-parameter, and both are ordinary in Cocoa. `objc:data` covers the first: it answers an
`NSMutableData` holding a packed buffer's bytes — a packed float array of any rank, a
packed `(unsigned-byte 8|16|32)` vector, or a string's UTF-8 — laid out exactly as
`write-sequence` would write them, little-endian and row-major. `[data bytes]` is then the
address a `void *` parameter wants, `[data mutableBytes]` is writable scratch to hand a
callee, and `objc:bytes` reads the block back.

The second is the `...error:` convention: pass the keyword `:error` where the
`NSError **` goes, and the binding allocates the slot, passes it, and — when the call
reports failure and the slot was filled — signals with what the error says, instead of
answering the bare `nil` the selector returns.

```console
CL-USER> (objc:bytes (objc:data (make-array 2 :element-type 'single-float :initial-contents '(1.0 2.0))))
#(0 0 128 63 0 0 0 64)
CL-USER> (handler-case
      (objc:send "NSJSONSerialization" "JSONObjectWithData:options:error:" (objc:data "nope") 0 :error)
    (error (e) (princ-to-string e)))
"objc:send: JSONObjectWithData:options:error:: The data couldn’t be read because it isn’t in the correct format. [NSCocoaErrorDomain 3840]"
```

Together they are what puts the GPU in reach: Metal is an Objective-C API almost
end to end, so `objc:send` drives it with nothing added — `examples/macos/metal-triangle.lisp`
draws the WebGL hello world, `examples/macos/metal-cube.lisp` a spinning, shaded cube, and
`examples/macos/metal-robot-arm.lisp` a robot arm that solves its own inverse kinematics and
reaches for wherever you click, with shaders compiled from Lisp strings at run time. (OpenGL
is the opposite and stays out of reach: `glClear` and friends are plain C functions, which
`objc_msgSend` does not reach.)

`objc:define-class` is what carries the mouse there: the drawing surface is an `NSView`
subclass defined at run time whose `mouseDown:` / `mouseDragged:` / `scrollWheel:` are Lisp
closures, the same verb the widget layer uses to make an `NSBox` answer a click.

### Threads: everything happens on the main thread

AppKit belongs to the process's first thread, and every `objc:send` hops there by
itself — synchronously, so its value comes back to the caller. A widget built from
several sends pays the hop once when wrapped in `objc:on-main`, which is what the
`appkit` functions do. A function already running on the main thread (a button's
handler) runs its sends inline, so a callback may call back into the GUI freely.

The first `appkit:` call also hands thread 0 to AppKit's own event loop
(`-[NSApplication run]`, started there without blocking anyone). That is what makes a
window answer a click at all, and it is why the process takes focus and appears in the
app switcher. It is the `appkit` layer that starts it, not `objc`, which stays the
generic binding: a window built from raw `objc:send` in a program that never calls an
`appkit:` function draws and responds to nothing, so build it with `appkit:window`.

A callback runs with the interpreter's *global* dynamic bindings — a `let` binding of
a special variable on the REPL thread is not visible to it — and an error it does not
handle is printed as `objc: error in a callback: ...` rather than signalled: there is
no Lisp frame above an AppKit event to signal to.

### A class defined at run time

`objc:define-class` registers a class whose methods are Lisp functions; each method
receives the receiver first and then its own arguments:

```console
CL-USER> (defvar *target-class*
    (objc:define-class "MyTarget" "NSObject"
      (list (list "invoke:" (lambda (self sender)
                              (format t "clicked ~a~%" sender))))))
CL-USER> (defvar *target* (objc:send (objc:send *target-class* "alloc") "init"))
CL-USER> (objc:send button "setTarget:" *target*)
CL-USER> (objc:send button "setAction:" "invoke:")
```

The method's type comes from the superclass when it declares the selector, from an
adopted protocol otherwise (`(objc:define-class "Delegate" "NSObject" methods
'("NSWindowDelegate"))` types `windowShouldClose:` as `BOOL`), and defaults to a
target/action shape — no result, one object argument per colon. The shapes a method
can have are a closed set: no arguments; one or two object arguments; one object
argument answering `BOOL`, an object, or an integer. Re-evaluating a definition
rebinds the class's methods rather than failing, so a REPL can iterate on a handler.

### Ownership

An `objc:` value owns one reference to its object — taken over from an `alloc` /
`new` / `copy` / `mutableCopy` / `retain` result, retained for everything else — and
releases it on the main thread when the Lisp value is collected. So a window or a
string you hold is valid for as long as you hold it, and there is nothing to free by
hand. The one rule: a window you make with `objc:` directly must have
`(objc:send win "setReleasedWhenClosed:" nil)`, as `appkit:window` does, or
closing it releases a reference the Lisp value still holds.

## The native binary

The `rontolisp` binary serves a fixed table of `objc_msgSend` shapes, registered when
the binary is built — every shape the `appkit` layer sends, plus the sixty most
common shapes across the core AppKit and Foundation classes, which reach nine of
every ten methods they declare. A selector outside the table signals with the exact
entry to add:

```text
objc:send: someRareSelector: the shape void(void*,void*,jshort) has no foreign-call stub
in this binary; register it under foreign.downcalls in reachability-metadata.json and rebuild
```

The JVM registers nothing ahead of time and binds any shape, so `java -jar` is the
place to find out what a program sends before a binary is built for it.

## Compiling to a JVM class

The same program compiles to a `.class` or a `.jar` and runs under a plain `java`
launcher, which parks the process's first thread in an event loop by itself:

```console
$ rontolisp examples/macos/counter.lisp -o Counter.class --class-name Counter
$ java Counter
$ rontolisp examples/macos/counter.lisp -o counter.jar
$ java -jar counter.jar
```

The class carries the whole binding (`am.ik.objc`, renamed into its own package) and
the `appkit` widgets it uses, so it needs nothing beside a JVM with `java.lang.foreign`
— the one the compiler ran on, or newer. A bare `.class` run without
`--enable-native-access=ALL-UNNAMED` prints the JDK's restricted-method warning once
and works; a `.jar` enables native access in its manifest. The `rontolisp` binary
compiles such a program too. A `.wasm` output is refused —
`Cannot compile: appkit:window ...` — and always will be: there is no foreign function
API and no AppKit on that side.

## Limitations

- macOS only: the interpreter (`java -jar`, or the `rontolisp` binary) and a compiled
  `.class` / `.jar`. Never a `.wasm`; an `objc:` / `appkit:` reference is a compile
  error on both WASM backends.
- A process without an application bundle gets no Dock icon or menu bar; there is no
  Cmd-Q, and closing the last window does not quit — the REPL is the process.
- Callback shapes are the closed set above; a delegate method with a struct or
  integer argument, or a block-taking selector, needs a rung this cut does not have.
- Apple silicon. On an Intel Mac a struct wider than two registers is returned
  through `objc_msgSend_stret`, which the binding selects but has not been exercised.
