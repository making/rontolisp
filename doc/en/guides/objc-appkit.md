# macOS GUI (objc / appkit)

Two built-in packages open a real Cocoa window from a rontolisp REPL with nothing
installed: `objc` binds the Objective-C runtime and AppKit through the JVM's
foreign function API (no JNI, no bundled native library, no reflection), and
`appkit` is a small widget layer written in rontolisp on top of it — a window, a
label, a button whose action is a Lisp closure, a coloured panel, a click, a
repeating timer and a menu bar item.

> **macOS only; interpreter, JVM class and native executable.** Both packages work
> under `java -jar rontolisp.jar`, in the `rontolisp` native binary — the binding needs
> no reflection, which is what `java:` interop lacks there — in a program compiled
> to a `.class` or `.jar`, which carries the binding with it, and in a `--native`
> executable for Apple silicon, whose runner is the binding. A `.wasm` has no foreign
> function API, so compiling such a program to one is a
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
`examples/macos/board.lisp`, a small `board` package holding the grid of clickable
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
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x00000008E34E7600>
CL-USER> (appkit:timer 1 (lambda () (appkit:set-text *digit* "4") nil))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x00000008E346ED00>
```

Every widget is a plain Objective-C object, so anything the layer lacks is one
`objc:invoke` away:

```console
CL-USER> (objc:invoke *win* "setBackgroundColor:"
    (objc:invoke "NSColor" "colorWithRed:green:blue:alpha:" 0.9 0.95 1.0 1.0))
NIL
CL-USER> (objc:invoke *win* "frame")
#(690.0 676.0 420.0 228.0)
```

## The objc package

`objc` is LispWorks 8.1's Objective-C interface: `objc:invoke`, `objc:invoke-bool`,
`objc:invoke-into`, `objc:retain` / `objc:release` / `objc:autorelease`, the
autorelease pools, the class and selector coercions and the class-defining macros, with
LispWorks' names and lambda lists, and the `cocoa` package its Foundation structures.
Code written against the LispWorks manual runs unchanged in a package that uses `objc`,
on the interpreter, a compiled class and a `--native` executable alike. Beside the
manual's names the package has blocks, conditions for what Objective-C reports itself,
and four verbs of its own: `objc:on-main`, `objc:data`, `objc:bytes` and
`objc:objectp`. The [function reference](../reference/functions/objc.md) lists every
name.

```console
CL-USER> (defpackage :my-app (:use :cl :objc))
:MY-APP
CL-USER> (in-package :my-app)
:MY-APP
MY-APP> (defvar *s* (invoke "NSString" "stringWithUTF8String:" "hello world"))
*S*
MY-APP> (invoke *s* "rangeOfString:" "world")
(6 . 5)
MY-APP> (invoke-bool *s* "hasPrefix:" "hello")
T
MY-APP> (invoke-into 'string *s* "uppercaseString")
"HELLO WORLD"
MY-APP> (invoke-into 'string "NSString"
                     '("stringWithFormat:" (objc-object-pointer :int)
                       :result-type objc-object-pointer :variadic-num-of-fixed 1)
                     "The integer %d" 42)
"The integer 42"
```

The receiver of `invoke` is an object pointer, a class pointer, or a string naming a
class, which sends a class method; a string is never an `NSString` receiver, so an
instance method of a string goes to `objc:string-to-ns-string`'s answer.

### The runtime is something you can ask

Everything Objective-C settles at the moment it happens is also readable at that moment:
whether a receiver answers to a name, which class it really is, what types a method
declares, what sits under a key.

```console
MY-APP> (invoke-bool *s* "respondsToSelector:" "uppercaseString")
T
MY-APP> (objc-class-name (invoke (string-to-ns-string "hi") "class"))
"NSTaggedPointerString"
MY-APP> (objc-class-method-signature "NSString" "hasPrefix:")
(OBJC-OBJECT-POINTER SEL OBJC-OBJECT-POINTER)
OBJC-C++-BOOL
"B24@0:8@16"
MY-APP> (invoke (invoke *s* "valueForKey:" "length") "doubleValue")
11.0
```

The second line catches a class cluster in the act — `string-to-ns-string` asked for an
`NSString` and got a private subclass chosen for the value. The third answers a method's
argument types (the receiver and the selector first), its result type and the encoding
the runtime holds for it. `examples/macos/objc-runtime.lisp` is this whole side of the
package in one runnable file: selectors carried around as strings and guarded by
`respondsToSelector:`, class hierarchies walked, a method's own type encoding read back,
key-value coding and a sort by a text key, a class defined in Lisp whose `isEqual:`
`containsObject:` calls, and an `NSNotificationCenter` observer. It opens no window.

### AppKit is not the boundary

Every framework on the machine speaks the Objective-C runtime, and one that is not linked
into this process is a single message away: `NSBundle` maps it and registers its classes,
so from the next form on the class name resolves.

```console
MY-APP> (invoke-bool (invoke "NSBundle" "bundleWithPath:"
                             "/System/Library/Frameworks/NaturalLanguage.framework")
                     "load")
