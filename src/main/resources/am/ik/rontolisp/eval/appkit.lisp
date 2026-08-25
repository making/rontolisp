;; The appkit package: a small Cocoa widget layer -- a window, a label, a button
;; whose action is a Lisp closure, a filled panel, a colour, a font, a click and a
;; repeating timer -- written in rontolisp itself over the objc: verbs and shipped
;; inside the interpreter (see AppKitLibrary.java): the interpreter loads these
;; definitions lazily on the first use of an appkit: function, so a bare REPL can
;; type (appkit:window "hi") with nothing required. Nothing here is a hand-written
;; Java surface: every widget is objc:send over the selector the runtime describes,
;; so anything this layer lacks is one objc:send away in user code.
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
;; bundle take focus and show a window; -[NSApplication run] below is what lets it
;; answer a click.
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
           ;; Hand thread 0 to AppKit's OWN event loop. The run loop it is
           ;; parked in delivers events to the process but dequeues none of
           ;; them: without -[NSApplication run] the window draws and nothing
           ;; in it answers a click -- not a button, not the red close button --
           ;; and the application never becomes active. run never returns, so no
           ;; thread that has to come back may call it; asking thread 0 to
           ;; perform it on its next run-loop cycle (waitUntilDone NO) starts it
           ;; without blocking the caller, and every objc:send hop still works,
           ;; because run drains the main queue like the loop it replaces.
           (objc:send app
                      "performSelectorOnMainThread:withObject:waitUntilDone:"
                      "run" nil nil)
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

;;; --- colours and fonts ------------------------------------------------------

;; (appkit:color 90 200 250) -> an NSColor from 0-255 components; the optional
;; fourth argument is the alpha, 0.0 (clear) to 1.0 (opaque).
(defun appkit:color (r g b &optional (alpha 1.0))
  (objc:send "NSColor" "colorWithRed:green:blue:alpha:" (/ r 255.0) (/ g 255.0)
             (/ b 255.0) (* 1.0 alpha)))

;; (appkit:font 19 :bold t) -> the system font at that size.
(defun appkit:font (size &key bold)
  (objc:send "NSFont" (if bold "boldSystemFontOfSize:" "systemFontOfSize:")
             (* 1.0 size)))

;; Font address -> the height one line of it needs, measured once per font by
;; asking a throwaway field to size itself to its content. This is what lets
;; appkit:label centre a string vertically in the rectangle it was given: an
;; NSTextField draws its string at the TOP of its own frame, so a label handed a
;; tall rectangle would otherwise hang from the ceiling.
(defvar appkit::*line-heights* (make-hash-table))

(defun appkit::%line-height (fnt)
  (let ((key (objc:address fnt)))
    (or (gethash key appkit::*line-heights*)
        (setf (gethash key appkit::*line-heights*)
              (let ((probe (objc:send "NSTextField" "labelWithString:" "8gjM")))
                (objc:send probe "setFont:" fnt)
                (objc:send probe "sizeToFit")
                (nth 3 (objc:send probe "frame")))))))

;;; --- clicks -----------------------------------------------------------------
;;;
;;; AppKit delivers a click to the view under the pointer, and NSBox and
;;; NSTextField answer none, so a panel and a label are instances of subclasses
;;; defined AT RUN TIME whose mouseDown: / rightMouseDown: are the Lisp functions
;;; below. Both look the receiver up by address in one table, so a panel and the
;;; label drawn over it can share a handler and the whole tile is live -- there
;;; is no event forwarding to arrange.

;; View address -> the (lambda (button) ...) registered for it.
(defvar appkit::*clicks* (make-hash-table))

(defvar appkit::*panel-class* nil)
(defvar appkit::*label-class* nil)

;; 1 for a left click, 3 for a right click -- the java.awt.event button numbers,
;; so a handler written for a Swing front-end reads the same here.
(defun appkit::%click (self button)
  (let ((handler (gethash (objc:address self) appkit::*clicks*)))
    (when handler (funcall handler button))
    nil))

