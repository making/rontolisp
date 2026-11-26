;; The appkit package: a small Cocoa widget layer -- a window, a label, a button
;; whose action is a Lisp closure, a filled panel, a colour, a font, a click, a
;; repeating timer and a menu bar item -- written in rontolisp itself over the
;; objc package and shipped inside the interpreter (see AppKitLibrary.java): the
;; interpreter loads these definitions lazily on the first use of an appkit:
;; function, so a bare REPL can type (appkit:window "hi") with nothing required.
;; Nothing here is a hand-written Java surface: every widget is objc:invoke over
;; the selector the runtime describes, so anything this layer lacks is one
;; objc:invoke away in user code.
;;
;; Portability constraints honored here (like linalg.lisp): do loops always
;; declare at least one variable; parameters are never assigned with setq.
;;
;; Threads: every objc:invoke hops to thread 0 on its own, so a widget is built
;; inside ONE objc:on-main to pay the hop once rather than per selector. A
;; button's :on-click closure runs on thread 0, from inside AppKit's event loop.
;;
;; Ownership: a window is created with releasedWhenClosed off, because the Lisp
;; pointer owns a reference of its own and releases it when the value is
;; collected (.kb/objc.md, "Ownership"); closing the window with the red button
;; therefore hides it and does not end the process or the REPL.
;;
;; The classes below are defined with objc:define-objc-class. The compile path
;; splices this file after user macros are expanded, so AppKitLibrary expands the
;; defining macros here itself (ObjcLibrary.expandDefinitions).

;; The shared application object, activated once per process. setActivationPolicy:
;; 0 is NSApplicationActivationPolicyRegular, which is what lets a process with no
;; bundle take focus and show a window; -[NSApplication run] below is what lets it
;; answer a click.
(defvar appkit::*app* nil)

(defun appkit::%app ()
  (or appkit::*app*
      (objc:on-main
       (lambda ()
         (let ((app (objc:invoke "NSApplication" "sharedApplication")))
           (objc:invoke app "setActivationPolicy:" 0)
           (objc:invoke app "finishLaunching")
           ;; Hand thread 0 to AppKit's OWN event loop. The run loop it is
           ;; parked in delivers events to the process but dequeues none of
           ;; them: without -[NSApplication run] the window draws and nothing
           ;; in it answers a click -- not a button, not the red close button --
           ;; and the application never becomes active. run never returns, so no
           ;; thread that has to come back may call it; asking thread 0 to
           ;; perform it on its next run-loop cycle (waitUntilDone NO) starts it
           ;; without blocking the caller, and every send's hop still works,
           ;; because run drains the main queue like the loop it replaces.
           (objc:invoke app
                        "performSelectorOnMainThread:withObject:waitUntilDone:"
                        "run" nil nil)
           (setq appkit::*app* app)
           app)))))

