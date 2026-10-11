package am.ik.rontolisp.runtime;

import java.util.Arrays;

/**
 * A hash table in the JVM backend's value representation: the one class the emitted
 * {@code _hash*} helpers, the printer, {@code hash-table-p}, the {@code java:} bridges
 * and the hand-written runtimes ({@link RontoHttpClack}) all agree a Lisp table is -- and
 * the interpreter's {@code LispHashTable} keeps its entries in one too. A host map a
 * {@code java:} call answers is never one, whatever its class.
 *
 * <p>
 * Only the emitted code (or the interpreter) can compare two keys -- the test is
 * {@code _equal} or {@code _eqv} over the program's own value model -- so the table
 * answers the CHAIN of entries a hash leads to ({@link #find}, {@link #putOrChain}), the
 * caller walks it, skipping an entry whose {@link Entry#hash} is not the key's, compares
 * with the table's test, and hands back the entry it matched ({@link #setValue},
 * {@link #remove}) or a fresh pair ({@link #add}). An entry's PAIR is what
 * {@code maphash} and the bridges read: {@code {key, value}}, or for an {@code equalp}
 * table whose fold differs from the key {@code {fold, value, key}} -- placed and compared
 * by the fold in slot 0, the key as first stored in slot 2, which {@code maphash} hands
 * back. {@code maphash} walks the pairs in insertion order ({@link #liveValues}): a
 * re-store keeps its place, a removal leaves a dead slot until half the slots are dead.
 *
 * <p>
 * TWO SHAPES. A table starts FLAT: a classic chained hash table, its own entries changed
 * in place, the insertion order an array -- what a Common Lisp table is all its life. The
 * first {@link #copyEqual} turns it into a TRIE (a hash array mapped trie of hash chains
 * plus a persistent vector of the pairs in order) and shares that with the copy:
 * {@code O(n)} once, and every copy after it {@code O(1)}. Every node, vector node and
 * entry of a trie carries the EDIT token of the trie that made it, and a trie changes in
 * place only what carries its own token, copying anything else along the path from the
 * root (and a pair whose entry carries another token before its value changes); a copy
 * gives both tables a fresh token over the same structure, so neither changes in place
 * what the other reads. Every Clojure {@code assoc} is such a copy and one store
 * ({@code .kb/hash-tables.md}, "Copies share structure"). {@link #clear} makes a table
 * flat again.
 *
 * <p>
 * THREADS. Reading a table ({@link #find}, {@link #liveValues}, {@link #count}) writes
 * nothing, so a table nobody changes may be read from many threads at once -- a Clojure
 * map shared by Ring request threads. A copy writes one field of its source: the shape
 * ({@code repr}, volatile), replaced by a trie of the same entries with a fresh token, so
 * a reader on another thread works on the shape it read -- the flat one, which nothing
 * changes any more, or the whole trie. Two threads copying one table race only on which
 * of two equal shapes the source ends with. Changing a table while another thread reads
 * or copies it is a race, as it is for any Common Lisp table.
 *
 * <p>
 * No {@code equals}/{@code hashCode} override: {@code _hash}, {@code _eqv} and
 * {@code _equal} key and compare a table by its identity, which is what {@code equal}
 * means for one.
 *
 * <p>
 * It lives in {@code runtime} because it travels with a compiled program
 * ({@code .kb/jvm-export.md}, "What travels"); like everything in this package it imports
 * nothing but {@code java.base} -- not even the build's {@code @Nullable}, whose class
 * file reference would follow the class into a consumer's artifact. That is why
 * {@link #get} takes the absent value and a chain, an empty bucket and a dead slot hold
 * {@link #END}, never null.
 */
public final class RontoHashTable {

	/** The runtime class of a table, in internal (slash-separated) form. */
	public static final String MAP_CLASS = "am/ik/rontolisp/runtime/RontoHashTable";

	/** The runtime class of a chain entry ({@link Entry}), in internal form. */
	public static final String ENTRY_CLASS = "am/ik/rontolisp/runtime/RontoHashTable$Entry";

	/** The test code of an {@code equal} table, as every backend numbers it. */
	public static final int TEST_EQUAL = 0;

	/** The test code of an {@code equalp} table, whose keys are folded first. */
	public static final int TEST_EQUALP = 1;

	/** The test code of an {@code eql} table, whose aggregates key by identity. */
	public static final int TEST_EQL = 2;

	/** The test code of an {@code eq} table, whose aggregates key by identity. */
	public static final int TEST_EQ = 3;

	/**
	 * How many levels {@link #equalpKey(Object, int)} descends before answering the
	 * subtree unfolded -- the same cap the structural hash uses, and for the same reason:
	 * a CYCLIC key must terminate.
	 */
	public static final int FOLD_DEPTH_CAP = 64;

