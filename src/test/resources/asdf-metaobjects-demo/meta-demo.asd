;; The AsdfMetaobjectsE2eTest fixture: a plain defsystem with a :module (the
;; flattened-children shape), a tests system chained through :in-order-to, and
;; a :perform (test-op ...) body -- fukamachi's .asd shape, reduced. It also
;; carries dexador.asd's two openers: a :version (read back by
;; asdf:component-version) and a :defsystem-depends-on on the built-in
;; trivial-features shim, whose announced features have to be in force while
;; src/one.lisp is READ.
;;
;; The file runs as Lisp. Its header is cl-csv.asd's (a defpackage guarded by
;; find-package) and cl-pdf.asd's (a feature computed from *features*, pushed,
;; and read by the #- below it and by src/one.lisp) and lisp-binary.asd's
;; asdf:*asdf-version* gate; then the file loads
;; meta-demo-tool itself, which every system defined after that call needs.
(eval-when (:compile-toplevel :load-toplevel :execute)
  (unless (find-package :meta-demo.system)
    (defpackage :meta-demo.system
      (:use :common-lisp :asdf))))

(in-package :meta-demo.system)

(eval-when (:load-toplevel :compile-toplevel :execute)
  (when (notany #'(lambda (feature) (find feature *features*))
                '(:meta-demo-fast :meta-demo-small))
    (push :meta-demo-small *features*)))

#-(or meta-demo-fast meta-demo-small)
(error "meta-demo: no build was chosen")

;; lisp-binary.asd's ASDF version gate: an ASDF older than 3.1.5 (or none
;; announcing a version) would load the library's own bundled asdf.lisp.
(let ((version (symbol-value (or (find-symbol (string :*asdf-version*) :asdf)
                                 (find-symbol (string :*asdf-revision*) :asdf)))))
  (unless (and (stringp version) (string>= version "3.1.5"))
    (error "meta-demo: ASDF ~a is too old" version)))

(load-system "meta-demo-tool")

(defsystem "meta-demo"
  :version "1.2.3"
  :defsystem-depends-on ("trivial-features")
  :pathname "src"
  :components ((:file "one") (:module "m" :components ((:file "two"))))
  :in-order-to ((test-op (test-op "meta-demo/tests"))))

(defsystem "meta-demo/tests"
  :depends-on ("meta-demo")
  :components ((:file "tests"))
  :perform (test-op (o c) (run-demo-tests (component-name c) (typep o 'asdf:test-op))))

;; The operation protocol: meta-demo/hooks adds asdf:perform methods from its
;; own source (asdf-flv's :around shape plus :before/:after loggers), and the
;; components of the systems loaded after it -- one of them loading a third
;; system from inside its own file -- go through them.
(defsystem "meta-demo/hooks"
  :components ((:file "hooks")))

(defsystem "meta-demo/perform"
  :depends-on ("meta-demo/hooks")
  :serial t
  :components ((:file "a") (:file "b")))

(defsystem "meta-demo/nested"
  :components ((:file "c")))

;; The options around a component's compile. :around-compile is coalton's lambda on
;; the system, nil on a component (no hook, what a child of a hooked system says to
;; opt out), a symbol of this file's package on a module (uiop's) and a string naming
;; a function of a :defsystem-depends-on system on a file (mgl-pax's); each decides how
;; the component reads and what its macros see, and none reaches the program. The
;; rest: pngload's :encoding, clingon's #P :pathname, the program-op trio, and
;; :weakly-depends-on, of which meta-demo-absent exists nowhere.
(defun call-with-named-feature (compile)
  (let ((*features* (cons :meta-demo-named-hook *features*)))
    (funcall compile)))

(defsystem "meta-demo/weak"
  :components ((:file "weak")))

(defsystem "meta-demo/hooked"
  :encoding :utf-8
  :entry-point "meta-demo:main"
  :build-operation "program-op"
  :build-pathname "bin/meta-demo"
  :defsystem-depends-on ("meta-demo-tool/hook")
  :weakly-depends-on ("meta-demo-absent" "meta-demo/weak")
  :around-compile (lambda (compile)
                    (let ((*features* (cons :meta-demo-hooked *features*)))
                      (funcall compile)))
  :serial t
  :components ((:file "hooked")
               (:file "unhooked" :around-compile nil :encoding :default)
               (:module "named"
                :pathname #P"hooked/"
                :around-compile call-with-named-feature
                :components ((:file "named")
                             (:file "by-string"
                              :around-compile "meta-demo-tool-hook:call-with-tool-feature")))))
