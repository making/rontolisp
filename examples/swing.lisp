;;;; swing.lisp -- a tiny reusable Swing helper library for rontolisp examples.
;;;;
;;;; It is written entirely on top of the generic `java:` interop package (no
;;;; bespoke Java class): a grid window is a frame holding a status label on top
;;;; and a GridLayout of one JPanel per cell in the center. Examples reuse it as
;;;; the rendering layer so their core logic stays free of any UI code.
;;;;
;;;; The helpers live in a `swing` package of their own; a program splices the
;;;; library in at compile time (idempotently, thanks to the provide below) with
;;;;
;;;;   (require :swing "swing.lisp")
;;;;
;;;; and calls the qualified names (swing:rgb ...), (swing:grid-window ...), ...
;;;;
;;;; Swing is reachable on the JVM -- interpret the program, or compile it to a
;;;; .class (the WASM backend cannot lower a java object) -- and it needs a
;;;; machine with a display. The
;;;; requiring example resolves "swing.lisp" relative to its own directory, so
;;;; run it from anywhere, e.g.:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/life-gui.lisp
;;;;
;;;; API:
;;;;   (swing:rgb r g b)                    -> a 0-255 RGB java.awt.Color
;;;;   (swing:grid-window title rows cols size) -> a window handle (shown on screen)
;;;;   (swing:cell win r c)                 -> the JPanel at (row col)
;;;;   (swing:paint win r c color)          -> set that cell's background color
;;;;   (swing:fill win color)               -> paint every cell one color
;;;;   (swing:status win text)              -> set the top status-label text
;;;;   (swing:animate delay step-fn)        -> start a repeating timer; step-fn is
;;;;                                           called with no args every `delay` ms
;;;;                                           and the timer stops when it returns nil
;;;;
;;;; For grids that need text and clicks (e.g. Minesweeper) use the label variant:
;;;;   (swing:label-grid-window title rows cols size) -> a clickable text grid
;;;;   (swing:cell-text win r c text)       -> set a cell's centred text
;;;;   (swing:cell-fg win r c color)        -> set a cell's text colour
;;;;   (swing:on-cell-click win handler)    -> handler is called (row col button)
;;;;                                           on click (button: 1 left, 3 right)

(provide :swing)

(defpackage swing
  (:use cl)
  (:export rgb grid-window label-grid-window
           cell paint fill status
           cell-text cell-fg on-cell-click
           animate))

(in-package swing)

;; A window handle is a small symbol-keyed hash table.
(defun rgb (r g b) (java:new "java.awt.Color" r g b))

