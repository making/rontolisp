package am.ik.maven;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Maven's version selection over a collected graph: Maven Resolver's
 * {@code ConflictResolver} as Maven's session configures it -- {@code ConflictMarker}
 * groups the nodes that are one artifact (relocations join a group),
 * {@code ConflictIdSorter} orders the groups parents first, and each group keeps one
 * node: the nearest ({@code NearestVersionSelector}; between two children of one parent,
 * the higher version), its scope chosen by {@code JavaScopeSelector} over the scopes
 * {@code JavaScopeDeriver} derives along every path, its optional flag by
 * {@code SimpleOptionalitySelector}. Every other node of the group is removed from the
 * child list it sits in, which removes it under every parent sharing that list.
 *
 * <p>
 * A port, not a re-derivation: the traversal orders, the hash-ordered collections and the
 * order-dependent loser removal are Maven Resolver 1.9's, so a tie breaks the way Maven
 * breaks it. Version ranges never reach here (the collector refuses them), so the
 * selector's range constraints and backtracking are absent.
 */
final class ConflictResolver {

	private ConflictResolver() {
	}

	/**
	 * Resolves a collected graph.
	 * @param collected the graph {@link DependencyCollector} answered
	 * @return the graph with one node per artifact, each with its selected scope and
	 * optional flag
	 */
	static DependencyGraph resolve(DependencyGraph collected) {
		Node root = Node.of(collected);
		Map<Node, Integer> conflictIds = mark(root);
		Sorted sorted = sort(root, conflictIds);
		new Resolution(root, conflictIds, sorted).run();
		return new DependencyGraph(Node.toRecords(root), collected.warnings());
	}

	/** A mutable node, as Maven Resolver's {@code DefaultDependencyNode}. */
	static final class Node {

		final @Nullable DependencyNode source;

		@Nullable Dependency dependency;

		List<Node> children = new ArrayList<>();

		private Node(@Nullable DependencyNode source) {
			this.source = source;
			this.dependency = source == null ? null : source.dependency();
		}

		/**
		 * The graph as Maven holds it: a child list the collector pooled is one list
		 * under every node it reached, and a cycle node shares its ancestor's list.
		 */
		static Node of(DependencyGraph graph) {
			Node root = new Node(null);
			Map<DependencyNode, Node> nodes = new IdentityHashMap<>();
			Map<List<DependencyNode>, List<Node>> lists = new IdentityHashMap<>();
			List<Node> path = new ArrayList<>();
			path.add(root);
			for (DependencyNode child : graph.roots()) {
				root.children.add(convert(child, nodes, lists, path));
			}
			return root;
		}

		private static Node convert(DependencyNode source, Map<DependencyNode, Node> nodes,
				Map<List<DependencyNode>, List<Node>> lists, List<Node> path) {
			Node existing = nodes.get(source);
			if (existing != null) {
				return existing;
			}
			Node node = new Node(source);
			nodes.put(source, node);
			if (source.cycle()) {
				node.children = ancestor(path, source.artifact()).children;
			}
			else if (!source.children().isEmpty()) {
				List<Node> shared = lists.get(source.children());
				if (shared == null) {
					shared = new ArrayList<>();
					lists.put(source.children(), shared);
					node.children = shared;
					path.add(node);
					for (DependencyNode child : source.children()) {
						shared.add(convert(child, nodes, lists, path));
					}
					path.remove(path.size() - 1);
				}
				node.children = shared;
			}
			return node;
		}

		// The nearest node of the path with the artifact's identity, as the collector's
		// cycle search finds it.
		private static Node ancestor(List<Node> path, Artifact artifact) {
			String key = artifact.versionlessKey();
			for (int i = path.size() - 1; i > 0; i--) {
				Node candidate = path.get(i);
				if (candidate.artifact().versionlessKey().equals(key)) {
					return candidate;
				}
			}
			throw new IllegalStateException("a cycle node with no ancestor: " + artifact);
		}

