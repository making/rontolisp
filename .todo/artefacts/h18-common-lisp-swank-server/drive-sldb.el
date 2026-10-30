;;; drive-sldb.el --- drive a real SLIME's debugger against a SWANK server in batch mode -*- lexical-binding: t -*-
;; Usage: emacs --batch -l drive-sldb.el   (SWANK_PORT, DRV_DIR, DRV_STEPS=sldb in the environment)

(setq debug-on-error nil)
(defvar drv-slime-dir "@WORK@/dl/slime/slime-2.32")
(add-to-list 'load-path drv-slime-dir)
(add-to-list 'load-path (expand-file-name "contrib" drv-slime-dir))
(require 'slime)
(slime-setup '(slime-fancy))
(setq slime-repl-auto-right-margin nil)
(setq slime-load-failed-fasl 'never)
(setq slime-repl-history-file (expand-file-name "repl-history.eld" (getenv "DRV_DIR")))

(defvar drv-port (string-to-number (getenv "SWANK_PORT")))
(defvar drv-dir (getenv "DRV_DIR"))
(defvar drv-messages '())

(advice-add 'message :around
            (lambda (orig fmt &rest args)
              (when fmt
                (let ((text (apply #'format fmt args)))
                  (push text drv-messages)
                  (princ (format "MSG: %s\n" text) #'external-debugging-output)))
              (apply orig fmt args)))

(defun drv-log (fmt &rest args)
  (princ (concat (apply #'format fmt args) "\n") #'external-debugging-output))

(defun drv-wait (name pred timeout)
  (let ((end (time-add (current-time) (seconds-to-time timeout))))
    (while (not (funcall pred))
      (when (time-less-p end (current-time))
        (drv-log "TIMEOUT waiting for %s" name)
        (throw 'drv-timeout name))
      (accept-process-output nil 0.05))))

(defun drv-top-level-p ()
  (and (not (sldb-get-default-buffer))
       (null (slime-rex-continuations))))

(defun drv-settle (&optional timeout)
  (drv-wait "top level" #'drv-top-level-p (or timeout 30))
  (let ((end (time-add (current-time) (seconds-to-time 0.3))))
    (while (time-less-p (current-time) end)
      (accept-process-output nil 0.05))))

(defun drv-repl (string)
  (drv-log "== REPL %s" string)
  (with-current-buffer (slime-output-buffer)
    (goto-char (point-max))
    (insert string)
    (slime-repl-return))
  (drv-settle))

(defun drv-repl-text ()
  (with-current-buffer (slime-output-buffer)
    (buffer-substring-no-properties (point-min) (point-max))))

(defun drv-source-buffer (name contents)
  (let ((file (expand-file-name name drv-dir)))
    (with-temp-file file (insert contents))
    (let ((buffer (find-file-noselect file)))
      (with-current-buffer buffer
        (lisp-mode)
        (slime-mode 1))
      buffer)))

(defun drv-step (name thunk)
  (drv-log "== STEP %s" name)
  (catch 'drv-timeout
    (condition-case err
        (funcall thunk)
      (error (drv-log "STEP-ERROR %s: %S" name err)))))

(slime-connect "127.0.0.1" drv-port)
(drv-wait "connection" (lambda () (and (slime-connected-p)
                                       (cl-some (lambda (b) (string-prefix-p "*slime-repl" (buffer-name b)))
                                                (buffer-list))))
          60)
(drv-settle)
(drv-log "CONNECTED %s %s" (slime-lisp-implementation-name) (slime-lisp-package))

(defvar drv-steps (split-string (or (getenv "DRV_STEPS") "basic") ","))


;;; The SLDB steps: an error, a numbered restart, continue, abort, frames, an interrupt,
;;; a nested level.

(defun drv-sldb-level ()
  (let ((b (sldb-get-default-buffer)))
    (and b (buffer-live-p b) (with-current-buffer b sldb-level))))

(defun drv-wait-sldb (level &optional timeout)
  (drv-wait (format "sldb level %s" level)
            (lambda () (equal (drv-sldb-level) level))
            (or timeout 20))
  (let ((end (time-add (current-time) (seconds-to-time 0.3))))
    (while (time-less-p (current-time) end)
      (accept-process-output nil 0.05))))

(defun drv-sldb-text ()
  (with-current-buffer (sldb-get-default-buffer)
    (buffer-substring-no-properties (point-min) (point-max))))

(defun drv-repl-send (string)
  (drv-log "== REPL-SEND %s" string)
  (with-current-buffer (slime-output-buffer)
    (goto-char (point-max))
    (insert string)
    (slime-repl-return)))

(defun drv-in-sldb (fn)
  (with-current-buffer (sldb-get-default-buffer)
    (funcall fn)))

(defun drv-pause (seconds)
  (let ((end (time-add (current-time) (seconds-to-time seconds))))
    (while (time-less-p (current-time) end)
      (accept-process-output nil 0.05))))

(when (member "sldb" drv-steps)
  (drv-step "sldb-quit"
            (lambda ()
              (drv-repl-send "(error \"boom ~a\" 1)")
              (drv-wait-sldb 1)
              (drv-log "SLDB-TEXT %s" (drv-sldb-text))
              (drv-in-sldb #'sldb-continue)
              (drv-pause 0.5)
              (drv-in-sldb #'sldb-quit)
              (drv-settle)))
  (drv-step "sldb-restart"
            (lambda ()
              (drv-repl-send "(restart-case (error \"pick ~a\" 2) (use-five () :report \"Return five.\" 5) (use-ten () 10))")
              (drv-wait-sldb 1)
              (drv-log "SLDB-TEXT %s" (drv-sldb-text))
              (drv-in-sldb (lambda () (sldb-invoke-restart 0)))
              (drv-settle)))
  (drv-step "sldb-continue"
            (lambda ()
              (drv-repl-send "(progn (cerror \"Keep going.\" \"Cerror ~a\" 3) :went-on)")
              (drv-wait-sldb 1)
              (drv-log "SLDB-TEXT %s" (drv-sldb-text))
              (drv-in-sldb #'sldb-continue)
              (drv-settle)))
  (drv-step "sldb-abort"
            (lambda ()
              (slime-interactive-eval "(car 1)")
              (drv-wait-sldb 1)
              (drv-log "SLDB-TEXT %s" (drv-sldb-text))
              (drv-in-sldb #'sldb-abort)
              (drv-settle)))
  (drv-step "sldb-frames"
            (lambda ()
              (drv-repl "(defun drv-g (z) (list (error \"deep ~a\" z)))")
              (drv-repl "(defun drv-f (x) (let ((y (* x 2))) (list (drv-g y))))")
              (drv-repl-send "(drv-f 21)")
              (drv-wait-sldb 1)
              (drv-log "SLDB-TEXT %s" (drv-sldb-text))
              (unwind-protect
                  (drv-in-sldb (lambda ()
                                 (drv-log "LOCALS0 %S" (slime-eval '(swank:frame-locals-and-catch-tags 0)))
                                 (drv-log "LOCALS1 %S" (slime-eval '(swank:frame-locals-and-catch-tags 1)))
                                 (drv-log "PKG %S" (slime-eval '(swank:frame-package-name 1)))
                                 (sldb-eval-in-frame 1 "(* x 2)" "COMMON-LISP-USER")
                                 (drv-pause 1)
                                 (drv-log "BT %S" (slime-eval '(swank:backtrace 1 3)))))
                (drv-in-sldb #'sldb-quit))
              (drv-settle)))
  (drv-step "sldb-interrupt"
            (lambda ()
              (drv-repl-send "(loop)")
              (drv-pause 1)
              (slime-interrupt)
              (drv-wait-sldb 1)
              (drv-log "SLDB-TEXT %s" (drv-sldb-text))
              (drv-in-sldb #'sldb-continue)
              (drv-pause 1)
              (drv-log "AFTER-CONTINUE %S" (drv-sldb-level))
              (slime-interrupt)
              (drv-wait-sldb 1)
              (drv-in-sldb #'sldb-quit)
              (drv-settle)))
  (drv-step "sldb-nested"
            (lambda ()
              (drv-repl-send "(error \"outer\")")
              (drv-wait-sldb 1)
              (drv-repl-send "(error \"inner\")")
              (drv-wait-sldb 2)
              (drv-log "SLDB-TEXT %s" (drv-sldb-text))
              (drv-in-sldb (lambda ()
                             (sldb-invoke-restart
                              (cl-position "Return to sldb level 1." sldb-restarts
                                           :key #'cadr :test #'string=))))
              (drv-wait-sldb 1)
              (drv-log "SLDB-TEXT %s" (drv-sldb-text))
              (drv-in-sldb #'sldb-quit)
              (drv-settle)
              (drv-repl "(+ 40 2)"))))

(drv-log "== REPL-BUFFER\n%s\n== END-REPL-BUFFER" (drv-repl-text))
(drv-log "DONE")
(kill-emacs 0)
