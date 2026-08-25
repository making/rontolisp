;;;; cocoa.lisp -- a tiny reusable AppKit helper library for rontolisp examples.
;;;;
;;;; The macOS counterpart of examples/jvm/swing.lisp: where that one builds a
;;;; window out of the generic `java:` package, this one builds a window out of
;;;; the generic `objc:` package and the built-in `appkit` widget layer -- no
;;;; bespoke Objective-C class, no bundled native code. Examples reuse it as the
;;;; rendering layer so their core logic stays free of any UI code.
;;;;
;;;; The helpers live in a `cocoa` package of their own; a program splices the
;;;; library in (idempotently, thanks to the provide below) with
;;;;
;;;;   (require :cocoa "cocoa.lisp")
;;;;
;;;; and calls the qualified names (cocoa:rgb ...), (cocoa:window ...), ...
;;;;
;;;; AppKit is reachable on macOS with a display -- under `java -jar`, in the
;;;; `rontolisp` native binary (which is what `java:` interop cannot do) and in a
;;;; compiled JVM class alike; only WASM refuses, having no foreign function API
;;;; (doc/en/guides/objc-appkit.md). The requiring example resolves "cocoa.lisp"
;;;; relative to its own directory, so run it from anywhere.
;;;;
;;;; Two things AppKit asks of a caller and this library hides:
;;;;
;;;;   * Coordinates are AppKit's -- the origin is the window's BOTTOM-left
;;;;     corner and y grows upwards. Only `grid` flips them, so that its row 0 is
;;;;     the top row, the way a board is written down.
;;;;   * An NSTextField draws its string inside its own frame, so a label given a
;;;;     tall frame sits at the top of it. Every text helper here therefore takes
;;;;     the RECTANGLE the text should be centred in and sizes the field to the
;;;;     font, which is what makes a grid cell's digit land in the middle.
;;;;
;;;; API:
;;;;   (cocoa:rgb r g b)                     -> a 0-255 RGB NSColor
;;;;   (cocoa:font size &key bold)           -> an NSFont
;;;;   (cocoa:window title width height &key background dark) -> a window handle
;;;;   (cocoa:ns-window win)                 -> the NSWindow itself
;;;;   (cocoa:panel win x y w h &key fill radius border border-color) -> an NSBox
;;;;   (cocoa:text win s x y w h &key size color align bold) -> a centred NSTextField
;;;;   (cocoa:set-text view s)               -> replace a label's string
;;;;   (cocoa:clickable view handler)        -> handler is called (button) on click
;;;;   (cocoa:grid win rows cols &key size gap x y font-size radius) -> the cells
;;;;   (cocoa:cell win r c)                  -> the NSBox behind cell (row col)
;;;;   (cocoa:cell-label win r c)            -> the NSTextField on top of it
;;;;   (cocoa:paint win r c color)           -> set that cell's fill colour
;;;;   (cocoa:cell-text win r c text)        -> set that cell's centred text
;;;;   (cocoa:cell-fg win r c color)         -> set that cell's text colour
;;;;   (cocoa:on-cell-click win handler)     -> handler is called (row col button)
;;;;                                            on click (button: 1 left, 3 right)
;;;;   (cocoa:animate seconds fn)            -> repeating NSTimer; stops when fn
;;;;                                            answers nil
;;;;   (cocoa:wait win)                      -> block until the window is closed

(provide :cocoa)

(defpackage cocoa
  (:use cl)
  (:export rgb font window ns-window panel text set-text clickable grid cell
           cell-label paint cell-text cell-fg on-cell-click animate wait))

(in-package cocoa)

;;; --- clicks -----------------------------------------------------------------
;;;
;;; AppKit delivers a click to the view under the pointer, so every view this
;;; library creates is an instance of one of two classes defined AT RUN TIME
;;; (objc:define-class) whose mouseDown: / rightMouseDown: are Lisp functions.
;;; Both look the receiver up by address in one table, so a cell's box and the
;;; label drawn over it can share a handler and the whole tile is live -- there
;;; is no event forwarding to arrange.

;; View address -> the (lambda (button) ...) registered for it.
(defvar *handlers* (make-hash-table))

(defvar *tile-class* nil)
(defvar *label-class* nil)
(defvar *timer-class* nil)
(defvar *timer-target* nil)

