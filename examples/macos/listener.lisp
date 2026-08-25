;;;; listener.lisp -- a Lisp listener in a Cocoa window, the way Clozure CL's IDE
;;;; does it: the window, the transcript and the evaluator are the same running
;;;; image, so a form typed into the window can build more of the window.
;;;;
;;;; A text view for the transcript, a text field whose Return key is a Lisp
;;;; closure, and `eval` on what it reads. Nothing here is an application: no nib,
;;;; no bundle, no Objective-C source file. The text field's action is an
;;;; Objective-C class defined AT RUN TIME whose method is a Lisp lambda
;;;; (objc:define-class), which is the whole of Clozure's bridge trick, and the
;;;; evaluator is the interpreter that is reading this file.
;;;;
;;;; Type into the window and press Return:
;;;;
;;;;   (+ 1 2)                                       ; => 3
;;;;   (defun sq (x) (* x x))                        ; then (sq 12)
;;;;   (dotimes (i 3) (print i))                     ; printed output is captured
;;;;   (objc:send (objc:string "hi") "uppercaseString")
;;;;   (appkit:window "a second window")             ; the app extends itself
;;;;   (objc:send *window* "setTitle:" "renamed from inside")
;;;;
;;;; macOS only, on the interpreter -- under `java -jar` AND in the `rontolisp`
;;;; native binary, which is what `java:` interop cannot do -- and compiled to a
;;;; JVM class or jar; never as WASM. It needs a display, so it is not in
;;;; examples.yaml.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/listener.lisp
;;;;   ./target/rontolisp examples/macos/listener.lisp
;;;;   ./target/rontolisp examples/macos/listener.lisp -o Listener.class --class-name Listener && java Listener
;;;;   ./target/rontolisp examples/macos/listener.lisp -o listener.jar && java -jar listener.jar

(defvar *window* (appkit:window "rontolisp listener" :width 720 :height 520))

(defvar *font* (objc:send "NSFont" "userFixedPitchFontOfSize:" 13.0))

;;; --- the transcript ---------------------------------------------------------
;;;
;;; An NSTextView inside an NSScrollView, the standard pair: the scroll view owns
;;; the visible rectangle, the text view grows downwards inside it. The text view
;;; is not editable -- it is a transcript, not an editor -- and the whole text is
;;; kept in a Lisp string, so appending a line is a string and one setString:.

(defvar *transcript-text* "")

(defvar *transcript*
  (objc:on-main
   (lambda ()
     (let ((scroll
            (objc:send (objc:send "NSScrollView" "alloc") "initWithFrame:"
                       (list 16 64 688 436)))
           (view
            (objc:send (objc:send "NSTextView" "alloc") "initWithFrame:"
                       (list 0 0 688 436))))
       (objc:send view "setEditable:" nil)
       (objc:send view "setFont:" *font*)
       (objc:send view "setTextContainerInset:" (list 8.0 8.0))
       ;; Grow with the text, not with the window's width: the text view tracks
       ;; the scroll view's width and is unbounded downwards.
       (objc:send view "setVerticallyResizable:" t)
       (objc:send view "setHorizontallyResizable:" nil)
       (objc:send view "setMinSize:" (list 0.0 0.0))
       (objc:send view "setMaxSize:" (list 1.0e7 1.0e7))
       (objc:send view "setAutoresizingMask:" 2)
       (objc:send (objc:send view "textContainer") "setWidthTracksTextView:" t)
       (objc:send scroll "setHasVerticalScroller:" t)
       ;; 18 = NSViewWidthSizable | NSViewHeightSizable: the transcript takes up
       ;; whatever the window is resized to.
       (objc:send scroll "setAutoresizingMask:" 18)
       (objc:send scroll "setBorderType:" 2)
       (objc:send scroll "setDocumentView:" view)
       (objc:send (objc:send *window* "contentView") "addSubview:" scroll)
       view))))

