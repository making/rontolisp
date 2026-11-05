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
			case LispCircularLiteral literal -> datumChanges(literal.datum(), rewrite, seen);
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
			case LispCircularLiteral literal -> new LispCircularLiteral(copyDatum(literal.datum(), rewrite, copies));
			case LispStructLiteral literal -> {
				LispStructLiteral out = new LispStructLiteral(rewrittenTypeName(literal, rewrite), literal.slotNames(),
						literal.slotValues());
				copies.put(literal, out);
				for (int i = 0; i < out.slotValues().size(); i++) {
					out.setSlotValue(i, copyDatum(literal.slotValues().get(i), rewrite, copies));
				}
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

	/**
	 * A copy of a cell with nothing in it yet, at the cell's source position, recorded in
	 * {@code copies} so a circle through the cell finds it.
	 * @param cell the cell
	 * @param copies the copies so far, by identity
	 * @return the empty copy
	 */
	static LispCons copyCell(LispCons cell, Map<LispVal, LispVal> copies) {
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
	 * Wraps every literal of a form that holds a circle in a {@link LispCircularLiteral},
	 * so the form is a tree to every walk that follows: a quoted datum whole, an array or
	 * a {@code #S(...)} literal whole, and otherwise the first node on the circle a walk
	 * from the form reaches. Writes into the form, which the read made and nothing else
	 * holds. A form with no circle comes back untouched.
	 * @param form a form as read
	 * @return the form, or the wrapper when the form itself is on a circle
	 */
	public static LispVal shieldCircles(LispVal form) {
		return new CircleShield(form).shield(form);
	}

	/**
	 * {@link #shieldCircles} for a top-level form of program source. A pass that decides
	 * what a program needs by the symbols it names -- a library's splice, a
	 * dead-definition prune -- reads quoted data too, and a wrapper hides it:
	 * {@code (progn '(names...)
	 * form)} names them again where every such pass looks, and evaluates to the form.
	 * @param form a top-level form as read
	 * @return the form, shielded and with what its wrappers hide named beside it when it
	 * holds a circle
	 */
	public static LispVal shieldProgramForm(LispVal form) {
		CircleShield shield = new CircleShield(form);
		LispVal shielded = shield.shield(form);
		LispVal names = shield.hiddenSymbols();
		if (names instanceof LispNil) {
			return shielded;
		}
		LispVal quoted = new LispCons(ReaderSymbols.QUOTE, new LispCons(names, LispNil.INSTANCE));
		return new LispCons(ReaderSymbols.PROGN, new LispCons(quoted, new LispCons(shielded, LispNil.INSTANCE)));
	}

	/**
	 * Takes the wrappers back out of a form on its way to a macro, which has to see the
	 * datum the source wrote: {@code (car x)} of a parameter bound to one is a program's
	 * own business. {@link #shieldCircles} wraps what the macro answers again.
	 * @param form a form
	 * @return the form with every wrapper replaced by its datum, the form itself when it
	 * holds none
	 */
	public static LispVal exposeCircles(LispVal form) {
		if (!LispCircularLiteral.anyMade()) {
			return form;
		}
		boolean wrapped = form instanceof LispCircularLiteral || anyCons(form,
				cell -> cell.car() instanceof LispCircularLiteral || cell.cdr() instanceof LispCircularLiteral);
		return wrapped ? exposed(form) : form;
	}

	private static LispVal exposed(LispVal form) {
		return rebuildSpine(form, node -> (node instanceof LispCircularLiteral literal) ? literal.datum() : null,
				LispTrees::exposed);
	}

	/**
	 * What a node of a form points at: the conses, arrays and literals that can lead on,
	 * and what a wrapper holds.
	 * @param node the node
	 * @return its children
	 */
	static List<LispVal> childNodes(LispVal node) {
		List<LispVal> children = new ArrayList<>();
		switch (node) {
			case LispCons cell -> {
				addNode(children, cell.car());
				addNode(children, cell.cdr());
			}
			case LispArray array -> {
				for (LispVal element : array.data()) {
					addNode(children, element);
				}
			}
			case LispStructLiteral literal -> literal.slotValues().forEach(value -> addNode(children, value));
			case LispCircularLiteral literal -> addNode(children, literal.datum());
			default -> {
			}
		}
		return children;
	}

	/**
	 * Whether a value is a node of a form's graph.
	 * @param value the value, null for an unfilled array element
	 * @return whether it can lead on
	 */
	static boolean isNode(@Nullable LispVal value) {
		return value instanceof LispCons || value instanceof LispArray || value instanceof LispStructLiteral
				|| value instanceof LispCircularLiteral;
	}

	private static void addNode(List<LispVal> nodes, @Nullable LispVal value) {
		if (isNode(value)) {
			nodes.add(value);
		}
	}

	/** One depth-first walk of a form, and what it found. */
	private static final class CircleShield {

		/** The nodes with a path to a circle. */
		private final Set<LispVal> leading = Collections.newSetFromMap(new IdentityHashMap<>());

		/**
		 * The nodes an edge returned to while the walk was still inside them. The first
		 * node of a circle the walk reaches is always one, so a descent from the form
		 * that stops at these stops before it can go round.
		 */
		private final Set<LispVal> closing = Collections.newSetFromMap(new IdentityHashMap<>());

		private final Map<LispVal, LispVal> wrappers = new IdentityHashMap<>();

		private final Set<LispCons> descended = Collections.newSetFromMap(new IdentityHashMap<>());

		/** A node the walk is inside, and how many of its children it has seen. */
		private static final class Visit {

			final LispVal node;

			int seen;

			Visit(LispVal node) {
				this.node = node;
			}

			/** The next child that can lead on, null when there is none. */
			@Nullable LispVal nextChild() {
				while (true) {
					int index = this.seen++;
					LispVal child;
					switch (this.node) {
						case LispCons cell -> {
							if (index > 1) {
								return null;
							}
							child = (index == 0) ? cell.car() : cell.cdr();
						}
						case LispArray array -> {
							if (index >= array.data().length) {
								return null;
							}
							child = array.data()[index];
						}
						case LispStructLiteral literal -> {
							if (index >= literal.slotValues().size()) {
								return null;
							}
							child = literal.slotValues().get(index);
						}
						default -> {
							return null;
						}
					}
					if (isCircleNode(child)) {
						return child;
					}
				}
			}

		}

		// What can be on a circle: a wrapper's circle is shielded already.
		private static boolean isCircleNode(@Nullable LispVal value) {
			return value instanceof LispCons || value instanceof LispArray || value instanceof LispStructLiteral;
		}

		CircleShield(LispVal form) {
			if (!isCircleNode(form)) {
				return;
			}
			Set<LispVal> open = Collections.newSetFromMap(new IdentityHashMap<>());
			Set<LispVal> finished = Collections.newSetFromMap(new IdentityHashMap<>());
			Deque<Visit> path = new ArrayDeque<>();
			open.add(form);
			path.push(new Visit(form));
			while (!path.isEmpty()) {
				Visit top = path.peek();
				LispVal child = top.nextChild();
				if (child == null) {
					path.pop();
					open.remove(top.node);
					finished.add(top.node);
					Visit parent = path.peek();
					if (parent != null && this.leading.contains(top.node)) {
						this.leading.add(parent.node);
					}
				}
				else if (open.contains(child)) {
					this.closing.add(child);
					this.leading.add(top.node);
				}
				else if (!finished.contains(child)) {
					open.add(child);
					path.push(new Visit(child));
				}
				else if (this.leading.contains(child)) {
					this.leading.add(top.node);
				}
			}
		}

		LispVal shield(LispVal node) {
			if (!this.leading.contains(node)) {
				return node;
			}
			if (node instanceof LispCons cell && !this.closing.contains(cell)) {
				descend(cell);
				return cell;
			}
			return wrap(node);
		}

		private LispVal wrap(LispVal node) {
			return this.wrappers.computeIfAbsent(node, LispCircularLiteral::new);
		}

		/** The distinct symbols inside the wrappers, as a list; keywords name nothing. */
		LispVal hiddenSymbols() {
			Map<String, LispVal> symbols = new java.util.LinkedHashMap<>();
			Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			Deque<LispVal> pending = new ArrayDeque<>(this.wrappers.keySet());
			while (!pending.isEmpty()) {
				LispVal node = pending.pop();
				if (node instanceof LispSymbol sym) {
					if (!sym.isKeyword()) {
						symbols.putIfAbsent(sym.name(), sym);
					}
				}
				else if (seen.add(node)) {
					switch (node) {
						case LispCons cell -> {
							pending.push(cell.cdr());
							pending.push(cell.car());
						}
						case LispArray array -> {
							for (LispVal element : array.data()) {
								if (element != null) {
									pending.push(element);
								}
							}
						}
						case LispStructLiteral literal -> {
							symbols.putIfAbsent(literal.typeName(), new LispSymbol(literal.typeName()));
							literal.slotValues().forEach(pending::push);
						}
						default -> {
						}
					}
				}
			}
			LispVal names = LispNil.INSTANCE;
			for (LispVal symbol : symbols.values()) {
				names = new LispCons(symbol, names);
			}
			return names;
		}

		/** Down a cdr spine in a loop, so a long form costs the stack nothing. */
		private void descend(LispCons head) {
			LispCons cell = head;
			while (this.descended.add(cell)) {
				if (cell.car() instanceof LispSymbol op && LispNames.isQuote(LispSymbol.memberName(op.name()))
						&& cell.cdr() instanceof LispCons quoted && this.leading.contains(quoted.car())) {
					quoted.setCar(wrap(quoted.car()));
					return;
				}
				cell.setCar(shield(cell.car()));
				if (!(cell.cdr() instanceof LispCons next) || !this.leading.contains(next)
						|| this.closing.contains(next)) {
					cell.setCdr(shield(cell.cdr()));
					return;
				}
				cell = next;
			}
		}

	}

}
