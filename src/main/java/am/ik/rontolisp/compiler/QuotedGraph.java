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
 * edge. A {@code #n=} reader label (CLHS 2.4.8.3) or a macro's computed constant shares a
 * node or closes a circle through it, and a build that walks the datum as a tree would
 * copy the one and never finish the other. Every circle runs through a node reached
 * twice, so a walk that stops at the named nodes ends. Both compile backends build a
 * datum with names in two steps, every named node empty and then every named node filled,
 * a named node inside a filling answering what the first step made.
 */
public final class QuotedGraph {

	private final Map<LispVal, Integer> names;

	private final List<LispVal> named;

	private QuotedGraph(Map<LispVal, Integer> names, List<LispVal> named) {
		this.names = names;
		this.named = named;
	}

	/**
	 * Finds the nodes of a datum reached more than once.
	 * @param datum the datum
	 * @return its graph, or {@code null} for a tree, which has no node to name
	 */
	public static @Nullable QuotedGraph of(LispVal datum) {
		Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<LispVal, Integer> names = new IdentityHashMap<>();
		List<LispVal> named = new ArrayList<>();
		Deque<LispVal> pending = new ArrayDeque<>();
		pending.push(datum);
		while (!pending.isEmpty()) {
			LispVal node = LispCircularLiteral.unwrapped(pending.pop());
			if (!isAggregate(node)) {
				continue;
			}
			if (!seen.add(node)) {
				if (names.putIfAbsent(node, named.size()) == null) {
					named.add(node);
				}
				continue;
			}
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
		return named.isEmpty() ? null : new QuotedGraph(names, named);
	}

	/**
	 * The values with an identity a program can observe. An atom has none, or the
	 * backends' literals answer one object for it already.
	 */
	private static boolean isAggregate(LispVal value) {
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

}
