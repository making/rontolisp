(ns clojure.zip
  "Functional tree editing with zippers, built into rontolisp. A location
  (loc) is a vector of the focused node and the path to it, carrying in its
  metadata the three functions that make the tree a tree: :zip/branch?,
  :zip/children and :zip/make-node. Moving and editing answer new locs; root
  rebuilds the edited tree. Written for this front end from the documented
  behaviour of Clojure's namespace of the same name."
  (:refer-clojure :exclude [replace remove next]))

(defn zipper
  "A loc at root, the top of a tree whose branch nodes branch? recognizes,
  whose children children answers (a seq) and which make-node rebuilds from a
  node and a seq of children."
  [branch? children make-node root]
  (with-meta [root nil]
    {:zip/branch? branch? :zip/children children :zip/make-node make-node}))

(defn seq-zip
  "A zipper over nested seqs."
  [root]
  (zipper seq? identity (fn [node children] (with-meta children (meta node))) root))

(defn vector-zip
  "A zipper over nested vectors."
  [root]
  (zipper vector? seq (fn [node children] (with-meta (vec children) (meta node))) root))

(defn xml-zip
  "A zipper over maps of :tag, :attrs and :content, as clojure.xml parses
  XML into; a string is a leaf."
  [root]
  (zipper (complement string?)
          (comp seq :content)
          (fn [node children] (assoc node :content (and children (apply vector children))))
          root))

(defn- moved
  "A loc focused on node at path, carrying the zipper functions of loc."
  [loc node path]
  (with-meta [node path] (meta loc)))

(defn node
  "The node at loc."
  [loc]
  (loc 0))

(defn branch?
  "Whether the node at loc is a branch, one that may have children."
  [loc]
  ((:zip/branch? (meta loc)) (node loc)))

(defn children
  "The children of the branch node at loc, a seq."
  [loc]
  (if (branch? loc)
    ((:zip/children (meta loc)) (node loc))
    (throw (Exception. "called children on a leaf node"))))

(defn make-node
  "A new branch node from node and the seq children, rebuilt the way loc's
  zipper rebuilds one; loc is used only for that."
  [loc node children]
  ((:zip/make-node (meta loc)) node children))

(defn path
  "The nodes from the root down to the parent of loc's node, a vector."
  [loc]
  (:pnodes (loc 1)))

(defn lefts
  "The siblings to the left of loc's node, as a seq."
  [loc]
  (seq (:l (loc 1))))

(defn rights
  "The siblings to the right of loc's node, as a seq."
  [loc]
  (:r (loc 1)))

(defn down
  "The loc of the leftmost child of loc's node, or nil when it has none."
  [loc]
  (when (branch? loc)
    (let [focus (node loc)
          above (loc 1)
          kids (children loc)]
      (when kids
        (moved loc (first kids)
               {:l []
                :pnodes (if above (conj (:pnodes above) focus) [focus])
                :ppath above
                :r (clojure.core/next kids)})))))

(defn up
  "The loc of the parent of loc's node, rebuilt when an edit below changed
  it; nil at the top."
  [loc]
  (let [focus (node loc)
        {:keys [l r pnodes ppath changed?]} (loc 1)]
    (when pnodes
      (let [parent (peek pnodes)]
        (if changed?
          (moved loc (make-node loc parent (concat l (cons focus r)))
                 (and ppath (assoc ppath :changed? true)))
          (moved loc parent ppath))))))

(defn end?
  "Whether loc is the end of a depth-first walk with next."
  [loc]
  (= :end (loc 1)))

(defn root
  "The root node of loc's tree, with every edit applied."
  [loc]
  (if (end? loc)
    (node loc)
    (loop [loc loc]
      (if-let [parent (up loc)]
        (recur parent)
        (node loc)))))

(defn right
  "The loc of the right sibling of loc's node, or nil."
  [loc]
  (let [focus (node loc)
        above (loc 1)
        siblings (:r above)]
    (when (and above siblings)
      (moved loc (first siblings)
             (assoc above :l (conj (:l above) focus) :r (clojure.core/next siblings))))))

(defn rightmost
  "The loc of the rightmost sibling of loc's node, or loc itself."
  [loc]
  (let [focus (node loc)
        above (loc 1)
        siblings (:r above)]
    (if (and above siblings)
      (moved loc (last siblings)
             (assoc above :l (apply conj (:l above) focus (butlast siblings)) :r nil))
      loc)))

(defn left
  "The loc of the left sibling of loc's node, or nil."
  [loc]
  (let [focus (node loc)
        above (loc 1)
        siblings (:l above)]
    (when (and above (seq siblings))
      (moved loc (peek siblings)
             (assoc above :l (pop siblings) :r (cons focus (:r above)))))))

(defn leftmost
  "The loc of the leftmost sibling of loc's node, or loc itself."
  [loc]
  (let [focus (node loc)
        above (loc 1)
        siblings (:l above)]
    (if (and above (seq siblings))
      (moved loc (first siblings)
             (assoc above :l [] :r (concat (rest siblings) [focus] (:r above))))
      loc)))

(defn insert-left
  "loc with item inserted as the left sibling of its node."
  [loc item]
  (let [above (loc 1)]
    (if (nil? above)
      (throw (Exception. "Insert at top"))
      (moved loc (node loc) (assoc above :l (conj (:l above) item) :changed? true)))))

(defn insert-right
  "loc with item inserted as the right sibling of its node."
  [loc item]
  (let [above (loc 1)]
    (if (nil? above)
      (throw (Exception. "Insert at top"))
      (moved loc (node loc) (assoc above :r (cons item (:r above)) :changed? true)))))

(defn replace
  "loc with node in place of its node."
  [loc node]
  (moved loc node (assoc (loc 1) :changed? true)))

(defn edit
  "loc with its node replaced by (apply f node args)."
  [loc f & args]
  (replace loc (apply f (node loc) args)))

(defn insert-child
  "loc with item inserted as the leftmost child of its node."
  [loc item]
  (replace loc (make-node loc (node loc) (cons item (children loc)))))

(defn append-child
  "loc with item inserted as the rightmost child of its node."
  [loc item]
  (replace loc (make-node loc (node loc) (concat (children loc) [item]))))

(defn next
  "The loc after loc in a depth-first walk; past the last node, the end loc,
  whose node is the root (end? is true of it and next answers it again)."
  [loc]
  (if (end? loc)
    loc
    (or (and (branch? loc) (down loc))
        (right loc)
        (loop [at loc]
          (if-let [parent (up at)]
            (or (right parent) (recur parent))
            [(node at) :end])))))

(defn- deepest-last
  "The rightmost leaf below loc's node, or loc when it has no children."
  [loc]
  (if-let [child (and (branch? loc) (down loc))]
    (recur (rightmost child))
    loc))

(defn prev
  "The loc before loc in a depth-first walk; nil at the root."
  [loc]
  (if-let [before (left loc)]
    (deepest-last before)
    (up loc)))

(defn remove
  "Removes the node at loc, answering the loc that came before it in a
  depth-first walk."
  [loc]
  (let [{:keys [l pnodes ppath r] :as above} (loc 1)]
    (if (nil? above)
      (throw (Exception. "Remove at top"))
      (if (pos? (count l))
        (deepest-last (moved loc (peek l) (assoc above :l (pop l) :changed? true)))
        (moved loc (make-node loc (peek pnodes) r)
               (and ppath (assoc ppath :changed? true)))))))