;; 1 for a left click, 3 for a right click -- the java.awt.event button numbers,
;; so a handler written for the swing library reads the same here.
(defun %dispatch (self button)
  (let ((handler (gethash (objc:address self) *handlers*)))
    (when handler (funcall handler button))
    nil))

(defun %tile-class ()
  (or *tile-class*
      (setq *tile-class*
            (objc:define-class "RontoLispCocoaTile"
              "NSBox"
              (list (list "mouseDown:" (lambda (self event) (%dispatch self 1)))
                    (list "rightMouseDown:"
                          (lambda (self event) (%dispatch self 3))))))))

(defun %label-class ()
  (or *label-class*
      (setq *label-class*
            (objc:define-class "RontoLispCocoaLabel"
              "NSTextField"
              (list (list "mouseDown:" (lambda (self event) (%dispatch self 1)))
                    (list "rightMouseDown:"
                          (lambda (self event) (%dispatch self 3))))))))

;; Make any view built here answer clicks. HANDLER takes the button number.
(defun clickable (view handler)
  (setf (gethash (objc:address view) *handlers*) handler)
  view)

;;; --- colours and fonts ------------------------------------------------------

(defun rgb (r g b)
  (objc:send "NSColor" "colorWithRed:green:blue:alpha:" (/ r 255.0) (/ g 255.0)
             (/ b 255.0) 1.0))

(defun font (size &key (bold t))
  (objc:send "NSFont" (if bold "boldSystemFontOfSize:" "systemFontOfSize:")
             (* 1.0 size)))

;; The height one line of FNT needs, measured once per font by asking a throwaway
;; field to size itself to its content. This is what lets `text` centre a string
;; vertically in a rectangle (see the note at the top of the file).
(defvar *line-heights* (make-hash-table))

(defun %line-height (fnt)
  (let ((key (objc:address fnt)))
    (or (gethash key *line-heights*)
        (setf (gethash key *line-heights*)
              (objc:on-main
               (lambda ()
                 (let ((probe
                        (objc:send "NSTextField" "labelWithString:" "8gjM")))
                   (objc:send probe "setFont:" fnt)
                   (objc:send probe "sizeToFit")
                   (nth 3 (objc:send probe "frame")))))))))

;;; --- window -----------------------------------------------------------------

