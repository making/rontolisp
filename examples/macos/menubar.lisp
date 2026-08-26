;;;; menubar.lisp -- a Lisp that lives in the menu bar.
;;;;
;;;; The program has no window. It puts a status item in the system menu bar, hangs a
;;;; menu off it whose entries are Lisp closures, and starts a timer that rewrites the
;;;; item's title once a second. `:dock nil` asks for the accessory activation policy,
;;;; so there is no Dock icon and no app switcher entry either -- the shape a menu bar
;;;; program has, and the reason `appkit:quit` is the only way out.
;;;;
;;;; Everything a menu item does happens in THIS image: `*clicks*` is an ordinary Lisp
;;;; variable, "Open a window" builds a Cocoa window from the same evaluator that is
;;;; running the menu, and the clock in the title is a Foundation date formatter called
;;;; from Lisp. macOS only, with a display; on the interpreter, the native binary and
;;;; compiled to a JVM class or jar; never as WASM.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/menubar.lisp
;;;;   ./target/rontolisp examples/macos/menubar.lisp
;;;;   ./target/rontolisp examples/macos/menubar.lisp -o MenuBar.class --class-name MenuBar \
;;;;     && java MenuBar
;;;;   ./target/rontolisp examples/macos/menubar.lisp -o menubar.jar && java -jar menubar.jar

;;; The clock in the title: Foundation formats it, Lisp asks for it.

(defun now (format)
  (let ((formatter (objc:send (objc:send "NSDateFormatter" "alloc") "init")))
    (objc:send formatter "setDateFormat:" (objc:string format))
    (objc:send
     (objc:send formatter "stringFromDate:" (objc:send "NSDate" "date"))
     "UTF8String")))

;;; The state is a Lisp variable, and the menu is what reads it.

(defvar *clicks* 0)

(defvar *item* nil)

(defun counted ()
  (setq *clicks* (+ *clicks* 1))
  (format t "menu: chosen ~a time(s)~%" *clicks*)
  (appkit:set-text *item* (format nil "λ ~a" *clicks*)))

;;; A window opened from a menu item -- the proof that the menu and the evaluator are
;;; one image, since nothing was declared ahead of time to make this possible.

(defun open-a-window ()
  (let ((window
         (appkit:window "Opened from the menu bar" :width 360 :height 120)))
    (appkit:label window
     (format nil "It is ~a, and this window is ~a" (now "HH:mm:ss") *clicks*)
     :x 20
     :y 50
     :width 320
     :height 24)
    window))

;;; The menu bar item itself. Each entry is (title handler), optionally with a key
;;; equivalent; :separator is a dividing line.

(setq *item*
      (appkit:status-item (format nil "λ ~a" *clicks*)
                          :dock nil
                          :menu (appkit:menu
                                 (list (list "Count a click" #'counted)
                                  (list "Open a window" #'open-a-window)
                                  :separator (list "Quit" #'appkit:quit "q")))))

;;; A second's worth of clock, written into the menu bar by a Lisp closure. The timer
;;; runs on thread 0, inside AppKit's event loop, so it may touch the item directly.

(appkit:timer 1
              (lambda ()
                (appkit:set-text *item*
                                 (format nil "λ ~a  ~a" *clicks* (now "HH:mm")))
                t))

(format t "In the menu bar now: λ ~a. Quit from its menu (or Cmd-Q).~%"
        *clicks*)

(appkit:wait)