	/**
	 * How many NODES {@link #equalpKey(Object, int)} may visit across one whole fold
	 * before it answers the rest of the key unfolded -- the same budget the structural
	 * hash spends, and for the same reason: the depth cap bounds the walk's HEIGHT and
	 * says nothing about its SIZE, and a key whose substructure is SHARED has
	 * exponentially many root-to-leaf paths. The fold BUILDS a structure, so an
	 * un-budgeted one does not merely take that long, it allocates that much.
	 */
	public static final int FOLD_WORK_CAP = 4096;

	private static final int BITS = 5;

	private static final int WIDTH = 1 << BITS;

	private static final int MASK = WIDTH - 1;

	// The token no trie ever holds: the shared empty nodes and every flat entry carry
	// it, so no trie changes them in place.
	private static final Object NOBODY = new Object();

	private static final Node EMPTY_NODE = new Node(NOBODY, 0, new Object[0]);

	private static final VNode EMPTY_VNODE = new VNode(NOBODY, new Object[WIDTH]);

	// What a removed pair's order slot in a trie holds until the trie rebuilds.
	private static final Object DEAD = new Object();

	/**
	 * The end of every chain -- what {@link #find} answers for a hash no key of the table
	 * has, an empty bucket holds and a removed entry's order slot holds.
	 */
	public static final Entry END = new Entry();

	private final int test;

	// The shape: a Flat or a Trie.
	private volatile Object repr;

	private RontoHashTable(int test, Object repr) {
		this.test = test;
		this.repr = repr;
	}

	/**
	 * One key's entry: its hash, its pair, its slot in the insertion order and the next
	 * entry of its chain. A flat table's entry is its own; a trie's is immutable but for
	 * the pair's value slot, which a store changes in place only when the entry carries
	 * the trie's own token.
	 */
	public static final class Entry {

		/** The key's hash, as the table was handed it. */
		public final int hash;

		/**
		 * The pair {@code maphash} reads: {@code {key, value}} or {@code {fold, value,
		 * key}}.
		 */
		public final Object[] pair;

		/**
		 * The next entry of the chain, or {@link #END}: in a trie every entry of a chain
		 * has the hash, in a flat table's bucket not every one does.
		 */
		public Entry next;

		int pos;

		final Object edit;

		Entry(int hash, Object[] pair, int pos, Entry next, Object edit) {
			this.hash = hash;
			this.pair = pair;
			this.pos = pos;
			this.next = next;
			this.edit = edit;
		}

		// END: a chain of no entries, ending in itself.
		private Entry() {
			this.hash = 0;
			this.pair = new Object[2];
			this.pos = -1;
			this.next = this;
			this.edit = NOBODY;
		}

		Entry withNext(Entry rest) {
			return new Entry(this.hash, this.pair, this.pos, rest, this.edit);
		}

	}

	/**
	 * Builds an empty {@code equal} table.
	 * @return the table
	 */
	public static RontoHashTable newTable() {
		return new RontoHashTable(TEST_EQUAL, new Flat());
	}

	/**
	 * Builds an empty table whose lookups implement the given test.
	 * @param test the test code ({@link #TEST_EQUAL} ... {@link #TEST_EQ})
	 * @return the table
	 */
	public static RontoHashTable newTable(int test) {
		return new RontoHashTable(test, new Flat());
	}

	/**
	 * The test a table's lookups implement.
	 * @param table the table
	 * @return its test code
	 */
	public static int test(RontoHashTable table) {
		return table.test;
	}

	/**
	 * The live entry count, O(1).
	 * @param table the table
	 * @return how many keys the table holds
	 */
	public static int count(RontoHashTable table) {
		Object repr = table.repr;
		return (repr instanceof Flat flat) ? flat.count : ((Trie) repr).count;
	}

	/**
	 * The chain a hash leads to: its first entry of the hash, or {@link #END} when no key
	 * of the table has it. The caller walks {@link Entry#next} from there, skipping an
	 * entry of another hash.
	 * @param table the table
	 * @param hash the key's hash, as the table places it
	 * @return the first entry of the hash, or {@link #END}
	 */
	public static Entry find(RontoHashTable table, int hash) {
		Object repr = table.repr;
		return (repr instanceof Flat flat) ? flat.find(hash) : ((Trie) repr).find(hash);
	}

	/**
	 * A store's one walk: when no key of the table has the hash, the fresh pair goes in
	 * -- last in the insertion order -- and {@link #END} is answered; else the first
	 * entry of the hash is answered and nothing is stored, the caller walking the chain
	 * for the key ({@link #setValue} on a match, {@link #add} on none).
	 * @param table the table
	 * @param hash the key's hash, as the table places it
	 * @param key the key the table places and compares (an {@code equalp} table's fold)
	 * @param value the value
	 * @param written the key as written: {@code key} itself, or the key an {@code equalp}
	 * fold changed, which a third pair slot keeps
	 * @return {@link #END} when the pair went in, else the first entry of the hash
	 */
	public static Entry putOrChain(RontoHashTable table, int hash, Object key, Object value, Object written) {
		Object repr = table.repr;
		return (repr instanceof Flat flat) ? flat.putOrChain(hash, key, value, written)
				: ((Trie) repr).putOrChain(hash, key, value, written);
	}