;; The target a button or a menu item sends its action to: one per control, its
;; closure in a slot. AppKit holds a target weakly; the Lisp object keeps it
;; alive (make-instance's reference is never given up).
(objc:define-objc-class appkit::action ()
  ((handler :initarg :handler :initform nil :accessor appkit::%action-handler))
  (:objc-class-name "RontoLispAppKitAction"))

(objc:define-objc-method ("invoke:" :void)
  ((self appkit::action) (sender objc:objc-object-pointer))
  (declare (ignore sender))
  (let ((handler (appkit::%action-handler self)))
    (when handler (funcall handler))))

;; Wires a control's target/action to HANDLER, reusing the target it already has.
(defun appkit::%set-action (control handler)
  (let* ((current (objc:invoke control "target"))
         (target (and current (objc:objc-object-from-pointer current))))
    (if (typep target 'appkit::action)
        (setf (appkit::%action-handler target) handler)
        (progn
          (objc:invoke control "setTarget:"
                       (make-instance 'appkit::action :handler handler))
          (objc:invoke control "setAction:" "invoke:"))))
  control)

(defun appkit::%content-view (window) (objc:invoke window "contentView"))

;;; --- colours and fonts ------------------------------------------------------

;; (appkit:color 90 200 250) -> an NSColor from 0-255 components; the optional
;; fourth argument is the alpha, 0.0 (clear) to 1.0 (opaque).
(defun appkit:color (r g b &optional (alpha 1.0))
  (objc:invoke "NSColor" "colorWithRed:green:blue:alpha:" (/ r 255.0)
               (/ g 255.0) (/ b 255.0) (* 1.0 alpha)))

;; (appkit:font 19 :bold t) -> the system font at that size.
(defun appkit:font (size &key bold)
  (objc:invoke "NSFont" (if bold "boldSystemFontOfSize:" "systemFontOfSize:")
               (* 1.0 size)))

;; Font -> the height one line of it needs, measured once per font by asking a
;; throwaway field to size itself to its content. This is what lets appkit:label
;; centre a string vertically in the rectangle it was given: an NSTextField draws
;; its string at the TOP of its own frame, so a label handed a tall rectangle
;; would otherwise hang from the ceiling.
(defvar appkit::*line-heights* (make-hash-table))

(defun appkit::%line-height (fnt)
  (or (gethash fnt appkit::*line-heights*)
      (setf (gethash fnt appkit::*line-heights*)
            (let ((probe (objc:invoke "NSTextField" "labelWithString:" "8gjM")))
              (objc:invoke probe "setFont:" fnt)
              (objc:invoke probe "sizeToFit")
              (aref (objc:invoke probe "frame") 3)))))

;;; --- clicks -----------------------------------------------------------------
;;;
;;; AppKit delivers a click to the view under the pointer, and NSBox and
;;; NSTextField answer none, so a panel and a label are instances of subclasses
;;; defined here whose mouseDown: / rightMouseDown: run the handler the view's
;;; Lisp object holds. A panel and the label drawn over it can share one handler,
;;; so the whole tile is live -- there is no event forwarding to arrange.

;; The mixin both clickable views inherit its two methods and its slot from.
(objc:define-objc-class appkit::clickable ()
  ((handler :initform nil :accessor appkit::%click-handler)))

;; 1 for a left click, 3 for a right click -- the java.awt.event button numbers,
;; so a handler written for a Swing front-end reads the same here.
(defun appkit::%click (self button)
  (let ((handler (appkit::%click-handler self)))
    (when handler (funcall handler button))))

(objc:define-objc-method ("mouseDown:" :void)
  ((self appkit::clickable) (event objc:objc-object-pointer))
  (declare (ignore event))
  (appkit::%click self 1))

(objc:define-objc-method ("rightMouseDown:" :void)
  ((self appkit::clickable) (event objc:objc-object-pointer))
  (declare (ignore event))
  (appkit::%click self 3))

(objc:define-objc-class appkit::panel-view (appkit::clickable)
  ()
  (:objc-class-name "RontoLispAppKitPanel")
  (:objc-superclass-name "NSBox"))

(objc:define-objc-class appkit::label-view (appkit::clickable)
  ()
  (:objc-class-name "RontoLispAppKitLabel")
  (:objc-superclass-name "NSTextField"))

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
            (objc:invoke (objc:invoke "NSWindow" "alloc")
                         "initWithContentRect:styleMask:backing:defer:"
                         (vector 0 0 width height) 15 2 nil)))
       (objc:invoke win "setReleasedWhenClosed:" nil)
       (objc:invoke win "setTitle:" title)
       (when dark
         (objc:invoke win "setAppearance:"
                      (objc:invoke "NSAppearance" "appearanceNamed:"
                                   "NSAppearanceNameDarkAqua"))
         (objc:invoke win "setTitlebarAppearsTransparent:" t))
       (when background (objc:invoke win "setBackgroundColor:" background))
       (objc:invoke win "center")
       (objc:invoke win "makeKeyAndOrderFront:" nil)
       (objc:invoke (appkit::%app) "activateIgnoringOtherApps:" t)
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
             (objc:invoke (objc:invoke "RontoLispAppKitLabel" "alloc")
                          "initWithFrame:"
                          (vector x (+ y (/ (- height line) 2.0)) width line))))
       (objc:invoke label "setFont:" fnt)
       (objc:invoke label "setEditable:" nil)
       (objc:invoke label "setSelectable:" nil)
       (objc:invoke label "setBezeled:" nil)
       (objc:invoke label "setBordered:" nil)
       (objc:invoke label "setDrawsBackground:" nil)
       ;; NSTextAlignment: 0 left, 1 centre, 2 right (the iOS values, which is
       ;; what AppKit uses on Apple silicon).
       (objc:invoke label "setAlignment:"
                    (cond ((eq align :center) 1) ((eq align :right) 2) (t 0)))
       (objc:invoke label "setStringValue:" text)
       (when color (objc:invoke label "setTextColor:" color))
       (objc:invoke (appkit::%content-view window) "addSubview:" label)
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
            (objc:invoke (objc:invoke "RontoLispAppKitPanel" "alloc")
                         "initWithFrame:" (vector x y width height))))
       (objc:invoke box "setBoxType:" 4)
       (objc:invoke box "setTitlePosition:" 0)
       (objc:invoke box "setCornerRadius:" (* 1.0 radius))
       (objc:invoke box "setBorderWidth:" (* 1.0 border))
       (when fill (objc:invoke box "setFillColor:" fill))
       (when border-color (objc:invoke box "setBorderColor:" border-color))
       (objc:invoke (appkit::%content-view window) "addSubview:" box)
       box))))

