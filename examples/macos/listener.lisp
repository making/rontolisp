;;;; listener.lisp -- a Lisp listener in a Cocoa window, the way Clozure CL's IDE
;;;; does it: the window, the transcript and the evaluator are the same running
;;;; image, so a form typed into the window can build more of the window.
;;;;
;;;; A text view for the transcript, a text field whose Return key is a Lisp
;;;; closure, and `eval` on what it reads. Nothing here is an application: no nib,
;;;; no bundle, no Objective-C source file. The text field's target is an
;;;; Objective-C class defined AT RUN TIME whose method body is Lisp
;;;; (objc:define-objc-class and objc:define-objc-method), which is the whole of
;;;; Clozure's bridge trick, and the evaluator is the interpreter that is reading
;;;; this file.
;;;;
;;;; Type into the window and press Return:
;;;;
;;;;   (+ 1 2)                                       ; => 3
;;;;   (defun sq (x) (* x x))                        ; then (sq 12)
;;;;   (dotimes (i 3) (print i))                     ; printed output is captured
;;;;   (objc:invoke-into 'string (objc:string-to-ns-string "hi") "uppercaseString")
;;;;   (appkit:window "a second window")             ; the app extends itself
;;;;   (objc:invoke *window* "setTitle:" "renamed from inside")
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

(defvar *font* (objc:invoke "NSFont" "userFixedPitchFontOfSize:" 13.0))

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
            (objc:invoke (objc:invoke "NSScrollView" "alloc") "initWithFrame:"
                         (vector 16 64 688 436)))
           (view
            (objc:invoke (objc:invoke "NSTextView" "alloc") "initWithFrame:"
                         (vector 0 0 688 436))))
       (objc:invoke view "setEditable:" nil)
       (objc:invoke view "setFont:" *font*)
       (objc:invoke view "setTextContainerInset:" (vector 8.0 8.0))
       ;; Grow with the text, not with the window's width: the text view tracks
       ;; the scroll view's width and is unbounded downwards.
       (objc:invoke view "setVerticallyResizable:" t)
       (objc:invoke view "setHorizontallyResizable:" nil)
       (objc:invoke view "setMinSize:" (vector 0.0 0.0))
       (objc:invoke view "setMaxSize:" (vector 1.0e7 1.0e7))
       (objc:invoke view "setAutoresizingMask:" 2)
       (objc:invoke (objc:invoke view "textContainer") "setWidthTracksTextView:"
                    t)
       (objc:invoke scroll "setHasVerticalScroller:" t)
       ;; 18 = NSViewWidthSizable | NSViewHeightSizable: the transcript takes up
       ;; whatever the window is resized to.
       (objc:invoke scroll "setAutoresizingMask:" 18)
       (objc:invoke scroll "setBorderType:" 2)
       (objc:invoke scroll "setDocumentView:" view)
       (objc:invoke (objc:invoke *window* "contentView") "addSubview:" scroll)
       view))))

(defun say (line)
  (setq *transcript-text*
        (concatenate 'string *transcript-text* line (string #\Newline)))
  (objc:on-main
   (lambda ()
     (objc:invoke *transcript* "setString:" *transcript-text*)
     (objc:invoke *transcript* "scrollToEndOfDocument:" nil)
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
;;; a target object -- so the target is an instance of a class defined at run
;;; time whose invoke: method calls submit below. The Eval button hands its click
;;; to the same function through appkit:button, which arranges the same thing for
;;; itself.

(defvar *input*
  (objc:on-main
   (lambda ()
     (let ((field
            (objc:invoke (objc:invoke "NSTextField" "alloc") "initWithFrame:"
                         (vector 16 20 560 28))))
       (objc:invoke field "setFont:" *font*)
       (objc:invoke field "setPlaceholderString:" "a form, then Return")
       ;; 34 = NSViewWidthSizable | NSViewMaxYMargin: pinned to the bottom edge,
       ;; as wide as the window.
       (objc:invoke field "setAutoresizingMask:" 34)
       (objc:invoke (objc:invoke *window* "contentView") "addSubview:" field)
       field))))

(defun submit ()
  (let ((text (appkit:text *input*)))
    (unless (string= (string-trim " " text) "")
      (appkit:set-text *input* "")
      (say (concatenate 'string "> " text))
      (evaluate text))))

(objc:define-objc-class prompt-target ()
  ()
  (:objc-class-name "RontoLispListenerPrompt"))

(objc:define-objc-method ("invoke:" :void)
  ((self prompt-target) (sender objc:objc-object-pointer))
  (declare (ignore sender))
  (submit))

;; AppKit holds a control's target weakly; this variable is what keeps it alive.
(defvar *prompt-target* (make-instance 'prompt-target))

(objc:on-main
 (lambda ()
   (objc:invoke *input* "setTarget:" *prompt-target*)
   (objc:invoke *input* "setAction:" "invoke:")
   (objc:invoke *window* "makeFirstResponder:" *input*)
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
   (objc:invoke *eval-button* "setAutoresizingMask:" 33)
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
