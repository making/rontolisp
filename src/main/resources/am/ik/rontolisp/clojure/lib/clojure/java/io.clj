(ns clojure.java.io
  "Files, URLs and the four kinds of stream, built into rontolisp: file names
  a java.io.File, reader and writer open a character stream, input-stream and
  output-stream a byte stream, over a path, a File, a URL or another stream;
  copy moves what one holds into another, resource finds a file on the source
  path. Coercions and IOFactory are protocols a program extends to its own
  types. Written for this front end from the documented behaviour of Clojure's
  namespace of the same name."
  (:require [rontolisp.internal.io :as k]))

(defprotocol Coercions
  "What can stand for a file or a URL."
  (as-file [x]
    "x as a java.io.File: a string is the File of that path, a file: URL the
    File it names, nil stays nil.")
  (as-url [x]
    "x as a java.net.URL: a string is the URL it spells, a File its file: URL,
    nil stays nil."))

(extend-protocol Coercions
  nil
  (as-file [_] nil)
  (as-url [_] nil)
  String
  (as-file [s] (k/file s))
  (as-url [s] (k/url s))
  java.io.File
  (as-file [f] (k/from-host f))
  (as-url [f] (k/file-url (k/from-host f)))
  java.net.URL
  (as-file [u] (k/url-file (k/from-host u)))
  (as-url [u] (k/from-host u))
  java.net.URI
  (as-file [u] (k/uri-file (k/from-host u)))
  (as-url [u] (k/uri-url (k/from-host u))))

(defprotocol IOFactory
  "What the four kinds of stream open over. Every method takes the options
  map (:encoding, :append) the opening functions pass."
  (make-reader [x opts] "A character input stream over x.")
  (make-writer [x opts] "A character output stream over x.")
  (make-input-stream [x opts] "A byte input stream over x.")
  (make-output-stream [x opts] "A byte output stream over x."))

(def default-streams-impl
  "The methods an IOFactory extension can start from: a reader over the byte
  stream make-input-stream opens, a writer over the one make-output-stream
  opens, and the refusal of both byte streams."
  {:make-reader (fn [x opts] (make-reader (make-input-stream x opts) opts))
   :make-writer (fn [x opts] (make-writer (make-output-stream x opts) opts))
   :make-input-stream (fn [x _] (k/refuse-input x))
   :make-output-stream (fn [x _] (k/refuse-output x))})

(extend-protocol IOFactory
  nil
  (make-reader [x _] (k/refuse-reader x))
  (make-writer [x _] (k/refuse-writer x))
  (make-input-stream [x _] (k/refuse-input x))
  (make-output-stream [x _] (k/refuse-output x))
  Object
  (make-reader [x opts]
    (or (k/open-reader x (:encoding opts))
        (make-reader (make-input-stream x opts) opts)))
  (make-writer [x opts]
    (or (k/open-writer x (:append opts) (:encoding opts))
        (make-writer (make-output-stream x opts) opts)))
  (make-input-stream [x _]
    (or (k/open-input x) (k/refuse-input x)))
  (make-output-stream [x opts]
    (or (k/open-output x (:append opts)) (k/refuse-output x))))

(defn reader
  "A character input stream over x, through make-reader: a path, a File or a
  URL is opened, a byte stream decoded, a reader answered as it is. Options:
  :encoding (UTF-8 by default)."
  [x & opts]
  (make-reader x (when opts (apply hash-map opts))))

(defn writer
  "A character output stream over x, through make-writer: a path or a File is
  opened (appended to under :append true), a byte stream encoded into, a
  writer answered as it is. Options: :append, :encoding (UTF-8 by default)."
  [x & opts]
  (make-writer x (when opts (apply hash-map opts))))

(defn input-stream
  "A byte input stream over x, through make-input-stream: a path, a File or a
  URL is opened, a byte stream answered as it is."
  [x & opts]
  (make-input-stream x (when opts (apply hash-map opts))))

(defn output-stream
  "A byte output stream over x, through make-output-stream: a path or a File
  is opened (appended to under :append true), a byte stream answered as it
  is."
  [x & opts]
  (make-output-stream x (when opts (apply hash-map opts))))

(defn copy
  "Writes what input holds -- a byte stream, a reader, a File, or the
  characters of a string -- to output -- a byte stream, a writer or a File --
  characters encoded or decoded in :encoding (UTF-8 by default). A stream is
  neither opened nor closed; a File is opened and closed around the copy."
  [input output & opts]
  (k/copy input output (:encoding (when opts (apply hash-map opts)))))

(defn as-relative-path
  "The path of (as-file x), refused when it is absolute."
  [x]
  (k/relative-path (as-file x)))

(defn file
  "A java.io.File: of one argument (as-file arg), of more each relative path
  after the first resolved against the File before it."
  ([arg] (as-file arg))
  ([parent child] (k/file-2 (as-file parent) (as-relative-path child)))
  ([parent child & more] (reduce file (file parent child) more)))

(defn delete-file
  "Deletes the file f names, answering true; when it cannot, answers silently
  if that is truthy and otherwise throws java.io.IOException."
  [f & [silently]]
  (or (k/delete (file f))
      silently
      (k/refuse-delete f)))

(defn make-parents
  "Creates every missing directory above the File (apply file f more),
  answering whether it made any."
  [f & more]
  (when-let [parent (k/parent-file (apply file f more))]
    (k/mkdirs parent)))

;; resource is a part of the namespace (io_resource.clj), loaded where a program
;; first names it

;; slurp and spit open what no path names through reader and writer, as the
;; oracle's do: a type a program extends IOFactory to included
(k/install reader writer)
