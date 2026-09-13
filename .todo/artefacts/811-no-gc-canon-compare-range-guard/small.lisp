(rontolisp:wasm-import 'host-log :from "env" :as "host_log" :params '(:string) :returns nil)
(rontolisp:wasm-import 'host-set-text :from "env" :as "host_set_text"
                       :params '(:string :string) :returns nil)

(defun fib (n)
  (if (<= n 1)
      n
      (+ (fib (- n 1)) (fib (- n 2)))))

(defun init-app ()
  (host-log "module initialized")
  (host-set-text "status-badge" "running"))

(defun run-computation (n)
  (fib n))

(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
(rontolisp:wasm-export 'run-computation :as "RunComputation" :params '(:s32) :returns :s32)