		static List<DependencyNode> toRecords(Node root) {
			Map<Node, DependencyNode> done = new IdentityHashMap<>();
			Map<List<Node>, List<DependencyNode>> lists = new IdentityHashMap<>();
			List<DependencyNode> roots = new ArrayList<>();
			for (Node child : root.children) {
				roots.add(child.toRecord(done, lists));
			}
			return roots;
		}

		private DependencyNode toRecord(Map<Node, DependencyNode> done, Map<List<Node>, List<DependencyNode>> lists) {
			DependencyNode existing = done.get(this);
			if (existing != null) {
				return existing;
			}
			DependencyNode source = Objects.requireNonNull(this.source);
			List<DependencyNode> records = List.of();
			if (!source.cycle() && !this.children.isEmpty()) {
				records = lists.get(this.children);
				if (records == null) {
					List<DependencyNode> built = new ArrayList<>();
					for (Node child : this.children) {
						built.add(child.toRecord(done, lists));
					}
					records = List.copyOf(built);
					lists.put(this.children, records);
				}
			}
			DependencyNode record = new DependencyNode(Objects.requireNonNull(this.dependency), source.relocations(),
					source.premanagedVersion(), source.premanagedScope(), source.premanagedOptional(), source.cycle(),
					records);
			done.put(this, record);
			return record;
		}

		Artifact artifact() {
			return Objects.requireNonNull(this.dependency).artifact();
		}

		boolean managedScope() {
			return this.source != null && this.source.premanagedScope() != null;
		}

		boolean managedOptional() {
			return this.source != null && this.source.premanagedOptional() != null;
		}

		void setScope(String scope) {
			Dependency d = Objects.requireNonNull(this.dependency);
			this.dependency = new Dependency(d.artifact(), d.type(), scope, d.optional(), d.exclusions());
		}

		void setOptional(boolean optional) {
			Dependency d = Objects.requireNonNull(this.dependency);
			this.dependency = new Dependency(d.artifact(), d.type(), d.scope(), optional, d.exclusions());
		}

	}

	// ConflictMarker: the conflict id of every node.

	/** {@code ConflictMarker.Key}: groupId, artifactId, extension, classifier. */
	private record Key(String groupId, String artifactId, String classifier, String extension) {

		static Key of(Artifact artifact) {
			return new Key(artifact.groupId(), artifact.artifactId(), artifact.classifier(), artifact.extension());
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof Key that && this.artifactId.equals(that.artifactId)
					&& this.groupId.equals(that.groupId) && this.extension.equals(that.extension)
					&& this.classifier.equals(that.classifier);
		}

		@Override
		public int hashCode() {
			int hash = 17;
			hash = hash * 31 + this.artifactId.hashCode();
			hash = hash * 31 + this.groupId.hashCode();
			hash = hash * 31 + this.classifier.hashCode();
			hash = hash * 31 + this.extension.hashCode();
			return hash;
		}

	}

	private record ConflictGroup(Set<Key> keys, int index) {
	}

	private static Map<Node, Integer> mark(Node root) {
		Map<Node, Boolean> nodes = new IdentityHashMap<>(1024);
		Map<Key, ConflictGroup> groups = new HashMap<>(1024);
		analyze(root, nodes, groups, new int[] { 0 });
		Map<Node, Integer> conflictIds = new IdentityHashMap<>(nodes.size() + 1);
		for (Node node : nodes.keySet()) {
			if (node.dependency != null) {
				conflictIds.put(node, Objects.requireNonNull(groups.get(Key.of(node.artifact()))).index());
			}
		}
		return conflictIds;
	}

