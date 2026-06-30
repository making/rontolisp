;;;; swing.lisp -- a tiny reusable Swing helper library for rontolisp examples.
;;;;
;;;; It is written entirely on top of the generic `java:` interop package (no
;;;; bespoke Java class): a grid window is a frame holding a status label on top
;;;; and a GridLayout of one JPanel per cell in the center. Examples reuse it as
;;;; the rendering layer so their core logic stays free of any UI code.
;;;;
;;;; Swing is reachable only on the interpreter (the JVM-class and WASM backends
;;;; cannot lower a java object), and only on a machine with a display. The
;;;; loading example resolves "swing.lisp" relative to its own directory, so run
;;;; it from anywhere, e.g.:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/life-gui.lisp
;;;;
;;;; API:
;;;;   (swing-rgb r g b)                    -> a 0-255 RGB java.awt.Color
;;;;   (swing-grid-window title rows cols size) -> a window handle (shown on screen)
;;;;   (swing-cell win r c)                 -> the JPanel at (row col)
;;;;   (swing-paint win r c color)          -> set that cell's background color
;;;;   (swing-fill win color)               -> paint every cell one color
;;;;   (swing-status win text)              -> set the top status-label text
;;;;   (swing-animate delay step-fn)        -> start a repeating timer; step-fn is
;;;;                                           called with no args every `delay` ms
;;;;                                           and the timer stops when it returns nil

;; A window handle is a small symbol-keyed hash table.
(defun swing-rgb (r g b) (java:new "java.awt.Color" r g b))

(defun swing-cell (win r c)
  (gethash (list r c) (gethash 'cells win)))

(defun swing-paint (win r c color)
  (java:call (swing-cell win r c) "setBackground" color))

(defun swing-status (win text)
  (java:call (gethash 'status win) "setText" text))

(defun swing-fill (win color)
  (let ((rows (gethash 'rows win)) (cols (gethash 'cols win)) (r 0))
    (while (< r rows)
      (let ((c 0))
        (while (< c cols)
          (swing-paint win r c color)
          (setq c (+ c 1))))
      (setq r (+ r 1)))))

(defun swing-grid-window (title rows cols size)
  (let ((frame (java:new "javax.swing.JFrame" title))
        (grid (java:new "javax.swing.JPanel"
                        (java:new "java.awt.GridLayout" rows cols 1 1)))
        (status (java:new "javax.swing.JLabel" " "))
        (cells (make-hash-table :test 'equal))
        (win (make-hash-table :test 'equal)))
    (java:call grid "setBackground" (swing-rgb 120 120 120))
    ;; One JPanel per cell, in row-major order so GridLayout lays them out
    ;; left-to-right, top-to-bottom; remember each by (row col).
    (let ((r 0))
      (while (< r rows)
        (let ((c 0))
          (while (< c cols)
            (let ((cell (java:new "javax.swing.JPanel")))
              (java:call cell "setPreferredSize"
                         (java:new "java.awt.Dimension" size size))
              (java:call cell "setBackground" (swing-rgb 255 255 255))
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

;; Run step-fn every `delay` ms on the Swing event thread until it returns nil.
;; step-fn is a zero-argument rontolisp function; the javax.swing.Timer's
;; ActionListener is a java:proxy over a lambda that closes over the timer so it
;; can stop itself.
(defun swing-animate (delay step-fn)
  (let ((timer nil))
    (setq timer
          (java:new "javax.swing.Timer" delay
                    (java:proxy "java.awt.event.ActionListener"
                                (lambda (method event)
                                  (unless (funcall step-fn)
                                    (java:call timer "stop"))))))
    (java:call timer "start")
    timer))
