;; The file, URL and resource responses of ring.util.response (ring-core
;; 1.15.5): loaded into the namespace where a program first names one of
;; them, so a program serving no file carries none of this. The oracle's
;; java.io.File and java.net.URL are clojure.java.io's values here, its class
;; loader the program's source path, and a canonical path resolves symbolic
;; links but stays relative where the File is (no wasm backend knows the
;; working directory).

(defn- canonical-path [file]
  (str (kernel/canonical-path file)
       (when (.isDirectory file) "/")))

(defn- safe-path? [root path]
  (.startsWith (canonical-path (java.io.File. root path))
               (canonical-path (java.io.File. root))))

(defn- directory-transversal?
  "Check if a path contains '..'."
  [path]
  (kernel/directory-traversal? path))

(defn- find-file-named [dir filename]
  (let [path (java.io.File. dir filename)]
    (when (.isFile path)
      path)))

(defn- find-file-starting-with [dir prefix]
  (first
   (filter
    #(.startsWith (str/lower-case (.getName %)) prefix)
    (.listFiles dir))))

(defn- find-index-file
  "Search the directory for an index file."
  [dir]
  (or (find-file-named dir "index.html")
      (find-file-named dir "index.htm")
      (find-file-starting-with dir "index.")))

(defn- safely-find-file [path opts]
  (if-let [root (:root opts)]
    (when (or (safe-path? root path)
              (and (:allow-symlinks? opts) (not (directory-transversal? path))))
      (java.io.File. root path))
    (java.io.File. path)))

(defn- find-file [path opts]
  (when-let [file (safely-find-file path opts)]
    (cond
      (.isDirectory file)
      (and (:index-files? opts true) (find-index-file file))
      (.exists file)
      file)))

(defn- last-modified-date
  "The last modified date of a file, rounded down to the nearest second."
  [file]
  (kernel/date (* 1000 (quot (.lastModified file) 1000))))

(defn- file-data [file]
  {:content        file
   :content-length (.length file)
   :last-modified  (last-modified-date file)})

(defn- content-length [resp len]
  (if len
    (header resp "Content-Length" len)
    resp))

(defn- last-modified [resp last-mod]
  (if last-mod
    (header resp "Last-Modified" (kernel/format-date last-mod))
    resp))

(defn file-response
  "Returns a Ring response to serve a static file, or nil if an appropriate
  file does not exist.
  Options:
    :root            - take the filepath relative to this root path
    :index-files?    - look for index.* files in directories (defaults to true)
    :allow-symlinks? - allow symlinks that lead to paths outside the root path
                       (defaults to false)"
  ([filepath]
   (file-response filepath {}))
  ([filepath options]
   (when-let [file (find-file filepath options)]
     (let [data (file-data file)]
       (-> (response (:content data))
           (content-length (:content-length data))
           (last-modified (:last-modified data)))))))

(defn- url-as-file [url]
  (io-kernel/url-file url))

(defn- jar-file
  "The jar a jar: URL names an entry of."
  [url]
  (let [spec (str url)]
    (io-kernel/url-file (io-kernel/url (subs spec 4 (str/index-of spec "!/"))))))

(defmulti resource-data
  "Returns data about the resource specified by url, or nil if an
  appropriate resource does not exist.

  The return value is a map with optional values for:
  :content        - the content of the URL, suitable for use as the :body
                    of a ring response
  :content-length - the length of the :content, nil if not available
  :last-modified  - the Date the :content was last modified, nil if not
                    available

  This dispatches on the protocol of the URL as a keyword, and
  implementations are provided for :file and :jar.

  This function is used internally by url-response."
  (fn [url]
    (keyword (.getProtocol url))))

(defmethod resource-data :file
  [url]
  (when-let [file (url-as-file url)]
    (when-not (.isDirectory file)
      (file-data file))))

;; A jar's entry is what clojure.java.io/resource found while the program
;; lowered, or read from the jar when it runs; a directory entry is none, as
;; jar-directory? answers. Its date is the jar's own, as a JarURLConnection
;; answers.
(defmethod resource-data :jar
  [url]
  (when-not (io-kernel/directory-entry? url)
    (let [content  (io-kernel/open-input url)
          last-mod (.lastModified (jar-file url))]
      {:content        content
       :content-length (.available content)
       :last-modified  (when-not (zero? last-mod) (kernel/date last-mod))})))

(defn url-response
  "Return a response for the supplied URL."
  [url]
  (when-let [data (resource-data url)]
    (-> (response (:content data))
        (content-length (:content-length data))
        (last-modified (:last-modified data)))))

(defn- without-leading-slash [s]
  (if (str/starts-with? s "/") (subs s 1) s))

(defn- get-resources [path loader]
  loader
  (io-kernel/resources path))

(defn- safe-file-resource? [{:keys [body]} {:keys [root loader allow-symlinks?]}]
  (or allow-symlinks?
      (nil? root)
      (let [root (without-leading-slash (str root))]
        (or (str/blank? root)
            (let [path (canonical-path body)]
              (some #(and (= "file" (.getProtocol %))
                          (.startsWith path (canonical-path (url-as-file %))))
                    (get-resources root loader)))))))

(defn resource-response
  "Returns a Ring response to serve a packaged resource, or nil if the
  resource does not exist.
  Options:
    :root            - take the resource relative to this root
    :loader          - resolve the resource in this class loader
    :allow-symlinks? - allow symlinks that lead to paths outside the root
                       classpath directories (defaults to false)"
  ([path]
   (resource-response path {}))
  ([path options]
   (let [path      (str/replace (str "/" path) "//" "/")
         root+path (without-leading-slash (str (:root options) path))
         load      #(if-let [loader (:loader options)]
                      (do loader (io-kernel/resource %))
                      (io-kernel/resource %))]
     (when-not (directory-transversal? root+path)
       (when-let [resource (load root+path)]
         (let [response (url-response resource)]
           (when (or (not (instance? java.io.File (:body response)))
                     (safe-file-resource? response options))
             response)))))))