	private static void analyze(Node node, Map<Node, Boolean> nodes, Map<Key, ConflictGroup> groups, int[] counter) {
		if (nodes.put(node, Boolean.TRUE) != null) {
			return;
		}
		Set<Key> keys = keysOf(node);
		if (!keys.isEmpty()) {
			ConflictGroup group = null;
			boolean fixMappings = false;
			for (Key key : keys) {
				ConflictGroup g = groups.get(key);
				if (group != g) {
					if (group == null) {
						Set<Key> newKeys = merge(Objects.requireNonNull(g).keys(), keys);
						if (newKeys == g.keys()) {
							group = g;
							break;
						}
						group = new ConflictGroup(newKeys, counter[0]++);
						fixMappings = true;
					}
					else if (g == null) {
						fixMappings = true;
					}
					else {
						Set<Key> newKeys = merge(g.keys(), group.keys());
						if (newKeys == g.keys()) {
							group = g;
							fixMappings = false;
							break;
						}
						else if (newKeys != group.keys()) {
							group = new ConflictGroup(newKeys, counter[0]++);
							fixMappings = true;
						}
					}
				}
			}
			if (group == null) {
				group = new ConflictGroup(keys, counter[0]++);
				fixMappings = true;
			}
			if (fixMappings) {
				for (Key key : group.keys()) {
					groups.put(key, group);
				}
			}
		}
		for (Node child : node.children) {
			analyze(child, nodes, groups, counter);
		}
	}

	private static Set<Key> merge(Set<Key> keys1, Set<Key> keys2) {
		if (keys1.size() < keys2.size()) {
			if (keys2.containsAll(keys1)) {
				return keys2;
			}
		}
		else if (keys1.containsAll(keys2)) {
			return keys1;
		}
		Set<Key> keys = new HashSet<>();
		keys.addAll(keys1);
		keys.addAll(keys2);
		return keys;
	}

	private static Set<Key> keysOf(Node node) {
		if (node.dependency == null) {
			return Set.of();
		}
		Key key = Key.of(node.artifact());
		List<Artifact> relocations = Objects.requireNonNull(node.source).relocations();
		if (relocations.isEmpty()) {
			return Set.of(key);
		}
		Set<Key> keys = new HashSet<>();
		keys.add(key);
		for (Artifact relocation : relocations) {
			keys.add(Key.of(relocation));
		}
		return keys;
	}

	// ConflictIdSorter: the conflict ids parents first, and the cycles among them.

	private record Sorted(List<Integer> ids, Collection<Collection<Integer>> cycles) {
	}

	private static final class ConflictId {

		final Integer key;

		Collection<ConflictId> children = Set.of();

		int inDegree;

		int minDepth;

		ConflictId(Integer key, int depth) {
			this.key = key;
			this.minDepth = depth;
		}

		void add(ConflictId child) {
			if (this.children.isEmpty()) {
				this.children = new HashSet<>();
			}
			if (this.children.add(child)) {
				child.inDegree++;
			}
		}

		void pullup(int depth) {
			if (depth < this.minDepth) {
				this.minDepth = depth;
				int next = depth + 1;
				for (ConflictId child : this.children) {
					child.pullup(next);
				}
			}
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof ConflictId that && this.key.equals(that.key);
		}

		@Override
		public int hashCode() {
			return this.key.hashCode();
		}

	}

	private static Sorted sort(Node root, Map<Node, Integer> conflictIds) {
		Map<Integer, ConflictId> ids = new LinkedHashMap<>(256);
		buildDag(ids, root, null, 0, new IdentityHashMap<>(conflictIds.size()), conflictIds);
		List<Integer> sorted = new ArrayList<>(ids.size());
		RootQueue roots = new RootQueue(ids.size() / 2);
		for (ConflictId id : ids.values()) {
			if (id.inDegree <= 0) {
				roots.add(id);
			}
		}
		processRoots(sorted, roots);
		boolean cycle = sorted.size() < ids.size();
		while (sorted.size() < ids.size()) {
			// cyclic: break the cycle at the nearest id (fewest predecessors on a tie)
			ConflictId nearest = null;
			for (ConflictId id : ids.values()) {
				if (id.inDegree <= 0) {
					continue;
				}
				if (nearest == null || id.minDepth < nearest.minDepth
						|| (id.minDepth == nearest.minDepth && id.inDegree < nearest.inDegree)) {
					nearest = id;
				}
			}
			Objects.requireNonNull(nearest).inDegree = 0;
			roots.add(nearest);
			processRoots(sorted, roots);
		}
		Collection<Collection<Integer>> cycles = cycle ? findCycles(ids.values()) : Set.of();
		return new Sorted(sorted, cycles);
	}

