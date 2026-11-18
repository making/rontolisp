(ns clojure.zip
  "Zippers: a cursor into a tree that can move and edit without mutating
  anything, built into rontolisp. Written here from the documented behaviour
  of Clojure's namespace of the same name and from what its locations print.

  A location is a vector [node trail]. The trail is nil at the top, :end
  once a depth-first walk has run out, and otherwise a map: :l the siblings
  to the left (a vector), :r the siblings to the right (a seq), :pnodes the
  ancestors from the root down, :ppath the parent's trail, and :changed?
  once something at this level was edited. What makes a value a tree -- which
  nodes have children, what they are, how to rebuild a node -- rides in the
  location's metadata."
  (:refer-clojure :exclude [replace remove next]))

(defn zipper
  "A location at the top of root. (branch? node) says whether node may have
  children, (children node) answers them as a seq, and (make-node node kids)
  builds a node like node holding kids."
  [branch? children make-node root]
  (with-meta [root nil]
    {:zip/branch? branch?, :zip/children children, :zip/make-node make-node}))

(defn seq-zip
  "A zipper over nested seqs: every seq is a branch of its members."
  [root]
  (zipper seq? identity (fn [old kids] (with-meta kids (meta old))) root))

(defn vector-zip
  "A zipper over nested vectors: every vector is a branch of its members."
  [root]
  (zipper vector? seq (fn [old kids] (with-meta (vec kids) (meta old))) root))

(defn xml-zip
  "A zipper over element maps as clojure.xml/parse answers them: anything but
  a string is a branch of its :content."
  [root]
  (zipper (fn [n] (not (string? n)))
          (fn [n] (seq (:content n)))
          (fn [n kids] (assoc n :content (when kids (vec kids))))
          root))

(defn node
  "The node loc points at."
  [loc]
  (loc 0))

(defn- trail [loc] (loc 1))

(defn- at
  "A location like loc, of the same tree kind, at node n with trail t."
  [loc n t]
  (with-meta [n t] (meta loc)))

(defn- edited [t] (assoc t :changed? true))

(defn branch?
  "True when the node at loc may have children."
  [loc]
  ((:zip/branch? (meta loc)) (node loc)))

(defn children
  "The children of the node at loc, which must be a branch."
  [loc]
  (when-not (branch? loc)
    (throw (Exception. "called children on a leaf node")))
  ((:zip/children (meta loc)) (node loc)))

(defn make-node
  "A new branch like node with the given children, built the way loc's
  zipper builds one."
  [loc node children]
  ((:zip/make-node (meta loc)) node children))

(defn path
  "The nodes from the root down to the parent of loc."
  [loc]
  (:pnodes (trail loc)))

(defn lefts
  "The siblings left of loc, nearest last."
  [loc]
  (seq (:l (trail loc))))

(defn rights
  "The siblings right of loc, nearest first."
  [loc]
  (:r (trail loc)))

(defn down
  "The location of the leftmost child of loc; nil when it has none."
  [loc]
  (when (branch? loc)
    (let [kids (children loc)]
      (when kids
        (let [t (trail loc)
              above (if t (conj (:pnodes t) (node loc)) [(node loc)])]
          (at loc (first kids) {:l [], :pnodes above, :ppath t, :r (clojure.core/next kids)}))))))

(defn up
  "The location of the parent of loc; nil at the top. A parent whose level
  was edited is rebuilt from its children."
  [loc]
  (let [t (when loc (trail loc))
        above (:pnodes t)]
    (when above
      (let [parent (peek above)
            outer (:ppath t)]
        (if (:changed? t)
          (at loc
              (make-node loc parent (concat (:l t) (cons (node loc) (:r t))))
              (and outer (edited outer)))
          (at loc parent outer))))))

(defn end?
  "True once a depth-first walk has gone past the last node."
  [loc]
  (= :end (trail loc)))

(defn root
  "The whole tree with every edit applied, from wherever loc is."
  [loc]
  (if (end? loc)
    (node loc)
    (loop [here loc]
      (let [parent (up here)]
        (if parent (recur parent) (node here))))))

(defn right
  "The location of the sibling right of loc; nil when there is none."
  [loc]
  (let [t (trail loc)
        more (:r t)]
    (when (and t more)
      (at loc (first more)
          (assoc t :l (conj (:l t) (node loc)) :r (clojure.core/next more))))))

(defn rightmost
  "The location of the last sibling of loc; loc itself when it is the last."
  [loc]
  (let [t (trail loc)
        more (:r t)]
    (if (and t more)
      (at loc (last more)
          (assoc t :l (into (conj (:l t) (node loc)) (butlast more)) :r nil))
      loc)))

(defn left
  "The location of the sibling left of loc; nil when there is none."
  [loc]
  (let [t (trail loc)
        before (:l t)]
    (when (and t (seq before))
      (at loc (peek before)
          (assoc t :l (pop before) :r (cons (node loc) (:r t)))))))

(defn leftmost
  "The location of the first sibling of loc; loc itself when it is the first."
  [loc]
  (let [t (trail loc)
        before (:l t)]
    (if (and t (seq before))
      (at loc (first before)
          (assoc t :l [] :r (concat (rest before) (cons (node loc) (:r t)))))
      loc)))

(defn- below-top
  "The trail of loc, refusing with what when loc is the top."
  [loc what]
  (or (trail loc) (throw (Exception. (str what " at top")))))

(defn insert-left
  "Puts item in as the left sibling of loc, without moving."
  [loc item]
  (let [t (below-top loc "Insert")]
    (at loc (node loc) (edited (assoc t :l (conj (:l t) item))))))

(defn insert-right
  "Puts item in as the right sibling of loc, without moving."
  [loc item]
  (let [t (below-top loc "Insert")]
    (at loc (node loc) (edited (assoc t :r (cons item (:r t)))))))

(defn replace
  "Puts node in place of the node at loc, without moving."
  [loc node]
  (at loc node (edited (trail loc))))

(defn edit
  "Replaces the node at loc with (f node args...)."
  [loc f & args]
  (replace loc (apply f (node loc) args)))

(defn insert-child
  "Puts item in as the first child of the node at loc, without moving."
  [loc item]
  (replace loc (make-node loc (node loc) (cons item (children loc)))))

(defn append-child
  "Puts item in as the last child of the node at loc, without moving."
  [loc item]
  (replace loc (make-node loc (node loc) (concat (children loc) (list item)))))

(defn- last-leaf
  "From loc, the last location of its subtree in depth-first order."
  [loc]
  (loop [here loc]
    (let [kid (and (branch? here) (down here))]
      (if kid (recur (rightmost kid)) here))))

(defn next
  "The location after loc in a depth-first walk. Past the last one it is an
  end location, which end? detects and which next answers again."
  [loc]
  (if (end? loc)
    loc
    (or (and (branch? loc) (down loc))
        (right loc)
        (loop [here loc]
          (let [parent (up here)]
            (if parent
              (or (right parent) (recur parent))
              [(node here) :end]))))))

(defn prev
  "The location before loc in a depth-first walk; nil at the top."
  [loc]
  (let [before (when loc (left loc))]
    (if before (last-leaf before) (up loc))))

(defn remove
  "Takes the node at loc out of the tree and answers the location that came
  before it in a depth-first walk."
  [loc]
  (let [t (below-top loc "Remove")
        before (:l t)]
    (if (seq before)
      (last-leaf (at loc (peek before) (edited (assoc t :l (pop before)))))
      (let [outer (:ppath t)]
        (at loc
            (make-node loc (peek (:pnodes t)) (:r t))
            (and outer (edited outer)))))))
