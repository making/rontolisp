;;;; counter.lisp -- a native Cocoa window from rontolisp, with nothing installed.
;;;;
;;;; A window, a label and a button whose action is a Lisp closure, built on the
;;;; built-in `appkit` package (a widget layer written in rontolisp over the `objc`
;;;; package, which binds AppKit through the foreign function API). It runs on macOS
;;;; on the interpreter -- under `java -jar` AND in the `rontolisp` native binary,
;;;; which is what `java:` interop cannot do -- and compiled to a JVM class or jar,
;;;; which carries the binding inside it; never as WASM. It needs a display, so it is
;;;; not in examples.yaml.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/counter.lisp
;;;;   ./target/rontolisp examples/macos/counter.lisp
;;;;   ./target/rontolisp examples/macos/counter.lisp -o Counter.class --class-name Counter && java Counter
;;;;   ./target/rontolisp examples/macos/counter.lisp -o counter.jar --class-name Counter && java -jar counter.jar
;;;;
;;;; Typed into the REPL instead, the same forms open the same window and the REPL
;;;; keeps taking input while it is up: the window lives on the process's first
;;;; thread, the REPL on its own. Close the window to end the script (the REPL
;;;; survives a close).

(defvar *window* (appkit:window "rontolisp counter" :width 420 :height 200))

(defvar *label*
  (appkit:label *window* "no clicks yet" :x 20 :y 120 :width 380 :height 24))

(defvar *clicks* 0)

;; The handler runs on the main thread, from inside AppKit's event loop, and may
;; call back into the GUI freely.
(defvar *button*
  (appkit:button *window* "Click me"
                 :x 20
                 :y 40
                 :width 140
                 :height 32
                 :on-click (lambda ()
                             (setq *clicks* (+ *clicks* 1))
                             (appkit:set-text *label*
                              (format nil "clicked ~a time(s)" *clicks*)))))

;; Anything the widget layer lacks is one objc:send away: the window is an
;; ordinary NSWindow.
(objc:send *window* "setBackgroundColor:"
 (objc:send "NSColor" "colorWithRed:green:blue:alpha:" 0.92 0.96 1.0 1.0))

(format t "window ~a is up; close it to exit~%"
        (objc:send *window* "windowNumber"))

;; A script's process ends when its last form returns, so wait for the close.
(appkit:wait *window*)
(format t "closed after ~a click(s)~%" *clicks*)
