;; text.clj -- strings and regular expressions as the JVM answers them: the
;; java.util.regex syntax (lookaround, inline flags, named groups, \G), Unicode
;; properties, grapheme clusters, named characters, format's flags, full case mapping,
;; Unicode whitespace and charsets.
;;
;;   rontolisp text.clj
;;   rontolisp text.clj -o Text.class && java Text
;;   rontolisp text.clj -o text.wasm && wasmtime run text.wasm
;;   rontolisp text.clj -o text-c.wasm --component && wasmtime run text-c.wasm
(ns text
  (:require [clojure.string :as str]))

(println "== regex")
;; lookbehind and lookahead
(println (re-seq #"(?<=\$)\d+(?=\.)" "$10. 20. $30. $40"))
;; inline flags: (?i) to the end of its group, (?x) comments and spaces
(println (re-find #"(?i)hello (?-i)World" "say HELLO World"))
(println (re-matches #"(?x) (\d{4}) - (\d{2})  # year and month" "2026-10"))
;; named groups and a replacement naming them
(println (str/replace "2026-10-12" #"(?<y>\d+)-(?<m>\d+)-(?<d>\d+)" "${d}/${m}/${y}"))
;; \G: each match must start where the last one ended
(println (re-seq #"\G\d" "123x45"))
;; a matcher walked by hand
(let [m (re-matcher #"(\w+)=(\d+)" "a=1, b=22, c=333")]
  (loop [acc []]
    (if (re-find m)
      (recur (conj acc [(re-groups m)]))
      (println (count acc) "pairs, last:" (peek acc)))))

(println "== unicode")
;; general categories, scripts and binary properties
(println (re-seq #"\p{L}+" "héllo wörld 42 Ωμέγα"))
(println (re-seq #"\p{IsGreek}+" "alpha Ωμέγα beta"))
(println (re-seq #"\p{Lu}" "Hello World Ünïcode"))
;; (?U): \w and \b are Unicode's
(println (re-seq #"\w+" "naïve café") (re-seq #"(?U)\w+" "naïve café"))
;; \X: a user-perceived character, however many code points it takes
(let [s "e\u0301 \uD83D\uDC4D\uD83C\uDFFD \uD83C\uDDEF\uD83C\uDDF5"]
  (println (count (re-seq #"\X" s)) "clusters of" (count (re-seq #"(?s)." s)) "code points"))
;; \N{name}: a character by its Unicode name
(println (re-find #"\N{GREEK SMALL LETTER LAMDA}+" "xλλy"))

(println "== split and replace")
(println (str/split "a,b,,c,," #","))
(println (str/split "a,b,,c,," #"," -1))
(println (str/split "one  two\tthree" #"\s+" 2))
(println (str/replace "hello world" #"o" (fn [m] (str/upper-case m))))
(println (str/replace "cost: $5" "$5" (str/re-quote-replacement "$6")))

(println "== format")
(println (format "|%-8s|%8s|" "left" "right"))
(println (format "|%08.3f|%+d|%,d|%(d|" 3.14159 42 1234567 -5))
(println (format "%x %X %o %#x %e" 255 255 8 255 12345.678))
(println (format "%2$s %1$s, %s!" "world" "hello"))

(println "== case and whitespace")
;; full case mapping: one character can map to several
(println (str/upper-case "straße") (str/lower-case "ΟΔΟΣ") (str/capitalize "élan"))
;; blank? and trim know Unicode's whitespace
(println (str/blank? "\u2003\u3000") (pr-str (str/trim "\u2002 padded \u3000")))

(println "== charsets")
(defn round-trip [s charset]
  (let [octets (.getBytes s charset)]
    (println charset (count octets) "bytes, round trip:" (String. octets charset))))

(let [s "héllo €"]
  (round-trip s "UTF-8")
  (round-trip s "ISO-8859-1")
  (round-trip s "US-ASCII")
  (println (vec (.getBytes "é" java.nio.charset.StandardCharsets/UTF_8))
           (str java.nio.charset.StandardCharsets/ISO_8859_1)))
