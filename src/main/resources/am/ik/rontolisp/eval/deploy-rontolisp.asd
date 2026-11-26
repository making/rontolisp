;;;; A replacement for upstream deploy's deploy.asd (eval.AsdOverrides): the same
;;;; system, with cffi a dependency only where a program can open a foreign library.
;;;;
;;;; deploy depends on cffi to copy the foreign libraries an image opened next to
;;;; its executable, and skips it on mezzano, where there are none. A .wasm (a
;;;; native executable included) has none either and refuses ffi: by name, so it
;;;; reads deploy the mezzano way: its #+cffi arms drop and the rest is the same.

(asdf:defsystem deploy
  :version "3.0.0"
  :license "zlib"
  :author "Yukari Hafner <shinmera@tymoon.eu>"
  :maintainer "Yukari Hafner <shinmera@tymoon.eu>"
  :description
  "Tools to aid in the deployment of a fully standalone application."
  :homepage "https://shinmera.com/docs/deploy/"
  :bug-tracker "https://shinmera.com/project/deploy/issues"
  :source-control (:git "https://shinmera.com/project/deploy.git")
  :serial T
  :components ((:file "package") (:file "toolkit") (:file "hooks")
               (:file "library") (:file "checksum") (:file "deploy")
               (:file "asdf" :if-feature :asdf3)
               (:file "osx" :if-feature :darwin)
               (:file "nx" :if-feature (:and :sbcl :nx))
               (:file "shrinkwrap" :if-feature :sbcl) (:file "documentation"))
  :depends-on ((:feature (:not (:or :mezzano :rontolisp-wasm)) :cffi)
               :sha3 :documentation-utils
               :pathname-utils :trivial-features))