;; A window handle is a small symbol-keyed hash table, like the swing library's.
(defun window (title width height &key background (dark nil))
  (let ((win (make-hash-table :test 'equal))
        (nswin (appkit:window title :width width :height height)))
    (setf (gethash 'window win) nswin)
    (setf (gethash 'view win) (objc:send nswin "contentView"))
    (objc:on-main
     (lambda ()
       ;; A dark window wants a dark title bar too, or the traffic lights sit on
       ;; a light strip above the content.
       (when dark
         (objc:send nswin "setAppearance:"
                    (objc:send "NSAppearance" "appearanceNamed:"
                               "NSAppearanceNameDarkAqua"))
         (objc:send nswin "setTitlebarAppearsTransparent:" t))
       (when background (objc:send nswin "setBackgroundColor:" background))
       nil))
    win))

(defun ns-window (win) (gethash 'window win))

(defun wait (win) (appkit:wait (gethash 'window win)))

;;; --- views ------------------------------------------------------------------

;; A filled, optionally rounded and bordered rectangle: NSBox in its custom form
;; (box type 4, no title) is the shortest way to one in AppKit.
(defun panel (win x y w h &key fill (radius 0) (border 0) border-color)
  (objc:on-main
   (lambda ()
     (let ((box
            (objc:send (objc:send (%tile-class) "alloc") "initWithFrame:"
                       (list x y w h))))
       (objc:send box "setBoxType:" 4)
       (objc:send box "setTitlePosition:" 0)
       (objc:send box "setCornerRadius:" (* 1.0 radius))
       (objc:send box "setBorderWidth:" (* 1.0 border))
       (when fill (objc:send box "setFillColor:" fill))
       (when border-color (objc:send box "setBorderColor:" border-color))
       (objc:send (gethash 'view win) "addSubview:" box)
       box))))

(defun fill-color (view color)
  (objc:on-main
   (lambda ()
     (objc:send view "setFillColor:" color)
     (objc:send view "setNeedsDisplay:" t)
     nil)))

;; A non-editable label showing S, centred in the rectangle (x y w h).
;; ALIGN is NSTextAlignment, which on Apple silicon takes the iOS values:
;; 0 left, 1 centre, 2 right.
(defun text (win s x y w h &key (size 13) color (align 1) (bold t))
  (objc:on-main
   (lambda ()
     (let* ((fnt (font size :bold bold))
            (line (%line-height fnt))
            (field
             (objc:send (objc:send (%label-class) "alloc") "initWithFrame:"
                        (list x (+ y (/ (- h line) 2.0)) w line))))
       (objc:send field "setFont:" fnt)
       (objc:send field "setEditable:" nil)
       (objc:send field "setSelectable:" nil)
       (objc:send field "setBezeled:" nil)
       (objc:send field "setBordered:" nil)
       (objc:send field "setDrawsBackground:" nil)
       (objc:send field "setAlignment:" align)
       (objc:send field "setStringValue:" s)
       (when color (objc:send field "setTextColor:" color))
       (objc:send (gethash 'view win) "addSubview:" field)
       field))))

(defun set-text (view s)
  (objc:on-main
   (lambda ()
     (objc:send view "setStringValue:" s)
     s)))

(defun set-color (view color)
  (objc:on-main
   (lambda ()
     (objc:send view "setTextColor:" color)
     nil)))

;;; --- the grid ---------------------------------------------------------------

;; ROWS x COLS square cells of SIZE pixels with GAP between them, the block's
;; bottom-left corner at (x y). Each cell is a rounded box with a centred label
;; over it; row 0 is the TOP row (AppKit's y grows upwards, so the rows are laid
;; out in reverse). The cells are remembered in WIN by (row col).
(defun grid
    (win rows cols &key (size 34) (gap 4) (x 0) (y 0) (font-size 19) (radius 6))
  (let ((cells (make-hash-table :test 'equal))
        (texts (make-hash-table :test 'equal)))
    (objc:on-main
     (lambda ()
       (dotimes (r rows)
         (dotimes (c cols)
           (let ((cx (+ x (* c (+ size gap))))
                 (cy (+ y (* (- rows 1 r) (+ size gap)))))
             (setf (gethash (list r c) cells)
                   (panel win cx cy size size :radius radius))
             (setf (gethash (list r c) texts)
                   (text win "" cx cy size size :size font-size)))))
       nil))
    (setf (gethash 'rows win) rows)
    (setf (gethash 'cols win) cols)
    (setf (gethash 'cells win) cells)
    (setf (gethash 'labels win) texts)
    cells))

(defun cell (win r c) (gethash (list r c) (gethash 'cells win)))

(defun cell-label (win r c) (gethash (list r c) (gethash 'labels win)))

(defun paint (win r c color) (fill-color (cell win r c) color))

(defun cell-text (win r c s) (set-text (cell-label win r c) s))

(defun cell-fg (win r c color) (set-color (cell-label win r c) color))

;; Call HANDLER with (row col button) on every click of any cell. The box and the
;; label of a cell share the handler, so the whole tile is live.
(defun on-cell-click (win handler)
  (dotimes (r (gethash 'rows win))
    (dotimes (c (gethash 'cols win))
      (let ((cr r) (cc c))
        (clickable (cell win r c)
                   (lambda (button) (funcall handler cr cc button)))
        (clickable (cell-label win r c)
                   (lambda (button) (funcall handler cr cc button)))))))

;;; --- a repeating timer ------------------------------------------------------

(defun %timer-target ()
  (or *timer-target*
      (let ((cls
             (objc:define-class "RontoLispCocoaTimer"
               "NSObject"
               (list
                (list "invoke:"
                      (lambda (self timer)
                        (let ((handler
                               (gethash (objc:address timer) *handlers*)))
                          (unless (and handler (funcall handler 0))
                            (objc:send timer "invalidate")))
                        nil))))))
        (setq *timer-class* cls)
        (setq *timer-target* (objc:send (objc:send cls "alloc") "init"))
        *timer-target*)))

;; Run FN (no arguments) every SECONDS on the main thread until it answers nil.
;; The mirror of swing:animate; FN runs inside AppKit's event loop, so it may
;; touch the GUI freely.
(defun animate (seconds fn)
  (objc:on-main
   (lambda ()
     (let ((timer
            (objc:send "NSTimer"
             "scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:"
             (* 1.0 seconds) (%timer-target) "invoke:" nil t)))
       (setf (gethash (objc:address timer) *handlers*)
             (lambda (button) (funcall fn)))
       timer))))

(in-package cl-user)
