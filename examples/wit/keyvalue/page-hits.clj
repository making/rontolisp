;; page-hits.clj -- page-hits.lisp in Clojure: a page-view counter written against
;; the real wasi:keyvalue/store, bound with rontolisp.wit.
;;
;;   rontolisp page-hits.clj                         the interpreter: the store below
;;   rontolisp page-hits.clj -o PageHits.class       the JVM: the same store
;;   rontolisp page-hits.clj -o kv.wasm --component
;;     + wasmtime run -S keyvalue=y kv.wasm          wasmtime's own wasi:keyvalue
;;
;; The import binds the interface's functions as the vars of the namespace `kv`
;; reaches: kv/open, and each resource method with the handle first --
;; kv/bucket-get, kv/bucket-set, kv/bucket-delete, kv/bucket-exists,
;; kv/bucket-list-keys. Each WIT value crosses in its Clojure spelling: a value,
;; a list<u8>, is a byte array, list-keys answers the record key-response as a
;; map, and a result's error arm (the variant error) is thrown as an ExceptionInfo
;; holding the case keyword.
(ns page-hits
  (:require [rontolisp.wit :as wit]))

(wit/import "wit/keyvalue.wit" {:interface "wasi:keyvalue/store@0.2.0-draft" :as kv})

;; The store, where the program provides the interface itself (the interpreter and
;; the JVM): a function of the member name and that member's arguments, seeing and
;; answering Clojure values. Only the empty identifier names a store, as in
;; wasmtime's; any other answers the error arm, thrown under ::wit/error. A WASM
;; build's host provides every import, so there this binds nothing.
(def store (atom {}))

(wit/provide "wasi:keyvalue/store@0.2.0-draft"
             (fn [member & args]
               (let [[_ k v] args]
                 (cond
                   (= member "open") (if (= (first args) "")
                                       0
                                       (throw (ex-info "no such store" {::wit/error :no-such-store})))
                   (= member "bucket-get") (get @store k)
                   (= member "bucket-set") (do (swap! store assoc k v) nil)
                   (= member "bucket-delete") (do (swap! store dissoc k) nil)
                   (= member "bucket-exists") (contains? @store k)
                   (= member "bucket-list-keys") {:keys (keys @store) :cursor nil}))))

(def requests ["/index" "/pricing" "/index" "/docs" "/index" "/pricing"])

;; Read the counter for a page, add one, write it back. bucket.get answers an
;; option<list<u8>>: the value's bytes, or nil when the key is absent; the
;; counter is stored as its decimal text.
(defn hits [bucket page]
  (some-> (kv/bucket-get bucket page) String.))

(defn record-hit [bucket page]
  (let [seen (hits bucket page)]
    (kv/bucket-set bucket page (.getBytes (str (inc (if seen (read-string seen) 0)))))))

;; bucket.list-keys answers a record, key-response: a map whose :keys is a vector
;; of the keys and whose :cursor is nil once they are all there.
(defn sorted-keys [bucket]
  (sort (:keys (kv/bucket-list-keys bucket nil))))

;; open answers a result<bucket, error>: the ok arm is the handle, and the error
;; arm throws. bucket.exists answers a result<bool, error>, crossing as true or
;; false.
(let [bucket (kv/open "")]
  (doseq [page requests]
    (record-hit bucket page))
  (println "hits per page:")
  (doseq [page (sorted-keys bucket)]
    (println (str "  " page " = " (hits bucket page))))
  (println "/docs exists?    " (kv/bucket-exists bucket "/docs"))
  (kv/bucket-delete bucket "/docs")
  (println "/docs exists now?" (kv/bucket-exists bucket "/docs"))
  (println "keys:            " (pr-str (sorted-keys bucket)))
  ;; a binding is a function value too
  (println "values:          " (mapv #(some-> % String.) (map kv/bucket-get (repeat bucket) ["/index" "/pricing" "/nope"]))))

(println "bad store:       "
         (try (kv/open "not-a-store-anyone-has")
              (catch clojure.lang.ExceptionInfo e (::wit/error (ex-data e)))))
