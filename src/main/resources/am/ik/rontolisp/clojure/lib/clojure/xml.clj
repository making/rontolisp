(ns clojure.xml
  "Reading XML documents into element maps and writing them back, built into
  rontolisp. Written for this front end from the documented behaviour of
  Clojure's namespace of the same name: parse reads a document itself on
  every target; a startparse function, and parse of a host object, hand a
  host SAX parser a ContentHandler like the oracle's."
  (:require [rontolisp.internal.pprint :as printer]
            [rontolisp.internal.xml :as k]))

(defstruct element :tag :attrs :content)

(defn tag
  "The tag of the element e."
  [e]
  (:tag e))

(defn attrs
  "The attributes of the element e, a map from keyword to string, or nil."
  [e]
  (:attrs e))

(defn content
  "The content of the element e, a vector of elements and strings, or nil."
  [e]
  (:content e))

(defn- attributes
  "The attribute map of the names and values of an element's start event,
  nil without any; its entries in the oracle's order, the last attribute
  first."
  [names-and-values]
  (when (seq names-and-values)
    (apply array-map (mapcat (fn [[n v]] [(keyword n) v]) (reverse (partition 2 names-and-values))))))

(defn- with-content
  "The open elements frames, the innermost one's content grown by c."
  [frames c]
  (let [[t a content] (first frames)]
    (cons [t a (conj content c)] (rest frames))))

(defn- tree
  "The root element of the document whose events are events: a vector
  [qname name value ...] opening an element, a string of character data, nil
  closing one. Character data between two element events that is all white
  space is dropped, any other kept as it is."
  [events]
  (loop [events (seq events)
         frames (list [nil nil []])
         text nil]
    (if-not events
      (first (nth (first frames) 2))
      (let [event (first events)]
        (if (string? event)
          (recur (next events) frames (str text event))
          (let [frames (if (and text (not (k/blank? text))) (with-content frames text) frames)]
            (if (nil? event)
              (let [[t a content] (first frames)]
                (recur (next events) (with-content (rest frames) (struct element t a (not-empty content))) nil))
              (recur (next events) (cons [(keyword (first event)) (attributes (rest event)) []] frames)
                     nil))))))))

(defn sax-parser
  "A new host javax.xml.parsers.SAXParser."
  []
  (.newSAXParser (javax.xml.parsers.SAXParserFactory/newInstance)))

(defn disable-external-entities
  "The host SAXParser parser, set to read no external DTD and no external
  entity, which keeps a document from reaching other files (XXE)."
  [parser]
  (let [reader (.getXMLReader parser)]
    (.setFeature reader "http://apache.org/xml/features/nonvalidating/load-external-dtd" false)
    (.setFeature reader "http://xml.org/sax/features/external-general-entities" false)
    (.setFeature reader "http://xml.org/sax/features/external-parameter-entities" false)
    parser))

(defn startparse-sax
  "A startparse function for parse: the host's SAX parser reads s and calls
  the ContentHandler ch. External entities are read; startparse-sax-safe
  reads none."
  [s ch]
  (.parse (sax-parser) s ch))

(defn startparse-sax-safe
  "A startparse function for parse: the host's SAX parser, reading no
  external entity, reads s and calls the ContentHandler ch."
  [s ch]
  (.parse (disable-external-entities (sax-parser)) s ch))

(defn- host-events
  "The events of the document a host SAX parser reads, startparse handing
  it s and a ContentHandler recording them."
  [s startparse]
  (let [events (atom [])
        handler (proxy [org.xml.sax.helpers.DefaultHandler] []
                  (startElement [uri local-name q-name atts]
                    (swap! events conj
                           (into [q-name]
                                 (mapcat (fn [i] [(.getQName atts (int i)) (.getValue atts (int i))])
                                         (range (.getLength atts))))))
                  (endElement [uri local-name q-name]
                    (swap! events conj nil))
                  (characters [ch start length]
                    (swap! events conj (String. ch (int start) (int length)))))]
    (startparse s handler)
    @events))

(defn parse
  "Reads the XML document s -- a File, an InputStream, or a string naming a
  file or a URL -- into its root element: a map of :tag (a keyword), :attrs
  (a map from keyword to string, nil without attributes) and :content (a
  vector of elements and strings, nil when empty). With startparse, a
  function of s and a host ContentHandler, the host's SAX parser it calls
  reads the document; without one, a host object s is read by
  startparse-sax-safe and anything else by rontolisp's own reader."
  ([s]
   (if (k/host? s)
     (parse s startparse-sax-safe)
     (tree (k/events (slurp s :encoding "ISO-8859-1")))))
  ([s startparse]
   (tree (host-events s startparse))))

(defn emit-element
  "Prints the element e as XML, a string as its own line."
  [e]
  (if (string? e)
    (println e)
    (do
      (print (str "<" (name (:tag e))))
      ;; in the order the map prints, as the oracle's array map walks
      (doseq [[k v] (let [m (:attrs e)] (or (printer/members m) (seq m)))]
        (print (str " " (name k) "='" v "'")))
      (if-let [children (:content e)]
        (do
          (println ">")
          (doseq [c children]
            (emit-element c))
          (println (str "</" (name (:tag e)) ">")))
        (println "/>")))))

(defn emit
  "Prints the element x as an XML document, after its declaration."
  [x]
  (println "<?xml version='1.0' encoding='UTF-8'?>")
  (emit-element x))
