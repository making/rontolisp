;;;; The browser-facing reactor 804 / 805 / 806 are measured on: four host DOM
;;;; imports (three taking two :string parameters), four thin Lisp helpers over
;;;; them, a recursive fib, four exports, seven string literals crossing out.
;;;;
;;;; The literal TEXT here is placeholder prose; only its LENGTH is load-bearing,
;;;; because it lands in the data section byte for byte. See README.md.

(rontolisp:wasm-import 'js-log :from "env" :as "js_log"
                       :params '(:string) :returns nil)
(rontolisp:wasm-import 'js-set-text :from "env" :as "js_set_text"
                       :params '(:string :string) :returns nil)
(rontolisp:wasm-import 'js-append-text :from "env" :as "js_append_text"
                       :params '(:string :string) :returns nil)
(rontolisp:wasm-import 'js-set-badge-color :from "env" :as "js_set_badge_color"
                       :params '(:string :string) :returns nil)

;;; Pure forwarders: the import call site's argument is a PARAMETER, which is
;;; why 805 measures zero without 800's inliner in front of it.
(defun emit-log (msg) (js-log msg))
(defun set-text (element-id text) (js-set-text element-id text))
(defun append-text (element-id text) (js-append-text element-id text))
(defun set-badge-color (element-id color) (js-set-badge-color element-id color))

(defun fib (n)
  (if (<= n 1)
      n
      (+ (fib (- n 1)) (fib (- n 2)))))

(defun init-app ()
  (emit-log "WebAssembly reactor core initialized correctly.")
  (set-text "status-badge" "Running (module alive)")
  (set-badge-color "status-badge" "#10b981")
  (set-text "wasm-output"
            "This reactor module is online.
Click the buttons below to trigger computations inside it now."))

(defun add-numbers (a b) (+ a b))

(defun run-computation (n)
  (emit-log "Executing RunComputation inside this module...")
  (fib n))

(defun append-log-message (msg-code)
  (cond ((= msg-code 1)
         (append-text "wasm-log-box"
                      "
[Event] Button A clicked: the memory layout is validated ok."))
        ((= msg-code 2)
         (append-text "wasm-log-box"
                      "
[Event] Button B clicked: the slice buffer manipulation is complete."))
        (t
         (append-text "wasm-log-box"
                      "
[Event] Heartbeat tick received from the browser."))))

(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
(rontolisp:wasm-export 'add-numbers :as "AddNumbers" :params '(:s32 :s32) :returns :s32)
(rontolisp:wasm-export 'run-computation :as "RunComputation" :params '(:s32) :returns :s32)
(rontolisp:wasm-export 'append-log-message :as "AppendLogMessage" :params '(:s32) :returns nil)