	/**
	 * Stores a key the table does not hold, which shares its hash with a key it does
	 * ({@link #putOrChain} stores every other): the pair goes last in the insertion
	 * order.
	 * @param table the table
	 * @param hash the key's hash, as the table places it
	 * @param pair the fresh pair, {@code {key, value}} or {@code {fold, value, key}}
	 */
	public static void add(RontoHashTable table, int hash, Object[] pair) {
		Object repr = table.repr;
		if (repr instanceof Flat flat) {
			flat.add(hash, pair);
		}
		else {
			((Trie) repr).add(hash, pair);
		}
	}

	/**
	 * Replaces the value of an entry {@link #find} answered: in place when the entry is
	 * the table's own, else in a copy of its pair, which a copy of the trie path to it
	 * and the pair's order slot then hold.
	 * @param table the table
	 * @param entry an entry of the table
	 * @param value the new value
	 */
	public static void setValue(RontoHashTable table, Entry entry, Object value) {
		Object repr = table.repr;
		if (repr instanceof Trie trie && entry.edit != trie.edit) {
			trie.setShared(entry, value);
			return;
		}
		entry.pair[1] = value;
	}

	/**
	 * Removes an entry {@link #find} answered. Its order slot turns dead; the order
	 * compacts once half the slots are, so each removal stays O(1) amortised.
	 * @param table the table
	 * @param entry an entry of the table
	 */
	public static void remove(RontoHashTable table, Entry entry) {
		Object repr = table.repr;
		if (repr instanceof Flat flat) {
			flat.remove(entry);
		}
		else {
			((Trie) repr).remove(entry);
		}
	}

	/**
	 * Removes every entry; the table keeps its test and is flat again.
	 * @param table the table
	 */
	public static void clear(RontoHashTable table) {
		table.repr = new Flat();
	}

	/**
	 * The live pairs in insertion order -- what {@code maphash} walks. A snapshot:
	 * changing the table afterwards changes neither the array nor which pairs it holds.
	 * @param table the table
	 * @return the pairs
	 */
	public static Object[] liveValues(RontoHashTable table) {
		Object repr = table.repr;
		return (repr instanceof Flat flat) ? flat.liveValues() : ((Trie) repr).liveValues();
	}

	/**
	 * A fresh {@code equal} table of an {@code equal} table's entries in its order
	 * ({@code rontolisp::%copy-equal-table}): the two share every trie node until one of
	 * them changes it. A flat table becomes the trie first, once, in {@code O(n)}; a
	 * trie's copy is {@code O(1)}. Another test's table is re-placed key by key under
	 * {@code equal} by the caller, which can hash.
	 * @param table an {@code equal} table
	 * @return the copy
	 */
	public static RontoHashTable copyEqual(RontoHashTable table) {
		if (table.test != TEST_EQUAL) {
			throw new IllegalArgumentException("copyEqual of a table whose test is " + table.test);
		}
		Object repr = table.repr;
		Trie shared = (repr instanceof Flat flat) ? flat.toTrie() : (Trie) repr;
		// Both take a fresh token: neither owns what the other reads from here.
		table.repr = shared.share();
		return new RontoHashTable(TEST_EQUAL, shared.share());
	}

	/**
	 * Stores {@code value} under an ATOM key -- a value whose structural hash is its own
	 * {@code hashCode} and whose {@code equal} is its own {@code equals} (a string, a
	 * symbol, a number), which is every key a hand-written runtime builds. An aggregate
	 * key would need the emitted {@code _hash}/{@code _equal} pair and belongs in Lisp.
	 * @param table the table
	 * @param key the key
	 * @param value the value to store
	 */
	public static void put(RontoHashTable table, Object key, Object value) {
		int hash = key.hashCode();
		Entry chain = putOrChain(table, hash, key, value, key);
		if (chain == END) {
			return;
		}
		for (Entry entry = chain; entry != END; entry = entry.next) {
			if (entry.hash == hash && key.equals(entry.pair[0])) {
				setValue(table, entry, value);
				return;
			}
		}
		add(table, hash, new Object[] { key, value });
	}

