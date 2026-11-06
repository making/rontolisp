;;;; uiop/run-program -- a program run to completion, and the stream pumps its
;;;; output goes to and its input comes from.
;;;;
;;;; run-program is real on the JVM family and on neither WASM backend. It
;;;; starts the program through rontolisp::%run-program -- runtime/RontoProcess
;;;; on the interpreter and in a compiled class alike -- waits for it, and makes
;;;; every stream the caller names into one that primitive takes: the null
;;;; device, this process's own stream, or a file. An input to vomit is written
;;;; to a temporary file first and an output to slurp is read back from one once
;;;; the child has exited, upstream's own route for every stream its
;;;; implementation cannot hand a child. WASI has no process API, so there the
;;;; definition signals not-implemented-error with that reason (.kb/uiop.md).
;;;;
;;;; slurp-input-stream and vomit-output-stream are upstream's method tables as
;;;; one dispatch each, like call-with-output (uiop-stream.lisp), so a program
;;;; cannot add a method to them. vomit-output-stream reads a pathname into the
;;;; stream; upstream's pathname method writes the stream to the file, which no
;;;; caller can mean of a source.

(defun uiop/run-program:slurp-input-stream
    (%sis-processor %sis-input &rest %sis-keys &key &allow-other-keys)
  (cond ((functionp %sis-processor) (funcall %sis-processor %sis-input))
        ((consp %sis-processor)
         (apply (first %sis-processor) %sis-input (rest %sis-processor)))
        ((streamp %sis-processor)
         (uiop/stream:copy-stream-to-stream %sis-input %sis-processor
          :linewise (getf %sis-keys :linewise)
          :prefix (getf %sis-keys :prefix)
          :element-type (getf %sis-keys :element-type 'character)
          :buffer-size (getf %sis-keys :buffer-size)))
        ((or (eq %sis-processor 'string) (eq %sis-processor :string))
         (uiop/stream:slurp-stream-string %sis-input
                                          :stripped (getf %sis-keys :stripped)))
        ((eq %sis-processor :lines)
         (uiop/stream:slurp-stream-lines %sis-input
                                         :count (getf %sis-keys :count)))
        ((eq %sis-processor :line)
         (uiop/stream:slurp-stream-line %sis-input :at (getf %sis-keys :at 0)))
        ((eq %sis-processor :forms)
         (uiop/stream:slurp-stream-forms %sis-input
                                         :count (getf %sis-keys :count)))
        ((eq %sis-processor :form)
         (uiop/stream:slurp-stream-form %sis-input :at (getf %sis-keys :at 0)))
        ((eq %sis-processor t)
         (apply #'uiop/run-program:slurp-input-stream *standard-output*
                %sis-input %sis-keys))
        ((null %sis-processor) nil)
        ((pathnamep %sis-processor)
         (uiop/stream:call-with-output-file %sis-processor
                                            (lambda (%sis-out)
                                              (uiop/stream:copy-stream-to-stream
                                               %sis-input %sis-out
                                               :linewise
                                               (getf %sis-keys :linewise)))
                                            :if-exists :supersede))
        (t (uiop/utility:parameter-error "Invalid ~S destination ~S"
                                         'uiop/run-program:slurp-input-stream
                                         %sis-processor))))

(defun uiop/run-program:vomit-output-stream
    (%vos-processor %vos-output &rest %vos-keys &key &allow-other-keys)
  (cond ((functionp %vos-processor) (funcall %vos-processor %vos-output))
        ((consp %vos-processor)
         (apply (first %vos-processor) %vos-output (rest %vos-processor)))
        ((streamp %vos-processor)
         (uiop/stream:copy-stream-to-stream %vos-processor %vos-output
          :linewise (getf %vos-keys :linewise)
          :prefix (getf %vos-keys :prefix)
          :element-type (getf %vos-keys :element-type 'character)
          :buffer-size (getf %vos-keys :buffer-size)))
        ((stringp %vos-processor)
         (princ %vos-processor %vos-output)
         (when (getf %vos-keys :fresh-line) (fresh-line %vos-output))
         (when (getf %vos-keys :terpri) (terpri %vos-output))
         (values))
        ((eq %vos-processor t)
         (apply #'uiop/run-program:vomit-output-stream *standard-input*
                %vos-output %vos-keys))
        ((null %vos-processor) (values))
        ((pathnamep %vos-processor)
         (uiop/stream:call-with-input-file %vos-processor
                                           (lambda (%vos-in)
                                             (uiop/stream:copy-stream-to-stream
                                              %vos-in %vos-output
                                              :linewise
                                              (getf %vos-keys :linewise)))))
        (t (uiop/utility:parameter-error "Invalid ~S source ~S"
                                         'uiop/run-program:vomit-output-stream
                                         %vos-processor))))

;; The readers are defuns over internal ones: each is a member of its own, which
;; a reader the condition defined would not be (UiopLibrary keys a definition by
;; the name it defines).
(define-condition uiop/run-program:subprocess-error (error)
  ((code :initform nil
         :initarg :code
         :reader uiop/run-program::%subprocess-error-code)
   (command :initform nil
            :initarg :command
            :reader uiop/run-program::%subprocess-error-command)
   (process :initform nil
            :initarg :process
            :reader uiop/run-program::%subprocess-error-process))
  (:report
   (lambda (%se-c %se-s)
     (write-string "Subprocess " %se-s)
     (when (uiop/run-program::%subprocess-error-process %se-c)
       (format %se-s "~S~% "
               (uiop/run-program::%subprocess-error-process %se-c)))
     (when (uiop/run-program::%subprocess-error-command %se-c)
       (format %se-s "with command ~S~% "
               (uiop/run-program::%subprocess-error-command %se-c)))
     (write-string "exited with error" %se-s)
     (when (uiop/run-program::%subprocess-error-code %se-c)
       (format %se-s " code ~D"
               (uiop/run-program::%subprocess-error-code %se-c))))))

(defun uiop/run-program:subprocess-error-code (%sec-c)
  (uiop/run-program::%subprocess-error-code %sec-c))

(defun uiop/run-program:subprocess-error-command (%secm-c)
  (uiop/run-program::%subprocess-error-command %secm-c))

(defun uiop/run-program:subprocess-error-process (%sep-c)
  (uiop/run-program::%subprocess-error-process %sep-c))

;; COMMAND is a list of tokens run as they are, or a string /bin/sh runs; a list
;; also goes through /bin/sh under :force-shell. Every stream argument is
;; upstream's: nil the null device, :interactive this process's own, a string or
;; a pathname a file, :output (the error output only) where the output goes, and
;; anything else a processor the child's output is slurped into or its input
;; vomited from, t meaning the standard stream. The process value of a
;; subprocess-error is nil: no process object outlives the call.
#-rontolisp-wasm
(defun uiop/run-program:run-program (%rp-command &rest %rp-keys &key
                                     ((:ignore-error-status
                                       %rp-ignore-error-status) nil)
                                     ((:force-shell %rp-force-shell) nil)
                                     ((:input %rp-input) nil)
                                     ((:if-input-does-not-exist
                                       %rp-if-input-does-not-exist) :error)
                                     ((:output %rp-output) nil)
                                     ((:if-output-exists %rp-if-output-exists)
                                      :supersede)
                                     ((:error-output %rp-error-output) nil)
                                     ((:if-error-output-exists
                                       %rp-if-error-output-exists) :supersede)
                                     ((:directory %rp-directory) nil)
                                     &allow-other-keys)
  (declare (ignore %rp-keys))
  (when (member :stream (list %rp-input %rp-output %rp-error-output))
    (uiop/utility:parameter-error
     "~S: ~S is not allowed as synchronous I/O redirection argument"
     'uiop/run-program:run-program :stream))
  ;; Per stream (0 input, 1 output, 2 error output): the file the child is
  ;; handed, and whether it is a temporary one the call reads or wrote.
  (let ((%rp-modes 0)
        (%rp-paths (list nil nil nil))
        (%rp-active (list nil nil nil))
        (%rp-results (list nil nil))
        (%rp-code nil))
    (labels ((%rp-token (%rp-x)
               (let ((%rp-s
                      (if (pathnamep %rp-x)
                          (uiop/filesystem:native-namestring %rp-x)
                          %rp-x)))
                 (unless (stringp %rp-s)
                   (uiop/utility:parameter-error "~S: ~S is no command token"
                                                 'uiop/run-program:run-program
                                                 %rp-x))
                 (when (find (code-char 0) %rp-s)
                   (uiop/utility:parameter-error
                    "~S: a command token cannot hold a NUL character: ~S"
                    'uiop/run-program:run-program %rp-s))
                 %rp-s))
             (%rp-file-p (%rp-x) (or (stringp %rp-x) (pathnamep %rp-x)))
             ;; The directory named here, as call-with-temporary-file names it, so
             ;; a compile path's selection carries what %temp-file-name calls.
             (%rp-temporary (%rp-index)
               (setf (nth %rp-index %rp-active) t)
               (setf (nth %rp-index %rp-paths)
                     (%temp-file-name
                      (namestring
                       (uiop/pathname:ensure-directory-pathname
                        (uiop/stream:default-temporary-directory)))
                      "uiop-run-program-" "" "tmp")))
             (%rp-process (%rp-fun %rp-processor %rp-stream)
               (if (consp %rp-processor)
                   (apply %rp-fun (first %rp-processor) %rp-stream
                          (rest %rp-processor))
                   (funcall %rp-fun %rp-processor %rp-stream)))
             ;; Where output stream INDEX (1 output, 2 error output) goes, given
             ;; its INHERIT and APPEND mode bits.
             (%rp-sink (%rp-index %rp-spec %rp-if-exists %rp-inherit %rp-append)
               (cond ((null %rp-spec))
                     ((eq %rp-spec :interactive)
                      (setq %rp-modes (logior %rp-modes %rp-inherit)))
                     ((%rp-file-p %rp-spec)
                      (let ((%rp-name
                             (uiop/filesystem:native-namestring %rp-spec)))
                        (uiop/stream:call-with-output-file %rp-name #'identity
                         :if-exists %rp-if-exists
                         :if-does-not-exist :create)
                        (when (eq %rp-if-exists :append)
                          (setq %rp-modes (logior %rp-modes %rp-append)))
                        (setf (nth %rp-index %rp-paths) %rp-name)))
                     ((eq %rp-spec :output)
                      (if (= %rp-index 2)
                          (setq %rp-modes (logior %rp-modes 32))
                          (uiop/utility:parameter-error
                           "~S does not allow ~S as a ~S spec"
                           'uiop/run-program:run-program
                           :output :output)))
                     (t (%rp-temporary %rp-index))))
             (%rp-slurp (%rp-index %rp-spec %rp-standard)
               (when (nth %rp-index %rp-active)
                 (setf (nth (- %rp-index 1) %rp-results)
                       (uiop/stream:call-with-input-file
                        (nth %rp-index %rp-paths)
                        (lambda (%rp-in)
                          (%rp-process #'uiop/run-program:slurp-input-stream
                           (if (eq %rp-spec t) %rp-standard %rp-spec)
                           %rp-in)))))))
      (let ((%rp-argv
             (cond ((stringp %rp-command) (list "/bin/sh" "-c" %rp-command))
                   (%rp-force-shell
                    (list "/bin/sh" "-c"
                          (uiop/launch-program:escape-sh-command
                           (cons "exec" (mapcar #'%rp-token %rp-command)))))
                   (t (mapcar #'%rp-token %rp-command)))))
        (unwind-protect (progn
                          (cond ((null %rp-input))
                                ((eq %rp-input :interactive)
                                 (setq %rp-modes (logior %rp-modes 1)))
                                ((%rp-file-p %rp-input)
                                 (let ((%rp-name
                                        (uiop/filesystem:native-namestring
                                         %rp-input)))
                                   (uiop/stream:call-with-input-file %rp-name
                                    #'identity
                                    :if-does-not-exist
                                    %rp-if-input-does-not-exist)
                                   (setf (nth 0 %rp-paths) %rp-name)))
                                ((eq %rp-input :output)
                                 (uiop/utility:parameter-error
                                  "~S does not allow ~S as a ~S spec"
                                  'uiop/run-program:run-program
                                  :output :input))
                                (t (uiop/stream:call-with-output-file
                                    (%rp-temporary 0)
                                    (lambda (%rp-out)
                                      (%rp-process
                                       #'uiop/run-program:vomit-output-stream
                                       (if (eq %rp-input t)
                                           *standard-input*
                                           %rp-input) %rp-out))
                                    :if-exists :supersede)))
                          (%rp-sink 1 %rp-output %rp-if-output-exists 2 8)
                          (%rp-sink 2 %rp-error-output
                                    %rp-if-error-output-exists 4 16)
                          ;; What this process wrote before the child writes to the same
                          ;; descriptors comes first.
                          (finish-output *standard-output*)
                          (finish-output *error-output*)
                          (setq %rp-code
                                (rontolisp::%run-program
                                 (with-output-to-string (%rp-joined)
                                   (let ((%rp-first t))
                                     (dolist (%rp-arg %rp-argv)
                                       (unless %rp-first
                                         (write-char (code-char 0) %rp-joined))
                                       (setq %rp-first nil)
                                       (write-string %rp-arg %rp-joined))))
                                 (and %rp-directory
                                      (uiop/filesystem:native-namestring
                                       %rp-directory)) (nth 0 %rp-paths)
                                 (nth 1 %rp-paths) (nth 2 %rp-paths) %rp-modes))
                          (%rp-slurp 1 %rp-output *standard-output*)
                          (%rp-slurp 2 %rp-error-output *error-output*))
          (loop for %rp-name in %rp-paths
                for %rp-temporary-p in %rp-active
                do
                  (when %rp-temporary-p
                    (uiop/filesystem:delete-file-if-exists %rp-name))))))
    (unless (or %rp-ignore-error-status (eql %rp-code 0))
      (cerror "IGNORE-ERROR-STATUS" 'uiop/run-program:subprocess-error
              :command %rp-command
              :code %rp-code
              :process nil))
    (values (first %rp-results) (second %rp-results) %rp-code)))

#+rontolisp-wasm
(defun uiop/run-program:run-program (%rp-command &rest %rp-keys)
  (declare (ignore %rp-command %rp-keys))
  (uiop/utility:not-implemented-error "UIOP/RUN-PROGRAM:RUN-PROGRAM"
   "-- no WASM backend starts a process: WASI has no process API"))
