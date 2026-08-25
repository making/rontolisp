;; The appkit package: a small Cocoa widget layer -- a window, a label, a button
;; whose action is a Lisp closure -- written in rontolisp itself over the objc:
;; verbs and shipped inside the interpreter (see AppKitLibrary.java): the
;; interpreter loads these definitions lazily on the first use of an appkit:
;; function, so a bare REPL can type (appkit:window "hi") with nothing required.
;; Nothing here is a hand-written Java surface: every widget is objc:send over the
;; selector the runtime describes, so anything this layer lacks is one objc:send
;; away in user code.
;;
;; Portability constraints honored here (like linalg.lisp): do loops always
;; declare at least one variable; parameters are never assigned with setq.
;;
;; Threads: every objc:send hops to thread 0 on its own, so a widget is built
;; inside ONE objc:on-main to pay the hop once rather than per selector. A
;; button's :on-click closure runs on thread 0, from inside AppKit's event loop.
;;
;; Ownership: a window is created with releasedWhenClosed off, because the
;; interpreter's wrapper owns a reference of its own and releases it when the
;; Lisp value is collected (see ObjcBridge.java); closing the window with the red
;; button therefore hides it and does not end the process or the REPL.

;; The shared application object, activated once per process. setActivationPolicy:
;; 0 is NSApplicationActivationPolicyRegular, which is what lets a process with no
;; bundle take focus and show a window.
(defvar appkit::*app* nil)

;; The one target object every button sends its action to: an instance of a class
;; defined at run time whose invoke: method is appkit::%invoke, which looks the
;; button up in appkit::*actions* by address. Kept in a global because AppKit
;; holds a target weakly.
(defvar appkit::*action-target* nil)

;; Button address -> the :on-click closure.
(defvar appkit::*actions* (make-hash-table))

(defun appkit::%app ()
  (or appkit::*app*
      (objc:on-main
       (lambda ()
         (let ((app (objc:send "NSApplication" "sharedApplication")))
           (objc:send app "setActivationPolicy:" 0)
           (objc:send app "finishLaunching")
           (setq appkit::*app* app)
           app)))))

;; The IMP of -[RontoLispAppKitAction invoke:]: self is the target, sender the
;; button.
(defun appkit::%invoke (self sender)
  (let ((handler (gethash (objc:address sender) appkit::*actions*)))
    (when handler (funcall handler))
    nil))

(defun appkit::%action-target ()
  (or appkit::*action-target*
      (let ((cls
             (objc:define-class "RontoLispAppKitAction"
               "NSObject"
               (list (list "invoke:" #'appkit::%invoke)))))
        (setq appkit::*action-target*
              (objc:send (objc:send cls "alloc") "init"))
        appkit::*action-target*)))

(defun appkit::%content-view (window) (objc:send window "contentView"))

;; (appkit:window "title" :width 480 :height 300) -> an NSWindow, shown, centered
;; and made key. Style mask 15 = titled | closable | miniaturizable | resizable;
;; backing 2 = buffered.
(defun appkit:window (title &key (width 480) (height 300))
  (appkit::%app)
  (objc:on-main
   (lambda ()
     (let ((win
            (objc:send (objc:send "NSWindow" "alloc")
                       "initWithContentRect:styleMask:backing:defer:"
                       (list 0 0 width height) 15 2 nil)))
       (objc:send win "setReleasedWhenClosed:" nil)
       (objc:send win "setTitle:" title)
       (objc:send win "center")
       (objc:send win "makeKeyAndOrderFront:" nil)
       (objc:send (appkit::%app) "activateIgnoringOtherApps:" t)
       win))))

;; (appkit:label win "text" :x 20 :y 20 :width 200 :height 24) -> an NSTextField
;; label added to the window. Coordinates are AppKit's: the origin is the
;; window's bottom-left corner.
(defun appkit:label (window text &key (x 20) (y 20) (width 200) (height 24))
  (objc:on-main
   (lambda ()
     (let ((label (objc:send "NSTextField" "labelWithString:" text)))
       (objc:send label "setFrame:" (list x y width height))
       (objc:send (appkit::%content-view window) "addSubview:" label)
       label))))

;; (appkit:button win "title" :x 20 :y 20 :width 120 :height 32
;;                :on-click (lambda () ...)) -> an NSButton added to the window.
;; Bezel style 1 = rounded, the standard push button.
(defun appkit:button
    (window title &key (x 20) (y 20) (width 120) (height 32) on-click)
  (objc:on-main
   (lambda ()
     (let ((button
            (objc:send (objc:send "NSButton" "alloc") "initWithFrame:"
                       (list x y width height))))
       (objc:send button "setTitle:" title)
       (objc:send button "setBezelStyle:" 1)
       (when on-click
         (setf (gethash (objc:address button) appkit::*actions*) on-click)
         (objc:send button "setTarget:" (appkit::%action-target))
         (objc:send button "setAction:" "invoke:"))
       (objc:send (appkit::%content-view window) "addSubview:" button)
       button))))

(defun appkit::%buttonp (view)
  (objc:send view "isKindOfClass:" (objc:class "NSButton")))

;; (appkit:set-text view "text"): a button's title, any other control's string
;; value. Answers the text.
(defun appkit:set-text (view text)
  (objc:on-main
   (lambda ()
     (if (appkit::%buttonp view)
         (objc:send view "setTitle:" text)
         (objc:send view "setStringValue:" text))
     text)))

;; (appkit:text view) -> the button's title or the control's string value, as a
;; Lisp string.
(defun appkit:text (view)
  (objc:on-main
   (lambda ()
     (objc:send
      (objc:send view (if (appkit::%buttonp view) "title" "stringValue"))
      "UTF8String"))))

;; (appkit:click button): performs the button's action as a user's click would --
;; the way a script drives a window without a human.
(defun appkit:click (button)
  (objc:on-main
   (lambda ()
     (objc:send button "performClick:" nil)
     nil)))

;; (appkit:close window): closes (hides) the window. The Lisp value stays valid.
(defun appkit:close (window)
  (objc:on-main
   (lambda ()
     (objc:send window "close")
     nil)))

;; (appkit:visible-p window) -> whether the window is on screen.
(defun appkit:visible-p (window)
  (objc:on-main (lambda () (objc:send window "isVisible"))))

;; (appkit:wait window): blocks the calling thread until the window is closed --
;; what a script does after building its window, since the process ends when
;; the program does. Never call it from a button's handler (that thread is the
;; one that would close the window).
(defun appkit:wait (window)
  (do ((i 0 (+ i 1)))
      ((not (appkit:visible-p window)) nil)
    (sleep 0.05)))