	private static void buildDag(Map<Integer, ConflictId> ids, Node node, @Nullable ConflictId id, int depth,
			Map<Node, Boolean> visited, Map<Node, Integer> conflictIds) {
		if (visited.put(node, Boolean.TRUE) != null) {
			return;
		}
		int childDepth = depth + 1;
		for (Node child : node.children) {
			Integer key = Objects.requireNonNull(conflictIds.get(child));
			ConflictId childId = ids.get(key);
			if (childId == null) {
				childId = new ConflictId(key, childDepth);
				ids.put(key, childId);
			}
			else {
				childId.pullup(childDepth);
			}
			if (id != null) {
				id.add(childId);
			}
			buildDag(ids, child, childId, childDepth, visited, conflictIds);
		}
	}

	private static void processRoots(List<Integer> sorted, RootQueue roots) {
		while (!roots.isEmpty()) {
			ConflictId root = roots.remove();
			sorted.add(root.key);
			for (ConflictId child : root.children) {
				child.inDegree--;
				if (child.inDegree == 0) {
					roots.add(child);
				}
			}
		}
	}

	private static Collection<Collection<Integer>> findCycles(Collection<ConflictId> conflictIds) {
		Collection<Collection<Integer>> cycles = new HashSet<>();
		Map<Integer, Integer> stack = new HashMap<>(128);
		Map<ConflictId, Boolean> visited = new IdentityHashMap<>(conflictIds.size());
		for (ConflictId id : conflictIds) {
			findCycles(id, visited, stack, cycles);
		}
		return cycles;
	}

	private static void findCycles(ConflictId id, Map<ConflictId, Boolean> visited, Map<Integer, Integer> stack,
			Collection<Collection<Integer>> cycles) {
		Integer depth = stack.put(id.key, stack.size());
		if (depth != null) {
			stack.put(id.key, depth);
			Collection<Integer> cycle = new HashSet<>();
			for (Map.Entry<Integer, Integer> entry : stack.entrySet()) {
				if (entry.getValue() >= depth) {
					cycle.add(entry.getKey());
				}
			}
			cycles.add(cycle);
		}
		else {
			if (visited.put(id, Boolean.TRUE) == null) {
				for (ConflictId childId : id.children) {
					findCycles(childId, visited, stack, cycles);
				}
			}
			stack.remove(id.key);
		}
	}

	/** {@code ConflictIdSorter.RootQueue}: a queue kept sorted by depth on insertion. */
	private static final class RootQueue {

		private int nextOut;

		private int nextIn;

		private ConflictId[] ids;

		RootQueue(int capacity) {
			this.ids = new ConflictId[capacity + 16];
		}

		boolean isEmpty() {
			return this.nextOut >= this.nextIn;
		}

		void add(ConflictId id) {
			if (this.nextOut >= this.nextIn && this.nextOut > 0) {
				this.nextIn -= this.nextOut;
				this.nextOut = 0;
			}
			if (this.nextIn >= this.ids.length) {
				ConflictId[] grown = new ConflictId[this.ids.length + this.ids.length / 2 + 16];
				System.arraycopy(this.ids, this.nextOut, grown, 0, this.nextIn - this.nextOut);
				this.ids = grown;
				this.nextIn -= this.nextOut;
				this.nextOut = 0;
			}
			int i;
			for (i = this.nextIn - 1; i >= this.nextOut && id.minDepth < this.ids[i].minDepth; i--) {
				this.ids[i + 1] = this.ids[i];
			}
			this.ids[i + 1] = id;
			this.nextIn++;
		}

		ConflictId remove() {
			return this.ids[this.nextOut++];
		}

	}

	// ConflictResolver itself.

	/** One occurrence of a group's node under one parent. */
	private static final class Item {

		final @Nullable List<Node> parent;

		final Node node;

		int depth;

		final Set<String> scopes = new HashSet<>();

		int optionalities;

		static final int OPTIONAL_FALSE = 0x01;

		static final int OPTIONAL_TRUE = 0x02;

		Item(@Nullable Node parent, Node node, String scope, boolean optional) {
			this.parent = parent == null ? null : parent.children;
			this.node = node;
			this.scopes.add(scope);
			this.optionalities = optional ? OPTIONAL_TRUE : OPTIONAL_FALSE;
		}

