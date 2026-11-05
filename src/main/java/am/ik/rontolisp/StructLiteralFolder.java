package am.ik.rontolisp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds every {@link LispStructLiteral} a {@code #S(NAME :SLOT value ...)} source literal
 * read into the {@link LispInstance} it denotes, against the {@link ClosRegistry} of the
 * running compilation or evaluator.
 *
 * <p>
 * This is the read-time construction Common Lisp performs inside the reader itself, moved
 * to the first point that knows the structure layouts. It runs PER TOP-LEVEL FORM on
 * every path -- {@code LispMacroExpander.expandTopLevelDefinitions} on the compile path,
 * the evaluator's top-level entries in the interpreter -- so CL's ordering rule survives:
 * the {@code defstruct} must precede the literal, exactly as it must in a file CL loads
 * one form at a time.
 *
 * <p>
 * The rules follow CLHS 2.4.8.13 -- a {@code #S} literal is the constructor called with
 * the given slots as keywords, each designator coerced with {@code (string slot)} (so a
 * symbol, a string and a character all name a slot; the reader stores the coerced
 * spelling):
 * <ul>
 * <li>slot values are read as DATA and are never evaluated;</li>
 * <li>a slot named more than once takes its LEFTMOST value;</li>
 * <li>a slot the literal omits takes its recorded initform, which must be a constant
 * (rontolisp cannot evaluate an initform at fold time on the compile path, so a
 * non-constant one is a clear error rather than a per-backend divergence);</li>
 * <li>{@code :allow-other-keys} is never a slot and never an error: the LEFTMOST pair
 * naming it decides, and a non-nil value licenses every other unknown slot (the same rule
 * {@code LispMacroExpander.keywordTailProblem} applies to keyword tails);</li>
 * <li>a name that is not a defined structure type, and a slot the type does not have
 * (with no licensing {@code :allow-other-keys}), are errors -- the odd-length and
 * non-symbol/string/character-name cases are already reader errors, since the reader can
 * decide those without a registry.</li>
 * </ul>
 */
public final class StructLiteralFolder {

	private StructLiteralFolder() {
	}

	/**
	 * Folds the struct literals of every form of a program, preserving identity when
	 * there is nothing to fold.
	 * @param program the top-level forms
	 * @param registry the layout registry
	 * @return the program with every {@code #S(...)} literal replaced by its instance, or
	 * {@code program} itself when it holds none
	 */
	public static List<LispVal> foldProgram(List<LispVal> program, ClosRegistry registry) {
		List<LispVal> out = null;
		for (int i = 0; i < program.size(); i++) {
			LispVal folded = fold(program.get(i), registry);
			if (folded != program.get(i) && out == null) {
				out = new ArrayList<>(program.subList(0, i));
			}
			if (out != null) {
				out.add(folded);
			}
		}
		return out == null ? program : out;
	}

	/**
	 * Folds every struct literal in a form, however deeply nested -- inside quoted data,
	 * a backquote's list-building code, a {@code #(...)} vector literal and another
	 * literal's slot values alike. A form is a graph, since a {@code #n=} reader label
	 * may share a node or close a circle through a car, a tail, an array or a literal
	 * (CLHS 2.4.8.3), and the fold answers the same graph: one instance per literal, one
	 * copy per node that leads to a literal, and every other node as it stands.
	 * @param form the form to fold
	 * @param registry the layout registry
	 * @return the folded form, or {@code form} itself when it holds no struct literal
	 */
	public static LispVal fold(LispVal form, ClosRegistry registry) {
		return new Folding(registry).foldGraph(form);
	}

	/** One fold's memo: what each node of the graph became. */
	private static final class Folding {

		private final ClosRegistry registry;

		private final Set<LispVal> leadingToLiteral = Collections.newSetFromMap(new IdentityHashMap<>());

		private final Map<LispVal, LispVal> folded = new IdentityHashMap<>();

		Folding(ClosRegistry registry) {
			this.registry = registry;
		}

		/** Folds a graph of its own: the form, or the constant initform of a slot. */
		LispVal foldGraph(LispVal root) {
			if (holdsLiteral(root)) {
				addNodesLeadingToLiteral(root);
			}
			return fold(root);
		}

		/**
		 * Adds every node with a path to a literal, the literals included, by walking the
		 * predecessors back from them.
		 */
		private void addNodesLeadingToLiteral(LispVal root) {
			Map<LispVal, List<LispVal>> predecessors = new IdentityHashMap<>();
			Deque<LispVal> pending = new ArrayDeque<>();
			Deque<LispVal> reached = new ArrayDeque<>();
			predecessors.put(root, new ArrayList<>());
			pending.push(root);
			while (!pending.isEmpty()) {
				LispVal node = pending.pop();
				if (node instanceof LispStructLiteral) {
					reached.push(node);
				}
				for (LispVal next : LispTrees.childNodes(node)) {
					List<LispVal> known = predecessors.get(next);
					if (known == null) {
						known = new ArrayList<>(1);
						predecessors.put(next, known);
						pending.push(next);
					}
					known.add(node);
				}
			}
			Set<LispVal> marked = Collections.newSetFromMap(new IdentityHashMap<>());
			while (!reached.isEmpty()) {
				LispVal node = reached.pop();
				if (marked.add(node)) {
					this.leadingToLiteral.add(node);
					predecessors.getOrDefault(node, List.of()).forEach(reached::push);
				}
			}
		}

		private LispVal fold(LispVal node) {
			if (!this.leadingToLiteral.contains(node)) {
				return node;
			}
			LispVal done = this.folded.get(node);
			if (done != null) {
				return done;
			}
			return switch (node) {
				case LispStructLiteral literal -> foldLiteral(literal);
				case LispCircularLiteral literal -> {
					LispVal copy = new LispCircularLiteral(fold(literal.datum()));
					this.folded.put(literal, copy);
					yield copy;
				}
				case LispCons head -> foldSpine(head);
				case LispArray array -> {
					LispVal[] data = array.data().clone();
					LispVal copy = new LispArray(array.dimensions(), data, -1, false, array.elementTypeCode());
					this.folded.put(array, copy);
					for (int i = 0; i < data.length; i++) {
						if (data[i] != null) {
							data[i] = fold(data[i]);
						}
					}
					yield copy;
				}
				default -> node;
			};
		}

		/**
		 * A list, walked down its cdr spine in a loop so the stack is spent per level of
		 * nesting rather than per element. Each copy exists before its car is folded, so
		 * a circle finds it to close on.
		 */
		private LispVal foldSpine(LispCons head) {
			LispCons first = LispTrees.copyCell(head, this.folded);
			LispCons last = first;
			LispCons cell = head;
			while (true) {
				last.setCar(fold(cell.car()));
				if (!(cell.cdr() instanceof LispCons next) || !this.leadingToLiteral.contains(next)
						|| this.folded.containsKey(next)) {
					last.setCdr(fold(cell.cdr()));
					return first;
				}
				LispCons copy = LispTrees.copyCell(next, this.folded);
				last.setCdr(copy);
				last = copy;
				cell = next;
			}
		}

		/**
		 * Builds the instance one {@code #S(...)} literal denotes. It exists before its
		 * slot values are folded, so a label naming the literal from inside finds it.
		 */
		private LispInstance foldLiteral(LispStructLiteral literal) {
			LispLayout layout = this.registry.findStructLayout(literal.typeName());
			if (layout == null) {
				String hint = this.registry.findClassLayout(literal.typeName()) != null
						? " (it names a class; #S reads defstruct types only)" : "";
				throw new IllegalArgumentException("#S(" + literal.typeName() + " ...): " + literal.typeName()
						+ " is not a defined structure type" + hint);
			}
			// The LEFTMOST :allow-other-keys pair decides whether unknown slots are
			// licensed; the pairs themselves are never slots and never errors.
			boolean allowOtherKeys = false;
			for (int i = 0; i < literal.slotNames().size(); i++) {
				if (isAllowOtherKeys(literal.slotNames().get(i))) {
					allowOtherKeys = !(literal.slotValues().get(i) instanceof LispNil);
					break;
				}
			}
			LispVal[] slots = new LispVal[layout.slotCount()];
			LispInstance instance = new LispInstance(layout, slots);
			this.folded.put(literal, instance);
			for (int i = 0; i < literal.slotNames().size(); i++) {
				String spelled = literal.slotNames().get(i);
				if (isAllowOtherKeys(spelled)) {
					continue;
				}
				int index = slotIndexOf(layout, spelled);
				if (index < 0) {
					if (allowOtherKeys) {
						continue;
					}
					throw new IllegalArgumentException("#S(" + literal.typeName() + " ...): " + layout.printName()
							+ " has no slot named " + spelled);
				}
				// Leftmost wins: a repeated slot keeps the value written first.
				if (slots[index] == null) {
					slots[index] = fold(literal.slotValues().get(i));
				}
			}
			for (int i = 0; i < slots.length; i++) {
				if (slots[i] == null) {
					slots[i] = defaultSlotValue(literal, layout, i);
				}
			}
			return instance;
		}

		/**
		 * The value of a slot the literal omits: the recorded initform, which must be a
		 * constant. An initform that has to be EVALUATED cannot be honored here -- the
		 * fold runs at compile time on three of the four backends, where there is no
		 * evaluator -- so it is rejected instead of silently reading as nil on some
		 * backends and as its value on others.
		 */
		private LispVal defaultSlotValue(LispStructLiteral literal, LispLayout layout, int index) {
			LispVal initform = layout.initforms().get(index);
			LispVal constant = constantValue(initform);
			if (constant == null) {
				throw new IllegalArgumentException(
						"#S(" + literal.typeName() + " ...): slot " + layout.slotNames().get(index)
								+ " is omitted and its initform " + initform.print() + " is not a constant");
			}
			return foldGraph(constant);
		}

	}

	/**
	 * Whether any node of a form is a struct literal. Every form costs this walk; only
	 * one that holds a literal goes on to the graph the fold needs.
	 */
	private static boolean holdsLiteral(LispVal form) {
		Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<LispVal> pending = new ArrayDeque<>();
		pending.push(form);
		while (!pending.isEmpty()) {
			LispVal node = pending.pop();
			while (node instanceof LispCons cell && seen.add(cell)) {
				if (LispTrees.isNode(cell.car())) {
					pending.push(cell.car());
				}
				node = cell.cdr();
			}
			switch (node) {
				case LispStructLiteral ignored -> {
					return true;
				}
				case LispCircularLiteral literal -> pending.push(literal.datum());
				case LispArray array when seen.add(array) -> {
					for (LispVal element : array.data()) {
						if (LispTrees.isNode(element)) {
							pending.push(element);
						}
					}
				}
				default -> {
				}
			}
		}
		return false;
	}

	/**
	 * The value of a constant initform, or null when the initform has to be evaluated. A
	 * self-evaluating datum stands for itself and {@code (quote x)} for its datum;
	 * anything else -- a variable reference, a function call -- is not a constant.
	 */
	@org.jspecify.annotations.Nullable
	private static LispVal constantValue(LispVal initform) {
		return switch (initform) {
			case LispCons cons -> cons.car() instanceof LispSymbol head && LispNames.QUOTE.equals(head.name())
					&& cons.cdr() instanceof LispCons datum && datum.cdr() instanceof LispNil
							? LispCircularLiteral.unwrapped(datum.car()) : null;
			case LispSymbol ignored -> null;
			default -> LispCircularLiteral.unwrapped(initform);
		};
	}

	/**
	 * Whether a spelled slot name is the {@code :allow-other-keys} marker: the
	 * package-stripped base name with the keyword marker dropped, compared
	 * case-insensitively, so a string designator {@code "ALLOW-OTHER-KEYS"} counts the
	 * same way {@code :allow-other-keys} does (CLHS 2.4.8.13 coerces every designator
	 * with {@code string} before interning the keyword).
	 */
	private static boolean isAllowOtherKeys(String spelled) {
		String base = LispSymbol.displayName(spelled);
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(base);
		if (qn != null) {
			base = qn.member();
		}
		return base.equalsIgnoreCase("ALLOW-OTHER-KEYS");
	}

	/**
	 * The layout index of a slot named in a literal. The name is matched by its
	 * package-stripped base name with the keyword marker dropped, so {@code :X},
	 * {@code X} and {@code PKG::X} all name slot {@code X} -- Common Lisp matches
	 * {@code #S} slot names by symbol-name too. The case-flip retry mirrors
	 * {@link ClosRegistry#slotPosition}: a literal written in a case-preserving internal
	 * source still finds an upcase-read layout's slot.
	 */
	private static int slotIndexOf(LispLayout layout, String spelled) {
		String base = LispSymbol.displayName(spelled);
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(base);
		if (qn != null) {
			base = qn.member();
		}
		int exact = layout.slotIndex(base);
		if (exact >= 0) {
			return exact;
		}
		String upper = base.toUpperCase(java.util.Locale.ROOT);
		if (!upper.equals(base) && layout.slotIndex(upper) >= 0) {
			return layout.slotIndex(upper);
		}
		String lower = base.toLowerCase(java.util.Locale.ROOT);
		if (!lower.equals(base)) {
			return layout.slotIndex(lower);
		}
		return -1;
	}

}
