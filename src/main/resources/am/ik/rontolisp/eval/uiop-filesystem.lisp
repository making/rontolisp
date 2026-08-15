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
;; pre-.todo/036 Java built-in that answered the literal "" before the special
;; existed.
(defun uiop/filesystem:get-pathname-defaults
    (&optional (%gpd-defaults *default-pathname-defaults*))
  (or (uiop/pathname:absolute-pathname-p %gpd-defaults)
      (pathname (%path-ns %gpd-defaults))))
