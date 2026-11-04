;; Found only through a push on asdf:*central-registry*: no search path names this
;; directory. Everything a definition declares beside its files, for the
;; system-<metadata> readers.
(defsystem "meta-demo-central"
  :description "The description"
  :long-description "The long description"
  :author "An Author"
  :maintainer "A Maintainer"
  :license "MIT"
  :homepage "https://example.org/home"
  :bug-tracker "https://example.org/bugs"
  :mailto "a@example.org"
  :long-name "The Long Name"
  :source-control (:git "https://example.org/git")
  :version "1.2.3"
  :properties ((:color . "red"))
  :depends-on ("meta-demo-central-dep")
  :weakly-depends-on ("meta-demo-absent")
  :components ((:file "central") (:module "m" :components ((:file "inner")))))

;; #. in metadata is never evaluated here (real ASDF's reader evaluates it): the option
;; reads back nil.
(defsystem "meta-demo-central/computed"
  :description #.(concatenate 'string "com" "puted"))