(defun appkit::%left-click (self event) (appkit::%click self 1))

(defun appkit::%right-click (self event) (appkit::%click self 3))

(defun appkit::%clickable-class (name super)
  (objc:define-class name
    super
    (list (list "mouseDown:" #'appkit::%left-click)
          (list "rightMouseDown:" #'appkit::%right-click))))

(defun appkit::%panel-class ()
  (or appkit::*panel-class*
      (setq appkit::*panel-class*
            (appkit::%clickable-class "RontoLispAppKitPanel" "NSBox"))))

(defun appkit::%label-class ()
  (or appkit::*label-class*
      (setq appkit::*label-class*
            (appkit::%clickable-class "RontoLispAppKitLabel" "NSTextField"))))

;;; --- widgets ----------------------------------------------------------------

;; (appkit:window "title" :width 480 :height 300) -> an NSWindow, shown, centered
;; and made key. :background is an NSColor for the window itself and :dark asks
;; for the dark appearance, which the title bar follows too -- without it the
;; traffic lights sit on a light strip above a dark window. Style mask 15 =
;; titled | closable | miniaturizable | resizable; backing 2 = buffered.
(defun appkit:window (title &key (width 480) (height 300) background dark)
  (appkit::%app)
  (objc:on-main
   (lambda ()
     (let ((win
            (objc:send (objc:send "NSWindow" "alloc")
                       "initWithContentRect:styleMask:backing:defer:"
                       (list 0 0 width height) 15 2 nil)))
       (objc:send win "setReleasedWhenClosed:" nil)
       (objc:send win "setTitle:" title)
       (when dark
         (objc:send win "setAppearance:"
                    (objc:send "NSAppearance" "appearanceNamed:"
                               "NSAppearanceNameDarkAqua"))
         (objc:send win "setTitlebarAppearsTransparent:" t))
       (when background (objc:send win "setBackgroundColor:" background))
       (objc:send win "center")
       (objc:send win "makeKeyAndOrderFront:" nil)
       (objc:send (appkit::%app) "activateIgnoringOtherApps:" t)
       win))))

;; (appkit:label win "text" :x 20 :y 20 :width 200 :height 24) -> an NSTextField
;; label added to the window, its string centred vertically in that rectangle
;; (see appkit::%line-height above). :align is :left (the default), :center or
;; :right, :size and :bold pick the font and :color the text colour.
;; Coordinates are AppKit's: the origin is the window's bottom-left corner.
(defun appkit:label (window text &key (x 20) (y 20) (width 200) (height 24)
                            (size 13) color (align :left) bold)
  (objc:on-main
   (lambda ()
     (let* ((fnt (appkit:font size :bold bold))
            (line (appkit::%line-height fnt))
            (label
             (objc:send (objc:send (appkit::%label-class) "alloc")
                        "initWithFrame:"
                        (list x (+ y (/ (- height line) 2.0)) width line))))
       (objc:send label "setFont:" fnt)
       (objc:send label "setEditable:" nil)
       (objc:send label "setSelectable:" nil)
       (objc:send label "setBezeled:" nil)
       (objc:send label "setBordered:" nil)
       (objc:send label "setDrawsBackground:" nil)
       ;; NSTextAlignment: 0 left, 1 centre, 2 right (the iOS values, which is
       ;; what AppKit uses on Apple silicon).
       (objc:send label "setAlignment:"
                  (cond ((eq align :center) 1) ((eq align :right) 2) (t 0)))
       (objc:send label "setStringValue:" text)
       (when color (objc:send label "setTextColor:" color))
       (objc:send (appkit::%content-view window) "addSubview:" label)
       label))))

;; (appkit:panel win :x 20 :y 20 :width 96 :height 44 :fill c :radius 10) -> a
;; filled, optionally rounded and bordered rectangle added to the window: an
;; NSBox in its custom form (box type 4, no title), which is the shortest way to
;; one in AppKit. Its colour is appkit:set-color and it answers appkit:on-click.
(defun appkit:panel (window &key (x 20) (y 20) (width 100) (height 100) fill
                            (radius 0) (border 0) border-color)
  (objc:on-main
   (lambda ()
     (let ((box
            (objc:send (objc:send (appkit::%panel-class) "alloc")
                       "initWithFrame:" (list x y width height))))
       (objc:send box "setBoxType:" 4)
       (objc:send box "setTitlePosition:" 0)
       (objc:send box "setCornerRadius:" (* 1.0 radius))
       (objc:send box "setBorderWidth:" (* 1.0 border))
       (when fill (objc:send box "setFillColor:" fill))
       (when border-color (objc:send box "setBorderColor:" border-color))
       (objc:send (appkit::%content-view window) "addSubview:" box)
       box))))

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

(defun appkit::%panelp (view)
  (objc:send view "isKindOfClass:" (objc:class "NSBox")))

;; (appkit:on-click view (lambda (button) ...)): makes a panel or a label answer
;; a click, the handler taking the button number -- 1 for a left click, 3 for a
;; right one (or a Ctrl-click). Given a button it sets its action instead, so one
;; verb wires any widget; the button's own :on-click closure takes no argument,
;; since a button has no right click. Answers the view.
(defun appkit:on-click (view handler)
  (objc:on-main
   (lambda ()
     (if (appkit::%buttonp view)
         (progn
           (setf (gethash (objc:address view) appkit::*actions*)
                 (lambda () (funcall handler 1)))
           (objc:send view "setTarget:" (appkit::%action-target))
           (objc:send view "setAction:" "invoke:"))
         (setf (gethash (objc:address view) appkit::*clicks*) handler))
     view)))

;; (appkit:set-text view "text"): a button's title, any other control's string
;; value. Answers the text.
(defun appkit:set-text (view text)
  (objc:on-main
   (lambda ()
     (if (appkit::%buttonp view)
         (objc:send view "setTitle:" text)
         (objc:send view "setStringValue:" text))
     text)))

;; (appkit:set-color view color): a panel's fill colour, any other control's text
;; colour. Answers the colour.
(defun appkit:set-color (view color)
  (objc:on-main
   (lambda ()
     (if (appkit::%panelp view)
         (progn
           (objc:send view "setFillColor:" color)
           (objc:send view "setNeedsDisplay:" t))
         (objc:send view "setTextColor:" color))
     color)))

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

;;; --- a repeating timer ------------------------------------------------------

;; Timer address -> the function it runs.
(defvar appkit::*timers* (make-hash-table))

(defvar appkit::*timer-target* nil)

;; The IMP of -[RontoLispAppKitTimer tick:]: run the timer's function, and let a
;; nil answer be what stops the clock.
(defun appkit::%tick (self timer)
  (let ((handler (gethash (objc:address timer) appkit::*timers*)))
    (unless (and handler (funcall handler)) (objc:send timer "invalidate")))
  nil)

(defun appkit::%timer-target ()
  (or appkit::*timer-target*
      (let ((cls
             (objc:define-class "RontoLispAppKitTimer"
               "NSObject"
               (list (list "tick:" #'appkit::%tick)))))
        (setq appkit::*timer-target* (objc:send (objc:send cls "alloc") "init"))
        appkit::*timer-target*)))

;; (appkit:timer 0.5 (lambda () ...)) -> a repeating NSTimer that calls the
;; function every SECONDS until it answers nil, which invalidates the timer. The
;; function runs on thread 0, inside AppKit's event loop, so it may touch the GUI
;; freely.
(defun appkit:timer (seconds fn)
  (objc:on-main
   (lambda ()
     (let ((timer
            (objc:send "NSTimer"
             "scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:"
             (* 1.0 seconds) (appkit::%timer-target) "tick:" nil t)))
       (setf (gethash (objc:address timer) appkit::*timers*) fn)
       timer))))

;;; --- the window's life ------------------------------------------------------

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