T
MY-APP> (invoke-into 'string "NLLanguageRecognizer" "dominantLanguageForString:"
                     "これは日本語の文章です")
"ja"
```

That is the whole of dependency management here: no manifest, no classpath, no download.
`objc:ensure-objc-initialized` with `:modules` does the same from the path of a framework
binary or a dylib. `examples/macos/system-frameworks.lisp` is the surface it opens, in one
runnable file — Vision, NaturalLanguage, Core Image and the speech synthesizer, none of them
wrapped for Lisp by anybody first. Its centre is a round trip: a Lisp string is drawn into an
image by Core Image and read back out of it by Vision, and `equal` decides whether the
machine read what it was given. It opens no window either, and it is silent, because the
speech is synthesized to an AIFF file instead of the speakers.

### Conversion by the declared type

`objc:invoke` never guesses a signature. The runtime describes every method
completely — `initWithContentRect:styleMask:backing:defer:` is
`@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64` — and `invoke` reads that encoding
(a list-form method states the types itself) and converts each argument and the result
by it. A string or a vector passed as an argument exists for the call only.

| Declared type | An argument may be | The result is |
|---------------|--------------------|---------------|
| `id` | an object pointer, `nil`, a string (an `NSString`), a vector (an `NSArray`, elements converted alike) | an `objc:objc-object-pointer`, or `nil` |
| `Class` | a class pointer or a class name | an `objc:objc-class` |
| `SEL` | a selector or its name | an `objc:sel` |
| `char *` | a string or an address | a string |
| `BOOL` / `_Bool` | `t`, `nil` or an integer | `1` or `0` |
| an integer | an integer (an unsigned 64-bit one up to 2^64-1) | an integer |
| `float` / `double` | a real | a double |
| `NSRect` / `NSPoint` / `NSSize` | `#(x y width height)` / `#(x y)` / `#(width height)` | a vector of doubles |
| `NSRange` | `(location . length)` | a cons |
| any other structure | a vector of its fields in memory order | a vector of its fields |
| a pointer | an address, `nil` or an object pointer | an address |

A `BOOL` result is `1` or `0`, as in LispWorks, so a test uses `objc:invoke-bool`. An
`NSString` result is a pointer like any other object; `objc:invoke-into` converts
further: `'string` turns it into a Lisp string, `'array` and `'(array string)` an
`NSArray` into a vector, and a vector or a cons passed as the first argument receives a
structure or an array's elements.

```console
MY-APP> (invoke *s* "hasPrefix:" "hello")
1
MY-APP> (invoke-into 'string *s* "substringWithRange:" (cons 0 5))
"hello"
MY-APP> (invoke (invoke "NSValue" "valueWithRect:" #(10 20 300 200)) "rectValue")
#(10.0 20.0 300.0 200.0)
MY-APP> (invoke-into '(array string) "NSArray" "arrayWithArray:" #("x" "y"))
#("x" "y")
```

A method the receiver does not have, a wrong argument count, or an argument that does
not fit its declared type is an `error` before anything is sent, never a crash; the
missing method names the class the runtime answers for the receiver:

```console
MY-APP> (invoke *s* "frobnicate")
Error: No method "frobnicate" for object #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000000A9ACE43C0>, class "__NSCFString".
MY-APP> (invoke *s* "length" 3)
Error: objc:invoke: length takes 0 argument(s), got 1
```

