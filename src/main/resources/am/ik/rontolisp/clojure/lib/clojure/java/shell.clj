(ns clojure.java.shell
  "Launching a sub-process, feeding its standard input and collecting its
  output, built into rontolisp for the targets with a host process API: the
  interpreter and the JVM. Written for this front end from the documented
  behaviour of Clojure's namespace of the same name."
  (:require [clojure.java.io :as io]))

(def ^:dynamic *sh-dir*
  "The directory sh runs a process in when no :dir is given; nil for the
  current one."
  nil)

(def ^:dynamic *sh-env*
  "The environment sh gives a process when no :env is given; nil for the
  current one."
  nil)

(defmacro with-sh-dir
  "Evaluates forms with *sh-dir* bound to dir."
  [dir & forms]
  `(binding [*sh-dir* ~dir]
     ~@forms))

(defmacro with-sh-env
  "Evaluates forms with *sh-env* bound to env."
  [env & forms]
  `(binding [*sh-env* ~env]
     ~@forms))

(defn- temp-file
  "A new empty host file for a process's input or error output."
  [suffix]
  (java.io.File/createTempFile "rontolisp-sh" suffix))

(defn- input-file
  "A file holding the input in for a process: a File as it is, else a
  temporary file of a byte array's or a byte stream's bytes as they are, or
  of a text (a string, or what slurp reads from it) encoded in encoding."
  [in encoding]
  (cond
    (instance? java.io.File in) [in false]
    (or (bytes? in) (instance? java.io.InputStream in))
    (let [f (temp-file ".in")]
      (io/copy in (io/file (.getPath f)))
      [f true])
    :else
    (let [f (temp-file ".in")
          text (if (string? in) in (slurp in))
          out (java.io.PrintStream. f encoding)]
      (.print out text)
      (.close out)
      [f true])))

(defn sh
  "Runs the command the leading strings of args name, then answers a map of
  its :exit code, its standard output :out (decoded from :out-enc, UTF-8 by
  default; a byte array under :out-enc :bytes) and its standard error :err
  (decoded from the platform's charset). The options after the strings: :in,
  a string (encoded in :in-enc, UTF-8 by default), a byte array, an
  InputStream, a File or a reader for its standard input; :dir, the directory
  to run it in; :env, a map that replaces its environment."
  [& args]
  (let [[cmd opts] (split-with string? args)
        {:keys [in in-enc out-enc dir env]} (merge {:in-enc "UTF-8" :out-enc "UTF-8" :dir *sh-dir* :env *sh-env*}
                                                   (apply hash-map opts))
        builder (ProcessBuilder. (vec cmd))
        err-file (temp-file ".err")
        [in-file temporary-in] (when in (input-file in in-enc))]
    (when dir
      (.directory builder (io/file dir)))
    (when env
      (let [environment (.environment builder)]
        (.clear environment)
        (doseq [[k v] env]
          (.put environment (name k) (str v)))))
    (when in-file
      (.redirectInput builder in-file))
    (.redirectError builder err-file)
    (try
      (let [process (.start builder)]
        (when-not in-file
          (.close (.getOutputStream process)))
        ;; host streams, so the host's charsets decode them
        (let [out (^[] java.io.ByteArrayOutputStream/new)
              err (^[] java.io.ByteArrayOutputStream/new)]
          (.transferTo (.getInputStream process) out)
          (let [exit (.waitFor process)]
            (java.nio.file.Files/copy (.toPath err-file) err)
            {:exit exit
             :out (if (= out-enc :bytes)
                    (.toByteArray out)
                    (.toString out out-enc))
             :err (.toString err (.name (java.nio.charset.Charset/defaultCharset)))})))
      (finally
        (.delete err-file)
        (when temporary-in
          (.delete in-file))))))
