;;;; How ASDF finds a system definition: *system-definition-search-functions* and the
;;;; source registry, upstream's bodies (ASDF 3.3.1). The interpreter evaluates this
;;;; with asdf.lisp and its loader walks the list (%asdf-search-file); a compile path
;;;; splices it only for a program naming one of its members, and reads a literal
;;;; initialize-source-registry call in its build image (%asdf-read-source-registry).
;;;; See .kb/asdf.md, "The search".

(defvar asdf:*system-definition-search-functions*
  '(asdf::sysdef-package-inferred-system-search
    asdf::sysdef-central-registry-search asdf::sysdef-source-registry-search))

(defun asdf:search-for-system-definition (system)
  (let ((name (asdf:coerce-name system)))
    (dolist (function asdf:*system-definition-search-functions* nil)
      (let ((found (funcall function name))) (when found (return found))))))

;; Real ASDF's check-type: a string is no answer, as a pathname is.
(defun %asdf-check-found (found)
  (unless (or (null found) (pathnamep found) (typep found 'asdf:system))
    (error 'simple-type-error
           :datum found
           :expected-type '(or null pathname asdf:system)
           :format-control
           "A search for a system definition answered ~S, neither a pathname nor a system."
           :format-arguments (list found)))
  found)

(defun asdf:locate-system (name)
  (let* ((name (asdf:coerce-name name))
         (previous (asdf:registered-system name))
         (found (%asdf-check-found (asdf:search-for-system-definition name))))
    (let* ((found-system (and (typep found 'asdf:system) found))
           (source
            (or (and (pathnamep found) found)
                (and found-system (asdf:system-source-file found-system))
                (and previous (asdf:system-source-file previous))))
           (pathname (and source (pathname source))))
      (values (and (or found-system pathname previous) t) found-system pathname
              previous (and previous 0)))))

;; A file of a package-inferred system answers its system. What the file depends on is
;; read by the seam, which is also what the interpreter's loader falls back on.
(defun asdf::sysdef-package-inferred-system-search (system)
  (let* ((system (asdf:coerce-name system))
         (primary (asdf:primary-system-name system)))
    (unless (equal primary system)
      (when (typep (asdf:find-system primary nil) 'asdf:package-inferred-system)
        (%asdf-derive-system system)))))

;; Only a value that is no literal is evaluated: a compiled program should not need the
;; run-time evaluator for a directory it spelled. The entry is any pathname designator,
;; a directory with or without its trailing slash.
(defun asdf::sysdef-central-registry-search (system)
  (let ((name (asdf:primary-system-name system)))
    (dolist (entry asdf:*central-registry* nil)
      (let ((defaults
             (if (or (stringp entry) (pathnamep entry)) entry (eval entry))))
        (when defaults
          (let ((file
                 (uiop/pathname:merge-pathnames*
                  (concatenate 'string name ".asd")
                  (uiop/pathname:ensure-directory-pathname defaults))))
            (when (probe-file file) (return file))))))))

(defvar asdf:*source-registry-parameter* nil)

(defvar asdf::*default-source-registry-exclusions*
  '(".bzr" ".cdv" ".git" ".hg" ".pc" ".svn" "CVS" "RCS" "SCCS" "_darcs" "_sgbak"
    "autom4te.cache" "cover_db" "_build" "debian"))

(defvar asdf::*source-registry-exclusions*
  asdf::*default-source-registry-exclusions*)

;; Nil until initialized, then name -> .asd for what stands ahead of the inherited
;; configuration. What a configuration names behind it is in %asdf-registry-behind%.
(defvar asdf::*source-registry* nil)

(defvar %asdf-registry-behind% nil)

;; Whether the configuration reached :inherit-configuration, so its search path counts.
(defvar %asdf-registry-inherits% t)

(defvar asdf::*wild-asd* #P"*.asd")

(defvar asdf::*recurse-beyond-asds* t)

;; What a configuration inherits here is rontolisp's search path, not the user's
;; ~/common-lisp and ~/.config: a program's search does not depend on the machine
;; beyond what --system-path and RONTOLISP_SOURCE_REGISTRY say. :default-registry
;; still names upstream's.
(defvar asdf:*default-source-registries* '(%asdf-inherited-search-path))

(defun asdf::source-registry-initialized-p ()
  (typep asdf::*source-registry* 'hash-table))

(defun asdf:clear-source-registry ()
  (setq asdf::*source-registry* nil)
  (values))

(uiop/configuration:register-clear-configuration-hook
 'asdf:clear-source-registry)

(defun asdf::directory-asd-files (directory)
  (uiop/filesystem:directory-files directory asdf::*wild-asd*))

(defun asdf::collect-asds-in-directory (directory collect)
  (let ((asds (asdf::directory-asd-files directory)))
    (mapc collect asds)
    asds))

(defun asdf::collect-sub*directories-asd-files (directory &key
                                                (exclude
                                                 asdf::*default-source-registry-exclusions*)
                                                collect
                                                (recurse-beyond-asds
                                                 asdf::*recurse-beyond-asds*)
                                                ignore-cache)
  (let ((visited (make-hash-table :test 'equalp)))
    (flet ((cached-p ()
             ;; Upstream reads it at the root of the tree only.
             (let ((cache
                    (and (not ignore-cache)
                         (ignore-errors
                           (uiop/stream:safe-read-file-form
                            (uiop/pathname:subpathname directory
                             ".cl-source-registry.cache"))))))
               (when (and (consp cache) (eq :source-registry-cache (car cache)))
                 (dolist (entry (cdr cache))
                   (funcall collect
                            (uiop/pathname:subpathname directory entry)))
                 t))))
      (uiop/filesystem:collect-sub*directories directory
       (lambda (dir)
         (unless (cached-p)
           (let ((asds (asdf::collect-asds-in-directory dir collect)))
             (or recurse-beyond-asds (not asds)))))
       (lambda (x)
         (and (not
               (member (car (last (pathname-directory x))) exclude
                       :test #'equal))
              (let ((key (namestring (uiop/filesystem:truename* x))))
                (if (gethash key visited) nil (setf (gethash key visited) t)))))
       (constantly nil)))))

(defun asdf::validate-source-registry-directive (directive)
  (or (eq directive :default-registry)
      (and (consp directive)
           (let ((rest (cdr directive)))
             (case (car directive)
               ((:include :directory :tree)
                (and (uiop/utility:length=n-p rest 1)
                     (uiop/configuration:location-designator-p (car rest))))
               ((:exclude :also-exclude) (every #'stringp rest))
               ((:default-registry) (null rest)))))))

(define-condition asdf::invalid-source-registry
    (uiop/configuration:invalid-configuration warning)
  ((format :initform "Invalid source registry ~S~@[ in ~S~]")))

(defun asdf::validate-source-registry-form (form &key location)
  (uiop/configuration:validate-configuration-form form
   :source-registry #'asdf::validate-source-registry-directive
   :location location
   :invalid-form-reporter 'asdf::invalid-source-registry))

(defun asdf::validate-source-registry-file (file)
  (uiop/configuration:validate-configuration-file file
   #'asdf::validate-source-registry-form
   :description "a source registry"))

(defun asdf::validate-source-registry-directory (directory)
  (uiop/configuration:validate-configuration-directory directory
   :source-registry #'asdf::validate-source-registry-directive
   :invalid-form-reporter 'asdf::invalid-source-registry))

(defun asdf::parse-source-registry-string (string &key location)
  (cond ((or (null string) (equal string ""))
         '(:source-registry :inherit-configuration))
        ((not (stringp string)) (error "Environment string isn't: ~S" string))
        ((find (char string 0) "\"(")
         (asdf::validate-source-registry-form (read-from-string string)
                                              :location location))
        (t
         (let ((inherit nil) (directives nil) (start 0))
           (flet ((check (dir)
                    (unless (uiop/pathname:absolute-pathname-p dir)
                      (error "source-registry string must specify absolute pathnames: ~S"
                             string))
                    dir))
             (loop
               (let* ((pos
                       (position (uiop/filesystem:inter-directory-separator)
                                 string
                                 :start start))
                      (s (subseq string start pos)))
                 (cond ((equal "" s)
                        (when inherit
                          (error "Only one inherited configuration allowed: ~S"
                                 string))
                        (setq inherit t)
                        (push :inherit-configuration directives))
                       ((uiop/utility:string-suffix-p s "//")
                        (push (list :tree (check (subseq s 0 (- (length s) 2))))
                              directives))
                       (t (push (list :directory (check s)) directives)))
                 (cond (pos (setq start (+ pos 1)))
                       (t
                        (unless inherit
                          (push '(:ignore-inherited-configuration) directives))
                        (return
                         (cons :source-registry (reverse directives))))))))))))

(defun asdf::register-asd-directory (directory &key recurse exclude collect)
  (if (not recurse)
      (asdf::collect-asds-in-directory directory collect)
      (asdf::collect-sub*directories-asd-files directory
                                               :exclude exclude
                                               :collect collect)))

(defun asdf::wrapping-source-registry ()
  '(:source-registry :inherit-configuration))

(defun asdf::default-user-source-registry ()
  (list :source-registry '(:tree (:home "common-lisp/"))
   (list :directory (uiop/configuration:xdg-data-home "common-lisp/systems/"))
   (list :tree (uiop/configuration:xdg-data-home "common-lisp/source/"))
   :inherit-configuration))

(defun asdf::default-system-source-registry ()
  (append (list :source-registry)
          (mapcan (lambda (dir)
                    (list (list :directory (list dir "systems/"))
                          (list :tree (list dir "source/"))))
                  (uiop/configuration:xdg-data-dirs "common-lisp/"))
          (list :inherit-configuration)))

(defun asdf:user-source-registry (&key (direction :input))
  (uiop/configuration:xdg-config-pathname "common-lisp/source-registry.conf"
                                          direction))

(defun asdf:system-source-registry (&key (direction :input))
  (uiop/configuration:find-preferred-file
   (uiop/configuration:system-config-pathnames
    "common-lisp/source-registry.conf")
   :direction direction))

(defun asdf:user-source-registry-directory (&key (direction :input))
  (uiop/configuration:xdg-config-pathname "common-lisp/source-registry.conf.d/"
                                          direction))

(defun asdf:system-source-registry-directory (&key (direction :input))
  (uiop/configuration:find-preferred-file
   (uiop/configuration:system-config-pathnames
    "common-lisp/source-registry.conf.d/")
   :direction direction))

(defun asdf::environment-source-registry ()
  (uiop/os:getenv "CL_SOURCE_REGISTRY"))

(defgeneric asdf:process-source-registry (spec &key inherit register))

(defun asdf::inherit-source-registry (inherit &key register)
  (when inherit
    (asdf:process-source-registry (car inherit)
                                  :register register
                                  :inherit (cdr inherit))))

(defun %asdf-process-source-registry-directive (directive inherit register)
  (let* ((form (if (consp directive) directive (list directive)))
         (kw (car form))
         (location (car (cdr form))))
    (case kw
      ((:include)
       (asdf:process-source-registry
        (uiop/configuration:resolve-location location)
        :inherit nil
        :register register))
      ((:directory)
       (when location
         (funcall register
          (uiop/configuration:resolve-location location :ensure-directory t))))
      ((:tree)
       (when location
         (funcall register
          (uiop/configuration:resolve-location location :ensure-directory t)
          :recurse t
          :exclude asdf::*source-registry-exclusions*)))
      ((:exclude) (setq asdf::*source-registry-exclusions* (cdr form)))
      ((:also-exclude)
       (setq asdf::*source-registry-exclusions*
             (append asdf::*source-registry-exclusions* (cdr form))))
      ((:default-registry)
       (asdf::inherit-source-registry '(asdf::default-user-source-registry asdf::default-system-source-registry)
                                      :register register))
      ((:inherit-configuration)
       (asdf::inherit-source-registry inherit :register register))
      ((:ignore-inherited-configuration) nil)
      (t (error "Invalid source registry directive: ~S" directive))))
  nil)

(defmethod asdf:process-source-registry ((x symbol) &key inherit register)
  (asdf:process-source-registry (funcall x)
                                :inherit inherit
                                :register register))

(defmethod asdf:process-source-registry
    ((pathname pathname) &key inherit register)
  (cond ((uiop/pathname:directory-pathname-p pathname)
         (let ((uiop/configuration:*here-directory*
                (uiop/filesystem:resolve-symlinks* pathname)))
           (asdf:process-source-registry
            (asdf::validate-source-registry-directory pathname)
            :inherit inherit
            :register register)))
        ((uiop/filesystem:probe-file* pathname)
         (let ((uiop/configuration:*here-directory*
                (uiop/pathname:pathname-directory-pathname pathname)))
           (asdf:process-source-registry
            (asdf::validate-source-registry-file pathname)
            :inherit inherit
            :register register)))
        (t (asdf::inherit-source-registry inherit :register register))))

(defmethod asdf:process-source-registry ((string string) &key inherit register)
  (asdf:process-source-registry (asdf::parse-source-registry-string string)
                                :inherit inherit
                                :register register))

(defmethod asdf:process-source-registry ((x null) &key inherit register)
  (asdf::inherit-source-registry inherit :register register))

(defmethod asdf:process-source-registry ((form cons) &key inherit register)
  (let ((asdf::*source-registry-exclusions*
         asdf::*default-source-registry-exclusions*))
    (dolist (directive (cdr (asdf::validate-source-registry-form form)))
      (%asdf-process-source-registry-directive directive inherit register))))

;; The flattened entries while a configuration is processed, newest first. The search
;; path has no directories to scan: it is probed where a search asks, so it stands in
;; them as this one mark.
(defvar %asdf-flattened-entries% nil)

(defun %asdf-inherited-search-path ()
  (push :search-path %asdf-flattened-entries%)
  nil)

(defun %asdf-flatten-source-registry (parameter)
  (let ((%asdf-flattened-entries% nil))
    (asdf::inherit-source-registry (append (list 'asdf::wrapping-source-registry
                                                 parameter)
                                           asdf:*default-source-registries*)
                                   :register
                                   (lambda (directory &key recurse exclude)
                                     (push (list directory
                                                 :recurse recurse
                                                 :exclude exclude)
                                           %asdf-flattened-entries%)))
    (remove-duplicates (reverse %asdf-flattened-entries%)
                       :test 'equal
                       :from-end t)))

(defun asdf::flatten-source-registry
    (&optional (parameter asdf:*source-registry-parameter*))
  (mapcan (lambda (entry)
            (if (eq entry :search-path)
                (mapcar (lambda (directory)
                          (list
                           (uiop/pathname:ensure-directory-pathname directory)
                           :recurse nil
                           :exclude nil)) (%asdf-search-path))
                (list entry))) (%asdf-flatten-source-registry parameter)))

(defun %asdf-preferred-source-path-p (x y)
  (flet ((depth (path) (length (pathname-directory path))))
    (or (< (depth x) (depth y))
        (and (= (depth x) (depth y)) (string< (namestring x) (namestring y))))))

(defun asdf:compute-source-registry (&optional
                                     (parameter
                                      asdf:*source-registry-parameter*)
                                     (registry asdf::*source-registry*))
  (let ((table registry))
    (setq %asdf-registry-inherits% nil)
    (unless %asdf-registry-behind%
      (setq %asdf-registry-behind% (make-hash-table :test 'equal)))
    (dolist (entry (%asdf-flatten-source-registry parameter))
      (if (eq entry :search-path)
          (progn
            (setq %asdf-registry-inherits% t)
            (setq table %asdf-registry-behind%))
          (let ((h (make-hash-table :test 'equal)))
            (asdf::register-asd-directory (car entry)
                                          :recurse (getf (cdr entry) :recurse)
                                          :exclude (getf (cdr entry) :exclude)
                                          :collect
                                          (lambda (asd)
                                            (let ((name (pathname-name asd)))
                                              (unless (or
                                                       (gethash name registry)
                                                       (gethash name
                                                        %asdf-registry-behind%))
                                                (let ((old (gethash name h)))
                                                  (when (or (null old)
                                                            (%asdf-preferred-source-path-p
                                                             asd old))
                                                    (setf (gethash name h)
                                                          asd)))))))
            (maphash (lambda (k v) (setf (gethash k table) v)) h)))))
  (values))

(defun asdf:initialize-source-registry
    (&optional (parameter asdf:*source-registry-parameter*))
  (setq asdf:*source-registry-parameter* parameter)
  (setq asdf::*source-registry* (make-hash-table :test 'equal))
  (setq %asdf-registry-behind% (make-hash-table :test 'equal))
  (asdf:compute-source-registry parameter))

(defun asdf:ensure-source-registry (&optional parameter)
  (unless (asdf::source-registry-initialized-p)
    (asdf:initialize-source-registry parameter))
  (values))

(defun asdf::sysdef-source-registry-search (system)
  (asdf:ensure-source-registry)
  (let ((name (asdf:primary-system-name system)))
    (or (gethash name asdf::*source-registry*)
        (let ((found (%asdf-search-path-locate name %asdf-registry-inherits%)))
          (and found (pathname found))) (gethash name %asdf-registry-behind%))))
