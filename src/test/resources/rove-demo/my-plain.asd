(defsystem "my-plain"
  :components ((:file "plain")))

(defsystem "my-plain/tests"
  :depends-on ("my-plain" "rove")
  :components ((:file "plain-tests")))