		boolean isSibling(Item item) {
			return this.parent == item.parent;
		}

		Dependency dependency() {
			return Objects.requireNonNull(this.node.dependency);
		}

	}

	/** What one walk learned of a parent (by its child list). */
	private static final class NodeInfo {

		static final int CHANGE_SCOPE = 0x01;

		static final int CHANGE_OPTIONAL = 0x02;

		private static final int OPT_FALSE = 0x01;

		private static final int OPT_TRUE = 0x02;

		int minDepth;

		final Set<@Nullable String> derivedScopes = new HashSet<>();

		int derivedOptionalities;

		@Nullable List<Item> children;

		NodeInfo(int depth, @Nullable String derivedScope, boolean optional) {
			this.minDepth = depth;
			this.derivedScopes.add(derivedScope);
			this.derivedOptionalities = optional ? OPT_TRUE : OPT_FALSE;
		}

		int update(int depth, @Nullable String derivedScope, boolean optional) {
			if (depth < this.minDepth) {
				this.minDepth = depth;
			}
			int changes = this.derivedScopes.add(derivedScope) ? CHANGE_SCOPE : 0;
			int bit = optional ? OPT_TRUE : OPT_FALSE;
			if ((this.derivedOptionalities & bit) == 0) {
				this.derivedOptionalities |= bit;
				changes |= CHANGE_OPTIONAL;
			}
			return changes;
		}

		void add(Item item) {
			if (this.children == null) {
				this.children = new ArrayList<>(1);
			}
			this.children.add(item);
		}

	}

	private static final class Resolution {

		private final Node root;

		private final Map<Node, Integer> conflictIds;

		private final Sorted sorted;

		private Object currentId = new Object();

		private final Set<Object> potentialAncestorIds = new HashSet<>();

		private final Map<Object, Node> resolvedIds = new HashMap<>();

		private final List<Item> items = new ArrayList<>(256);

		private final Map<List<Node>, NodeInfo> infos = new IdentityHashMap<>(64);

		private final Map<List<Node>, Boolean> stack = new IdentityHashMap<>(64);

		private final List<Node> parentNodes = new ArrayList<>(64);

		private final List<@Nullable String> parentScopes = new ArrayList<>(64);

		private final List<Boolean> parentOptionals = new ArrayList<>(64);

		private final List<@Nullable NodeInfo> parentInfos = new ArrayList<>(64);

		private @Nullable Item winner;

		Resolution(Node root, Map<Node, Integer> conflictIds, Sorted sorted) {
			this.root = root;
			this.conflictIds = conflictIds;
			this.sorted = sorted;
		}

		void run() {
			Map<Object, Collection<Object>> cyclicPredecessors = new HashMap<>();
			for (Collection<Integer> cycle : this.sorted.cycles()) {
				for (Integer conflictId : cycle) {
					cyclicPredecessors.computeIfAbsent(conflictId, k -> new HashSet<>()).addAll(cycle);
				}
			}
			for (Iterator<Integer> it = this.sorted.ids().iterator(); it.hasNext();) {
				Integer conflictId = it.next();
				prepare(conflictId, cyclicPredecessors.get(conflictId));
				gatherConflictItems(this.root);
				finish();
				if (!this.items.isEmpty()) {
					Item selected = selectVersion();
					this.winner = selected;
					selected.node.setScope(selectScope(selected));
					selected.node.setOptional(selectOptionality());
					removeLosers(selected);
				}
				this.resolvedIds.put(this.currentId, this.winner == null ? null : this.winner.node);
				// with cycles, a last walk below the final winner removes leftover losers
				if (!it.hasNext() && !this.sorted.cycles().isEmpty() && this.winner != null) {
					Node last = this.winner.node;
					prepare(new Object(), null);
					gatherConflictItems(last);
				}
			}
		}

		private void prepare(Object conflictId, @Nullable Collection<Object> cyclicPredecessors) {
			this.currentId = conflictId;
			this.winner = null;
			this.items.clear();
			this.infos.clear();
			if (cyclicPredecessors != null) {
				this.potentialAncestorIds.addAll(cyclicPredecessors);
			}
		}

