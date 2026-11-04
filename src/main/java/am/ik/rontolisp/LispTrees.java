package am.ik.rontolisp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
	 * A walk met a list whose tail leads back into itself. The recursive walks the loops
	 * replaced overflowed the stack on one; a loop reports it instead of running without
	 * end.
	 */
	public static final class CircularListException extends IllegalArgumentException {

		private static final long serialVersionUID = 1L;

		/** Reports a list whose tail leads back into itself. */
		public CircularListException() {
			super("a circular list cannot be walked: its tail leads back into itself");
		}

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
		// Brent's cycle check: a cell remembered at every power of two is met again only
		// on a spine that closes into itself, where the walk would otherwise collect
		// cells until memory runs out.
		LispCons remembered = null;
		int power = 1;
		int steps = 0;
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
			if (cell == remembered) {
				throw new CircularListException();
			}
			if (++steps == power) {
				remembered = cell;
				power <<= 1;
				steps = 0;
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
	 * Rewrites every symbol of a DATUM, however deep: through conses, array literals and
	 * {@code #S(...)} literals (a literal's type name too, when the rewrite answers a
	 * symbol for it; its slot names match by base name and stay as spelled).
	 * <p>
	 * A datum is a graph, since a {@code #n=} reader label may share a node or close a
	 * circle through a car, a tail or an array, and the rewrite answers the same graph:
	 * the datum itself when no symbol changes, else a copy with one new node per old one.
	 * <p>
	 * An array the reader cannot write -- displaced, adjustable, with a fill pointer --
	 * is a value a {@code #.} computed, not a datum, and stands as it is.
	 * @param datum the datum
	 * @param rewrite what a symbol becomes, the symbol itself when it stays; asked twice
	 * of a symbol in a datum that changes, so it has to answer the same both times
	 * @return the rewritten datum
	 */
	public static LispVal rewriteDatumSymbols(LispVal datum, Function<LispSymbol, LispVal> rewrite) {
		if (datum instanceof LispSymbol sym) {
			return rewrite.apply(sym);
		}
		return datumChanges(datum, rewrite, Collections.newSetFromMap(new IdentityHashMap<>()))
				? copyDatum(datum, rewrite, new IdentityHashMap<>()) : datum;
	}

	private static boolean datumChanges(LispVal datum, Function<LispSymbol, LispVal> rewrite, Set<LispVal> seen) {
		LispVal node = datum;
		while (node instanceof LispCons cell) {
			if (!seen.add(cell)) {
				return false;
			}
			if (datumChanges(cell.car(), rewrite, seen)) {
				return true;
			}
			node = cell.cdr();
		}
		return switch (node) {
			case LispSymbol sym -> rewrite.apply(sym) != sym;
			case LispStructLiteral literal ->
				seen.add(literal) && (!literal.typeName().equals(rewrittenTypeName(literal, rewrite))
						|| anyDatumChanges(literal.slotValues(), rewrite, seen));
			case LispArray array when isLiteralArray(array) ->
				seen.add(array) && anyDatumChanges(Arrays.asList(array.data()), rewrite, seen);
			default -> false;
		};
	}

	private static boolean anyDatumChanges(List<LispVal> data, Function<LispSymbol, LispVal> rewrite,
			Set<LispVal> seen) {
		for (LispVal element : data) {
			if (datumChanges(element, rewrite, seen)) {
				return true;
			}
		}
		return false;
	}

	private static LispVal copyDatum(LispVal datum, Function<LispSymbol, LispVal> rewrite,
			Map<LispVal, LispVal> copies) {
		LispVal copied = copies.get(datum);
		if (copied != null) {
			return copied;
		}
		return switch (datum) {
			case LispSymbol sym -> rewrite.apply(sym);
			case LispCons head -> copyDatumSpine(head, rewrite, copies);
			case LispStructLiteral literal -> {
				// The reader closes no circle through a literal (patchLabel does not
				// enter
				// one), so its values can be copied before it exists.
				LispVal[] values = new LispVal[literal.slotValues().size()];
				for (int i = 0; i < values.length; i++) {
					values[i] = copyDatum(literal.slotValues().get(i), rewrite, copies);
				}
				LispVal out = new LispStructLiteral(rewrittenTypeName(literal, rewrite), literal.slotNames(),
						List.of(values));
				copies.put(literal, out);
				yield out;
			}
			case LispArray array when isLiteralArray(array) -> {
				LispVal[] data = array.data().clone();
				LispVal out = new LispArray(array.dimensions(), data, -1, false, array.elementTypeCode());
				copies.put(array, out);
				for (int i = 0; i < data.length; i++) {
					data[i] = copyDatum(data[i], rewrite, copies);
				}
				yield out;
			}
			default -> datum;
		};
	}

	/**
	 * Each cell exists before its car is copied, so a circle finds the copy to close on.
	 */
	private static LispVal copyDatumSpine(LispCons head, Function<LispSymbol, LispVal> rewrite,
			Map<LispVal, LispVal> copies) {
		LispCons first = copyCell(head, copies);
		LispCons last = first;
		LispCons cell = head;
		while (true) {
			last.setCar(copyDatum(cell.car(), rewrite, copies));
			if (!(cell.cdr() instanceof LispCons next) || copies.containsKey(next)) {
				last.setCdr(copyDatum(cell.cdr(), rewrite, copies));
				return first;
			}
			LispCons copy = copyCell(next, copies);
			last.setCdr(copy);
			last = copy;
			cell = next;
		}
	}

	private static LispCons copyCell(LispCons cell, Map<LispVal, LispVal> copies) {
		LispCons fresh = cell instanceof LocatedCons located
				? new LocatedCons(LispNil.INSTANCE, LispNil.INSTANCE, located.file(), located.line())
				: new LispCons(LispNil.INSTANCE, LispNil.INSTANCE);
		SourceProvenance.inherit(cell, fresh);
		copies.put(cell, fresh);
		return fresh;
	}

	private static String rewrittenTypeName(LispStructLiteral literal, Function<LispSymbol, LispVal> rewrite) {
		return rewrite.apply(new LispSymbol(literal.typeName())) instanceof LispSymbol sym ? sym.name()
				: literal.typeName();
	}

	private static boolean isLiteralArray(LispArray array) {
		return array.displacedTo() == null && !array.hasFillPointer() && !array.adjustable();
	}

	/**
	 * Whether any cons of a tree -- through cars and cdrs alike, quoted data included --
	 * satisfies {@code test}. The walk keeps its own stack and visits each cell once, so
	 * neither a long list, a deep nesting nor a {@code #n=} cycle can stop it.
	 * @param tree the tree to search
	 * @param test the property one cell is asked for
	 * @return true at the first cell that has it
	 */
	public static boolean anyCons(LispVal tree, java.util.function.Predicate<LispCons> test) {
		Set<LispCons> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<LispVal> pending = new ArrayDeque<>();
		pending.push(tree);
		while (!pending.isEmpty()) {
			LispVal node = pending.pop();
			while (node instanceof LispCons cell && seen.add(cell)) {
				if (test.test(cell)) {
					return true;
				}
				pending.push(cell.car());
				node = cell.cdr();
			}
		}
		return false;
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
