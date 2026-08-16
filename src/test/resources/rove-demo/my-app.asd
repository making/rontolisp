(defsystem "my-app"
  :class :package-inferred-system
  :depends-on ("my-app/main"))

(defsystem "my-app/tests"
  :class :package-inferred-system
  :depends-on ("my-app"
               "my-app/tests/main"))