		private void finish() {
			List<Node> previousParent = null;
			int previousDepth = 0;
			for (ListIterator<Item> iterator = this.items.listIterator(this.items.size()); iterator.hasPrevious();) {
				Item item = iterator.previous();
				if (item.parent == previousParent) {
					item.depth = previousDepth;
				}
				else if (item.parent != null) {
					previousParent = item.parent;
					previousDepth = Objects.requireNonNull(this.infos.get(previousParent)).minDepth + 1;
					item.depth = previousDepth;
				}
			}
			this.potentialAncestorIds.add(this.currentId);
		}

		private boolean gatherConflictItems(Node node) {
			Integer conflictId = this.conflictIds.get(node);
			if (this.currentId.equals(conflictId)) {
				add(node);
			}
			else if (loser(node, conflictId)) {
				return false;
			}
			else if (push(node, conflictId)) {
				for (Iterator<Node> it = node.children.iterator(); it.hasNext();) {
					Node child = it.next();
					if (!gatherConflictItems(child)) {
						it.remove();
					}
				}
				pop();
			}
			return true;
		}

		private boolean loser(Node node, @Nullable Integer conflictId) {
			Node won = this.resolvedIds.get(conflictId);
			return won != null && won != node;
		}

		private boolean push(Node node, @Nullable Integer conflictId) {
			if (conflictId == null) {
				if (node.dependency != null) {
					throw new IllegalStateException("missing conflict id for node " + node.dependency);
				}
			}
			else if (!this.potentialAncestorIds.contains(conflictId)) {
				return false;
			}
			List<Node> graphNode = node.children;
			if (this.stack.put(graphNode, Boolean.TRUE) != null) {
				return false;
			}
			int depth = this.parentNodes.size();
			String scope = deriveScope(node, conflictId);
			boolean optional = deriveOptional(node, conflictId);
			NodeInfo info = this.infos.get(graphNode);
			if (info == null) {
				info = new NodeInfo(depth, scope, optional);
				this.infos.put(graphNode, info);
				this.parentInfos.add(info);
			}
			else {
				int changes = info.update(depth, scope, optional);
				if (changes == 0) {
					this.stack.remove(graphNode);
					return false;
				}
				// no new items below this parent: the existing ones are updated
				this.parentInfos.add(null);
				this.parentNodes.add(node);
				this.parentScopes.add(scope);
				this.parentOptionals.add(optional);
				List<Item> existing = info.children;
				if (existing != null) {
					if ((changes & NodeInfo.CHANGE_SCOPE) != 0) {
						for (ListIterator<Item> it = existing.listIterator(existing.size()); it.hasPrevious();) {
							Item item = it.previous();
							item.scopes.add(deriveScope(item.node, null));
						}
					}
					if ((changes & NodeInfo.CHANGE_OPTIONAL) != 0) {
						for (ListIterator<Item> it = existing.listIterator(existing.size()); it.hasPrevious();) {
							Item item = it.previous();
							item.optionalities |= deriveOptional(item.node, null) ? Item.OPTIONAL_TRUE
									: Item.OPTIONAL_FALSE;
						}
					}
				}
				return true;
			}
			this.parentNodes.add(node);
			this.parentScopes.add(scope);
			this.parentOptionals.add(optional);
			return true;
		}

		private void pop() {
			int last = this.parentInfos.size() - 1;
			this.parentInfos.remove(last);
			this.parentScopes.remove(last);
			this.parentOptionals.remove(last);
			Node node = this.parentNodes.remove(last);
			this.stack.remove(node.children);
		}

		private void add(Node node) {
			Node parent = this.parentNodes.isEmpty() ? null : this.parentNodes.get(this.parentNodes.size() - 1);
			if (parent == null) {
				this.items.add(newItem(null, node));
				return;
			}
			NodeInfo info = this.parentInfos.get(this.parentInfos.size() - 1);
			if (info != null) {
				Item item = newItem(parent, node);
				info.add(item);
				this.items.add(item);
			}
		}

