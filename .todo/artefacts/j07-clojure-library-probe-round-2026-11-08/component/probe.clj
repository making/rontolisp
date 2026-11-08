(ns probe (:require [com.stuartsierra.component :as c]))
(def log (atom []))
(defrecord Db [url conn]
  c/Lifecycle
  (start [this] (swap! log conj [:start-db url]) (assoc this :conn :open))
  (stop [this] (swap! log conj :stop-db) (assoc this :conn nil)))
(defrecord App [db started]
  c/Lifecycle
  (start [this] (swap! log conj [:start-app (:conn db)]) (assoc this :started true))
  (stop [this] (swap! log conj :stop-app) (assoc this :started false)))
(defn system [] (c/system-map :db (map->Db {:url "jdbc:x"}) :app (c/using (map->App {}) [:db])))
(def s (c/start (system)))
(prn (get-in s [:app :started]) (get-in s [:app :db :conn]))
(def s2 (c/stop s))
(prn @log)
(prn (get-in s2 [:db :conn]))
(prn (c/dependencies (c/using {} {:x :y})))
(prn (try (c/start (c/system-map :a (c/using {} [:missing]))) (catch Exception e (:reason (ex-data e)))))
(prn (keys (c/update-system (system) [:db] assoc :touched true)))
