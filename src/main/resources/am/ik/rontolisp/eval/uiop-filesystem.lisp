;;;; uiop/filesystem -- probe and list the file system. Canonical shape; see .kb/uiop.md.

;; file-exists-p IS probe-file (same contract: the truename on success, nil
;; otherwise). Both compile paths additionally FOLD a direct call onto the
;; probe-file primitive (LispMacroExpander.expandUiopStubCall), so this
;; definition is what a first-class #'uiop:file-exists-p resolves to.
(defun uiop/filesystem:file-exists-p (%fep-path) (probe-file %fep-path))

;; A rontolisp namestring IS the host spelling (no backend translates), so the
;; native namestring is cl:namestring. Folded on the compile paths like
;; file-exists-p above.
(defun uiop/filesystem:native-namestring (%nns-path) (namestring %nns-path))

(defun uiop/filesystem:directory-exists-p (%de-path)
  (let ((%de-d (%dir-namestring %de-path)))
    (if (%list-directory (if (string= %de-d "") "." %de-d))
        (pathname %de-d)
        nil)))

;; The optional PATTERN is UIOP's own second argument: a name-and-type wildcard
;; (never a directory one -- real UIOP signals "Invalid file pattern" for that and
;; so does this), appended to the directory and matched by the same `directory`
;; rules. mito's migration reader spells it (uiop:directory-files dir "*.up.sql").
(defun uiop/filesystem:directory-files (%df-dir &optional (%df-pat "*.*"))
  (when (position #\/ (%path-ns %df-pat))
    (error "Invalid file pattern ~S" %df-pat))
  (let ((%df-acc nil))
    (dolist (%df-e
             (directory
              (concatenate 'string (%dir-namestring %df-dir)
                           (%path-ns %df-pat))))
      (let ((%df-s (namestring %df-e)))
        (unless (char= (char %df-s (- (length %df-s) 1)) #\/)
          (setq %df-acc (cons %df-e %df-acc)))))
    (nreverse %df-acc)))

(defun uiop/filesystem:subdirectories (%sd-dir)
  (let ((%sd-acc nil))
    (dolist (%sd-e
             (directory (concatenate 'string (%dir-namestring %sd-dir) "*.*")))
      (let ((%sd-s (namestring %sd-e)))
        (when (char= (char %sd-s (- (length %sd-s) 1)) #\/)
          (setq %sd-acc (cons %sd-e %sd-acc)))))
    (nreverse %sd-acc)))

(defun uiop/filesystem:collect-sub*directories
    (%cd-dir %cd-collectp %cd-recursep %cd-collector)
  (let ((%cd-d (pathname (%dir-namestring %cd-dir))))
    (when (funcall %cd-collectp %cd-d) (funcall %cd-collector %cd-d))
    (dolist (%cd-sub (uiop/filesystem:subdirectories %cd-d))
      (when (funcall %cd-recursep %cd-sub)
        (uiop/filesystem:collect-sub*directories %cd-sub %cd-collectp
                                                 %cd-recursep %cd-collector))))
  nil)

;; delete-file with the missing-file file-error swallowed -- the whole reason
;; real UIOP exports it. Over the %delete-file primitive rather than over
;; delete-file, so the "missing file" answer is nil in one step.
(defun uiop/filesystem:delete-file-if-exists (%dfe-path)
  (if (and %dfe-path (%delete-file (%path-ns %dfe-path))) t nil))

;; The defaults relative names resolve against. Upstream absolutizes them
;; against getcwd; rontolisp absolutizes nowhere (every backend resolves a
;; relative path against the host's working directory), so the honest answer is
;; the defaults themselves -- *default-pathname-defaults* unless overridden,
;; whose initial #P"" designates exactly the host working directory, keeping
;; (merge-pathnames x (uiop:get-pathname-defaults)) = x. This retired the
;; Java built-in that predated it and answered the literal "" before the
;; special existed.
(defun uiop/filesystem:get-pathname-defaults
    (&optional (%gpd-defaults *default-pathname-defaults*))
  (or (uiop/pathname:absolute-pathname-p %gpd-defaults)
      (pathname (%path-ns %gpd-defaults))))

;;;; Probing: truename*, probe-file*, directory* and safe-file-write-date.
;;;; Upstream's per-implementation stat dance collapses here: probe-file is the
;;;; one existence probe on every backend (a directory counts as existing,
;;;; nothing resolves symlinks or absolutizes), truename is probe-file plus a
;;;; signal, and directory is the one listing over %list-directory
;;;; (.kb/directory-listing.md). So probe-file* parses, probes, and answers
;;;; either the parsed pathname or its truename; truename* is the nil-tolerant
;;;; truename that also tries the directory form (a missing trailing separator
;;;; defeats some implementations' truename, and the fallback keeps this total
;;;; where upstream needs it); directory* is directory with the
;;;; symlink-resolution keys upstream passes dropped (nothing here resolves
;;;; them); safe-file-write-date is file-write-date with the missing-file
;;;; file-error swallowed -- nil on both WASM backends, where file-write-date
;;;; itself is nil (.kb/read-load-streams.md).
(defun uiop/filesystem:truename* (%tns-p)
  (when %tns-p
    (let ((%tns-x (pathname (%path-ns %tns-p))))
      (or (ignore-errors (truename %tns-x))
          (ignore-errors
            (truename (uiop/pathname:ensure-directory-pathname %tns-x)))))))

;; Upstream passes :defaults 'get-pathname-defaults (the function designator);
;; the value is read eagerly here instead -- ensure-absolute-pathname only
;; funcalls a FUNCTION value, and a symbol is not one.
(defun uiop/filesystem:probe-file*
    (%pfp-p &rest %pfp-keys &key ((:truename %pfp-truename)) &allow-other-keys)
  (declare (ignore %pfp-keys))
  ;; The whole probe is guarded: a designator ensure-pathname rejects (a wild
  ;; pathname, a non-absolute one it cannot absolutize) answers nil, exactly
  ;; like a file that is not there. probe-file itself never signals.
  (ignore-errors
    (let ((%pfp-x
           (uiop/pathname:ensure-pathname %pfp-p
            :namestring :lisp
            :ensure-physical t
            :ensure-absolute t
            :defaults (uiop/filesystem:get-pathname-defaults)
            :want-non-wild t
            :on-error nil)))
      (when %pfp-x
        (if %pfp-truename
            (probe-file %pfp-x)
            (and (probe-file %pfp-x) %pfp-x))))))

;; Upstream forwards its keys to directory (per-implementation symlink knobs);
;; there is nothing to forward to here -- directory takes the pathspec alone
;; and nothing resolves symlinks -- so they are accepted and dropped.
(defun uiop/filesystem:directory*
    (%ds-spec &rest %ds-keys &key &allow-other-keys)
  (declare (ignore %ds-keys))
  (directory %ds-spec))

;; Logical pathnames cannot exist (logical-pathname-p is nil on every backend,
;; .kb/uiop.md), so there is nothing to filter and no merger to call: the
;; entries pass through, identically everywhere.
(defun uiop/filesystem:filter-logical-directory-results
    (%fld-directory %fld-entries %fld-merger)
  (declare (ignore %fld-directory %fld-merger))
  %fld-entries)

(defun uiop/filesystem:safe-file-write-date (%sfwd-pathname)
  (and %sfwd-pathname
       (handler-case (file-write-date
                      (uiop/pathname:physicalize-pathname %sfwd-pathname))
         (file-error () nil))))

;;;; Native namestrings and environment pathnames. os-unix-p is t outright on
;;;; every backend (.kb/uiop.md), so the native spelling IS the Unix one:
;;;; parse-native-namestring is parse-unix-namestring plus the ensure-pathname
;;;; constraints, and the separator is #\:.
(defun uiop/filesystem:parse-native-namestring (%pnn-string &rest
                                                %pnn-constraints &key
                                                ((:ensure-directory
                                                  %pnn-ensure-directory))
                                                &allow-other-keys)
  (check-type %pnn-string (or string null))
  (let ((%pnn-pathname
         (when %pnn-string
           (uiop/pathname:parse-unix-namestring %pnn-string
            :ensure-directory %pnn-ensure-directory))))
    (apply #'uiop/pathname:ensure-pathname %pnn-pathname %pnn-constraints)))

(defun uiop/filesystem:inter-directory-separator () #\:)

;; An empty component denotes NIL, so a leading, trailing or doubled separator
;; answers nil in that position -- which is what makes getenv-pathnames answer
;; NILs for the empty entries of its variable.
(defun uiop/filesystem:split-native-pathnames-string
    (%snps-string &rest %snps-constraints &key &allow-other-keys)
  (let ((%snps-parts
         (uiop/utility:split-string (or %snps-string "")
          :separator (list (uiop/filesystem:inter-directory-separator)))))
    (mapcar (lambda (%snps-one)
              (unless (uiop/utility:emptyp %snps-one)
                (apply #'uiop/filesystem:parse-native-namestring %snps-one
                       %snps-constraints))) %snps-parts)))

;; Upstream's default on-error is a (error ...) FORM evaluated for its message;
;; call-function on that cons applies the error function to the report, so the
;; shape is kept rather than flattened into a direct error.
(defun uiop/filesystem:getenv-pathname (%gep-x &rest %gep-constraints &key
                                        ((:ensure-directory
                                          %gep-ensure-directory))
                                        ((:want-directory %gep-want-directory))
                                        ((:on-error %gep-on-error))
                                        &allow-other-keys)
  (apply #'uiop/filesystem:parse-native-namestring (uiop/os:getenvp %gep-x)
         :ensure-directory (or %gep-ensure-directory %gep-want-directory)
         :on-error (or %gep-on-error
                       (list 'error "In (~S ~S), invalid pathname ~*~S: ~*~?"
                             'getenv-pathname %gep-x)) %gep-constraints))

(defun uiop/filesystem:getenv-pathnames (%geps-x &rest %geps-constraints &key
                                                 ((:on-error %geps-on-error))
                                                 &allow-other-keys)
  (unless (getf %geps-constraints :empty-is-nil t)
    (uiop/utility:parameter-error "Cannot have EMPTY-IS-NIL false for ~S"
                                  'uiop/filesystem:getenv-pathnames))
  (apply #'uiop/filesystem:split-native-pathnames-string
         (uiop/os:getenvp %geps-x)
         :on-error (or %geps-on-error
                       (list 'error "In (~S ~S), invalid pathname ~*~S: ~*~?"
                             'getenv-pathnames %geps-x))
         :empty-is-nil t %geps-constraints))

(defun uiop/filesystem:getenv-absolute-directory (%gad-x)
  (uiop/filesystem:getenv-pathname %gad-x :want-absolute t :ensure-directory t))

(defun uiop/filesystem:getenv-absolute-directories (%gads-x)
  (uiop/filesystem:getenv-pathnames %gads-x
                                    :want-absolute t
                                    :ensure-directory t))

;;;; The implementation's own directory. Rontolisp has no install directory to
;;;; name -- there is no compile-file and no fasl cache segregated by
;;;; architecture -- so the honest answer is nil, and a pathname is therefore
;;;; never under it. The shape stays upstream's so the day a backend gains one
;;;; only the constant moves.
(defun uiop/filesystem:lisp-implementation-directory
    (&rest %lid-keys &key ((:truename %lid-truename)) &allow-other-keys)
  (declare (ignore %lid-keys %lid-truename))
  nil)

(defun uiop/filesystem:lisp-implementation-pathname-p (%lipp-pathname)
  (and %lipp-pathname
       (let ((%lipp-impdir (uiop/filesystem:lisp-implementation-directory)))
         (and %lipp-impdir
              (or (uiop/pathname:subpathp %lipp-pathname %lipp-impdir)
                  (when uiop/filesystem:*resolve-symlinks*
                    (let ((%lipp-true
                           (uiop/filesystem:truename* %lipp-pathname))
                          (%lipp-trueimp
                           (uiop/filesystem:truename* %lipp-impdir)))
                      (and %lipp-true %lipp-trueimp
                           (uiop/pathname:subpathp %lipp-true %lipp-trueimp)))))
              t))))

;;;; Symlinks. No backend resolves them -- truename carries the argument
;;;; namestring on all four (.kb/pathnames.md) -- so *resolve-symlinks*
;;;; defaults to nil (upstream's t would promise what is not there) and the
;;;; functions are the identity over a pathname coercion, exactly what upstream
;;;; answers on an implementation without the API. That is coverage, not a
;;;; stub (.kb/uiop.md).
(defvar uiop/filesystem:*resolve-symlinks* nil)

;; Upstream absolutizes against get-pathname-defaults first; rontolisp
;; absolutizes nowhere (see get-pathname-defaults above), so the coercion is
;; the whole function.
(defun uiop/filesystem:truenamize (%tnz-pathname)
  (when %tnz-pathname (pathname (%path-ns %tnz-pathname))))

(defun uiop/filesystem:resolve-symlinks (%rs-path)
  (uiop/filesystem:truenamize %rs-path))

(defun uiop/filesystem:resolve-symlinks* (%rss-path)
  (if uiop/filesystem:*resolve-symlinks*
      (and %rss-path (uiop/filesystem:resolve-symlinks %rss-path))
      %rss-path))

;;;; The working directory. call-with-current-directory inherits chdir's
;;;; decision (.kb/uiop.md): chdir signals on every backend, so a non-nil dir
;;;; signals UIOP/OS:CHDIR identically on all four -- the binding and the
;;;; restore are upstream's shape, kept so only the primitive moves the day it
;;;; becomes real. chdir runs BEFORE getcwd so the signal names the same
;;;; operation everywhere (getcwd has no answer on either WASM backend). A nil
;;;; dir just runs the thunk. with-current-directory is the macro over it in
;;;; LispMacroExpander, like every other uiop macro.
(defun uiop/filesystem:call-with-current-directory (%cwcd-dir %cwcd-thunk)
  (if %cwcd-dir
      (let* ((%cwcd-d
              (uiop/filesystem:resolve-symlinks*
               (uiop/filesystem:get-pathname-defaults
                (uiop/pathname:ensure-directory-pathname %cwcd-dir))))
             (*default-pathname-defaults* %cwcd-d))
        (uiop/os:chdir %cwcd-d)
        (let ((%cwcd-cwd (uiop/os:getcwd)))
          (unwind-protect (funcall %cwcd-thunk) (uiop/os:chdir %cwcd-cwd))))
      (funcall %cwcd-thunk)))

;;;; Mutating the tree. Each is Lisp over the one primitive the matching CL
;;;; operator already bottoms out in, so the write side is real wherever the
;;;; primitive is: on all four backends, except removing a DIRECTORY on WASM
;;;; (preview1 has no path_remove_directory import), where the call signals the
;;;; SAME call-time error the primitive signals, with no second code path and no
;;;; silent no-op (.kb/uiop.md).
(defun uiop/filesystem:ensure-all-directories-exist (%eade-pathnames)
  (dolist (%eade-pathname %eade-pathnames)
    (when %eade-pathname
      (ensure-directories-exist
       (uiop/pathname:physicalize-pathname %eade-pathname)))))

;; Files.move already replaces the target (REPLACE_EXISTING), so the overwrite
;; is the primitive's own -- no delete-then-rename window. Lite: CL's extra
;; values are not returned, the rename-file rule (.kb/read-load-streams.md).
(defun uiop/filesystem:rename-file-overwriting-target
    (%rfot-source %rfot-target)
  (let ((%rfot-s
         (uiop/pathname:ensure-pathname %rfot-source
                                        :namestring :lisp
                                        :ensure-physical t
                                        :want-file t))
        (%rfot-t
         (uiop/pathname:ensure-pathname %rfot-target
                                        :namestring :lisp
                                        :ensure-physical t
                                        :want-file t)))
    (rename-file %rfot-s %rfot-t)))

;; Files.deleteIfExists removes an empty directory as well as a file, so the
;; file primitive is the directory one -- and a non-empty directory answers
;; the same refusal a file the host would not remove does.
(defun uiop/filesystem:delete-empty-directory (%ded-directory-pathname)
  (delete-file
   (uiop/pathname:ensure-directory-pathname %ded-directory-pathname)))

;; Upstream's rm -rf branch (spawning run-program, which has no backend here)
;; is not taken: the portable recursive walk below is the whole function, over
;; the same primitives. An explicit :validate nil fails the first check rather
;; than the second -- both are the same parameter-error naming this function.
(defun uiop/filesystem:delete-directory-tree (%ddt-directory-pathname &rest
                                              %ddt-keys &key
                                              ((:validate %ddt-validate))
                                              ((:if-does-not-exist
                                                %ddt-if-does-not-exist) :error)
                                              &allow-other-keys)
  (declare (ignore %ddt-keys))
  (unless (or (eq %ddt-if-does-not-exist :error)
              (eq %ddt-if-does-not-exist :ignore))
    (error "DELETE-DIRECTORY-TREE: :if-does-not-exist must be :error or :ignore, got ~S"
           %ddt-if-does-not-exist))
  (setq %ddt-directory-pathname
        (uiop/pathname:ensure-pathname %ddt-directory-pathname
                                       :want-pathname t
                                       :want-non-wild t
                                       :want-physical t
                                       :want-directory t))
  (cond
   ((not %ddt-validate)
    (uiop/utility:parameter-error
     "~S was asked to delete ~S but was not provided a validation predicate"
     'uiop/filesystem:delete-directory-tree %ddt-directory-pathname))
   ((not (uiop/utility:call-function %ddt-validate %ddt-directory-pathname))
    (uiop/utility:parameter-error
     "~S was asked to delete ~S but it is not valid ~@[according to ~S~]"
     'uiop/filesystem:delete-directory-tree %ddt-directory-pathname
     %ddt-validate))
   ((not (uiop/filesystem:directory-exists-p %ddt-directory-pathname))
    (ecase %ddt-if-does-not-exist
      (:error
       (error "~S was asked to delete ~S but the directory does not exist"
              'uiop/filesystem:delete-directory-tree %ddt-directory-pathname))
      (:ignore nil)))
   ;; The collector below conses, so the collected list is already deepest
   ;; first (children before their parents) -- which is the deletion order,
   ;; no reversal needed.
   (t (let ((%ddt-subs nil))
        (uiop/filesystem:collect-sub*directories %ddt-directory-pathname
         (constantly t) (constantly t)
         (lambda (%ddt-d) (setq %ddt-subs (cons %ddt-d %ddt-subs))))
        (dolist (%ddt-d %ddt-subs)
          (dolist (%ddt-f (uiop/filesystem:directory-files %ddt-d))
            (delete-file %ddt-f))
          (uiop/filesystem:delete-empty-directory %ddt-d))))))
