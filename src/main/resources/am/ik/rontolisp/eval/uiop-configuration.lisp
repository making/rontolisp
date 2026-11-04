;;;; uiop/configuration -- ASDF's configuration DSL and where its files live.
;;;; Upstream's bodies (ASDF 3.3.1's uiop/configuration), which the source registry
;;;; (asdf-search.lisp) stands on. See .kb/uiop.md.
;;;;
;;;; A location is absolute by its first element and relative by the rest:
;;;; (:here "sub/"), (:home "common-lisp/"). :here is the directory
;;;; *here-directory* holds, else the one of the file being loaded, whose truename is
;;;; the absolute one here: *load-pathname* is the spelling load was given.

(defvar uiop/configuration:*here-directory* nil)

(defvar uiop/configuration:*ignored-configuration-form* nil)

(defvar uiop/configuration:*clear-configuration-hook* nil)

(define-condition uiop/configuration:invalid-configuration ()
  ((form :initarg :form) (location :initarg :location) (format :initarg :format)
   (arguments :initarg :arguments :initform nil))
  (:report
   (lambda (%ic-c %ic-s)
     (format %ic-s "~? (will be skipped)" (slot-value %ic-c 'format)
             (append
              (list (slot-value %ic-c 'form) (slot-value %ic-c 'location))
              (slot-value %ic-c 'arguments))))))

(defun uiop/configuration:configuration-inheritance-directive-p (%cid-x)
  (let ((%cid-kw '(:inherit-configuration :ignore-inherited-configuration)))
    (or (member %cid-x %cid-kw)
     (and (uiop/utility:length=n-p %cid-x 1) (member (car %cid-x) %cid-kw)))))

;; A direct error of a made condition: a funcalled #'error is a raw trap on the wasm
;; backends, where only a direct call is a signal a handler sees.
(defun uiop/configuration:report-invalid-form (%rif-reporter &rest %rif-args)
  (cond ((null %rif-reporter)
         (error
          (apply #'make-condition 'uiop/configuration:invalid-configuration
                 %rif-args)))
        ((functionp %rif-reporter) (apply %rif-reporter %rif-args))
        ((symbolp %rif-reporter)
         (error (apply #'make-condition %rif-reporter %rif-args)))
        ((stringp %rif-reporter)
         (error "~A" (apply #'format nil %rif-reporter %rif-args)))
        ((consp %rif-reporter) (apply #'apply (append %rif-reporter %rif-args)))
        (t (error "Invalid form reporter: ~S" %rif-reporter))))

(defun uiop/configuration:validate-configuration-form (%vcf-form %vcf-tag
                                                       %vcf-validator &key
                                                       ((:location
                                                         %vcf-location))
                                                       ((:invalid-form-reporter
                                                         %vcf-reporter)))
  (block nil
    (unless (and (consp %vcf-form) (eq (car %vcf-form) %vcf-tag))
      (setq uiop/configuration:*ignored-configuration-form* t)
      (uiop/configuration:report-invalid-form %vcf-reporter
                                              :form %vcf-form
                                              :location %vcf-location)
      (return nil))
    (let ((%vcf-inherit 0) (%vcf-ignore-invalid nil) (%vcf-x (list %vcf-tag)))
      (dolist (%vcf-directive (cdr %vcf-form))
        (when (cond ((uiop/configuration:configuration-inheritance-directive-p
                      %vcf-directive)
                     (setq %vcf-inherit (+ %vcf-inherit 1))
                     t)
                    ((eq %vcf-directive :ignore-invalid-entries)
                     (setq %vcf-ignore-invalid t)
                     t)
                    ((funcall %vcf-validator %vcf-directive) t)
                    (%vcf-ignore-invalid nil)
                    (t
                     (setq uiop/configuration:*ignored-configuration-form* t)
                     (uiop/configuration:report-invalid-form %vcf-reporter
                      :form %vcf-directive
                      :location %vcf-location)
                     nil))
          (setq %vcf-x (cons %vcf-directive %vcf-x))))
      (unless (= %vcf-inherit 1)
        (uiop/configuration:report-invalid-form %vcf-reporter
         :form %vcf-form
         :location %vcf-location
         :format
         "Invalid source registry ~S~@[ in ~S~]. One and only one of ~S or ~S is required."
         :arguments '(:inherit-configuration :ignore-inherited-configuration)))
      (reverse %vcf-x))))

(defun uiop/configuration:validate-configuration-file
    (%vcfi-file %vcfi-validator &key ((:description %vcfi-description)))
  (let ((%vcfi-forms (uiop/stream:read-file-forms %vcfi-file)))
    (unless (uiop/utility:length=n-p %vcfi-forms 1)
      (error "One and only one form allowed for ~A. Got: ~S~%" %vcfi-description
             %vcfi-forms))
    (funcall %vcfi-validator (car %vcfi-forms) :location %vcfi-file)))

(defun uiop/configuration:validate-configuration-directory (%vcd-directory
                                                            %vcd-tag
                                                            %vcd-validator &key
                                                            ((:invalid-form-reporter
                                                              %vcd-reporter)))
  (let ((%vcd-files
         (sort (remove-if #'uiop/pathname:hidden-pathname-p
                (uiop/filesystem:directory-files %vcd-directory "*.conf"))
               #'string<
               :key #'namestring))
        (%vcd-directives nil))
    (dolist (%vcd-file %vcd-files)
      (let ((%vcd-ignore-invalid nil))
        (dolist (%vcd-form (uiop/stream:read-file-forms %vcd-file))
          (cond
           ((eq %vcd-form :ignore-invalid-entries) (setq %vcd-ignore-invalid t))
           ((funcall %vcd-validator %vcd-form)
            (setq %vcd-directives (cons %vcd-form %vcd-directives)))
           (%vcd-ignore-invalid
            (setq uiop/configuration:*ignored-configuration-form* t))
           (t (uiop/configuration:report-invalid-form %vcd-reporter
                                                      :form %vcd-form
                                                      :location %vcd-file))))))
    (cons %vcd-tag
          (append (reverse %vcd-directives) (list :inherit-configuration)))))

(defun uiop/configuration:resolve-relative-location
    (%rrl-x &key ((:ensure-directory %rrl-dir)) ((:wilden %rrl-wilden)))
  (uiop/pathname:ensure-pathname
   (cond ((null %rrl-x) nil)
         ((pathnamep %rrl-x) %rrl-x)
         ((stringp %rrl-x)
          (uiop/pathname:parse-unix-namestring %rrl-x
                                               :ensure-directory %rrl-dir))
         ((consp %rrl-x)
          (if (null (cdr %rrl-x))
              (uiop/configuration:resolve-relative-location (car %rrl-x)
               :ensure-directory %rrl-dir
               :wilden %rrl-wilden)
              (let ((%rrl-car
                     (uiop/configuration:resolve-relative-location (car %rrl-x)
                      :ensure-directory t
                      :wilden nil))
                    (%rrl-cdr
                     (uiop/configuration:resolve-relative-location (cdr %rrl-x)
                      :ensure-directory %rrl-dir
                      :wilden %rrl-wilden)))
                (if %rrl-cdr
                    (uiop/pathname:merge-pathnames* %rrl-cdr %rrl-car)
                    %rrl-car))))
         ((eq %rrl-x :*/) uiop/pathname:*wild-directory*)
         ((eq %rrl-x :**/) uiop/pathname:*wild-inferiors*)
         ((eq %rrl-x :*.*.*) uiop/pathname:*wild-file*)
         ((eq %rrl-x :implementation)
          (uiop/pathname:parse-unix-namestring
           (uiop/os:implementation-identifier)
           :ensure-directory t))
         ((eq %rrl-x :implementation-type)
          (uiop/pathname:parse-unix-namestring
           (string-downcase (uiop/os:implementation-type))
           :ensure-directory t))
         ((eq %rrl-x :hostname)
          (uiop/pathname:parse-unix-namestring (uiop/os:hostname)
                                               :ensure-directory t))
         (t (error "Invalid relative location: ~S" %rrl-x)))
   :wilden (and %rrl-wilden (not (pathnamep %rrl-x))
                (not (member %rrl-x '(:*/ :**/ :*.*.*))))
   :want-relative t))

(defun uiop/configuration:resolve-absolute-location
    (%ral-x &key ((:ensure-directory %ral-dir)) ((:wilden %ral-wilden)))
  (block nil
    (uiop/pathname:ensure-pathname
     (cond ((null %ral-x) nil)
           ((pathnamep %ral-x) %ral-x)
           ((stringp %ral-x)
            (if %ral-dir
                (uiop/pathname:ensure-directory-pathname
                 (parse-namestring %ral-x))
                (parse-namestring %ral-x)))
           ((consp %ral-x)
            (return
             (if (null (cdr %ral-x))
                 (uiop/configuration:resolve-absolute-location (car %ral-x)
                  :ensure-directory %ral-dir
                  :wilden %ral-wilden)
                 (let ((%ral-base
                        (uiop/configuration:resolve-absolute-location
                         (car %ral-x)
                         :ensure-directory t
                         :wilden nil))
                       (%ral-sub
                        (uiop/configuration:resolve-relative-location
                         (cdr %ral-x)
                         :ensure-directory %ral-dir
                         :wilden %ral-wilden)))
                   (if %ral-sub
                       (uiop/pathname:merge-pathnames* %ral-sub %ral-base)
                       %ral-base)))))
           ((eq %ral-x :root)
            (return (if %ral-wilden (uiop/pathname:wilden #P"") #P"")))
           ((eq %ral-x :home) (user-homedir-pathname))
           ((eq %ral-x :here)
            (uiop/configuration:resolve-absolute-location
             (or uiop/configuration:*here-directory*
                 (uiop/pathname:pathname-directory-pathname
                  (or *load-truename*
                   (error ":here names the file being loaded, and none is"))))
             :ensure-directory t
             :wilden nil))
           ((eq %ral-x :user-cache)
            (uiop/configuration:resolve-absolute-location
             uiop/configuration:*user-cache*
             :ensure-directory t
             :wilden nil))
           (t (error "Invalid absolute location: ~S" %ral-x)))
     :wilden (and %ral-wilden (not (pathnamep %ral-x)))
     :want-absolute t)))

(defun uiop/configuration:resolve-location (%rl-x &key
                                            ((:ensure-directory %rl-ensure))
                                            ((:wilden %rl-wilden))
                                            ((:directory %rl-directory)))
  (let* ((%rl-dirp (or %rl-directory %rl-ensure))
         (%rl-list (if (atom %rl-x) (list %rl-x) %rl-x))
         (%rl-rest (cdr %rl-list))
         (%rl-path
          (uiop/configuration:resolve-absolute-location (car %rl-list)
           :ensure-directory (and (or %rl-dirp %rl-rest) t)
           :wilden (and %rl-wilden (null %rl-rest)))))
    (when %rl-path
      (do ((%rl-tail %rl-rest (cdr %rl-tail)))
          ((null %rl-tail) %rl-path)
        (let ((%rl-sub
               (uiop/configuration:resolve-relative-location (car %rl-tail)
                :ensure-directory (and (or (cdr %rl-tail) %rl-dirp) t)
                :wilden (and %rl-wilden (null (cdr %rl-tail))))))
          (when %rl-sub
            (setq %rl-path
                  (uiop/pathname:merge-pathnames* %rl-sub %rl-path))))))))

(defun uiop/configuration:location-designator-p (%ldp-x)
  (flet ((%ldp-absolute (%ldp-c)
           (or (stringp %ldp-c) (pathnamep %ldp-c)
               (and (member %ldp-c '(:root :home :here :user-cache)) t)))
         (%ldp-relative (%ldp-c)
           (or (stringp %ldp-c) (pathnamep %ldp-c)
               (and (member %ldp-c
                     '(:*/ :**/ :*.*.* :implementation :implementation-type))
                    t))))
    (or (eq %ldp-x t) (null %ldp-x) (%ldp-absolute %ldp-x)
        (and (consp %ldp-x) (%ldp-absolute (car %ldp-x))
             (every #'%ldp-relative (cdr %ldp-x))))))

(defun uiop/configuration:location-function-p (%lfp-x)
  (and (uiop/utility:length=n-p %lfp-x 2) (eq (car %lfp-x) :function)))

(defun uiop/configuration:register-clear-configuration-hook
    (%rcch-hook &optional %rcch-call-now-p)
  (uiop/utility:register-hook-function
   'uiop/configuration:*clear-configuration-hook* %rcch-hook %rcch-call-now-p))

(defun uiop/configuration:clear-configuration ()
  (uiop/utility:call-functions uiop/configuration:*clear-configuration-hook*))

(defun uiop/configuration:upgrade-configuration ()
  (when uiop/configuration:*ignored-configuration-form*
    (uiop/configuration:clear-configuration)
    (setq uiop/configuration:*ignored-configuration-form* nil)))

;;; The XDG base directories. Rontolisp targets are unix-shaped, so upstream's
;;; os-windows-p arms have nothing to take.
(defun uiop/configuration:xdg-data-home (&rest %xdh-more)
  (uiop/configuration:resolve-absolute-location
   (list (or (uiop/filesystem:getenv-absolute-directory "XDG_DATA_HOME")
          (uiop/pathname:subpathname (user-homedir-pathname) ".local/share/"))
         %xdh-more)))

(defun uiop/configuration:xdg-config-home (&rest %xch-more)
  (uiop/configuration:resolve-absolute-location
   (list (or (uiop/filesystem:getenv-absolute-directory "XDG_CONFIG_HOME")
             (uiop/pathname:subpathname (user-homedir-pathname) ".config/"))
         %xch-more)))

(defun uiop/configuration:xdg-data-dirs (&rest %xdd-more)
  (mapcar (lambda (%xdd-d)
            (uiop/configuration:resolve-location (list %xdd-d %xdd-more)))
          (or (remove nil
               (uiop/filesystem:getenv-absolute-directories "XDG_DATA_DIRS"))
              (list #P"/usr/local/share/" #P"/usr/share/"))))

(defun uiop/configuration:xdg-config-dirs (&rest %xcd-more)
  (mapcar (lambda (%xcd-d)
            (uiop/configuration:resolve-location (list %xcd-d %xcd-more)))
          (or (remove nil
               (uiop/filesystem:getenv-absolute-directories "XDG_CONFIG_DIRS"))
              (list #P"/etc/xdg/"))))

(defun uiop/configuration:xdg-cache-home (&rest %xcah-more)
  (uiop/configuration:resolve-absolute-location
   (list (or (uiop/filesystem:getenv-absolute-directory "XDG_CACHE_HOME")
             (uiop/pathname:subpathname* (user-homedir-pathname) ".cache/"))
         %xcah-more)))

(defun uiop/configuration:xdg-runtime-dir (&rest %xrd-more)
  (uiop/configuration:resolve-absolute-location
   (list (uiop/filesystem:getenv-absolute-directory "XDG_RUNTIME_DIR")
         %xrd-more)))

(defun uiop/configuration:system-config-pathnames (&rest %scp-more)
  (list
   (uiop/configuration:resolve-absolute-location (list #P"/etc/" %scp-more))))

(defun uiop/configuration:filter-pathname-set (%fps-dirs)
  (remove-duplicates
   (remove-if-not #'uiop/pathname:absolute-pathname-p %fps-dirs)
   :from-end t
   :test 'equal))

(defun uiop/configuration:xdg-data-pathnames (&rest %xdp-more)
  (uiop/configuration:filter-pathname-set
   (cons (uiop/configuration:xdg-data-home %xdp-more)
         (uiop/configuration:xdg-data-dirs %xdp-more))))

(defun uiop/configuration:xdg-config-pathnames (&rest %xcp-more)
  (uiop/configuration:filter-pathname-set
   (cons (uiop/configuration:xdg-config-home %xcp-more)
         (uiop/configuration:xdg-config-dirs %xcp-more))))

(defun uiop/configuration:find-preferred-file
    (%fpf-files &key ((:direction %fpf-direction) :input))
  (cond ((member %fpf-direction '(:probe :input))
         (find-if #'uiop/filesystem:probe-file* %fpf-files))
        ((member %fpf-direction '(:output :io)) (car %fpf-files))
        (t (error "Invalid direction: ~S" %fpf-direction))))

(defun uiop/configuration:xdg-data-pathname
    (&optional %xdpn-more (%xdpn-direction :input))
  (uiop/configuration:find-preferred-file
   (uiop/configuration:xdg-data-pathnames %xdpn-more)
   :direction %xdpn-direction))

(defun uiop/configuration:xdg-config-pathname
    (&optional %xcpn-more (%xcpn-direction :input))
  (uiop/configuration:find-preferred-file
   (uiop/configuration:xdg-config-pathnames %xcpn-more)
   :direction %xcpn-direction))

;;; Upstream's uiop/backward-driver, homed here by the inventory.
(defun uiop/configuration:user-configuration-directories ()
  (uiop/configuration:xdg-config-pathnames "common-lisp"))

(defun uiop/configuration:system-configuration-directories ()
  (uiop/configuration:system-config-pathnames "common-lisp"))

(defun uiop/configuration:in-first-directory
    (%ifd-dirs %ifd-x &key ((:direction %ifd-direction) :input))
  (uiop/configuration:find-preferred-file
   (mapcar (lambda (%ifd-dir)
             (uiop/pathname:subpathname
              (uiop/pathname:ensure-directory-pathname %ifd-dir) %ifd-x))
           %ifd-dirs)
   :direction %ifd-direction))

(defun uiop/configuration:in-user-configuration-directory
    (%iucd-x &key ((:direction %iucd-direction) :input))
  (uiop/configuration:xdg-config-pathname (list "common-lisp" %iucd-x)
                                          %iucd-direction))

(defun uiop/configuration:in-system-configuration-directory
    (%iscd-x &key ((:direction %iscd-direction) :input))
  (uiop/configuration:find-preferred-file
   (uiop/configuration:system-config-pathnames "common-lisp" %iscd-x)
   :direction %iscd-direction))