		private Item newItem(@Nullable Node parent, Node node) {
			return new Item(parent, node, Objects.requireNonNullElse(deriveScope(node, null), ""),
					deriveOptional(node, null));
		}

		// JavaScopeDeriver along the walked path, unless the scope is settled.
		private @Nullable String deriveScope(Node node, @Nullable Integer conflictId) {
			if (node.managedScope() || (conflictId != null && this.resolvedIds.containsKey(conflictId))) {
				return scopeOf(node);
			}
			int depth = this.parentNodes.size();
			String child = scopeOf(node);
			if (depth == 0) {
				return child;
			}
			return derive(this.parentScopes.get(depth - 1), child);
		}

		private static @Nullable String scopeOf(Node node) {
			return node.dependency == null ? null : node.dependency.scope();
		}

		private static String derive(@Nullable String parentScope, @Nullable String childScope) {
			String derived;
			if ("system".equals(childScope) || "test".equals(childScope)) {
				derived = childScope;
			}
			else if (parentScope == null || parentScope.isEmpty() || "compile".equals(parentScope)) {
				derived = childScope;
			}
			else if ("test".equals(parentScope) || "runtime".equals(parentScope)) {
				derived = parentScope;
			}
			else if ("system".equals(parentScope) || "provided".equals(parentScope)) {
				derived = "provided";
			}
			else {
				derived = "runtime";
			}
			return derived == null ? "" : derived;
		}

		private boolean deriveOptional(Node node, @Nullable Integer conflictId) {
			boolean optional = node.dependency != null && node.dependency.isOptional();
			if (optional || node.managedOptional()
					|| (conflictId != null && this.resolvedIds.containsKey(conflictId))) {
				return optional;
			}
			int depth = this.parentNodes.size();
			return depth > 0 && this.parentOptionals.get(depth - 1);
		}

		// NearestVersionSelector without version ranges: the shallowest occurrence, or
		// between two children of one parent the higher version; the first on a tie.
		private Item selectVersion() {
			Item selected = null;
			for (Item item : this.items) {
				if (selected == null || isNearer(item, selected)) {
					selected = item;
				}
			}
			return Objects.requireNonNull(selected);
		}

		private static boolean isNearer(Item item1, Item item2) {
			if (item1.isSibling(item2)) {
				return GenericVersion.parse(item1.dependency().artifact().version())
					.compareTo(GenericVersion.parse(item2.dependency().artifact().version())) > 0;
			}
			return item1.depth < item2.depth;
		}

		// JavaScopeSelector.
		private String selectScope(Item selected) {
			String scope = selected.dependency().scope();
			if (scope.equals("system")) {
				return scope;
			}
			Set<String> scopes = new HashSet<>();
			for (Item item : this.items) {
				if (item.depth <= 1) {
					return item.dependency().scope();
				}
				scopes.addAll(item.scopes);
			}
			if (scopes.size() > 1) {
				scopes.remove("system");
			}
			if (scopes.size() == 1) {
				return scopes.iterator().next();
			}
			for (String preferred : List.of("compile", "runtime", "provided", "test")) {
				if (scopes.contains(preferred)) {
					return preferred;
				}
			}
			return "";
		}

		// SimpleOptionalitySelector.
		private boolean selectOptionality() {
			boolean optional = true;
			for (Item item : this.items) {
				if (item.depth <= 1) {
					return item.dependency().isOptional();
				}
				if ((item.optionalities & Item.OPTIONAL_FALSE) != 0) {
					optional = false;
				}
			}
			return optional;
		}

		// Each loser leaves the child list it sits in; the scan of one list resumes
		// where the previous loser of that list was found, as Maven's does.
		private void removeLosers(Item selected) {
			List<Node> previousParent = null;
			ListIterator<Node> childIt = null;
			for (Item item : this.items) {
				if (item == selected || item.parent == null) {
					continue;
				}
				if (item.parent != previousParent) {
					childIt = item.parent.listIterator();
					previousParent = item.parent;
				}
				ListIterator<Node> children = Objects.requireNonNull(childIt);
				while (children.hasNext()) {
					if (children.next() == item.node) {
						children.remove();
						break;
					}
				}
			}
		}

	}

}
