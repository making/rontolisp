package am.ik.rontolisp.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCircularLiteral;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispVal;

/**
 * The nodes of a literal datum that a build has to NAME: those reached by more than one
 * edge. A {@code #n=} reader label (CLHS 2.4.8.15) or a macro's computed constant shares
 * a node or closes a circle through it, and a build that walks the datum as a tree would
 * copy the one and never finish the other. Every circle runs through a node reached
 * twice, so a walk that stops at the named nodes ends. Both compile backends build a
 * datum with names in two steps, every named node empty and then every named node filled,
 * a named node inside a filling answering what the first step made. A node another
 * literal reaches too belongs to a unit of its own ({@link QuotedSharing}): the walk
 * stops there, and the build reads it from the unit.
 */
public final class QuotedGraph {

	private final Map<LispVal, Integer> names;

	private final List<LispVal> named;

	private final Map<LispVal, QuotedGraph> units;

	private QuotedGraph(Map<LispVal, Integer> names, List<LispVal> named, Map<LispVal, QuotedGraph> units) {
		this.names = names;
		this.named = named;
		this.units = units;
	}

	/**
	 * Finds the nodes of a datum reached more than once.
	 * @param datum the datum
	 * @param units the units of the program's shared nodes, by entry
	 * @return its graph, or {@code null} for a tree reaching no unit, which has no node
	 * to name
	 */
	static @Nullable QuotedGraph of(LispVal datum, Map<LispVal, QuotedGraph> units) {
		Map<LispVal, Integer> names = new IdentityHashMap<>();
		List<LispVal> named = new ArrayList<>();
		boolean reachesUnit = walk(List.of(datum), names, named, units);
		return (named.isEmpty() && !reachesUnit) ? null : new QuotedGraph(names, named, units);
	}

	/**
	 * Finds the nodes of a datum of a program with no node shared between literals.
	 * @param datum the datum
	 * @return its graph, or {@code null} for a tree, which has no node to name
	 */
	public static @Nullable QuotedGraph of(LispVal datum) {
		return of(datum, Map.of());
	}

	/**
	 * The graph of a unit of shared nodes: every entry has a name, however often it is
	 * reached.
	 * @param entries where the literals come in
	 * @return its graph
	 */
	static QuotedGraph ofUnit(List<LispVal> entries) {
		Map<LispVal, Integer> names = new IdentityHashMap<>();
		List<LispVal> named = new ArrayList<>(entries);
		for (int i = 0; i < named.size(); i++) {
			names.put(named.get(i), i);
		}
		walk(entries, names, named, Map.of());
		return new QuotedGraph(names, named, Map.of());
	}

	/** Names what the starts reach twice, and answers whether they reach a unit. */
	private static boolean walk(List<LispVal> starts, Map<LispVal, Integer> names, List<LispVal> named,
			Map<LispVal, QuotedGraph> units) {
		Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<LispVal> pending = new ArrayDeque<>();
		for (int i = starts.size() - 1; i >= 0; i--) {
			pending.push(starts.get(i));
		}
		boolean reachesUnit = false;
		while (!pending.isEmpty()) {
			LispVal node = LispCircularLiteral.unwrapped(pending.pop());
			if (!isAggregate(node)) {
				continue;
			}
			// The datum being built is built, a unit's entry or not: an array in code
			// position is a constructor.
			if (node != starts.getFirst() && units.containsKey(node)) {
				reachesUnit = true;
				continue;
			}
			if (!seen.add(node)) {
				if (names.putIfAbsent(node, named.size()) == null) {
					named.add(node);
				}
				continue;
			}
			pushChildren(node, pending);
		}
		return reachesUnit;
	}

	/**
	 * Pushes what a node points at, its first child on top.
	 * @param node the node
	 * @param pending the stack
	 */
	static void pushChildren(LispVal node, Deque<LispVal> pending) {
		switch (node) {
			case LispCons cell -> {
				pending.push(cell.cdr());
				pending.push(cell.car());
			}
			case LispArray array -> {
				LispVal[] data = array.data();
				for (int i = data.length - 1; i >= 0; i--) {
					if (data[i] != null) {
						pending.push(data[i]);
					}
				}
			}
			case LispInstance instance -> {
				for (int i = instance.slotCount() - 1; i >= 0; i--) {
					pending.push(instance.slot(i));
				}
			}
			default -> {
			}
		}
	}

	/**
	 * The values with an identity a program can observe. An atom has none, or the
	 * backends' literals answer one object for it already.
	 */
	static boolean isAggregate(LispVal value) {
		return value instanceof LispCons || value instanceof LispArray || value instanceof LispInstance
				|| value instanceof LispFloatArray || value instanceof LispIntVector;
	}

	/**
	 * {@return the nodes to name, in a fixed order}
	 */
	public List<LispVal> named() {
		return this.named;
	}

	/**
	 * The position of a node in {@link #named()}.
	 * @param node the value
	 * @return its position, -1 when it has no name
	 */
	public int nameOf(LispVal node) {
		Integer name = this.names.get(node);
		return (name != null) ? name : -1;
	}

	/**
	 * The unit a build of this graph reads a node from.
	 * @param node the value
	 * @return the unit, {@code null} when the node is this graph's own
	 */
	public @Nullable QuotedGraph unitOf(LispVal node) {
		return this.units.get(node);
	}

	/**
	 * Whether a build of this graph reads a node instead of walking on through it.
	 * @param node the value
	 * @return whether the node has a name here or lives in a unit
	 */
	public boolean stopsAt(LispVal node) {
		return nameOf(node) >= 0 || unitOf(node) != null;
	}

}
