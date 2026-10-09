(ns ring.util.codec
  "Functions for encoding and decoding data: the built-in subset of
  ring-codec's ring.util.codec. A charset is named by a string -- UTF-8,
  ISO-8859-1 or US-ASCII and their aliases -- and defaults to UTF-8. The
  base64 functions, over byte arrays, are loaded where a program names one."
  (:require [clojure.string :as str]
            [rontolisp.internal.ring :as kernel]))

(defn assoc-conj
  "Associate a key with a value in a map. If the key already exists in the map,
  a vector of values is associated with the key."
  [map key val]
  (assoc map key
         (if-let [cur (get map key)]
           (if (vector? cur)
             (conj cur val)
             [cur val])
           val)))

(defn percent-encode
  "Percent-encode every character in the given string using either the specified
  encoding, or UTF-8 by default."
  ([unencoded]
   (kernel/percent-encode unencoded nil))
  ([unencoded encoding]
   (kernel/percent-encode unencoded encoding)))

(defn percent-decode
  "Decode every percent-encoded character in the given string using the
  specified encoding, or UTF-8 by default."
  ([encoded]
   (kernel/percent-decode encoded nil))
  ([encoded encoding]
   (kernel/percent-decode encoded encoding)))

(defn url-encode
  "Returns the url-encoded version of the given string, using either a specified
  encoding or UTF-8 by default."
  ([unencoded]
   (kernel/url-encode unencoded nil))
  ([unencoded encoding]
   (kernel/url-encode unencoded encoding)))

(defn url-decode
  "Returns the url-decoded version of the given string, using either a specified
  encoding or UTF-8 by default."
  ([encoded]
   (kernel/percent-decode encoded nil))
  ([encoded encoding]
   (kernel/percent-decode encoded encoding)))

(defn form-encode
  "Encode the supplied value into www-form-urlencoded format, often used in
  URL query strings and POST request bodies, using the specified encoding.
  If the encoding is not specified, it defaults to UTF-8"
  ([x]
   (form-encode x nil))
  ([x encoding]
   (cond
     (nil? x) ""
     (string? x) (kernel/form-encode x encoding)
     (map? x)
     (letfn [(encode [v] (form-encode v encoding))
             (encode-param [k v] (str (encode (name k)) "=" (encode v)))]
       (->> x
            (mapcat
             (fn [[k v]]
               (cond
                 (sequential? v) (map #(encode-param k %) v)
                 (set? v) (sort (map #(encode-param k %) v))
                 :else (list (encode-param k v)))))
            (str/join "&")))
     :else (kernel/form-encode (str x) encoding))))

(defn form-decode-str
  "Decode the supplied www-form-urlencoded string using the specified encoding,
  or UTF-8 by default."
  ([encoded]
   (kernel/form-decode-str encoded nil))
  ([encoded encoding]
   (kernel/form-decode-str encoded encoding)))

(defn form-decode-map
  "Decode the supplied www-form-urlencoded string using the specified encoding,
  or UTF-8 by default. Expects an encoded map of key/value pairs as defined by:
  https://url.spec.whatwg.org/#urlencoded-parsing"
  ([encoded]
   (kernel/form-decode-map encoded nil))
  ([encoded encoding]
   (kernel/form-decode-map encoded encoding)))

(defn form-decode
  "Decode the supplied www-form-urlencoded string using the specified encoding,
  or UTF-8 by default. If the encoded value is a string, a string is returned.
  If the encoded value is a map of parameters, a map is returned."
  ([encoded]
   (kernel/form-decode encoded nil))
  ([encoded encoding]
   (kernel/form-decode encoded encoding)))