	/**
	 * Returns the value stored under an atom key, or {@code ifAbsent} when the key is not
	 * in the table. The absent value is a PARAMETER rather than a null return because
	 * this package carries no annotation of any kind -- see the class javadoc.
	 * @param table the table
	 * @param key the key
	 * @param ifAbsent what to answer when the key is absent
	 * @return the stored value, or {@code ifAbsent}
	 */
	public static Object get(RontoHashTable table, Object key, Object ifAbsent) {
		int hash = key.hashCode();
		for (Entry entry = find(table, hash); entry != END; entry = entry.next) {
			if (entry.hash == hash && key.equals(entry.pair[0])) {
				return entry.pair[1];
			}
		}
		return ifAbsent;
	}

	// The high half folded into the low, which the bucket index and the trie read first
	// (HashMap's spread): a bijection, so two keys share a trie chain exactly when their
	// hashes are equal; a hash whose low bits barely vary (a double's, often zero below
	// bit 20) takes its first bits from the high ones, and small integers keep their own,
	// so consecutive keys stay neighbours.
	static int spread(int hash) {
		return hash ^ (hash >>> 16);
	}

	// --- the flat shape -------------------------------------------------------------

	// A chained hash table of the table's own entries, a bucket's chain linked through
	// Entry.next and the insertion order an array of the entries, a removed one END
	// until half the slots are and the array compacts.
	static final class Flat {

		Entry[] buckets;

		Entry[] order;

		int size;

		int count;

		int dead;

		Flat() {
			this.buckets = new Entry[8];
			Arrays.fill(this.buckets, END);
			this.order = new Entry[8];
		}

		Entry find(int hash) {
			Entry entry = this.buckets[spread(hash) & (this.buckets.length - 1)];
			while (entry != END && entry.hash != hash) {
				entry = entry.next;
			}
			return entry;
		}

		Entry putOrChain(int hash, Object key, Object value, Object written) {
			Entry entry = find(hash);
			if (entry != END) {
				return entry;
			}
			add(hash, (written == key) ? new Object[] { key, value } : new Object[] { key, value, written });
			return END;
		}

		void add(int hash, Object[] pair) {
			int index = spread(hash) & (this.buckets.length - 1);
			Entry entry = new Entry(hash, pair, this.size, this.buckets[index], NOBODY);
			this.buckets[index] = entry;
			if (this.size == this.order.length) {
				this.order = Arrays.copyOf(this.order, 2 * this.size);
			}
			this.order[this.size++] = entry;
			this.count++;
			if (this.count > this.buckets.length - (this.buckets.length >>> 2)) {
				grow();
			}
		}

		// Twice the buckets, each entry relinked by the hash it keeps.
		private void grow() {
			Entry[] fresh = new Entry[2 * this.buckets.length];
			Arrays.fill(fresh, END);
			int mask = fresh.length - 1;
			for (Entry head : this.buckets) {
				Entry entry = head;
				while (entry != END) {
					Entry next = entry.next;
					int index = spread(entry.hash) & mask;
					entry.next = fresh[index];
					fresh[index] = entry;
					entry = next;
				}
			}
			this.buckets = fresh;
		}

		void remove(Entry target) {
			int index = spread(target.hash) & (this.buckets.length - 1);
			Entry entry = this.buckets[index];
			if (entry == target) {
				this.buckets[index] = target.next;
			}
			else {
				while (entry.next != target) {
					entry = entry.next;
				}
				entry.next = target.next;
			}
			this.order[target.pos] = END;
			this.count--;
			this.dead++;
			if (this.dead * 2 >= this.size) {
				int kept = 0;
				for (int i = 0; i < this.size; i++) {
					Entry live = this.order[i];
					if (live != END) {
						live.pos = kept;
						this.order[kept++] = live;
					}
				}
				Arrays.fill(this.order, kept, this.size, END);
				this.size = kept;
				this.dead = 0;
			}
		}

		Object[] liveValues() {
			Object[] out = new Object[this.count];
			int kept = 0;
			for (int i = 0; i < this.size; i++) {
				Entry entry = this.order[i];
				if (entry != END) {
					out[kept++] = entry.pair;
				}
			}
			return out;
		}

		// The trie of the live entries in their order, fresh entries over the same
		// pairs: the flat ones stay as they are for any reader still on this shape.
		Trie toTrie() {
			Trie trie = new Trie(new Object());
			for (int i = 0; i < this.size; i++) {
				Entry entry = this.order[i];
				if (entry != END) {
					trie.add(entry.hash, entry.pair);
				}
			}
			return trie;
		}

	}

	// --- the trie shape -------------------------------------------------------------

	// A trie node: the children (chains and nodes) of the set bits of bitmap, in bit
	// order. Slots past the bitmap's count are spare room an owned node grows into.
	static final class Node {

		final Object edit;

		int bitmap;

		Object[] array;

		Node(Object edit, int bitmap, Object[] array) {
			this.edit = edit;
			this.bitmap = bitmap;
			this.array = array;
		}

	}

	// An order-vector node: 32 children, or 32 pairs at a leaf.
	static final class VNode {

		final Object edit;

		final Object[] array;