;; (appkit:button win "title" :x 20 :y 20 :width 120 :height 32
;;                :on-click (lambda () ...)) -> an NSButton added to the window.
;; Bezel style 1 = rounded, the standard push button.
(defun appkit:button
    (window title &key (x 20) (y 20) (width 120) (height 32) on-click)
  (objc:on-main
   (lambda ()
     (let ((button
            (objc:invoke (objc:invoke "NSButton" "alloc") "initWithFrame:"
                         (vector x y width height))))
       (objc:invoke button "setTitle:" title)
       (objc:invoke button "setBezelStyle:" 1)
       (when on-click (appkit::%set-action button on-click))
       (objc:invoke (appkit::%content-view window) "addSubview:" button)
       button))))

(defun appkit::%kind-p (object class-name)
  (objc:invoke-bool object "isKindOfClass:" class-name))

(defun appkit::%buttonp (view) (appkit::%kind-p view "NSButton"))

(defun appkit::%panelp (view) (appkit::%kind-p view "NSBox"))

;; A status item is not a view at all: the menu bar draws it through a button it
;; owns, which is why set-text and text ask about it before anything else.
(defun appkit::%status-item-p (object) (appkit::%kind-p object "NSStatusItem"))

;; (appkit:on-click view (lambda (button) ...)): makes a panel or a label answer
;; a click, the handler taking the button number -- 1 for a left click, 3 for a
;; right one (or a Ctrl-click). Given a button it sets its action instead, so one
;; verb wires any widget; the button's own :on-click closure takes no argument,
;; since a button has no right click. Answers the view.
(defun appkit:on-click (view handler)
  (objc:on-main
   (lambda ()
     (if (appkit::%buttonp view)
         (appkit::%set-action view (lambda () (funcall handler 1)))
         (let ((object (objc:objc-object-from-pointer view)))
           (unless (typep object 'appkit::clickable)
             (error "appkit:on-click: ~s is neither a button nor a panel or label of appkit"
                    view))
           (setf (appkit::%click-handler object) handler)))
     view)))

;; (appkit:set-text view "text"): a status item's or button's title, any other
;; control's string value. Answers the text.
(defun appkit:set-text (view text)
  (objc:on-main
   (lambda ()
     (cond ((appkit::%status-item-p view)
            (objc:invoke (objc:invoke view "button") "setTitle:" text))
           ((appkit::%buttonp view) (objc:invoke view "setTitle:" text))
           (t (objc:invoke view "setStringValue:" text)))
     text)))

;; (appkit:set-color view color): a panel's fill colour, any other control's text
;; colour. Answers the colour.
(defun appkit:set-color (view color)
  (objc:on-main
   (lambda ()
     (if (appkit::%panelp view)
         (progn
           (objc:invoke view "setFillColor:" color)
           (objc:invoke view "setNeedsDisplay:" t))
         (objc:invoke view "setTextColor:" color))
     color)))

;; (appkit:text view) -> the status item's or button's title, or the control's
;; string value, as a Lisp string.
(defun appkit:text (view)
  (objc:on-main
   (lambda ()
     (if (appkit::%status-item-p view)
         (objc:invoke-into 'string (objc:invoke view "button") "title")
         (objc:invoke-into 'string view
          (if (appkit::%buttonp view) "title" "stringValue"))))))

;; (appkit:click button): performs the button's action as a user's click would --
;; the way a script drives a window without a human.
(defun appkit:click (button)
  (objc:on-main
   (lambda ()
     (objc:invoke button "performClick:" nil)
     nil)))

;;; --- a repeating timer ------------------------------------------------------

;; The target of a timer: the function it runs, and a nil answer is what stops
;; the clock. The timer retains its target.
(objc:define-objc-class appkit::ticker ()
  ((fn :initarg :fn :reader appkit::%ticker-fn))
  (:objc-class-name "RontoLispAppKitTimer"))

(objc:define-objc-method ("tick:" :void)
  ((self appkit::ticker) (timer objc:objc-object-pointer))
  (unless (funcall (appkit::%ticker-fn self)) (objc:invoke timer "invalidate")))

;; (appkit:timer 0.5 (lambda () ...)) -> a repeating NSTimer that calls the
;; function every SECONDS until it answers nil, which invalidates the timer. The
;; function runs on thread 0, inside AppKit's event loop, so it may touch the GUI
;; freely.
(defun appkit:timer (seconds fn)
  (objc:on-main
   (lambda ()
     (objc:invoke "NSTimer"
      "scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:"
      (* 1.0 seconds) (make-instance 'appkit::ticker :fn fn) "tick:" nil t))))

;;; --- the menu bar -----------------------------------------------------------

;; (appkit:menu (list (list "Say hi" (lambda () ...))
;;                    :separator
;;                    (list "Quit" (lambda () (appkit:quit)) "q")))
;; -> an NSMenu whose items are Lisp closures. An entry is (title handler) with
;; an optional key equivalent third, and the keyword :separator is a dividing
;; line. The handler takes no arguments and runs on thread 0, like a button's --
;; a menu item is wired exactly as a button is, its target an appkit::action.
(defun appkit:menu (items)
  (objc:on-main
   (lambda ()
     (let ((menu (objc:invoke (objc:invoke "NSMenu" "alloc") "init")))
       (dolist (entry items)
         (if (equal entry :separator)
             (objc:invoke menu "addItem:"
                          (objc:invoke "NSMenuItem" "separatorItem"))
             (let ((item
                    (objc:invoke menu "addItemWithTitle:action:keyEquivalent:"
                                 (car entry) "invoke:" (or (nth 2 entry) ""))))
               (objc:invoke item "setTarget:"
                (make-instance 'appkit::action :handler (cadr entry))))))
       menu))))

;; (appkit:status-item "title" :menu (appkit:menu ...) :dock nil) -> an
;; NSStatusItem in the system menu bar, of variable width (-1), its title drawn
;; by the button the status bar owns. :dock nil sets the accessory activation
;; policy -- no Dock icon and no app switcher entry, the shape a menu bar program
;; has -- and the default leaves the regular policy a window wants.
;;
;; KEEP THE ANSWER: outside the status bar the Lisp value owns the item's only
;; reference, so letting it be collected takes the item out of the menu bar.
(defun appkit:status-item (title &key menu (dock t))
  (appkit::%app)
  (objc:on-main
   (lambda ()
     (unless dock (objc:invoke (appkit::%app) "setActivationPolicy:" 1))
     (let ((item
            (objc:invoke (objc:invoke "NSStatusBar" "systemStatusBar")
                         "statusItemWithLength:" -1.0)))
       (objc:invoke (objc:invoke item "button") "setTitle:" title)
       (when menu (objc:invoke item "setMenu:" menu))
       item))))

;; (appkit:quit): ends the application, the way Cmd-Q does. It is the only way
;; out of a program whose whole interface is a menu bar, since there is no window
;; to close; the process does not come back, so nothing after it runs.
(defun appkit:quit ()
  (objc:on-main
   (lambda ()
     (objc:invoke (appkit::%app) "terminate:" nil)
     nil)))

;;; --- the window's life ------------------------------------------------------

;; (appkit:close window): closes (hides) the window. The Lisp value stays valid.
(defun appkit:close (window)
  (objc:on-main
   (lambda ()
     (objc:invoke window "close")
     nil)))

;; (appkit:visible-p window) -> whether the window is on screen.
(defun appkit:visible-p (window)
  (objc:on-main (lambda () (objc:invoke-bool window "isVisible"))))

;; (appkit:wait window): blocks the calling thread until the window is closed --
;; what a script does after building its window, since the process ends when
;; the program does. Never call it from a button's handler (that thread is the
;; one that would close the window). With NO window it blocks until the
;; application ends, which is what a menu bar program does after building its
;; status item: there is no window whose closing could release it, and
;; appkit:quit is the way out.
(defun appkit:wait (&optional window)
  (do ((i 0 (+ i 1)))
      ((and window (not (appkit:visible-p window))) nil)
    (sleep 0.05)))
