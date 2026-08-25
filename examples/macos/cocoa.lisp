;;;; cocoa.lisp -- a clickable AppKit grid for rontolisp examples.
;;;;
;;;; The macOS counterpart of the `swing` library's label-grid-window
;;;; (examples/jvm/swing.lisp): a board of square tiles, each a rounded panel
;;;; with a centred label over it, every one of them live to a click. It is
;;;; board-game POLICY, which is why it is an example and not part of the
;;;; interpreter: the rungs it stands on -- a colour, a panel, a centred label, a
;;;; click, a timer -- are the built-in `appkit` package, so this file is the ten
;;;; lines above them that a game happens to want.
;;;;
;;;; The helpers live in a `cocoa` package of their own; a program splices the
;;;; library in (idempotently, thanks to the provide below) with
;;;;
;;;;   (require :cocoa "cocoa.lisp")
;;;;
;;;; and calls the qualified names (cocoa:grid ...), (cocoa:paint ...), ...
;;;;
;;;; AppKit is reachable on macOS with a display -- under `java -jar`, in the
;;;; `rontolisp` native binary (which is what `java:` interop cannot do) and in a
;;;; compiled JVM class alike; only WASM refuses, having no foreign function API
;;;; (doc/en/guides/objc-appkit.md). The requiring example resolves "cocoa.lisp"
;;;; relative to its own directory, so run it from anywhere.
;;;;
;;;; Coordinates are AppKit's -- the origin is the window's BOTTOM-left corner
;;;; and y grows upwards -- and the grid is the one place that flips them, so
;;;; that its row 0 is the top row, the way a board is written down.
;;;;
;;;; API:
;;;;   (cocoa:grid win rows cols &key size gap x y font-size radius labels)
;;;;                                         -> a grid
;;;;   (cocoa:cell g r c)                    -> the NSBox at (row col)
;;;;   (cocoa:cell-label g r c)              -> the NSTextField on top of it
;;;;   (cocoa:paint g r c color)             -> set that cell's fill colour
;;;;   (cocoa:cell-text g r c text)          -> set that cell's centred text
;;;;   (cocoa:cell-fg g r c color)           -> set that cell's text colour
;;;;   (cocoa:on-cell-click g handler)       -> handler is called (row col button)
;;;;                                            on click (button: 1 left, 3 right)

(provide :cocoa)

(defpackage cocoa
  (:use cl)
  (:export grid cell cell-label paint cell-text cell-fg on-cell-click))

(in-package cocoa)

;; ROWS x COLS square cells of SIZE pixels with GAP between them, the block's
;; bottom-left corner at (x y). Each cell is a rounded appkit:panel with a centred
;; appkit:label over it; row 0 is the TOP row (AppKit's y grows upwards, so the
;; rows are laid out in reverse). The whole board is built inside one hop to
;; thread 0, and the cells are remembered by (row col) in the handle answered.
;;
;; A board that shows no text -- a Life world, say -- passes :labels nil and gets
;; the panels alone; cell-text and cell-fg are then no-ops.
(defun grid (win rows cols &key (size 34) (gap 4) (x 0) (y 0) (font-size 19)
                 (radius 6) (labels t))
  (let ((g (make-hash-table :test 'equal))
        (cells (make-hash-table :test 'equal))
        (texts (make-hash-table :test 'equal)))
    (objc:on-main
     (lambda ()
       (dotimes (r rows)
         (dotimes (c cols)
           (let ((cx (+ x (* c (+ size gap))))
                 (cy (+ y (* (- rows 1 r) (+ size gap)))))
             (setf (gethash (list r c) cells)
                   (appkit:panel win
                                 :x cx
                                 :y cy
                                 :width size
                                 :height size
                                 :radius radius))
             (when labels
               (setf (gethash (list r c) texts)
                     (appkit:label win ""
                                   :x cx
                                   :y cy
                                   :width size
                                   :height size
                                   :size font-size
                                   :align :center
                                   :bold t))))))
       nil))
    (setf (gethash 'rows g) rows)
    (setf (gethash 'cols g) cols)
    (setf (gethash 'cells g) cells)
    (setf (gethash 'labels g) texts)
    g))

(defun cell (g r c) (gethash (list r c) (gethash 'cells g)))

(defun cell-label (g r c) (gethash (list r c) (gethash 'labels g)))

(defun paint (g r c color) (appkit:set-color (cell g r c) color))

(defun cell-text (g r c s)
  (let ((label (cell-label g r c))) (when label (appkit:set-text label s))))

(defun cell-fg (g r c color)
  (let ((label (cell-label g r c)))
    (when label (appkit:set-color label color))))

;; Call HANDLER with (row col button) on every click of any cell. The panel and
;; the label of a cell share the handler, so the whole tile is live.
(defun on-cell-click (g handler)
  (dotimes (r (gethash 'rows g))
    (dotimes (c (gethash 'cols g))
      (let ((cr r) (cc c))
        (appkit:on-click (cell g r c)
                         (lambda (button) (funcall handler cr cc button)))
        (let ((label (cell-label g r c)))
          (when label
            (appkit:on-click label
             (lambda (button) (funcall handler cr cc button)))))))))

(in-package cl-user)