		VNode(Object edit, Object[] array) {
			this.edit = edit;
			this.array = array;
		}

	}

	// The persistent shape: a hash array mapped trie of chains (five bits a level, a
	// node's children packed by a bitmap, a chain the entries of one whole hash) and the
	// pairs in insertion order, Clojure's PersistentVector shape -- a 32-way trie of full
	// leaves plus a tail leaf.
	static final class Trie {

		final Object edit;

		Node root = EMPTY_NODE;

		int count;

		VNode orderRoot = EMPTY_VNODE;

		VNode orderTail = EMPTY_VNODE;

		int orderSize;

		int orderShift = BITS;

		// How many order slots hold DEAD.
		int dead;

		Trie(Object edit) {
			this.edit = edit;
		}

		// Another Trie over the same structure with a fresh token: what each side of a
		// copy takes.
		Trie share() {
			Trie trie = new Trie(new Object());
			trie.root = this.root;
			trie.count = this.count;
			trie.orderRoot = this.orderRoot;
			trie.orderTail = this.orderTail;
			trie.orderSize = this.orderSize;
			trie.orderShift = this.orderShift;
			trie.dead = this.dead;
			return trie;
		}

		Entry find(int hash) {
			int spread = spread(hash);
			Node node = this.root;
			int shift = 0;
			for (;;) {
				int bit = 1 << ((spread >>> shift) & MASK);
				int bitmap = node.bitmap;
				if ((bitmap & bit) == 0) {
					return END;
				}
				Object child = node.array[Integer.bitCount(bitmap & (bit - 1))];
				if (child instanceof Node sub) {
					node = sub;
					shift += BITS;
				}
				else {
					Entry chain = (Entry) child;
					return (chain.hash == hash) ? chain : END;
				}
			}
		}

		// One walk down: the chain of the hash, or the fresh pair in where the walk
		// stopped -- in place when that node is the trie's own, else along a copied path.
		Entry putOrChain(int hash, Object key, Object value, Object written) {
			int spread = spread(hash);
			Node node = this.root;
			int shift = 0;
			int bit;
			int index;
			Entry chain = END;
			for (;;) {
				bit = 1 << ((spread >>> shift) & MASK);
				index = Integer.bitCount(node.bitmap & (bit - 1));
				if ((node.bitmap & bit) == 0) {
					break;
				}
				Object child = node.array[index];
				if (child instanceof Node sub) {
					node = sub;
					shift += BITS;
					continue;
				}
				chain = (Entry) child;
				if (chain.hash == hash) {
					return chain;
				}
				break;
			}
			Object[] pair = (written == key) ? new Object[] { key, value } : new Object[] { key, value, written };
			int pos = this.orderSize;
			orderAppend(pair);
			if (node.edit != this.edit) {
				this.root = insert(this.root, 0, hash, pair, pos, this.edit);
			}
			else if (chain == END) {
				// A free slot in a node the trie owns: in place.
				int children = Integer.bitCount(node.bitmap);
				if (children + 1 > node.array.length) {
					node.array = Arrays.copyOf(node.array, Math.max(4, 2 * children));
				}
				System.arraycopy(node.array, index, node.array, index + 1, children - index);
				node.array[index] = new Entry(hash, pair, pos, END, this.edit);
				node.bitmap |= bit;
			}
			else {
				// Another hash in the slot: a new level holds the two.
				node.array[index] = split(shift + BITS, chain, new Entry(hash, pair, pos, END, this.edit));
			}
			this.count++;
			return END;
		}

		void add(int hash, Object[] pair) {
			int pos = this.orderSize;
			orderAppend(pair);
			this.root = insert(this.root, 0, hash, pair, pos, this.edit);
			this.count++;
		}

		// A new value for an entry another trie shares: a copy of its pair in a copied
		// path, and in its order slot.
		void setShared(Entry entry, Object value) {
			Object[] pair = entry.pair.clone();
			pair[1] = value;
			Entry fresh = new Entry(entry.hash, pair, entry.pos, entry.next, this.edit);
			this.root = replace(this.root, 0, entry, fresh);
			orderSet(entry.pos, pair);
		}

		void remove(Entry entry) {
			this.root = without(this.root, 0, entry);
			orderSet(entry.pos, DEAD);
			this.count--;
			this.dead++;
			if (this.dead * 2 >= this.orderSize) {
				rebuild();
			}
		}

		Object[] liveValues() {
			Object[] out = new Object[this.count];
			int kept = 0;
			int size = this.orderSize;
			for (int base = 0; base < size; base += WIDTH) {
				Object[] leaf = orderLeaf(base);
				int end = Math.min(WIDTH, size - base);
				for (int i = 0; i < end; i++) {
					Object pair = leaf[i];
					if (pair != DEAD) {
						out[kept++] = pair;
					}
				}
			}
			return out;
		}