(defun cell (win r c)
  (gethash (list r c) (gethash 'cells win)))

(defun paint (win r c color)
  (java:call (cell win r c) "setBackground" color))

(defun status (win text)
  (java:call (gethash 'status win) "setText" text))

(defun fill (win color)
  (let ((rows (gethash 'rows win)) (cols (gethash 'cols win)) (r 0))
    (while (< r rows)
      (let ((c 0))
        (while (< c cols)
          (paint win r c color)
          (setq c (+ c 1))))
      (setq r (+ r 1)))))

(defun grid-window (title rows cols size)
  (let ((frame (java:new "javax.swing.JFrame" title))
        (grid (java:new "javax.swing.JPanel"
                        (java:new "java.awt.GridLayout" rows cols 1 1)))
        (status (java:new "javax.swing.JLabel" " "))
        (cells (make-hash-table :test 'equal))
        (win (make-hash-table :test 'equal)))
    (java:call grid "setBackground" (rgb 120 120 120))
    ;; One JPanel per cell, in row-major order so GridLayout lays them out
    ;; left-to-right, top-to-bottom; remember each by (row col).
    (let ((r 0))
      (while (< r rows)
        (let ((c 0))
          (while (< c cols)
            (let ((cell (java:new "javax.swing.JPanel")))
              (java:call cell "setPreferredSize"
                         (java:new "java.awt.Dimension" size size))
              (java:call cell "setBackground" (rgb 255 255 255))
              (java:call grid "add" cell)
              (setf (gethash (list r c) cells) cell))
            (setq c (+ c 1))))
        (setq r (+ r 1))))
    (setf (gethash 'frame win) frame)
    (setf (gethash 'grid win) grid)
    (setf (gethash 'status win) status)
    (setf (gethash 'cells win) cells)
    (setf (gethash 'rows win) rows)
    (setf (gethash 'cols win) cols)
    (java:call frame "add" status (java:field "java.awt.BorderLayout" "NORTH"))
    (java:call frame "add" grid (java:field "java.awt.BorderLayout" "CENTER"))
    (java:call frame "setDefaultCloseOperation"
               (java:field "javax.swing.WindowConstants" "EXIT_ON_CLOSE"))
    (java:call frame "pack")
    (java:call frame "setLocationRelativeTo" nil)
    (java:call frame "setVisible" t)
    win))

;; --- clickable, text-capable grid -------------------------------------------
;;
;; grid-window's cells are blank JPanels -- perfect for a painter like Life
;; but they cannot show text or, on their own, tell a left-click from a right one.
;; The three helpers below build a grid whose cells are opaque JLabels instead
;; (JButton backgrounds are ignored by the macOS Aqua look-and-feel, so a label
;; is the portable way to get both a background colour AND centred text). The
;; window handle has the same shape, so cell / paint / status /
;; fill all work on it unchanged.

;; Like grid-window, but every cell is a centred, bold JLabel that can hold
;; text (cell-text) and receive per-cell clicks (on-cell-click).
(defun label-grid-window (title rows cols size)
  (let ((frame (java:new "javax.swing.JFrame" title))
        (grid (java:new "javax.swing.JPanel"
                        (java:new "java.awt.GridLayout" rows cols 1 1)))
        (status (java:new "javax.swing.JLabel" " "))
        (cells (make-hash-table :test 'equal))
        (win (make-hash-table :test 'equal))
        (font (java:new "java.awt.Font" "SansSerif" 1 (floor (/ (* size 6) 10)))))
    (java:call grid "setBackground" (rgb 120 120 120))
    (let ((r 0))
      (while (< r rows)
        (let ((c 0))
          (while (< c cols)
            (let ((cell (java:new "javax.swing.JLabel" "")))
              (java:call cell "setOpaque" t)
              (java:call cell "setHorizontalAlignment"
                         (java:field "javax.swing.SwingConstants" "CENTER"))
              (java:call cell "setPreferredSize"
                         (java:new "java.awt.Dimension" size size))
              (java:call cell "setFont" font)
              (java:call cell "setBackground" (rgb 255 255 255))
              (java:call grid "add" cell)
              (setf (gethash (list r c) cells) cell))
            (setq c (+ c 1))))
        (setq r (+ r 1))))
    (setf (gethash 'frame win) frame)
    (setf (gethash 'grid win) grid)
    (setf (gethash 'status win) status)
    (setf (gethash 'cells win) cells)
    (setf (gethash 'rows win) rows)
    (setf (gethash 'cols win) cols)
    (java:call frame "add" status (java:field "java.awt.BorderLayout" "NORTH"))
    (java:call frame "add" grid (java:field "java.awt.BorderLayout" "CENTER"))
    (java:call frame "setDefaultCloseOperation"
               (java:field "javax.swing.WindowConstants" "EXIT_ON_CLOSE"))
    (java:call frame "pack")
    (java:call frame "setLocationRelativeTo" nil)
    (java:call frame "setVisible" t)
    win))

;; Set the centred text of a label cell (e.g. a mine count).
(defun cell-text (win r c text)
  (java:call (cell win r c) "setText" text))

;; Set a label cell's text colour.
(defun cell-fg (win r c color)
  (java:call (cell win r c) "setForeground" color))

;; Call HANDLER with (row col button) on every click of any cell, where button
;; is the java.awt.event.MouseEvent button code (1 = left, 2 = middle, 3 = right).
;; Each cell gets its own MouseListener, a java:proxy over a lambda that closes
;; over that cell's fixed row/col.
(defun on-cell-click (win handler)
  (let ((rows (gethash 'rows win)) (cols (gethash 'cols win)) (r 0))
    (while (< r rows)
      (let ((c 0))
        (while (< c cols)
          (let ((cell (cell win r c)) (cr r) (cc c))
            (java:call cell "addMouseListener"
                       (java:proxy "java.awt.event.MouseListener"
                                   (lambda (method event)
                                     (when (equal method "mouseClicked")
                                       (funcall handler cr cc
                                                (java:call event "getButton")))))))
          (setq c (+ c 1))))
      (setq r (+ r 1)))))

;; Run step-fn every `delay` ms on the Swing event thread until it returns nil.
;; step-fn is a zero-argument rontolisp function; the javax.swing.Timer's
;; ActionListener is a java:proxy over a lambda that closes over the timer so it
;; can stop itself.
(defun animate (delay step-fn)
  (let ((timer nil))
    (setq timer
          (java:new "javax.swing.Timer" delay
                    (java:proxy "java.awt.event.ActionListener"
                                (lambda (method event)
                                  (unless (funcall step-fn)
                                    (java:call timer "stop"))))))
    (java:call timer "start")
    timer))

(in-package cl-user)