The answer of a `performSelector...` message is discarded (its type is the target
method's, which the encoding does not show). A block parameter takes a block, never a bare
function ([Blocks](#blocks) below); a union or a bitfield parameter is refused by name.

### The one declaration that is not the whole call

A VARIADIC method is the exception the runtime does not mark.
`+[NSArray arrayWithObjects:]` and `+[NSArray arrayWithObject:]` are both declared
`@@:@`, byte for byte, and nothing distinguishes them — yet on Apple silicon that
difference is the whole call, since a variadic argument travels on the stack where a
fixed one travels in a register.

So the family is known by name instead: the nil-terminated constructors
(`arrayWithObjects:`, `initWithObjects:`, `setWithObjects:`, `orderedSetWithObjects:`,
`dictionaryWithObjectsAndKeys:`, `initWithObjectsAndKeys:`) and the format-string one
(`stringWithFormat:`, `initWithFormat:`, `localizedStringWithFormat:`,
`stringByAppendingFormat:`, `appendFormat:`, `predicateWithFormat:`, `raise:format:`).
Called by name, each of them takes as many arguments as you give it past its declared
arity, each typed by its value — an object, a string, an integer or a float — and the
`nil` terminator is the binding's own, never yours.

```console
MY-APP> (invoke (invoke "NSArray" "arrayWithObjects:" "a" "b" "c") "count")
3
MY-APP> (invoke-into 'string "NSString" "stringWithFormat:"
                     "%@ has %ld items, %.1f%% full" "cache" 3 62.5)
"cache has 3 items, 62.5% full"
```

Any other variadic method, one your own program declares included, is called with the
list form and its `:variadic-num-of-fixed`, as `stringWithFormat:` is in the first
example above. `arrayWithObjects:count:` is not variadic at all: it takes a real array
and a count, and is the fixed-arity way to build a collection of any size.

### Values

An object answers an `objc:objc-object-pointer`, a class an `objc:objc-class` (also an
object pointer) and a selector an `objc:sel`. None of them is a `structure-object`, and
two answers for one object are `eq`, `eql`, `equal` and `equalp`, so either finds the
other in any hash table. A pointer prints as LispWorks prints one,
`#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C04010>`: its address, never anything
read from the object. `objc:objectp` is true for every object pointer, classes included,
and false for anything else, a selector among them:

```console
MY-APP> (eq (invoke *s* "self") *s*)
T
MY-APP> (objc:objectp *s*)
T
MY-APP> (objc:objectp (coerce-to-objc-class "NSString"))
T
MY-APP> (objc:objectp (coerce-to-selector "length"))
NIL
MY-APP> (objc:objectp "hello")
NIL
```

### Ownership: a pointer releases only what it holds

Every send runs on the main thread inside an autorelease pool of its own, so an
object the send autoreleased is gone when it returns unless the pointer value took
a reference first -- which it does for every object result, taking over the one an
`alloc` / `new` / `copy` / `mutableCopy` / `init` method hands back. The value
releases what it still holds when the collector frees it, on the main thread. So a
window or a string you hold is valid for as long as you hold it, and there is nothing
to free by hand. `objc:retain` adds a reference the program must release;
`objc:release` and `objc:autorelease` give up one the pointer holds and signal when it
holds none, so code that releases what it owns, as the manual prescribes, never
releases twice what the collector would release once. `(objc:invoke p "release")` goes
through the same count.

```console
MY-APP> (defvar *o* (alloc-init-object "NSObject"))
*O*
MY-APP> (retain-count (retain *o*))
2
MY-APP> (release *o*)
NIL
MY-APP> (release *o*)
NIL
MY-APP> (release *o*)
Error: objc:release: #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C04010> holds no reference this program can give up
MY-APP> (with-autorelease-pool ()
          (invoke-into 'string (autorelease (string-to-ns-string "pooled")) "description"))
"pooled"
```

The pools are kept in Lisp: a real `NSAutoreleasePool` could not span two sends,
each of which pushes and pops its own on the main thread.

One rule concerns windows: a window made with `objc:invoke` directly must be sent
`setReleasedWhenClosed:` with `nil`, as `appkit:window` does, or closing it releases a
reference the pointer still holds.

### Defining classes

`objc:define-objc-class` defines a CLOS class that implements an Objective-C class, and
`objc:define-objc-method` / `objc:define-objc-class-method` give it methods whose bodies
are Lisp. The manual's section 1.4 examples run unchanged -- a method over two
`(:unsigned :int)`, a subclass whose method sends to `(current-super)`, a method
answering a structure `objc:define-objc-struct` declared:

```console
MY-APP> (define-objc-class my-object ()
          ((slot1 :initarg :slot1 :initform nil))
          (:objc-class-name "MyObject"))
MY-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* width height))
"areaOfWidth:height:"
MY-APP> (define-objc-class my-special-object (my-object)
          ()
          (:objc-class-name "MySpecialObject"))
MY-SPECIAL-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-special-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* 4 (invoke (current-super) "areaOfWidth:height:" width height)))
"areaOfWidth:height:"
MY-APP> (invoke (alloc-init-object "MySpecialObject") "areaOfWidth:height:" 6 7)
168
MY-APP> (define-objc-struct (pair (:foreign-name "_Pair"))
          (:first :float)
          (:second :float))
PAIR
MY-APP> (define-objc-method ("pair" (:struct pair)) ((this my-object))
          (vector 1.0 2.0))
"pair"
MY-APP> (invoke (alloc-init-object "MyObject") "pair")
#(1.0 2.0)
```

A method's arguments and result may be of any type the declarations name: integers of
every width, `:float` / `:double`, structures (the Lisp value `invoke` uses for one),
`objc:objc-bool` / `:boolean` as `t` / `nil`, objects, classes and selectors. An object
argument can be converted on the way in (`(arg objc-object-pointer string)`, `array`,
`(array string)`), and a string or a vector answered as an object becomes an `NSString` /
`NSArray`. A class naming no Objective-C class and inheriting none is a mixin whose methods
go to every subclass that names one. An error in a method body is printed as
`objc: error in a callback: ...` and the method answers zero; it never unwinds into the
Objective-C frame that called it, since there is no Lisp frame above it to signal to.
Re-evaluating a `define-objc-method` rebinds the method, so a REPL can iterate on a
handler.

An instance is an `objc:standard-objc-object`. `make-instance` allocates and initializes the
Objective-C object (`init`, or the `:init-function` it is given), and an object
Objective-C allocates gets its Lisp object as well, so `objc:objc-object-from-pointer` maps
either back; `objc:objc-object-var-value` reads the instance variables
`(:objc-instance-vars ...)` declared. The Lisp object lives until the Objective-C object's
reference count reaches zero -- the reference `make-instance` took is the program's to
`objc:release` -- and then `objc:objc-object-destroyed` runs, inside `dealloc`; a copy made
through `copy` gets `objc:objc-object-copied`.

Such a class is how Cocoa calls back into Lisp. A button sends its action to a target;
here the target is an instance, and the action a Lisp method:

```console
MY-APP> (define-objc-class my-target ()
          ((clicks :initform 0 :accessor clicks))
          (:objc-class-name "MyTarget"))
MY-TARGET
MY-APP> (define-objc-method ("clicked:" :void) ((self my-target) (sender objc-object-pointer))
          (incf (clicks self))
          (format t "clicked ~a~%" (invoke-into 'string sender "title")))
"clicked:"
MY-APP> (defvar *target* (make-instance 'my-target))
*TARGET*
MY-APP> (defvar *window* (appkit:window "target" :width 200 :height 80))
*WINDOW*
MY-APP> (defvar *go* (appkit:button *window* "Go"))
*GO*
MY-APP> (invoke *go* "setTarget:" *target*)
NIL
MY-APP> (invoke *go* "setAction:" "clicked:")
NIL
MY-APP> (appkit:click *go*)
clicked Go
NIL
MY-APP> (clicks *target*)
1
```

AppKit holds a target weakly; `*target*` is what keeps it alive. The `appkit` widgets are
built exactly this way, and so is the drawing surface of `metal` programs and `scene`: an
`NSView` subclass whose `mouseDown:` / `mouseDragged:` / `scrollWheel:` are Lisp
methods. A method is also how an object observes notifications:

```console
MY-APP> (define-objc-class watcher ()
          ((seen :initform nil :accessor seen))
          (:objc-class-name "Watcher"))
WATCHER
MY-APP> (define-objc-method ("noticed:" :void) ((self watcher) (note objc-object-pointer))
          (push (invoke-into 'string note "name") (seen self)))
"noticed:"
MY-APP> (defvar *w* (make-instance 'watcher))
*W*
MY-APP> (cocoa:add-observer *w* "noticed:" :name "Ping")
NIL
MY-APP> (invoke (invoke "NSNotificationCenter" "defaultCenter")
                "postNotificationName:object:" "Ping" nil)
NIL
MY-APP> (seen *w*)
("Ping")
```

### Blocks

A block is how Cocoa takes a closure: a comparator, an enumerator, a completion handler.
`objc:make-objc-block` makes one from a Lisp function and a signature. The signature is
the caller's to state, because a method's encoding says only that it takes a block, not
what the block takes; a bare function passed where a block goes signals rather than
guess. It is `(result-type (argument-type*))` in the types a list-form method takes, or a
name `objc:define-objc-block-type` gave one. The arguments reach the function converted
as a method's reach its body, and its value goes back the same way. `objc:with-objc-block`
makes a block for the extent of a body and frees it on every exit; that is right for
asynchronous work too, since a callee that keeps a block keeps a copy, and the copy keeps
the function alive. `objc:call-objc-block` calls a block, whoever made it.

```console
MY-APP> (defvar *words* (invoke "NSArray" "arrayWithObjects:" "pear" "fig" "apple"))
*WORDS*
MY-APP> (with-objc-block (compare '(:long-long (objc-object-pointer objc-object-pointer))
                                  (lambda (a b)
                                    (let ((x (ns-string-to-string a))
                                          (y (ns-string-to-string b)))
                                      (cond ((string< x y) -1) ((string> x y) 1) (t 0)))))
          (invoke-into '(array string) *words* "sortedArrayUsingComparator:" compare))
#("apple" "fig" "pear")
MY-APP> (with-objc-block (each '(:void (objc-object-pointer (:unsigned :long-long)
                                        (:pointer objc-c++-bool)))
                               (lambda (word index stop)
                                 (declare (ignore stop))
                                 (format t "~a ~a~%" index (ns-string-to-string word))))
          (invoke *words* "enumerateObjectsUsingBlock:" each))
0 pear
1 fig
2 apple
NIL
MY-APP> (defvar *add* (make-objc-block '(:int (:int :int)) (lambda (a b) (+ a b))))
*ADD*
MY-APP> (call-objc-block '(:int (:int :int)) *add* 3 4)
7
MY-APP> (free-objc-block *add*)
NIL
```

C functions take blocks too -- libdispatch's, for one. `fli:define-foreign-function`, the
part of LispWorks' foreign language interface this package carries, declares one in the
same types:

```console
MY-APP> (fli:define-foreign-function (dispatch-queue-create "dispatch_queue_create")
            ((label objc-c-string) (attributes :pointer))
          :result-type objc-object-pointer)
DISPATCH-QUEUE-CREATE
MY-APP> (fli:define-foreign-function (dispatch-async "dispatch_async")
            ((queue objc-object-pointer) (work objc-at-question-mark))
          :result-type :void)
DISPATCH-ASYNC
MY-APP> (defvar *queue* (dispatch-queue-create "com.example.work" nil))
*QUEUE*
MY-APP> (defvar *done* nil)
*DONE*
MY-APP> (with-objc-block (work '(:void ()) (lambda () (setq *done* t)))
          (dispatch-async *queue* work))
NIL
MY-APP> (sleep 0.1)
NIL
MY-APP> *done*
T
```

A block runs on the thread that calls it. Foundation calls a comparator or an enumerator
on the thread that sent to it, which is the main thread, since every send runs there. A
serial queue runs its work, and `NSURLSession` its completion handler, on a libdispatch
worker, concurrently with the program. There the function sees the global values of
special variables, not the bindings the program's thread made (a closure's own captures
aside), as in a thread `rontolisp:make-thread` starts. A `--native` executable cannot
run Lisp on another thread: a `void` block called on one waits for the program's next
`sleep`, which runs it on the main thread, and a block answering a value is refused,
printed and answered with zero. So a program that waits for a block waits by sleeping,
as below, and runs the same on every target:

```console
MY-APP> (defvar *status* nil)
*STATUS*
MY-APP> (with-objc-block (handler '(:void (objc-object-pointer objc-object-pointer
                                           objc-object-pointer))
                                  (lambda (data response error)
                                    (declare (ignore data error))
                                    (setq *status* (invoke response "statusCode"))))
          (invoke (invoke (invoke "NSURLSession" "sharedSession")
                          "dataTaskWithURL:completionHandler:"
                          (invoke "NSURL" "URLWithString:" "https://example.com/")
                          handler)
                  "resume"))
NIL
MY-APP> (loop until *status* do (sleep 0.05))
NIL
MY-APP> *status*
200
```

### Exceptions and NSError

An Objective-C exception raised inside a call — an index out of range, a `nil` where
an object must go, an `NSException` sent `raise` — signals `objc:objc-exception` from
the innermost `objc:invoke` (or C function, or block call) running on that thread, and
the program goes on. `objc:objc-exception-name`, `objc:objc-exception-reason` and
`objc:objc-exception-object` answer the exception's name, its reason (or `nil`) and the
thrown object, whose reference the condition holds:

```console
MY-APP> (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
          (objc-exception (e) (list (objc-exception-name e) (objc-exception-reason e))))
("NSRangeException" "*** -[__NSArray0 objectAtIndex:]: index 5 beyond bounds for empty array")
```

The Objective-C frames between the raise and the call unwind with their cleanups, as
for Objective-C's own `@catch`, and an exception Cocoa catches itself never reaches
Lisp. One raised inside a method or a block defined in Lisp and not handled there is an
error inside a callback: printed, and the method answers zero.

A method that reports failure through a last `NSError **` argument is called with
`objc:invoke-with-error`, which supplies that argument. When the result says the call
failed — `nil`, `NO` or zero — and the method wrote an error, it signals `objc:ns-error`,
whose readers `objc:ns-error-domain`, `objc:ns-error-code`, `objc:ns-error-description`
and `objc:ns-error-object` answer the domain, the code, the localized description and
the `NSError`; otherwise it answers what `objc:invoke` answers:

```console
MY-APP> (handler-case
            (invoke-with-error (invoke "NSFileManager" "defaultManager")
                               "attributesOfItemAtPath:error:" "/no/such/file")
          (ns-error (e) (list (ns-error-domain e) (ns-error-code e))))
("NSCocoaErrorDomain" 260)
```

### Bytes: `objc:data` and `objc:bytes`

A block of memory is ordinary in Cocoa and has no Lisp value of its own, so the package
makes one an `NSData`. `objc:data` answers an `NSMutableData` holding a packed buffer's
bytes — a packed float array of any rank, a packed `(unsigned-byte 8|16|32)` vector, or a
string's UTF-8 — laid out exactly as `write-sequence` would write them, little-endian and
row-major; anything else signals. `[data bytes]` is then the address a `void *` parameter
wants, `[data mutableBytes]` is writable scratch to hand a callee, and `objc:bytes` reads
an `NSData`'s contents back as a fresh `(unsigned-byte 8)` vector.

```console
MY-APP> (objc:bytes (objc:data (make-array 2 :element-type 'single-float
                                            :initial-contents '(1.0 2.0))))
#(0 0 128 63 0 0 0 64)
MY-APP> (objc:bytes (invoke *s* "dataUsingEncoding:" 4))
#(104 101 108 108 111 32 119 111 114 108 100)
MY-APP> (handler-case
            (invoke-with-error "NSJSONSerialization" "JSONObjectWithData:options:error:"
                               (objc:data "nope") 0)
          (ns-error (e) (ns-error-description e)))
"The data couldn’t be read because it isn’t in the correct format."
```

Bytes and `invoke-with-error` together are what puts the GPU in reach: Metal is an
Objective-C API almost end to end, so `objc:invoke` drives it with nothing added.

### The `metal` package

The boilerplate every Metal program writes identically — the `CAMetalLayer` on the
window's content view, the device, the command queue, the render pass, the drawable,
present and commit, plus the shader, pipeline and buffer helpers — is the built-in
**`metal`** package, shipped inside the interpreter and loaded on first use like
`appkit`. What it deliberately does NOT carry is the shader source, the geometry and
the draw calls: those are the program.

```console
CL-USER> (defvar *win* (appkit:window "metal" :width 640 :height 400 :dark t))
CL-USER> (defvar *ctx* (metal:attach *win* :clear '(0.05 0.06 0.09 1.0) :depth t))
CL-USER> (defvar *pipe* (metal:pipeline *ctx* (metal:library *ctx* *shaders*) "vertex_main" "fragment_main"))
CL-USER> (metal:run *ctx*
    (lambda (encoder)
      (objc:invoke encoder "setRenderPipelineState:" *pipe*)
      (objc:invoke encoder "drawPrimitives:vertexStart:vertexCount:" metal:+triangle+ 0 3)))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000000BBF47A7C0>
```

The one C function Metal appears to need, `MTLCreateSystemDefaultDevice()`, is
avoidable: `CAMetalLayer`'s `preferredDevice` is a property and answers the same
device, which is the fact the whole package stands on. Shaders are compiled from Lisp
strings at run time, and a shader that does not compile signals `objc:ns-error` whose
description is the Metal compiler's own diagnostics, caret and all. `metal:buffer`
copies numbers to the GPU once; `metal:shared-buffer` plus `metal:upload` is for
geometry rewritten every frame; `metal:uniform` sets the small per-frame values Metal
wants inline. A packed single-float array IS a buffer's bytes, so a `linalg` matrix and
a `geom:mesh` reach the GPU with no conversion at all. The
[function reference](../reference/functions/metal.md) lists the whole surface.

`metal` stands on its own — `examples/macos/metal-triangle.lisp`
draws the WebGL hello world, `examples/macos/metal-cube.lisp` a spinning, shaded cube, and
`examples/macos/metal-robot-arm.lisp` a robot arm that solves its own inverse kinematics and
reaches for wherever you click, and `examples/macos/metal-pagoda-garden.lisp` a voxel garden --
a five-storey pagoda over a koi pond under falling cherry blossom, and a night that comes on
when you click -- whose thirteen thousand voxels are ONE cube drawn thirteen thousand times,
the vertex function dividing `vertex_id` by 36 to find which voxel it is on. All four use
`metal` directly and none of them uses `geom` or `scene`. (OpenGL
is the opposite and stays out of reach: `glClear` and friends are plain C functions, which
`objc_msgSend` does not reach.)

The rung above `metal` is the **`scene`** package: a 3-D viewer for `geom` solids, with
the camera, the grid and the frame loop already written, so a modelled machine is three
lines from a window. See the [Solid Modeling guide](solid-modeling.md#seeing-it-the-scene-viewer).

### Threads: everything happens on the main thread

AppKit belongs to the process's first thread, and every `objc:invoke` hops there by
itself — synchronously, so its value comes back to the caller. A widget built from
several sends pays the hop once when wrapped in `objc:on-main`, which is what the
`appkit` functions do; `objc:on-main` calls a function there and answers its value, and
an error the function signals is signalled again to the caller. A function already
running on the main thread (a button's handler) runs its sends inline, so a callback may
call back into the GUI freely.

```console
MY-APP> (objc:on-main (lambda () (+ 1 2)))
3
```

The first `appkit:` call also hands thread 0 to AppKit's own event loop
(`-[NSApplication run]`, started there without blocking anyone). That is what makes a
window answer a click at all, and it is why the process takes focus and appears in the
app switcher. It is the `appkit` layer that starts it, not `objc`, which stays the
generic binding: a window built from raw `objc:invoke` in a program that never calls an
`appkit:` function draws and responds to nothing, so build it with `appkit:window`.

A method or a block defined in Lisp and called on the main thread runs with the
interpreter's *global* dynamic bindings — a `let` binding of a special variable on the
REPL thread is not visible to it.

### Where it differs from LispWorks

rontolisp has no foreign memory interface, so a structure is the Lisp value `invoke`
passes and answers for it (`cocoa:set-ns-rect*` fills a vector) and the manual's
`fli:with-dynamic-foreign-objects` forms have no counterpart. LispWorks makes blocks with
its foreign language interface, not `objc`; the names `objc:make-objc-block` and the rest
are this package's own, and `fli` carries `define-foreign-function` alone. Calling
`objc:ensure-objc-initialized` first is optional: every function opens the runtime on
its first use. A variadic method named in the table above (`stringWithFormat:`,
`arrayWithObjects:` ...) also takes the string form, each extra argument typed by its
value and a `nil` terminator appended. A method's structure result is answered as its
Lisp value, or filled into the variable a non-keyword result style names; the manual's
`fli:foreign-slot-value` over it has no counterpart. LispWorks lets an Objective-C
exception end the process and has no `NSError` helper; `objc:objc-exception`,
`objc:ns-error` and `objc:invoke-with-error` are this package's own, as are
`objc:on-main`, `objc:data`, `objc:bytes` and `objc:objectp`.

## The native binary

The `rontolisp` binary serves a fixed table of `objc_msgSend` shapes, registered when
the binary is built — every shape the `appkit`, `metal` and `scene` layers send, plus
the sixty most common shapes across the core AppKit and Foundation classes, which reach
nine of every ten methods they declare. A method outside the table signals with the
exact entry to add:

```text
objc: someRareSelector:: the shape void(void*,void*,jshort) has no foreign-call stub
in this binary; register it under foreign.downcalls in reachability-metadata.json and rebuild
```

The JVM registers nothing ahead of time and binds any shape, so `java -jar` is the
place to find out what a program sends before a binary is built for it.

A method defined with `objc:define-objc-method` is an upcall of its own shape, and those are
registered the same way: the binary serves the shapes of the class examples above, of the
methods the `appkit`, `metal` and `scene` layers define and of the three methods every class
defined in Lisp gets, and refuses a definition of any other shape with the entry to add under
`foreign.upcalls`. A block is such an upcall too: the binary serves the shapes of the blocks
above (and of a comparator, a work item and a completion handler of three objects) and
refuses a block of any other shape when it is made. `java -jar` and a `--native` executable
take any shape.

A variadic call is its own registration, so the binary serves a bounded grid of those
too: up to eleven arguments past the declared ones — the binding's own `nil` terminator
makes twelve — of which the first three may be numbers and the rest objects. A longer
or more numeric list signals the same way.

## Compiling to a JVM class

The same program compiles to a `.class` or a `.jar` and runs under a plain `java`
launcher, which parks the process's first thread in an event loop by itself:

```console
$ rontolisp examples/macos/counter.lisp -o Counter.class --class-name Counter
$ java Counter
$ rontolisp examples/macos/counter.lisp -o counter.jar
$ java -jar counter.jar
```

The class carries the `objc` package and the `appkit` widgets it uses, and the whole
binding (`am.ik.objc`, renamed after the class) is written beside it as
`Counter$Objc*.class` files, or into the jar; with those files it needs nothing else but
a JVM with `java.lang.foreign` — the one the compiler ran on, or newer. A bare `.class`
run without `--enable-native-access=ALL-UNNAMED` prints the JDK's restricted-method
warning once and works; a `.jar` enables native access in its manifest. The `rontolisp`
binary compiles such a program too. A `.wasm` output is refused —
`Cannot compile: appkit:window ...` — and always will be: there is no foreign function
API and no AppKit on that side.

The jar also builds into a GraalVM native image with no configuration
(`native-image -jar counter.jar`): it carries the native-image metadata the binding needs,
the same table of message shapes the `rontolisp` binary serves (see
[the native binary](#the-native-binary) above). In the image the program's `main` starts
on the process's first thread, so it hands that thread to the event loop itself and runs
the program on a second one, as the `rontolisp` binary does.

## A native executable

`--native` compiles the same program to one executable of about 2.4 MB that needs no
JVM (Apple silicon; the default target on such a Mac, or
`--native-target macos-aarch64`):

```console
$ rontolisp examples/macos/counter.lisp --native -o counter
$ ./counter
```

The executable's runner is the binding: it calls the Objective-C runtime itself, so
every selector the runtime describes can be sent — there is no fixed table of shapes
as in the `rontolisp` binary — and a send costs about a microsecond. The
program runs on the process's first thread, the one AppKit wants, so
`objc:on-main` is a plain call and there is no hop; while the program waits in
`sleep` (as `appkit:wait` does) the window handles its events and runs its timers,
and a button's closure runs inside that wait. A program that reads standard input
leaves the window unresponsive until the read returns.

Values follow the same rules as everywhere else: a pointer holds its references and
releases them once it is garbage, and two answers for one object are `eq`, compared by
address. A `bfloat16` array or a quantized matrix is not accepted by `objc:data` in an
executable.

## Limitations

- macOS only: the interpreter (`java -jar`, or the `rontolisp` binary), a compiled
  `.class` / `.jar` and a `--native` executable for Apple silicon. Never a `.wasm`; an
  `objc:` / `appkit:` reference is a compile error on every other WASM output.
- A process without an application bundle gets no Dock icon or menu bar; there is no
  Cmd-Q, and closing the last window does not quit — the REPL is the process.
- In the `rontolisp` binary a message, a method defined in Lisp and a block each take
  only the shapes the binary registered; `java -jar` and a `--native` executable take any.
- A `--native` executable runs a block another thread calls only if the block answers
  nothing, and then at the program's next `sleep`.
- A variadic method outside the table above is called with the list form; the runtime
  gives the binding no way to tell one from its fixed-arity twin.
- Apple silicon. On an Intel Mac a struct wider than two registers is returned
  through `objc_msgSend_stret`, which the binding selects but has not been exercised,
  and an Objective-C exception inside a call still ends the process.
