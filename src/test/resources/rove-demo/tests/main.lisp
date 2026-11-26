(in-package #:cl-user)
(defpackage #:my-app/tests/main
  (:use #:cl
        #:rove
        #:my-app/main))
(in-package #:my-app/tests/main)

(setup
  (diag "setup: my-app tests
"))

(teardown
  (diag "teardown: my-app tests
"))

(defhook announce :before
  (diag "before: a test starts
"))

(deftest add-test
  (testing "adding two integers"
    (ok (= (add 1 2) 3))
    (ng (= (add 1 2) 4)))
  (testing "printing"
    (ok (outputs (write-string "hi") "hi"))))

(deftest parse-token-test
  (testing "invalid tokens"
    (ok (signals (parse-token "") 'app-error)
        "Parse error")
    (ok (signals (parse-token 10) 'type-error)))
  (testing "valid tokens"
    (ok (equal (parse-token "a") "a"))
    (skip "unicode tokens are not supported yet")))

(deftest misc-test
  (pass "Okay. It's passed")
  (fail "Oops. It's failed")
  (failing "not implemented yet"
    (ok (= (add 2 2) 5)))
  (ok (parse-token "")))