		// The node with room for `room` more children that this trie may change: node
		// itself when it carries the trie's token, else a copy that does.
		private Node editable(Node node, int room) {
			int children = Integer.bitCount(node.bitmap);
			if (node.edit == this.edit) {
				if (children + room > node.array.length) {
					node.array = Arrays.copyOf(node.array, Math.max(children + room, 2 * children));
				}
				return node;
			}
			return new Node(this.edit, node.bitmap, Arrays.copyOf(node.array, children + room));
		}

		private Node insert(Node node, int shift, int hash, Object[] pair, int pos, Object entryEdit) {
			int bit = 1 << ((spread(hash) >>> shift) & MASK);
			int index = Integer.bitCount(node.bitmap & (bit - 1));
			if ((node.bitmap & bit) == 0) {
				Node owned = editable(node, 1);
				int children = Integer.bitCount(owned.bitmap);
				System.arraycopy(owned.array, index, owned.array, index + 1, children - index);
				owned.array[index] = new Entry(hash, pair, pos, END, entryEdit);
				owned.bitmap |= bit;
				return owned;
			}
			Object child = node.array[index];
			Object replacement;
			if (child instanceof Node sub) {
				Node grown = insert(sub, shift + BITS, hash, pair, pos, entryEdit);
				if (grown == sub) {
					return node;
				}
				replacement = grown;
			}
			else {
				Entry chain = (Entry) child;
				if (chain.hash == hash) {
					replacement = new Entry(hash, pair, pos, chain, entryEdit);
				}
				else {
					replacement = split(shift + BITS, chain, new Entry(hash, pair, pos, END, entryEdit));
				}
			}
			Node owned = editable(node, 0);
			owned.array[index] = replacement;
			return owned;
		}

		// A node holding two chains of different hashes, one level per shared five bits.
		// Terminates: two different 32-bit hashes differ in some level's bits.
		private Node split(int shift, Entry a, Entry b) {
			int ma = (spread(a.hash) >>> shift) & MASK;
			int mb = (spread(b.hash) >>> shift) & MASK;
			if (ma == mb) {
				return new Node(this.edit, 1 << ma, new Object[] { split(shift + BITS, a, b), END });
			}
			// Room for two more children, which the trie that owns the node adds in
			// place.
			return new Node(this.edit, (1 << ma) | (1 << mb),
					(ma < mb) ? new Object[] { a, b, END, END } : new Object[] { b, a, END, END });
		}

		private Node without(Node node, int shift, Entry target) {
			int bit = 1 << ((spread(target.hash) >>> shift) & MASK);
			int index = Integer.bitCount(node.bitmap & (bit - 1));
			Object child = node.array[index];
			Object replacement;
			if (child instanceof Node sub) {
				Node shrunk = without(sub, shift + BITS, target);
				if (shrunk.bitmap == 0) {
					return removeChild(node, bit, index);
				}
				if (shrunk == sub) {
					return node;
				}
				replacement = shrunk;
			}
			else {
				Entry rest = relink((Entry) child, target, target.next);
				if (rest == END) {
					return removeChild(node, bit, index);
				}
				replacement = rest;
			}
			Node owned = editable(node, 0);
			owned.array[index] = replacement;
			return owned;
		}

		private Node removeChild(Node node, int bit, int index) {
			Node owned = editable(node, 0);
			int children = Integer.bitCount(owned.bitmap);
			System.arraycopy(owned.array, index + 1, owned.array, index, children - index - 1);
			// The vacated slot drops its reference (END: this package writes no null).
			owned.array[children - 1] = END;
			owned.bitmap &= ~bit;
			return owned;
		}

		private Node replace(Node node, int shift, Entry old, Entry fresh) {
			int bit = 1 << ((spread(old.hash) >>> shift) & MASK);
			int index = Integer.bitCount(node.bitmap & (bit - 1));
			Object child = node.array[index];
			Object replacement;
			if (child instanceof Node sub) {
				Node changed = replace(sub, shift + BITS, old, fresh);
				if (changed == sub) {
					return node;
				}
				replacement = changed;
			}
			else {
				replacement = relink((Entry) child, old, fresh);
			}
			Node owned = editable(node, 0);
			owned.array[index] = replacement;
			return owned;
		}

		// The chain with target replaced by tail: the entries ahead of target rebuilt
		// onto tail (a trie's entries are immutable), the ones behind it shared.
		// Iterative, so a chain of many keys sharing one hash costs no stack.
		private static Entry relink(Entry chain, Entry target, Entry tail) {
			int ahead = 0;
			for (Entry entry = chain; entry != target; entry = entry.next) {
				ahead++;
			}
			if (ahead == 0) {
				return tail;
			}
			Entry[] prefix = new Entry[ahead];
			Entry entry = chain;
			for (int i = 0; i < ahead; i++) {
				prefix[i] = entry;
				entry = entry.next;
			}
			Entry rest = tail;
			for (int i = ahead - 1; i >= 0; i--) {
				rest = prefix[i].withNext(rest);
			}
			return rest;
		}

