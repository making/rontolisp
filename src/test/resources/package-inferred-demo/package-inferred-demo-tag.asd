;;;; An ordinary system whose package name (pkg.inferred.tag) is nothing like its own,
;;;; so it is reachable only through the register-system-packages line next door.

(defsystem "package-inferred-demo-tag"
  :version "0.1.0"
  :license "MIT"
  :description "The differently-named companion system of package-inferred-demo."
  :components ((:file "tag")))
