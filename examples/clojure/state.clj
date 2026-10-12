;; state.clj -- the reference types: atoms, refs and their transactions, agents with
;; their failed state, validators and watches, delays, vars redefined at run time.
;;
;;   rontolisp state.clj
;;   rontolisp state.clj -o State.class && java State
;;   rontolisp state.clj -o state.wasm && wasmtime run state.wasm
;;   rontolisp state.clj -o state-c.wasm --component && wasmtime run state-c.wasm
(ns state)

(println "== atoms and refs")
(let [hits (atom 0)]
  (dotimes [_ 1000] (swap! hits inc))
  (println "hits:" @hits))

;; a transfer between two refs is all or nothing: a failing check rolls both back
(let [checking (ref 100)
      savings (ref 100)
      transfer (fn [from to n]
                 (dosync
                  (alter from - n)
                  (alter to + n)
                  (when (neg? @from) (throw (ex-info "insufficient funds" {:short (- @from)})))))]
  (transfer checking savings 30)
  (println (try (transfer savings checking 500) (catch Exception e [(ex-message e) (ex-data e)])))
  (println "balances:" @checking @savings (dosync (commute savings + 0) (+ @checking @savings))))

(println "== compare-and-set! and validators")
(let [temperature (atom 20)]
  (println (compare-and-set! temperature 20 25) (compare-and-set! temperature 20 30) @temperature))
(let [stock (ref 3 :validator #(>= % 0))]
  (println (try (dosync (alter stock - 5)) (catch IllegalStateException e (ex-message e))) @stock))

(println "== watches and validators")
;; a watch sees every change after it is stored; a validator refuses a value before
(let [temperature (atom 20 :validator #(<= -50 % 60))
      readings (atom [])]
  (add-watch temperature :log (fn [_ _ old new] (swap! readings conj [old new])))
  (swap! temperature + 5)
  (println (swap-vals! temperature - 3) (reset-vals! temperature 30))
  (println (try (reset! temperature 99) (catch IllegalStateException e (ex-message e))) @temperature)
  (remove-watch temperature :log)
  (reset! temperature 0)
  (println "readings:" @readings))

;; a ref's watch runs once its transaction commits, with the value from before it
(let [balance (ref 100)
      audit (atom [])]
  (add-watch balance :audit (fn [_ _ old new] (swap! audit conj (- new old))))
  (dosync (alter balance - 30) (alter balance + 5))
  (dosync (alter balance + 50))
  (println "audit:" @audit @balance))

(println "== agents")
(let [counter (agent 0)]
  (dotimes [_ 5] (send counter inc))
  (await counter)
  (println "counter:" @counter))

;; an action that throws leaves the agent failed: further sends are refused until it is
;; restarted. (Here an action sent to an idle agent runs before send returns; Clojure
;; runs it on its pool, so the program waits for the failure to land.)
(defn wait-for-failure [a]
  (loop [] (when-not (agent-error a) (recur))))

(let [account (agent 10)]
  (send account - 20)
  (send account (fn [n] (if (neg? n) (throw (ex-info "overdrawn" {:balance n})) n)))
  (wait-for-failure account)
  (println "failed with:" (ex-message (agent-error account)) (ex-data (agent-error account)))
  (println "send refused:" (try (send account inc) :sent (catch Exception e (ex-message e))))
  (restart-agent account 0 :clear-actions true)
  (send account + 5)
  (await account)
  (println "restarted:" @account (agent-error account)))

;; :continue mode hands the failure to the handler and keeps going
(let [seen (atom [])
      a (agent 1 :error-mode :continue :error-handler (fn [_ e] (swap! seen conj (ex-message e))))]
  (send a (fn [_] (throw (ex-info "skipped" {}))))
  (send a * 7)
  (await a)
  (println "continue:" @a @seen (error-mode a)))

(let [worker (agent 0)]
  (set-validator! worker #(< % 10))
  (dotimes [i 4] (send worker + i))
  (println "await-for:" (await-for 1000 worker) @worker))

(println "== delays and volatiles")
(let [d (delay (println "  (computing once)") 42)
      v (volatile! 1)]
  (println (realized? d) @d @d (realized? d))
  (vswap! v + 2)
  (println @v (volatile? v)))

(println "== vars")
(def ^:dynamic *greeting* "hello")
(defn greet [who] (str *greeting* ", " who))
(def counter 0)
(add-watch #'counter :trace (fn [_ _ old new] (println "  counter:" old "->" new)))
(alter-var-root #'counter + 10)
(println (greet "ada") (binding [*greeting* "hi"] (greet "bob")) counter)
(with-redefs [greet (fn [who] (str "stub for " who))]
  (println (greet "eve")))
(println (greet "eve") (:doc (alter-meta! #'greet assoc :doc "Greets."))
         (bound? #'counter) (thread-bound? #'*greeting*))
(shutdown-agents)
