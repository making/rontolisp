;;;; cli.lisp -- the lispresen command.
;;;;
;;;;   lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|hosted|none] [--embed] [--check] [--strict]
;;;;
;;;; Reads DECK, writes its HTML, prints diagnostics to standard error. Exit status: 0
;;;; success, 1 errors (or warnings under --strict), 2 usage. Runs unchanged on every
;;;; backend:
;;;;
;;;;   rontolisp lispresen/cli.lisp -- talk.lisp
;;;;   rontolisp lispresen/cli.lisp -o lispresen.jar && java -jar lispresen.jar talk.lisp
;;;;   rontolisp lispresen/cli.lisp -o lispresen.wasm && wasmtime run --dir . lispresen.wasm talk.lisp
;;;;
;;;; and on another Common Lisp:
;;;;
;;;;   sbcl --script lispresen/cli.lisp talk.lisp

#-rontolisp (require :asdf)

#+rontolisp (load "src/all.lisp")
#-rontolisp (load (merge-pathnames "src/all.lisp" *load-truename*))

(defparameter *usage*
  "usage: lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|hosted|none] [--embed] [--check] [--strict]
  DECK and OUT may be - for standard input / output; OUT defaults to DECK with .html.
  --theme NAME  light, dark, high-contrast or midnight, overriding the deck's :theme
  --runtime R   where runnable code loads the runtime from, overriding the deck's
                :runtime: a directory URL, hosted (the project site) or none (static code)
  --embed       inline images as data: URIs, so the HTML file stands alone
  --check       report diagnostics without writing HTML
  --strict      fail on warnings too")

(defun usage-error (message)
  (format *error-output* "lispresen: ~a~%~a~%" message *usage*)
  (finish-output *error-output*)
  (uiop:quit 2))

(defun read-standard-input ()
  (with-output-to-string (out)
    (loop for line = (read-line *standard-input* nil nil)
          while line
          do (write-line line out))))

(defun default-output (deck)
  "DECK with its extension replaced by .html."
  (let ((dot (position #\. deck :from-end t))
        (slash (position #\/ deck :from-end t)))
    (concatenate 'string
                 (if (and dot (> dot 0) (or (null slash) (> dot (1+ slash))))
                     (subseq deck 0 dot)
                     deck) ".html")))

(defun read-deck (deck)
  (if (string= deck "-")
      (read-standard-input)
      (handler-case (uiop:read-file-string deck) (error () nil))))

(defun read-octets (path)
  "The bytes of the file PATH, or nil when it cannot be read."
  (handler-case (with-open-file (s path :element-type '(unsigned-byte 8))
                  (let ((octets
                         (make-array (file-length s)
                                     :element-type '(unsigned-byte 8))))
                    (read-sequence octets s)
                    octets))
    (error () nil)))

(defun file-reader (deck)
  "A function from a path DECK names to the file's bytes; a relative path is relative to
DECK's directory, or to the working directory when DECK is standard input."
  (let* ((slash (position #\/ deck :from-end t))
         (dir
          (if (and slash (string/= deck "-")) (subseq deck 0 (1+ slash)) "")))
    (lambda (src)
      (read-octets
       (if (and (> (length src) 0) (char= (char src 0) #\/))
           src
           (concatenate 'string dir src))))))

(defun write-output (html target)
  (if (string= target "-")
      (progn
        (write-string html)
        (finish-output))
      (with-open-file (s target
                         :direction :output
                         :if-exists :supersede
                         :if-does-not-exist :create)
        (write-string html s))))

(defun main (args)
  (let ((deck nil)
        (out nil)
        (theme nil)
        (runtime nil)
        (embed nil)
        (check nil)
        (strict nil))
    (loop while args
          do
            (let ((a (pop args)))
              (cond ((string= a "-o")
                     (unless args (usage-error "-o needs a file"))
                     (setf out (pop args)))
                    ((string= a "--theme")
                     (unless args (usage-error "--theme needs a name"))
                     (setf theme (pop args)))
                    ((string= a "--runtime")
                     (unless args
                       (usage-error "--runtime needs a URL, hosted or none"))
                     (setf runtime (pop args)))
                    ((string= a "--embed") (setf embed t))
                    ((string= a "--check") (setf check t))
                    ((string= a "--strict") (setf strict t))
                    ((string= a "--help")
                     (write-line *usage*)
                     (uiop:quit 0))
                    ((and (> (length a) 1) (char= (char a 0) #\-))
                     (usage-error (format nil "unknown option ~a" a)))
                    (deck (usage-error "give one deck"))
                    (t (setf deck a)))))
    (unless deck (usage-error "no deck given"))
    (let ((source (read-deck deck)))
      (unless source
        (format *error-output* "lispresen: cannot read ~a~%" deck)
        (finish-output *error-output*)
        (uiop:quit 1))
      (multiple-value-bind (html diagnostics) (lispresen:render source
                                                                :theme theme
                                                                :runtime runtime
                                                                :read-file
                                                                (file-reader
                                                                 deck)
                                                                :embed embed)
        (let ((name (if (string= deck "-") "<stdin>" deck))
              (errors 0)
              (warnings 0))
          (dolist (d diagnostics)
            (if (eq (lispresen:diagnostic-severity d) :error)
                (incf errors)
                (incf warnings))
            (write-line (lispresen:format-diagnostic name d) *error-output*))
          (finish-output *error-output*)
          (unless check
            (write-output html
             (or out (if (string= deck "-") "-" (default-output deck)))))
          (uiop:quit
           (if (or (> errors 0) (and strict (> warnings 0))) 1 0)))))))

(main (uiop:command-line-arguments))