		// Rebuilds the trie and the order vector from the live entries, in their order:
		// the dead slots go. Each entry keeps its pair and its token, so a pair another
		// table shares stays one this trie copies before changing.
		private void rebuild() {
			Entry[] byPos = new Entry[this.orderSize];
			collect(this.root, byPos);
			this.root = EMPTY_NODE;
			this.orderRoot = EMPTY_VNODE;
			this.orderTail = EMPTY_VNODE;
			this.orderSize = 0;
			this.orderShift = BITS;
			this.dead = 0;
			for (Entry entry : byPos) {
				if (entry != null) {
					int pos = this.orderSize;
					orderAppend(entry.pair);
					this.root = insert(this.root, 0, entry.hash, entry.pair, pos, entry.edit);
				}
			}
		}

		private static void collect(Node node, Entry[] byPos) {
			int children = Integer.bitCount(node.bitmap);
			for (int i = 0; i < children; i++) {
				Object child = node.array[i];
				if (child instanceof Node sub) {
					collect(sub, byPos);
				}
				else {
					for (Entry entry = (Entry) child; entry != END; entry = entry.next) {
						byPos[entry.pos] = entry;
					}
				}
			}
		}

		private int tailOffset() {
			return (this.orderSize < WIDTH) ? 0 : ((this.orderSize - 1) >>> BITS) << BITS;
		}

		private VNode editable(VNode node) {
			return (node.edit == this.edit) ? node : new VNode(this.edit, node.array.clone());
		}

		private void orderAppend(Object pair) {
			int size = this.orderSize;
			if (size - tailOffset() < WIDTH) {
				this.orderTail = editable(this.orderTail);
				this.orderTail.array[size & MASK] = pair;
			}
			else {
				// The full tail becomes a leaf of the trie, as it is: one this trie does
				// not own stays unchanged there.
				VNode full = this.orderTail;
				if ((size >>> BITS) > (1 << this.orderShift)) {
					Object[] children = new Object[WIDTH];
					children[0] = this.orderRoot;
					children[1] = newPath(this.orderShift, full);
					this.orderRoot = new VNode(this.edit, children);
					this.orderShift += BITS;
				}
				else {
					this.orderRoot = pushTail(this.orderShift, this.orderRoot, full);
				}
				Object[] tail = new Object[WIDTH];
				tail[0] = pair;
				this.orderTail = new VNode(this.edit, tail);
			}
			this.orderSize = size + 1;
		}

		private VNode pushTail(int level, VNode parent, VNode full) {
			VNode owned = editable(parent);
			int index = ((this.orderSize - 1) >>> level) & MASK;
			if (level == BITS) {
				owned.array[index] = full;
			}
			else {
				Object child = owned.array[index];
				owned.array[index] = (child instanceof VNode sub) ? pushTail(level - BITS, sub, full)
						: newPath(level - BITS, full);
			}
			return owned;
		}

		private VNode newPath(int level, VNode leaf) {
			if (level == 0) {
				return leaf;
			}
			Object[] children = new Object[WIDTH];
			children[0] = newPath(level - BITS, leaf);
			return new VNode(this.edit, children);
		}

		private void orderSet(int pos, Object pair) {
			if (pos >= tailOffset()) {
				this.orderTail = editable(this.orderTail);
				this.orderTail.array[pos & MASK] = pair;
				return;
			}
			this.orderRoot = orderAssoc(this.orderShift, this.orderRoot, pos, pair);
		}

		private VNode orderAssoc(int level, VNode node, int pos, Object pair) {
			VNode owned = editable(node);
			if (level == 0) {
				owned.array[pos & MASK] = pair;
			}
			else {
				int index = (pos >>> level) & MASK;
				owned.array[index] = orderAssoc(level - BITS, (VNode) owned.array[index], pos, pair);
			}
			return owned;
		}

		private Object[] orderLeaf(int pos) {
			if (pos >= tailOffset()) {
				return this.orderTail.array;
			}
			VNode node = this.orderRoot;
			for (int level = this.orderShift; level > 0; level -= BITS) {
				node = (VNode) node.array[(pos >>> level) & MASK];
			}
			return node.array;
		}

	}

	// --- the equalp fold -------------------------------------------------------------

