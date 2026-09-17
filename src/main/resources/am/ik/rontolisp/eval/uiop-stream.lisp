;;;; uiop/stream -- file contents and the temporary-file directory.
;;;; Canonical shape; see .kb/uiop.md.

;; Chunked, NOT (make-string (file-length s)): file-length answers nil on both
;; WASM backends (no WASI filestat call is imported, .kb/read-load-streams.md), so
;; sizing the buffer from it traps there. The loop also reads EOF at most once --
;; it stops as soon as a read comes back short -- because a SECOND read past EOF
;; traps on the --component backend (the adapter's stream_read after the writable
;; end dropped).
;;
;; The chunks accumulate into a string OUTPUT STREAM, never into a string: every
;; backend's string stream appends into a buffer that doubles, while
;; (setq acc (concatenate 'string acc chunk)) re-copies everything read so far on
;; every chunk and is quadratic in the file (.kb/string-accumulate-cost.md).
;; with-output-to-string costs no extra machinery here -- with-open-file already
;; puts the wasm module in EH mode.
(defun uiop/stream:read-file-string (%rfs-file &rest %rfs-keys)
  (with-open-file (%rfs-in %rfs-file)
    (with-output-to-string (%rfs-out)
      (let ((%rfs-buf (make-string 4096)) (%rfs-n 4096))
        (while (= %rfs-n 4096)
          (setq %rfs-n (read-sequence %rfs-buf %rfs-in))
          (when (> %rfs-n 0) (write-string %rfs-buf %rfs-out :end %rfs-n)))))))

;; $TMPDIR or /tmp/: getenv is the one environment reader every backend has, and
;; a backend whose environment is empty (both WASM ones without --env) takes the
;; fallback rather than failing.
(defun uiop/stream:default-temporary-directory ()
  (let ((%dtd-e (uiop/os:getenv "TMPDIR")))
    (uiop/pathname:ensure-directory-pathname
     (if (and %dtd-e (string/= %dtd-e "")) %dtd-e "/tmp"))))

;;;; File contents: the call-with-* openers, the designator coercions, the slurp
;;;; family and the safe-IO syntax. Canonical shape; see .kb/uiop.md.
;;;;
;;;; The designator table (a stream is used as-is, nil is the standard stream, t
;;;; is the terminal/console stream, a string is a string stream, a pathname is
;;;; opened) is written once, in the `%call-with-input` / `%call-with-output`
;;;; prelude entries: upstream defines `call-with-input` / `call-with-output` but
;;;; does NOT export them, so no resource here may define them (.kb/uiop.md).
;;;; The `with-input` / `with-output` macro expansions call the same entries, so
;;;; the twelve callers above stay thin.
;;;;
;;;; Lite decisions shared by the whole half (each is one portable shape on all
;;;; four backends rather than a per-backend approximation):
;;;; - `:element-type` defaults to `'character`, not upstream's
;;;;   `*default-stream-element-type*`: that variable is .todo/360's and still a
;;;;   nil stub, which the computed-option check behind `with-open-file` would
;;;;   refuse at call time (.kb/read-load-streams.md).
;;;; - `:external-format` defaults to `:utf-8`, not upstream's
;;;;   `*utf-8-external-format*` (also .todo/360's): `open` drops it either way,
;;;;   every backend reads UTF-8.
;;;; - `call-with-output-file` defaults `:if-exists` to `:supersede`, not
;;;;   upstream's `:error`: an `open` always truncates, so `:error` has no native
;;;;   spelling, and the lowering refuses it loudly rather than silently
;;;;   reinterpreting it. `concatenate-files` spells `:supersede` for the same
;;;;   reason (upstream's `:rename-and-delete` has no atomic-rename surface).
;;;; - `slurp-stream-forms` reads with `read`, not `read-preserving-whitespace`
;;;;   (which does not exist): preserving the whitespace only matters to a caller
;;;;   that keeps positions, and a slurp keeps forms.
;;;; - `safe-read-from-string` reads through `with-input-from-string` + `read`,
;;;;   not `read-from-string`: the compile paths compile `read-from-string` with
;;;;   one argument only, while prelude `read` honors `eof-error-p`/`eof-value`
;;;;   on all four. It answers the object only (no secondary position).
;;;; - `call-with-output` over a string signals: `with-output-to-string` is
;;;;   fresh-string only (no fill-pointer append surface), so there is nothing
;;;;   honest to append to.
;;;; - `copy-stream-to-stream` `:linewise` always ends lines with `terpri`:
;;;;   `read-line` answers one value (no missing-newline-p), so a source missing
;;;;   its trailing newline gains one.

;; Upstream's recognized keys, passed down as function arguments -- the shape the
;; computed-option lowering behind with-open-file exists for
;; (.kb/read-load-streams.md). Anything outside the accepted value set signals
;; at call time through that same lowering, identically on all four backends.
(defun uiop/stream:call-with-input-file (%cwif-pathname %cwif-thunk &key
                                         ((:element-type %cwif-element-type)
                                          'character)
                                         ((:external-format
                                           %cwif-external-format) :utf-8)
                                         ((:if-does-not-exist
                                           %cwif-if-does-not-exist) :error))
  (with-open-file (%cwif-s %cwif-pathname
                           :direction :input
                           :element-type %cwif-element-type
                           :external-format %cwif-external-format
                           :if-does-not-exist %cwif-if-does-not-exist)
    (funcall %cwif-thunk %cwif-s)))

(defun uiop/stream:call-with-output-file (%cwof-pathname %cwof-thunk &key
                                          ((:element-type %cwof-element-type)
                                           'character)
                                          ((:external-format
                                            %cwof-external-format) :utf-8)
                                          ((:if-exists %cwof-if-exists)
                                           :supersede)
                                          ((:if-does-not-exist
                                            %cwof-if-does-not-exist) :create))
  (with-open-file (%cwof-s %cwof-pathname
                           :direction :output
                           :element-type %cwof-element-type
                           :external-format %cwof-external-format
                           :if-exists %cwof-if-exists
                           :if-does-not-exist %cwof-if-does-not-exist)
    (funcall %cwof-thunk %cwof-s)))

;; The designator table, written once in the %call-with-input / %call-with-output
;; prelude entries so the twelve callers stay thin: a stream is used as-is, nil
;; is the standard stream, t is the terminal/console stream, a string is a
;; string stream, a pathname is opened. Upstream's etypecase shape as a cond so
;; the string arm of the output side can refuse with a reason instead of
;; falling through.
(defun uiop/stream:input-string (&optional %is-input)
  (if (stringp %is-input)
      %is-input
      (%call-with-input %is-input #'uiop/stream:slurp-stream-string)))

(defun uiop/stream:output-string (%os-string &optional %os-output)
  (if %os-output
      (%call-with-output %os-output (lambda (%os-s) (princ %os-string %os-s)))
      %os-string))

;;;; Safe IO syntax. with-standard-io-syntax binds *package* to :cl-user; the
;;;; function rebinds it to the caller's package and pins the three reader
;;;; controls upstream pins. Findings for this item (.todo/359):
;;;; - `*read-eval*` nil is honored by the interpreter's runtime read; the
;;;;   compiled runtime readers refuse `#.` unconditionally, which is stricter
;;;;   than upstream and therefore safe.
;;;; - `:package` is honored wherever the backend knows the package
;;;;   (`find-package` over the baked table on the compile paths); the safe
;;;;   family only ever spells `:cl`.
(defun uiop/stream:call-with-safe-io-syntax
    (%cwsi-thunk &key ((:package %cwsi-package) :cl))
  (with-standard-io-syntax
    (let ((*package* (find-package %cwsi-package))
          (*read-default-float-format* 'double-float)
          (*print-readably* nil)
          (*read-eval* nil))
      (funcall %cwsi-thunk))))

(defun uiop/stream:safe-read-from-string (%srfs-string &key
                                          ((:package %srfs-package) :cl)
                                          ((:eof-error-p %srfs-eof-error-p) t)
                                          ((:eof-value %srfs-eof-value) nil)
                                          ((:start %srfs-start) 0)
                                          ((:end %srfs-end) nil)
                                          ((:preserve-whitespace
                                            %srfs-preserve-whitespace) nil))
  (declare (ignore %srfs-preserve-whitespace))
  (uiop/stream:call-with-safe-io-syntax (lambda ()
                                          (let ((%srfs-text
                                                 (if (or (/= %srfs-start 0)
                                                         %srfs-end)
                                                     (subseq %srfs-string
                                                             %srfs-start
                                                             %srfs-end)
                                                     %srfs-string)))
                                            (with-input-from-string (%srfs-s
                                                                     %srfs-text)
                                              (read %srfs-s %srfs-eof-error-p
                                                    %srfs-eof-value))))
                                        :package %srfs-package))

;;;; Whole-stream copying. The input stream is NOT closed here (nor in the
;;;; slurpers below): rontolisp `close` signals on an already-closed stream, so
;;;; upstream's close-inside-plus-close-in-`with-open-file` composition would
;;;; signal where upstream is silent. The owner closes exactly once -- the file
;;;; openers around a file, `with-input-from-string` around a string. Divergence:
;;;; a caller-owned stream handed directly to these stays open (upstream closes
;;;; it).
(defun uiop/stream:copy-stream-to-stream (%csts-input %csts-output &key
                                          ((:element-type %csts-element-type))
                                          ((:buffer-size %csts-buffer-size)
                                           8192) ((:linewise %csts-linewise))
                                          ((:prefix %csts-prefix)))
  (if %csts-linewise
      (do ((%csts-line
            (read-line %csts-input nil nil)
            (read-line %csts-input nil nil)))
          ((null %csts-line) nil)
        (when %csts-prefix (princ %csts-prefix %csts-output))
        (write-string %csts-line %csts-output)
        (terpri %csts-output)
        (finish-output %csts-output))
      (let ((%csts-size (or %csts-buffer-size 8192))
            (%csts-buffer nil)
            (%csts-end 0))
        (setq %csts-buffer
              (make-array %csts-size
                          :element-type (or %csts-element-type 'character)))
        (loop
          (setq %csts-end (read-sequence %csts-buffer %csts-input))
          (when (zerop %csts-end) (return nil))
          (write-sequence %csts-buffer %csts-output :end %csts-end)
          (when (< %csts-end %csts-size) (return nil))))))

;; Binary both ways (upstream's element-type), truncating the target
;; (upstream's :rename-and-delete, .todo/359 lite above).
(defun uiop/stream:concatenate-files (%cf-inputs %cf-output)
  (with-open-file (%cf-o %cf-output
                         :element-type '(unsigned-byte 8)
                         :direction :output
                         :if-exists :supersede)
    (dolist (%cf-input %cf-inputs)
      (with-open-file (%cf-i %cf-input
                             :element-type '(unsigned-byte 8)
                             :direction :input
                             :if-does-not-exist :error)
        (uiop/stream:copy-stream-to-stream %cf-i %cf-o
                                           :element-type '(unsigned-byte 8))))))

;; Upstream's #+allegro/#+ecl native arms have no counterpart here; the portable
;; arm is the whole function.
(defun uiop/stream:copy-file (%cp-input %cp-output)
  (uiop/stream:concatenate-files (list %cp-input) %cp-output))

;;;; Slurping a stream (see the ownership note above: no closing here).
(defun uiop/stream:slurp-stream-string (%sss-input &key
                                        ((:element-type %sss-element-type)
                                         'character)
                                        ((:stripped %sss-stripped) nil))
  (let ((%sss-string
         (with-output-to-string (%sss-out)
           (uiop/stream:copy-stream-to-stream %sss-input %sss-out
            :element-type %sss-element-type))))
    (if %sss-stripped (uiop/utility:stripln %sss-string) %sss-string)))

(defun uiop/stream:slurp-stream-lines
    (%ssl-input &key ((:count %ssl-count) nil))
  (check-type %ssl-count (or null integer))
  (do ((%ssl-n 0 (+ %ssl-n 1)) (%ssl-line nil) (%ssl-acc nil))
      (nil)
    (setq %ssl-line
          (and (or (not %ssl-count) (< %ssl-n %ssl-count))
               (read-line %ssl-input nil nil)))
    (unless %ssl-line (return (nreverse %ssl-acc)))
    ;; stripln: drop a CR when the source is CRLF and the line reader only
    ;; dropped the LF (upstream's contract).
    (setq %ssl-acc (cons (uiop/utility:stripln %ssl-line) %ssl-acc))))

(defun uiop/stream:slurp-stream-line (%sssl-input &key ((:at %sssl-at) 0))
  (uiop/utility:access-at (uiop/stream:slurp-stream-lines %sssl-input
                           :count (uiop/utility:access-at-count %sssl-at))
                          %sssl-at))

;; read, not read-preserving-whitespace (.todo/359 lite above). The sentinel is
;; a fresh cons, so a file naming #:eof cannot end the read early the way
;; upstream's quoted sentinel can.
(defun uiop/stream:slurp-stream-forms
    (%ssf-input &key ((:count %ssf-count) nil))
  (check-type %ssf-count (or null integer))
  (let ((%ssf-eof (list nil)))
    (do ((%ssf-n 0 (+ %ssf-n 1)) (%ssf-form nil) (%ssf-acc nil))
        (nil)
      (setq %ssf-form
            (if (and %ssf-count (>= %ssf-n %ssf-count))
                %ssf-eof
                (read %ssf-input nil %ssf-eof)))
      (when (eq %ssf-form %ssf-eof) (return (nreverse %ssf-acc)))
      (setq %ssf-acc (cons %ssf-form %ssf-acc)))))

(defun uiop/stream:slurp-stream-form (%ssof-input &key ((:at %ssof-at) 0))
  (uiop/utility:access-at (uiop/stream:slurp-stream-forms %ssof-input
                           :count (uiop/utility:access-at-count %ssof-at))
                          %ssof-at))

;;;; Slurping a file: call-with-input-file over the stream slurper, stripping
;;;; this half's own keys before the apply (upstream's shape).
(defun uiop/stream:read-file-lines (%rfl-file &rest %rfl-keys)
  (apply #'uiop/stream:call-with-input-file %rfl-file
         #'uiop/stream:slurp-stream-lines %rfl-keys))

(defun uiop/stream:read-file-line
    (%rfli-file &rest %rfli-keys &key ((:at %rfli-at) 0) &allow-other-keys)
  (apply #'uiop/stream:call-with-input-file %rfli-file
         (lambda (%rfli-s) (uiop/stream:slurp-stream-line %rfli-s :at %rfli-at))
         (uiop/utility:remove-plist-key :at %rfli-keys)))

(defun uiop/stream:read-file-forms
    (%rfo-file &rest %rfo-keys &key ((:count %rfo-count) nil) &allow-other-keys)
  (apply #'uiop/stream:call-with-input-file %rfo-file
   (lambda (%rfo-s) (uiop/stream:slurp-stream-forms %rfo-s :count %rfo-count))
   (uiop/utility:remove-plist-key :count %rfo-keys)))

(defun uiop/stream:read-file-form
    (%rff-file &rest %rff-keys &key ((:at %rff-at) 0) &allow-other-keys)
  (apply #'uiop/stream:call-with-input-file %rff-file
         (lambda (%rff-s) (uiop/stream:slurp-stream-form %rff-s :at %rff-at))
         (uiop/utility:remove-plist-key :at %rff-keys)))

(defun uiop/stream:safe-read-file-line (%srfl-pathname &rest %srfl-keys &key
                                        ((:package %srfl-package) :cl)
                                        &allow-other-keys)
  (uiop/stream:call-with-safe-io-syntax (lambda ()
                                          (apply #'uiop/stream:read-file-line
                                                 %srfl-pathname
                                                 (uiop/utility:remove-plist-key
                                                  :package %srfl-keys)))
                                        :package %srfl-package))

(defun uiop/stream:safe-read-file-form (%srff-pathname &rest %srff-keys &key
                                        ((:package %srff-package) :cl)
                                        &allow-other-keys)
  (uiop/stream:call-with-safe-io-syntax (lambda ()
                                          (apply #'uiop/stream:read-file-form
                                                 %srff-pathname
                                                 (uiop/utility:remove-plist-key
                                                  :package %srff-keys)))
                                        :package %srff-package))

;;;; Evaluating input. The eof sentinel is a fresh cons (see
;;;; slurp-stream-forms); the last form's values are the answer
;;;; (multiple-value-list/values-list, upstream's shape).
(defun uiop/stream:eval-input (%ei-input)
  (%call-with-input %ei-input
                    (lambda (%ei-s)
                      (let ((%ei-results nil)
                            (%ei-eof (list nil))
                            (%ei-form nil))
                        (loop
                          (setq %ei-form (read %ei-s nil %ei-eof))
                          (when (eq %ei-form %ei-eof)
                            (return (values-list %ei-results)))
                          (setq %ei-results
                                (multiple-value-list (eval %ei-form))))))))

;; Upstream's etypecase as a cond: a compound (or ...) specifier in an etypecase
;; dispatch is fine, but the arms below read the same either way and the cond
;; keeps the string arm (eval-input over it) beside the function arm without
;; leaning on `function`/`boolean` being exact type names on all four.
(defun uiop/stream:eval-thunk (%et-thunk)
  (cond ((or (null %et-thunk) (eq %et-thunk t) (keywordp %et-thunk)
             (numberp %et-thunk) (characterp %et-thunk) (pathnamep %et-thunk))
         %et-thunk)
        ((or (consp %et-thunk) (symbolp %et-thunk)) (eval %et-thunk))
        ((functionp %et-thunk) (funcall %et-thunk))
        ((stringp %et-thunk) (uiop/stream:eval-input %et-thunk))
        (t (error "EVAL-THUNK: invalid thunk ~S" %et-thunk))))

;; "standard-" not "safe-": evaluation is never safe (upstream's note). The
;; *read-eval* rebind undoes the safe syntax's nil for the thunk's own reads.
(defun uiop/stream:standard-eval-thunk
    (%set-thunk &key ((:package %set-package) :cl))
  (when %set-thunk
    (uiop/stream:call-with-safe-io-syntax
     (lambda () (let ((*read-eval* t)) (uiop/stream:eval-thunk %set-thunk)))
     :package %set-package)))

;;;; Output helpers. finish-outputs flushes the named streams plus every
;;;; standard one; each flush is guarded, so a closed or missing stream is
;;;; skipped, not fatal (upstream's contract). *stdout*/*stderr* are .todo/360's
;;;; nil stubs today -- a nil designator flushes *standard-output*, which is
;;;; harmless -- and turn real without touching this function.
(defun uiop/stream:finish-outputs (&rest %fo-streams)
  (dolist (%fo-s
           (append %fo-streams
                   (list uiop/stream:*stdout* uiop/stream:*stderr*
                         *error-output* *standard-output* *trace-output*
                         *debug-io* *terminal-io* *query-io*)))
    (ignore-errors (finish-output %fo-s)))
  (values))

;; Like format, flushed before and after (upstream's contract).
(defun uiop/stream:format! (%fi-stream %fi-format &rest %fi-args)
  (uiop/stream:finish-outputs %fi-stream)
  (apply #'format %fi-stream %fi-format %fi-args)
  (uiop/stream:finish-outputs %fi-stream))

;; The one that must never signal (an error handler's printer): the syntax is
;; safe and the format itself is guarded; the final flush runs either way.
(defun uiop/stream:safe-format! (%sfi-stream %sfi-format &rest %sfi-args)
  (uiop/stream:call-with-safe-io-syntax
   (lambda ()
     (ignore-errors
       (apply #'uiop/stream:format! %sfi-stream %sfi-format %sfi-args))
     (uiop/stream:finish-outputs %sfi-stream))))

(defun uiop/stream:println (%pl-x &optional (%pl-stream *standard-output*))
  (princ %pl-x %pl-stream)
  (terpri %pl-stream)
  (finish-output %pl-stream)
  (values))

(defun uiop/stream:writeln (%wl-x &rest %wl-keys &key
                                  ((:stream %wl-stream) *standard-output*)
                                  &allow-other-keys)
  (apply #'write %wl-x %wl-keys)
  (terpri %wl-stream)
  (finish-output %wl-stream)
  (values))

;;;; Stream predicates over the self-describing stream value
;;;; (.kb/read-load-streams.md): exact kind tests, recursing through synonym
;;;; streams for the file question (upstream's shape).
(defun uiop/stream:file-stream-p (%fsp-stream) (typep %fsp-stream 'file-stream))

(defun uiop/stream:file-or-synonym-stream-p (%fossp-stream)
  (or (uiop/stream:file-stream-p %fossp-stream)
      (and (typep %fossp-stream 'synonym-stream)
           (uiop/stream:file-or-synonym-stream-p
            (symbol-value (synonym-stream-symbol %fossp-stream))))))

;;;; Standard streams (.todo/360). These are the RAW underlying streams at
;;;; startup, distinct from *standard-output*: a program that captures
;;;; *standard-output* (with-output-to-string) and still wants the console has
;;;; the same escape hatch it has in SBCL -- *stdout* still names process
;;;; stdout, *stderr* the process error stream. setup-* re-derive from the
;;;; current standard stream, upstream's contract.
(defvar uiop/stream:*stdin* *standard-input*)
(defun uiop/stream:setup-stdin ()
  (setq uiop/stream:*stdin* *standard-input*)
  (values))
(defvar uiop/stream:*stdout* *standard-output*)
(defun uiop/stream:setup-stdout ()
  (setq uiop/stream:*stdout* *standard-output*)
  (values))
(defvar uiop/stream:*stderr* *error-output*)
(defun uiop/stream:setup-stderr ()
  (setq uiop/stream:*stderr* *error-output*)
  (values))

;;;; Encodings (.todo/360). One lite decision, not eight: every backend reads
;;;; and writes UTF-8 and there is no external-format surface, so
;;;; *default-encoding* is :utf-8, *utf-8-external-format* is that, the two
;;;; hooks are the identity functions upstream installs, and detect-encoding
;;;; answers :utf-8 without reading the file contents. default-encoding-
;;;; external-format keeps upstream's shape: :default and :utf-8 map, anything
;;;; else signals through the cerror (which lowers to an error here -- the
;;;; continue restart does not exist).
(defvar uiop/stream:*default-encoding* :utf-8)
(defvar uiop/stream:*utf-8-external-format* :utf-8)

(defun uiop/stream:always-default-encoding (%ade-pathname)
  (declare (ignore %ade-pathname))
  uiop/stream:*default-encoding*)

(defvar uiop/stream:*encoding-detection-hook*
  #'uiop/stream:always-default-encoding)

(defun uiop/stream:detect-encoding (%de-pathname)
  (if (and %de-pathname (not (uiop/pathname:directory-pathname-p %de-pathname))
           (uiop/filesystem:probe-file* %de-pathname))
      (funcall uiop/stream:*encoding-detection-hook* %de-pathname)
      uiop/stream:*default-encoding*))

(defun uiop/stream:default-encoding-external-format (%deef-encoding)
  (case %deef-encoding
    (:default :default)
    (:utf-8 uiop/stream:*utf-8-external-format*)
    (otherwise
     (cerror "Continue using :external-format :default for encoding ~S"
             %deef-encoding)
     :default)))

(defvar uiop/stream:*encoding-external-format-hook*
  #'uiop/stream:default-encoding-external-format)

(defun uiop/stream:encoding-external-format (%ee-encoding)
  (funcall uiop/stream:*encoding-external-format-hook*
           (or %ee-encoding uiop/stream:*default-encoding*)))

;;;; Null device (.todo/360). null-device-pathname is /dev/null on unix (the
;;;; one os-cond arm that exists here); the with-null-* family is implemented
;;;; over streams rather than the device -- a string stream that always returns
;;;; EOF, and the discarding sink make-broadcast-stream returns -- which is
;;;; faster and portable (on WASM the device is only openable if the host
;;;; preopened it).
(defun uiop/stream:null-device-pathname () #p"/dev/null")

(defun uiop/stream:call-with-null-input (%cw-ni-fun &key
                                         ((:element-type %cw-ni-et))
                                         ((:external-format %cw-ni-ef))
                                         ((:if-does-not-exist %cw-ni-idne)))
  (declare (ignore %cw-ni-et %cw-ni-ef %cw-ni-idne))
  (with-input-from-string (%cw-ni-s "") (funcall %cw-ni-fun %cw-ni-s)))

(defun uiop/stream:call-with-null-output (%cw-no-fun &key
                                          ((:element-type %cw-no-et))
                                          ((:external-format %cw-no-ef))
                                          ((:if-exists %cw-no-ie))
                                          ((:if-does-not-exist %cw-no-idne)))
  (declare (ignore %cw-no-et %cw-no-ef %cw-no-ie %cw-no-idne))
  (funcall %cw-no-fun (make-broadcast-stream)))

;;;; Temporary files (.todo/360). call-with-temporary-file is the real
;;;; function, the one place the temporary-file mechanism lives; with-
;;;; temporary-file (a LispMacroExpander expansion) and tmpize-pathname are
;;;; wrappers over it. The uniqueness rule is %temp-file-name's (the prelude
;;;; entry), called here -- no second rule.
(defvar uiop/stream:*temporary-directory* nil)

(defun uiop/stream:temporary-directory ()
  (or uiop/stream:*temporary-directory*
      (uiop/stream:default-temporary-directory)))

(defun uiop/stream:setup-temporary-directory ()
  (setq uiop/stream:*temporary-directory*
        (uiop/stream:default-temporary-directory))
  (values))

(defun uiop/stream:call-with-temporary-file (%cw-tf-thunk &key
                                             ((:want-stream-p
                                               %cw-tf-want-stream) t)
                                             ((:want-pathname-p
                                               %cw-tf-want-pathname) t)
                                             ((:direction %cw-tf-direction)
                                              :output) ((:keep %cw-tf-keep) nil)
                                             ((:directory %cw-tf-directory) nil)
                                             ((:type %cw-tf-type) "tmp")
                                             ((:prefix %cw-tf-prefix) nil)
                                             ((:element-type
                                               %cw-tf-element-type) 'character)
                                             ((:external-format
                                               %cw-tf-external-format) :utf-8))
  (check-type %cw-tf-direction (member :input :output))
  (assert (or %cw-tf-want-stream %cw-tf-want-pathname))
  (let* ((%cw-tf-d
          (namestring
           (uiop/pathname:ensure-directory-pathname
            (or %cw-tf-directory (uiop/stream:default-temporary-directory)))))
         (%cw-tf-pn (%temp-file-name %cw-tf-d %cw-tf-prefix %cw-tf-type))
         (%cw-tf-result nil))
    (unwind-protect (progn
                      (with-open-file (%cw-tf-s %cw-tf-pn
                                       :direction %cw-tf-direction
                                       :element-type %cw-tf-element-type
                                       :external-format %cw-tf-external-format
                                       :if-does-not-exist :create)
                        (when %cw-tf-want-stream
                          (setq %cw-tf-result
                                (if %cw-tf-want-pathname
                                    (funcall %cw-tf-thunk %cw-tf-s %cw-tf-pn)
                                    (funcall %cw-tf-thunk %cw-tf-s)))))
                      (when (and %cw-tf-want-pathname (not %cw-tf-want-stream))
                        (setq %cw-tf-result (funcall %cw-tf-thunk %cw-tf-pn))))
      (unless (uiop/utility:call-function %cw-tf-keep)
        (uiop/filesystem:delete-file-if-exists %cw-tf-pn)))
    %cw-tf-result))

(defun uiop/stream:tmpize-pathname (%tp-x)
  (let* ((%tp-px (uiop/pathname:ensure-pathname %tp-x :ensure-physical t))
         (%tp-name (pathname-name %tp-px))
         (%tp-prefix
          (if (and %tp-name (stringp %tp-name))
              (uiop/utility:strcat %tp-name "-tmp")
              "tmp"))
         (%tp-dir (uiop/pathname:pathname-directory-pathname %tp-px))
         (%tp-type (pathname-type %tp-px)))
    (uiop/stream:call-with-temporary-file (lambda (%tp-pn) (pathname %tp-pn))
     :want-stream-p nil
     :want-pathname-p t
     :directory %tp-dir
     :prefix %tp-prefix
     :type (and %tp-type (stringp %tp-type) %tp-type)
     :keep t)))

(defun uiop/stream:call-with-staging-pathname (%cwsp-pathname %cwsp-fun)
  (let* ((%cwsp-pathname (pathname %cwsp-pathname))
         (%cwsp-staging (uiop/stream:tmpize-pathname %cwsp-pathname)))
    (unwind-protect (multiple-value-prog1 (funcall %cwsp-fun %cwsp-staging)
                      (uiop/filesystem:rename-file-overwriting-target
                       %cwsp-staging %cwsp-pathname))
      (uiop/filesystem:delete-file-if-exists %cwsp-staging))))

(defun uiop/stream:add-pathname-suffix
    (%aps-pathname %aps-suffix &rest %aps-keys)
  (apply #'make-pathname
         :name (uiop/utility:strcat (pathname-name %aps-pathname) %aps-suffix)
         :defaults %aps-pathname %aps-keys))

(defvar uiop/stream:*default-stream-element-type* 'character)
