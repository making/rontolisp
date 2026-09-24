package am.ik.rontolisp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * Tree walks that spend a Java frame per level of NESTING, never per list element.
 * <p>
 * A walk written as {@code f(cons.car()) ... f(cons.cdr())} recurses once per element of
 * every list it meets, so a program's stack ceiling becomes its longest LIST: one
 * {@code (progn ...)} of a few thousand forms, or one quoted table, overflowed the
 * compile path where the same forms side by side at top level did not. A predicate or a
 * collector turns its cdr call into a loop by hand (the call is a tail call); a rewrite,
 * which needs the rewritten tail before it can rebuild a cell, goes through
 * {@link #rebuildSpine}.
 */
public final class LispTrees {

	private LispTrees() {
	}

	/**
	 * How one walked cell is put back together.
	 */
	@FunctionalInterface
	public interface Rebuild {

		/**
		 * @param original the cell that was walked
		 * @param car its rewritten car
		 * @param cdr its rewritten cdr
		 * @return the cell's replacement
		 */
		LispVal rebuild(LispCons original, LispVal car, LispVal cdr);

	}

	/**
	 * Rewrites a tree the way the recursive {@code rebuilt(cons, f(car), f(cdr))} does,
	 * with {@link LispCons#rebuilt} -- identity kept for every cell whose car and tail
	 * came back unchanged -- but walking the cdr spine in a loop.
	 * @param form the node to rewrite
	 * @param stop what a node the walk ends at becomes: an atom, or a cons the caller
	 * handles itself (a quoted datum, a special form). {@code null} for a cons means "an
	 * ordinary cell: rewrite its car with {@code element} and walk on down its cdr"; for
	 * an atom it means the atom itself
	 * @param element rewrites one car -- typically the caller's own method, so nesting
	 * still recurses
	 * @return the rewritten node
	 */
	public static LispVal rebuildSpine(LispVal form, Function<LispVal, @Nullable LispVal> stop,
			UnaryOperator<LispVal> element) {
		return rebuildSpine(form, stop, element, LispCons::rebuilt);
	}

	/**
	 * {@link #rebuildSpine(LispVal, Function, UnaryOperator)} with the caller's own way
	 * of putting a cell back together, for a rewrite that always allocates or that
	 * carries a cell's source position over to its replacement.
	 * <p>
	 * The order of the calls is the recursive walk's: {@code stop} on a cell, then
	 * {@code element} on its car, then the same on its cdr; the cells are rebuilt from
	 * the tail back.
	 * @param form the node to rewrite
	 * @param stop as {@link #rebuildSpine(LispVal, Function, UnaryOperator)}
	 * @param element rewrites one car
	 * @param rebuild puts one cell back together from its rewritten car and tail
	 * @return the rewritten node
	 */
	public static LispVal rebuildSpine(LispVal form, Function<LispVal, @Nullable LispVal> stop,
			UnaryOperator<LispVal> element, Rebuild rebuild) {
		List<LispCons> cells = new ArrayList<>();
		List<LispVal> cars = new ArrayList<>();
		LispVal node = form;
		LispVal tail;
		while (true) {
			LispVal stopped = stop.apply(node);
			if (stopped != null) {
				tail = stopped;
				break;
			}
			if (!(node instanceof LispCons cell)) {
				tail = node;
				break;
			}
			cells.add(cell);
			cars.add(element.apply(cell.car()));
			node = cell.cdr();
		}
		for (int i = cells.size() - 1; i >= 0; i--) {
			tail = rebuild.rebuild(cells.get(i), cars.get(i), tail);
		}
		return tail;
	}

	/**
	 * The first cell whose cdr chain leads back into itself, or {@code null} when every
	 * list in the tree ends. A {@code #n=} reader label can close such a list
	 * ({@code #1=(a b . #1#)}), and a walk that loops down the spine would never finish
	 * on one -- so a caller that hands a tree to such walks refuses it first. Cycles
	 * through a car are not reported: a walk recurses on the car and meets them as a
	 * {@link StackOverflowError}, as before.
	 * @param tree the tree to check, including the elements of any array in it
	 * @return a cell of a circular spine, or {@code null}
	 */
	public static @Nullable LispCons circularSpine(LispVal tree) {
		Set<LispVal> done = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<LispVal> pending = new ArrayDeque<>();
		pending.push(tree);
		while (!pending.isEmpty()) {
			LispVal node = pending.pop();
			if (node instanceof LispArray array) {
				if (done.add(array)) {
					for (LispVal element : array.data()) {
						pending.push(element);
					}
				}
				continue;
			}
			Set<LispCons> spine = Collections.newSetFromMap(new IdentityHashMap<>());
			while (node instanceof LispCons cell && !done.contains(cell)) {
				if (!spine.add(cell)) {
					return cell;
				}
				pending.push(cell.car());
				node = cell.cdr();
			}
			done.addAll(spine);
			if (node instanceof LispArray) {
				pending.push(node);
			}
		}
		return null;
	}

}