	/**
	 * Folds a key into the representative an {@code equalp} table places it under:
	 * {@code equalp} on two values is the structural {@code equal} on their folds, so ONE
	 * table carries both tests and the emitted {@code _hash}/{@code _equal} pair is
	 * untouched.
	 *
	 * <p>
	 * Over the JVM backend's value representation: a Lisp string (a Java String with its
	 * framing quotes) and a character (an {@code int[]} of one code point) fold to upper
	 * case ONE CODE POINT AT A TIME, a finite float to the exact rational it equals (so
	 * {@code 1}, {@code 1.0} and {@code 2/2} are one key, and so are {@code 0.5} and
	 * {@code 1/2}), and a cons ({@code Object[2]}) folds element-wise. Everything else is
	 * its own representative -- a SYMBOL (a bare String, with no framing quotes) and a
	 * general ARRAY deliberately included.
	 *
	 * <p>
	 * The specification is {@code LispEquality.equalpKey}, which folds the same values in
	 * the interpreter's representation; the two are pinned against each other so an
	 * {@code equalp} table places the same keys on every backend
	 * ({@code .kb/hash-tables.md}).
	 * @param key the key as the caller wrote it
	 * @param depth how many levels are left to descend; at zero the value is its own fold
	 * @return the folded key
	 */
	public static Object equalpKey(Object key, int depth) {
		return equalpKey(key, depth, new int[] { FOLD_WORK_CAP });
	}

	// gas is the WHOLE fold's remaining node budget, a one-cell array because the
	// siblings have to share it: a per-branch count bounds nothing when the branches
	// share their substructure, and the paths through a shared graph are exponential in
	// its height. Sound for the same reason the depth cap is -- two keys equalp calls the
	// same have the same shape, so this walk visits them in the same order and runs out
	// in the same place, and their folds stay equal.
	private static Object equalpKey(Object key, int depth, int[] gas) {
		if (depth <= 0 || gas[0] <= 0 || key == null) {
			return key;
		}
		gas[0]--;
		if (key instanceof String text) {
			// A framed string folds its content; a SYMBOL (unframed) is its own key.
			return (text.length() >= 2 && text.charAt(0) == '"') ? upcase(text) : text;
		}
		if (key instanceof int[] character) {
			return character.length == 1 ? new int[] { Character.toUpperCase(character[0]) } : key;
		}
		if (key instanceof Double number) {
			return exactValue(number.doubleValue());
		}
		// The cons discrimination the emitted _hash makes: an Object[] that is neither a
		// ratio (a BigInteger[]) nor a function reference (an Integer funcId in slot 0)
		// nor an instance (its interned String[] layout in slot 0).
		if (key instanceof Object[] cell && !(key instanceof java.math.BigInteger[]) && !(key instanceof String[])
				&& cell.length == 2 && !(cell[0] instanceof Integer) && !(cell[0] instanceof String[])) {
			return new Object[] { equalpKey(cell[0], depth - 1, gas), equalpKey(cell[1], depth - 1, gas) };
		}
		return key;
	}

	// Upper case one code point at a time -- the mapping every backend's char-upcase
	// applies, which a whole-string toUpperCase would part company with wherever one
	// code point expands into several.
	private static String upcase(String text) {
		StringBuilder folded = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			int codePoint = text.codePointAt(i);
			folded.appendCodePoint(Character.toUpperCase(codePoint));
			i += Character.charCount(codePoint);
		}
		return folded.toString();
	}

	// The exact rational a double equals, in the shapes the emitted arithmetic produces:
	// an integer is a Long when it fits one and a BigInteger otherwise, a ratio a
	// BigInteger[] { numerator, denominator } in lowest terms. A finite double is exactly
	// mantissa * 2^exponent, which is where the value is read from -- no decimal detour,
	// exact at every magnitude. Stripping the mantissa's trailing zero bits against a
	// negative exponent leaves an odd numerator over a power of two, already in lowest
	// terms. A non-finite double has no rational value and is its own key.
	private static Object exactValue(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return Double.valueOf(value);
		}
		if (value == 0.0) {
			// Both zeros fold to the integer 0, which is what (= -0.0 0) answers.
			return Long.valueOf(0);
		}
		long bits = Double.doubleToLongBits(value);
		long fraction = bits & 0x000fffffffffffffL;
		int biasedExponent = (int) ((bits >> 52) & 0x7ff);
		long mantissa = (biasedExponent == 0) ? fraction : (fraction | 0x0010000000000000L);
		int exponent = (biasedExponent == 0) ? -1074 : biasedExponent - 1075;
		while (exponent < 0 && (mantissa & 1L) == 0L) {
			mantissa >>= 1;
			exponent++;
		}
		java.math.BigInteger numerator = java.math.BigInteger.valueOf(bits < 0 ? -mantissa : mantissa);
		if (exponent < 0) {
			return new java.math.BigInteger[] { numerator, java.math.BigInteger.ONE.shiftLeft(-exponent) };
		}
		java.math.BigInteger magnitude = numerator.shiftLeft(exponent);
		return magnitude.bitLength() < 64 ? (Object) Long.valueOf(magnitude.longValue()) : magnitude;
	}

}
