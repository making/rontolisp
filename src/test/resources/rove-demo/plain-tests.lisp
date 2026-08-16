(defpackage #:my-plain/tests
  (:use #:cl
        #:rove
        #:my-plain))
(in-package #:my-plain/tests)

(deftest greet-test
  (testing "formats the name"
    (ok (equal (greet "World") "Hello, World!"))
    (ng (equal (greet "World") "Hello!"))))
