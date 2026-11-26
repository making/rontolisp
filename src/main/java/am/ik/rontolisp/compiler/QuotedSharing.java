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
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The nodes of a program's literal data that more than one literal reaches: a {@code #n=}
 * label spelled in two quotes, or a macro's constant spliced into two (CLHS 3.2.4.4 keeps
 * them one object through {@code compile-file}). What such a node reaches is shared as
 * well, so the shared nodes are closed downward, and a literal is the nodes only it
 * reaches on top of them.
 * <p>
 * A connected set of shared nodes is a UNIT, whose graph ({@link QuotedGraph#ofUnit})
 * names the entries the literals come in through. It is built once, whichever site runs
 * first, behind one lazy slot holding the array of its named nodes, so a circle between
 * two sites closes and a racing first build publishes the unit whole. Every other node
 * stays with its literal, and a program with no shared node has no sharing at all.
 */
public final class QuotedSharing {

	private final Map<LispVal, QuotedGraph> units;

	private QuotedSharing(Map<LispVal, QuotedGraph> units) {
		this.units = units;
	}

	/**
	 * Finds the nodes more than one literal of a program reaches. To be called once the
	 * program's forms are final: a site made after that builds what it holds on its own,
	 * except the entries.
	 * @param program the top-level forms
	 * @return the program's sharing, {@code null} when no node is reached from two
	 * literals
	 */
	public static @Nullable QuotedSharing of(List<LispVal> program) {
		List<LispVal> heldByConstructors = new ArrayList<>();
		List<LispVal> literals = literals(program, heldByConstructors);
		List<LispVal> reachedTwice = reachedFromTwoLiterals(literals);
		reachedTwice.addAll(heldByConstructors);
		if (reachedTwice.isEmpty()) {
			return null;
		}
		List<LispVal> sharedNodes = new ArrayList<>();
		Set<LispVal> shared = closedDownward(reachedTwice, sharedNodes);
		Map<LispVal, LispVal> parents = new IdentityHashMap<>();
		for (LispVal node : sharedNodes) {
			Deque<LispVal> children = new ArrayDeque<>();
			QuotedGraph.pushChildren(node, children);
			for (LispVal child : children) {
				LispVal target = LispCircularLiteral.unwrapped(child);
				if (shared.contains(target)) {
					LispVal from = find(parents, node);
					LispVal to = find(parents, target);
					if (from != to) {
						parents.put(to, from);
					}
				}
			}
		}
		// In program order: the emitted bytes may not depend on an identity hash.
		Map<LispVal, List<LispVal>> entriesByUnit = new IdentityHashMap<>();
		List<List<LispVal>> unitEntries = new ArrayList<>();
		for (LispVal entry : entries(literals, shared)) {
			entriesByUnit.computeIfAbsent(find(parents, entry), ignored -> {
				List<LispVal> entries = new ArrayList<>();
				unitEntries.add(entries);
				return entries;
			}).add(entry);
		}
		Map<LispVal, QuotedGraph> units = new IdentityHashMap<>();
		for (List<LispVal> entries : unitEntries) {
			QuotedGraph unit = QuotedGraph.ofUnit(entries);
			entries.forEach(entry -> units.put(entry, unit));
		}
		return new QuotedSharing(units);
	}

	/**
	 * Finds the nodes of a datum of this program a build has to name.
	 * @param datum the datum
	 * @return its graph, or {@code null} for a tree reaching no unit, which has no node
	 * to name
	 */
	public @Nullable QuotedGraph graphOf(LispVal datum) {
		return QuotedGraph.of(datum, this.units);
	}

	/**
	 * The unit a node is an entry of.
	 * @param node the value
	 * @return its unit, {@code null} when it is not an entry
	 */
	public @Nullable QuotedGraph unitOf(LispVal node) {
		return this.units.get(node);
	}

	/**
	 * Every distinct literal of the forms, in program order: what a {@code quote} holds,
	 * and an instance or a circle in code position. The walk never reads a quote's datum
	 * as code, and it takes anything spelled like a quote for one: a variable named
	 * {@code quote} costs at most a unit nothing observes.
	 * <p>
	 * An array in code position is a constructor (.kb/array-literals.md), fresh at every
	 * evaluation along with the arrays in it, so it has no identity to share. What it
	 * holds does, and has to outlive an evaluation, so each such element is an entry: a
	 * list of them stands for the array as a literal, and they land in
	 * {@code heldByConstructors}.
	 */
	private static List<LispVal> literals(List<LispVal> program, List<LispVal> heldByConstructors) {
		Set<LispVal> seenCode = identitySet();
		Set<LispVal> seenLiterals = identitySet();
		List<LispVal> literals = new ArrayList<>();
		Deque<LispVal> pending = new ArrayDeque<>();
		for (int i = program.size() - 1; i >= 0; i--) {
			pending.push(program.get(i));
		}
		while (!pending.isEmpty()) {
			LispVal node = pending.pop();
			LispVal literal = null;
			if (node instanceof LispCons cell) {
				if (!seenCode.add(cell)) {
					continue;
				}
				if (cell.car() instanceof LispSymbol op && LispNames.QUOTE.equals(op.name())
						&& cell.cdr() instanceof LispCons held) {
					literal = LispCircularLiteral.unwrapped(held.car());
				}
				else {
					pending.push(cell.cdr());
					pending.push(cell.car());
				}
			}
			else if (node instanceof LispCircularLiteral || node instanceof LispInstance || node instanceof LispArray) {
				LispVal held = LispCircularLiteral.unwrapped(node);
				literal = (held instanceof LispArray array) ? constructedElements(array, heldByConstructors) : held;
			}
			if (literal != null && QuotedGraph.isAggregate(literal) && seenLiterals.add(literal)) {
				literals.add(literal);
			}
		}
		return literals;
	}

	/** A list of what an array in code position holds, the arrays in it opened too. */
	private static LispVal constructedElements(LispArray array, List<LispVal> out) {
		Set<LispVal> seen = identitySet();
		LispVal elements = LispNil.INSTANCE;
		Deque<LispVal> pending = new ArrayDeque<>();
		pending.push(array);
		while (!pending.isEmpty()) {
			LispVal node = LispCircularLiteral.unwrapped(pending.pop());
			if (!(node instanceof LispArray opened)) {
				if (QuotedGraph.isAggregate(node)) {
					elements = new LispCons(node, elements);
					out.add(node);
				}
			}
			else if (seen.add(opened)) {
				QuotedGraph.pushChildren(opened, pending);
			}
		}
		return elements;
	}

	/** The nodes a literal's walk finds an earlier literal has reached. */
	private static List<LispVal> reachedFromTwoLiterals(List<LispVal> literals) {
		Map<LispVal, LispVal> owners = new IdentityHashMap<>();
		List<LispVal> reachedTwice = new ArrayList<>();
		Deque<LispVal> pending = new ArrayDeque<>();
		for (LispVal literal : literals) {
			pending.push(literal);
			while (!pending.isEmpty()) {
				LispVal node = LispCircularLiteral.unwrapped(pending.pop());
				if (!QuotedGraph.isAggregate(node)) {
					continue;
				}
				LispVal owner = owners.putIfAbsent(node, literal);
				if (owner == null) {
					QuotedGraph.pushChildren(node, pending);
				}
				else if (owner != literal) {
					reachedTwice.add(node);
				}
			}
		}
		return reachedTwice;
	}

	/** Everything the nodes reach, themselves included, also listed in {@code out}. */
	private static Set<LispVal> closedDownward(List<LispVal> nodes, List<LispVal> out) {
		Set<LispVal> closed = identitySet();
		Deque<LispVal> pending = new ArrayDeque<>();
		for (int i = nodes.size() - 1; i >= 0; i--) {
			pending.push(nodes.get(i));
		}
		while (!pending.isEmpty()) {
			LispVal node = LispCircularLiteral.unwrapped(pending.pop());
			if (QuotedGraph.isAggregate(node) && closed.add(node)) {
				out.add(node);
				QuotedGraph.pushChildren(node, pending);
			}
		}
		return closed;
	}

	/** The shared nodes a literal, or a node only it reaches, points at. */
	private static List<LispVal> entries(List<LispVal> literals, Set<LispVal> shared) {
		Set<LispVal> seen = identitySet();
		List<LispVal> entries = new ArrayList<>();
		Deque<LispVal> pending = new ArrayDeque<>();
		for (LispVal literal : literals) {
			pending.push(literal);
			while (!pending.isEmpty()) {
				LispVal node = LispCircularLiteral.unwrapped(pending.pop());
				if (!QuotedGraph.isAggregate(node) || !seen.add(node)) {
					continue;
				}
				if (shared.contains(node)) {
					entries.add(node);
				}
				else {
					QuotedGraph.pushChildren(node, pending);
				}
			}
		}
		return entries;
	}

	private static LispVal find(Map<LispVal, LispVal> parents, LispVal node) {
		LispVal root = node;
		while (true) {
			LispVal parent = parents.get(root);
			if (parent == null) {
				return root;
			}
			root = parent;
		}
	}

	private static Set<LispVal> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

}