(defun say (line)
  (setq *transcript-text*
        (concatenate 'string *transcript-text* line (string #\Newline)))
  (objc:on-main
   (lambda ()
     (objc:send *transcript* "setString:" *transcript-text*)
     (objc:send *transcript* "scrollToEndOfDocument:" nil)
     nil)))

;;; --- the evaluator ----------------------------------------------------------
;;;
;;; What a listener owes the typist: the value, whatever the form printed on the
;;; way there, and an error as text instead of a dead process. `read-from-string`
;;; and `eval` are the two halves the reader and the interpreter already export,
;;; `with-output-to-string` captures the printing, and `handler-case` catches
;;; everything a bad form can signal -- including the read itself, so an
;;; unbalanced paren is a message and not a crash.

(defun split-lines (text)
  (let ((lines nil) (start 0))
    (dotimes (i (length text))
      (when (char= (char text i) #\Newline)
        (push (subseq text start i) lines)
        (setq start (+ i 1))))
    (when (< start (length text)) (push (subseq text start) lines))
    (reverse lines)))

(defun evaluate (text)
  (let ((value nil) (failed nil) (output nil))
    (setq output
          (with-output-to-string (stream)
            (let ((*standard-output* stream))
              (handler-case (setq value (eval (read-from-string text)))
                (error (condition)
                  (setq failed t)
                  (setq value condition))))))
    ;; Printed output arrives as one string; the transcript is a list of lines.
    (dolist (line (split-lines output)) (say line))
    (if failed
        (say (format nil "; Error: ~a" value))
        (say (prin1-to-string value)))))

;;; --- the prompt -------------------------------------------------------------
;;;
;;; An editable NSTextField. Its Return key is its ACTION, which AppKit sends to
;;; a target object -- so the target is a class defined at run time whose
;;; invoke: is the closure below. The Eval button hands its click to the same
;;; closure through appkit:button, which arranges the same thing for itself.

(defvar *input*
  (objc:on-main
   (lambda ()
     (let ((field
            (objc:send (objc:send "NSTextField" "alloc") "initWithFrame:"
                       (list 16 20 560 28))))
       (objc:send field "setFont:" *font*)
       (objc:send field "setPlaceholderString:" "a form, then Return")
       ;; 34 = NSViewWidthSizable | NSViewMaxYMargin: pinned to the bottom edge,
       ;; as wide as the window.
       (objc:send field "setAutoresizingMask:" 34)
       (objc:send (objc:send *window* "contentView") "addSubview:" field)
       field))))

(defun submit ()
  (let ((text (appkit:text *input*)))
    (unless (string= (string-trim " " text) "")
      (appkit:set-text *input* "")
      (say (concatenate 'string "> " text))
      (evaluate text))))

(defvar *prompt-class*
  (objc:define-class "RontoLispListenerPrompt"
    "NSObject"
    (list
     (list "invoke:"
           (lambda (self sender)
             (submit)
             nil)))))

(defvar *prompt-target* (objc:send (objc:send *prompt-class* "alloc") "init"))

(objc:on-main
 (lambda ()
   (objc:send *input* "setTarget:" *prompt-target*)
   (objc:send *input* "setAction:" "invoke:")
   (objc:send *window* "makeFirstResponder:" *input*)
   nil))

(defvar *eval-button*
  (appkit:button *window* "Eval"
                 :x 584
                 :y 18
                 :width 120
                 :height 32
                 :on-click (lambda () (submit))))

;; 33 = NSViewMinXMargin | NSViewMaxYMargin: pinned to the bottom-right corner.
(objc:on-main
 (lambda ()
   (objc:send *eval-button* "setAutoresizingMask:" 33)
   nil))

;;; --- the banner -------------------------------------------------------------

(say ";; rontolisp listener -- the image you are typing into is the one that")
(say ";; built this window. Try:")
(say ";;   (+ 1 2)")
(say ";;   (dotimes (i 3) (print i))")
(say ";;   (appkit:window \"a second window\")")
(say "")

;; A script's process ends when its last form returns, so wait for the close.
(appkit:wait *window*)
(format t "listener closed~%")
