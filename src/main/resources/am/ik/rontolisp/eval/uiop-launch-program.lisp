;;;; uiop/launch-program -- escaping a command line for a shell.
;;;;
;;;; The escape family is upstream's, and pure string work on every backend: it
;;;; quotes a token or a whole command for /bin/sh or for a Windows command line
;;;; and starts nothing. Upstream's private helpers (requires-escaping-p,
;;;; easy-windows-character-p and the two within-double-quotes escapers) are
;;;; local functions of their one caller each, since a resource defines only
;;;; what uiop exports (.kb/uiop.md). An escaper argument is any function
;;;; designator, as upstream's.
;;;;
;;;; launch-program and the process-info family stay stubs: no backend hands a
;;;; running child back to the program. uiop:run-program, which waits for its
;;;; child, is uiop-run-program.lisp.

(defun uiop/launch-program:easy-sh-character-p (%esc-x)
  (or (alphanumericp %esc-x) (find %esc-x "+-_.,%@:/=")))

;; ESCAPER is called with the token, the stream and, given a QUOTE, :quote QUOTE.
(defun uiop/launch-program:escape-token (%et-token &key
                                         ((:stream %et-stream) nil)
                                         ((:quote %et-quote) nil)
                                         ((:good-chars %et-good-chars) nil)
                                         ((:bad-chars %et-bad-chars) nil)
                                         ((:escaper %et-escaper) nil))
  (let ((%et-needs
         (cond ((and %et-good-chars %et-bad-chars)
                (uiop/utility:parameter-error
                 "~S: only one of good-chars and bad-chars can be provided"
                 'uiop/launch-program:escape-token))
               ((functionp %et-good-chars) (complement %et-good-chars))
               ((functionp %et-bad-chars) %et-bad-chars)
               ((and %et-good-chars (typep %et-good-chars 'sequence))
                (lambda (%et-c) (not (find %et-c %et-good-chars))))
               ((and %et-bad-chars (typep %et-bad-chars 'sequence))
                (lambda (%et-c) (find %et-c %et-bad-chars)))
               (t (uiop/utility:parameter-error "~S: no good-char criterion"
                   'uiop/launch-program:escape-token)))))
    (if (some %et-needs %et-token)
        (uiop/stream:with-output (%et-stream)
          (apply %et-escaper %et-token %et-stream
                 (when %et-quote (list :quote %et-quote))))
        (uiop/stream:output-string %et-token %et-stream))))

;; Within double quotes, a backslash before each of $ ` \ and ".
(defun uiop/launch-program:escape-sh-token (%est-token &optional %est-s)
  (uiop/launch-program:escape-token %est-token
   :stream %est-s
   :quote #\"
   :good-chars #'uiop/launch-program:easy-sh-character-p
   :escaper (lambda (%est-x %est-out &key ((:quote %est-quote) t))
              (when %est-quote (princ #\" %est-out))
              (loop for %est-c across %est-x
                    do (when (find %est-c "$`\\\"") (princ #\\ %est-out))
                      (princ %est-c %est-out))
              (when %est-quote (princ #\" %est-out)))))

;; CommandLineToArgv's rules: a backslash run is doubled before a quote and at
;; the end, kept as it is anywhere else.
(defun uiop/launch-program:escape-windows-token (%ewt-token &optional %ewt-s)
  (uiop/launch-program:escape-token %ewt-token
                                    :stream %ewt-s
                                    :good-chars
                                    (lambda (%ewt-c)
                                      (or (alphanumericp %ewt-c)
                                          (find %ewt-c "+-_.,@:/=")))
                                    :quote nil
                                    :escaper
                                    (lambda (%ewt-x &optional %ewt-out)
                                      (flet ((%ewt-issue (%ewt-c)
                                               (princ %ewt-c %ewt-out))
                                             (%ewt-backslashes (%ewt-n)
                                               (loop repeat %ewt-n
                                                     do (princ #\\ %ewt-out))))
                                        (let ((%ewt-l (length %ewt-x))
                                              (%ewt-i 0))
                                          (%ewt-issue #\")
                                          (loop while (< %ewt-i %ewt-l)
                                                do
                                                  (let ((%ewt-c
                                                         (char %ewt-x %ewt-i)))
                                                    (cond ((eql %ewt-c #\")
                                                           (%ewt-backslashes 1)
                                                           (%ewt-issue #\")
                                                           (setq %ewt-i
                                                                 (+ %ewt-i 1)))
                                                          ((eql %ewt-c #\\)
                                                           (let* ((%ewt-j
                                                                   (and (<
                                                                         (+
                                                                          %ewt-i
                                                                          1)
                                                                         %ewt-l)
                                                                        (position-if-not
                                                                         (lambda (%ewt-d)
                                                                           (eql %ewt-d
                                                                                #\\))
                                                                         %ewt-x
                                                                         :start
                                                                         (+
                                                                          %ewt-i
                                                                          1))))
                                                                  (%ewt-n
                                                                   (- (or %ewt-j
                                                                       %ewt-l)
                                                                      %ewt-i)))
                                                             (cond
                                                              ((null %ewt-j)
                                                               (%ewt-backslashes
                                                                (* 2 %ewt-n))
                                                               (setq %ewt-i
                                                                     %ewt-l))
                                                              ((eql (char %ewt-x
                                                                     %ewt-j)
                                                                    #\")
                                                               (%ewt-backslashes
                                                                (+ 1
                                                                 (* 2 %ewt-n)))
                                                               (%ewt-issue #\")
                                                               (setq %ewt-i
                                                                (+ %ewt-j 1)))
                                                              (t
                                                               (%ewt-backslashes
                                                                %ewt-n)
                                                               (setq %ewt-i
                                                                     %ewt-j)))))
                                                          (t
                                                           (%ewt-issue %ewt-c)
                                                           (setq %ewt-i
                                                            (+ %ewt-i 1))))))
                                          (%ewt-issue #\"))))))

(defun uiop/launch-program:escape-shell-token (%esht-token &optional %esht-s)
  (cond ((uiop/os:os-unix-p)
         (uiop/launch-program:escape-sh-token %esht-token %esht-s))
        ((uiop/os:os-windows-p)
         (uiop/launch-program:escape-windows-token %esht-token %esht-s))))

;; A string is a command line already; a list is its tokens, escaped and spaced.
(defun uiop/launch-program:escape-command (%ec-command &optional %ec-s
                                                       (%ec-escaper
                                                        #'uiop/launch-program:escape-shell-token))
  (etypecase %ec-command
    (string (uiop/stream:output-string %ec-command %ec-s))
    (list (uiop/stream:with-output (%ec-s)
            (let ((%ec-first t))
              (dolist (%ec-token %ec-command)
                (unless %ec-first (princ #\Space %ec-s))
                (setq %ec-first nil)
                (funcall %ec-escaper %ec-token %ec-s)))))))

(defun uiop/launch-program:escape-windows-command
    (%ewc-command &optional %ewc-s)
  (uiop/launch-program:escape-command %ewc-command %ewc-s
   #'uiop/launch-program:escape-windows-token))

(defun uiop/launch-program:escape-sh-command (%esc-command &optional %esc-s)
  (uiop/launch-program:escape-command %esc-command %esc-s
                                      #'uiop/launch-program:escape-sh-token))

(defun uiop/launch-program:escape-shell-command
    (%eshc-command &optional %eshc-stream)
  (uiop/launch-program:escape-command %eshc-command %eshc-stream
                                      #'uiop/launch-program:escape-shell-token))
