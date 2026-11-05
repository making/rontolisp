package am.ik.rontolisp.macro;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * Which earlier method tables of a generic function a compiled program can observe. The
 * compile walk knows the whole program, so a dispatcher over the last table would answer
 * a call with methods whose forms have not run yet. Where a top-level form that runs
 * before a method's form can reach the generic, the dispatcher keeps the earlier table's
 * body behind a flag the method's form sets ({@code .kb/clos.md}, "Method definitions
 * take effect in program order"); every other generic is emitted as if this pass did not
 * exist.
 *
 * <p>
 * The reach is by name: a form reaches what it or a function it reaches mentions, a
 * generic function reaches its methods' bodies, and a form that calls through a function
 * value reaches whatever an earlier form mentioned. A call this cannot see keeps the last
 * table.
 */
final class MethodGenerations {

	/** What an operator calls without naming it: the generic functions behind it. */
	private static final Map<String, List<String>> IMPLICIT_CALLS = implicitCalls();

	/** Operators calling a function value they were handed. */
	private static final Set<String> CALLS_FUNCTION_VALUES = Set.of(LispNames.FUNCALL, LispNames.APPLY,
			LispNames.MAPCAR, LispNames.MAPC, LispNames.MAP, LispNames.REDUCE, LispNames.SORT, LispNames.EVAL);

	private MethodGenerations() {
	}

	private static Map<String, List<String>> implicitCalls() {
		Map<String, List<String>> calls = new HashMap<>();
		List<String> init = List.of(LispNames.INITIALIZE_INSTANCE, LispNames.SHARED_INITIALIZE);
		calls.put(LispNames.MAKE_INSTANCE,
				List.of(LispNames.INITIALIZE_INSTANCE, LispNames.SHARED_INITIALIZE, LispNames.ALLOCATE_INSTANCE));
		calls.put(LispNames.CHANGE_CLASS, init);
		List<String> slotProtocol = List.of(LispNames.SLOT_UNBOUND, LispNames.SLOT_MISSING);
		calls.put(LispNames.SLOT_VALUE, slotProtocol);
		calls.put(LispNames.WITH_SLOTS, slotProtocol);
		calls.put(LispNames.SLOT_READ_INTERNAL, slotProtocol);
		for (String printer : List.of(LispNames.PRINT, LispNames.PRIN1, LispNames.PRINC, LispNames.PPRINT,
				LispNames.WRITE, LispNames.FORMAT, LispNames.PRIN1_TO_STRING, LispNames.PRINC_TO_STRING,
				LispNames.WRITE_TO_STRING)) {
			calls.put(printer, List.of(LispNames.PRINT_OBJECT));
		}
		return calls;
	}

	/**
	 * Assigns each generic function the earlier tables a call can observe.
	 * @param out the compile walk's output, its forms at the positions the method changes
	 * recorded
	 * @param registry the registry
	 * @param systemGeneric whether a plain name is a generic CL supplies a default method
	 * of, which a call can reach before any definition of the program
	 * @return the generic functions with at least one generation
	 */
	static List<ClosRegistry.GenericInfo> assign(List<LispVal> out, ClosRegistry registry,
			Predicate<String> systemGeneric) {
		int[] running = runningPositions(out);
		List<ClosRegistry.GenericInfo> candidates = new ArrayList<>();
		for (ClosRegistry.GenericInfo generic : registry.generics().values()) {
			if (generic.methodChanges().stream().anyMatch(change -> canShowAtAll(change, running, registry))) {
				candidates.add(generic);
			}
		}
		if (candidates.isEmpty()) {
			return List.of();
		}
		ReachGraph graph = new ReachGraph(out, registry);
		List<ClosRegistry.GenericInfo> generational = new ArrayList<>();
		for (ClosRegistry.GenericInfo generic : candidates) {
			List<ClosRegistry.MethodGeneration> generations = generationsOf(generic, graph.positionsReaching(generic),
					registry, systemGeneric.test(plain(generic.name())));
			if (!generations.isEmpty()) {
				generic.generations(generations);
				generational.add(generic);
			}
		}
		return generational;
	}

	/**
	 * Puts each generation's flag in the program: a {@code defvar} ahead of everything,
	 * so a dispatcher running first reads nil, and a {@code setq} ahead of the form that
	 * ends the generation, which leaves that form's value the top level's. The dispatcher
	 * slots must already be filled: the forms shift every later index.
	 * @param out the compile walk's output, mutated
	 * @param generational what {@link #assign} answered
	 */
	static void placeFlags(List<LispVal> out, List<ClosRegistry.GenericInfo> generational) {
		List<ClosRegistry.MethodGeneration> generations = new ArrayList<>();
		generational.forEach(generic -> generations.addAll(generic.generations()));
		generations.sort((a, b) -> Integer.compare(b.position(), a.position()));
		List<LispVal> defvars = new ArrayList<>();
		for (ClosRegistry.MethodGeneration generation : generations) {
			LispSymbol flag = new LispSymbol(generation.flag());
			out.add(generation.position(), threeElements(new LispSymbol(LispNames.SETQ), flag, LispTrue.INSTANCE));
			defvars.add(threeElements(new LispSymbol(LispNames.DEFVAR), flag, LispNil.INSTANCE));
		}
		out.addAll(0, defvars);
	}

	private static LispVal threeElements(LispVal first, LispVal second, LispVal third) {
		return new LispCons(first, new LispCons(second, new LispCons(third, LispNil.INSTANCE)));
	}

	/**
	 * Whether a change could matter to a form that runs before it, on what the walk knows
	 * without the reach: some form runs earlier and the method's types exist by then.
	 */
	private static boolean canShowAtAll(ClosRegistry.MethodChange change, int[] running, ClosRegistry registry) {
		int last = lastBelow(running, change.position());
		return last >= 0 && !cannotApplyBefore(change, last, registry);
	}

	/**
	 * Whether a method cannot apply to any call of the program up to {@code lastCall}: a
	 * specializer names a type whose first definition comes after it, so no such instance
	 * exists yet. An earlier table without the method answers those calls the same.
	 */
	private static boolean cannotApplyBefore(ClosRegistry.MethodChange change, int lastCall, ClosRegistry registry) {
		if (change.method() == null) {
			return false;
		}
		for (ClosRegistry.Specializer specializer : change.method().specializers()) {
			if (specializer.name() != null && registry.typeFirstDefined(specializer.name()) > lastCall) {
				return true;
			}
		}
		return false;
	}

	private static List<ClosRegistry.MethodGeneration> generationsOf(ClosRegistry.GenericInfo generic, int[] reach,
			ClosRegistry registry, boolean systemGeneric) {
		List<ClosRegistry.MethodGeneration> generations = new ArrayList<>();
		int previous = -1;
		List<ClosRegistry.MethodChange> changes = generic.methodChanges();
		for (int i = 0; i < changes.size();) {
			int position = changes.get(i).position();
			int last = lastBelow(reach, position);
			boolean observable = false;
			for (; i < changes.size() && changes.get(i).position() == position; i++) {
				observable |= last >= previous && last >= 0 && !cannotApplyBefore(changes.get(i), last, registry);
			}
			if (!observable) {
				continue;
			}
			Map<String, ClosRegistry.MethodInfo> table = generic.methodsBefore(position);
			// A system generic has its default from the start, unless the program's own
			// default took its key: that one stays visible from the start too.
			if (systemGeneric && table.values().stream().noneMatch(m -> m.isPrimary() && m.isDefault())) {
				continue;
			}
			generations.add(new ClosRegistry.MethodGeneration(generic.generationFlag(generations.size()), position,
					table, generic.existsBefore(position)));
			previous = position;
		}
		return generations;
	}

	/** The positions of the forms that run something when the program starts. */
	private static int[] runningPositions(List<LispVal> out) {
		int[] positions = new int[out.size()];
		int count = 0;
		for (int i = 0; i < out.size(); i++) {
			if (runs(out.get(i))) {
				positions[count++] = i;
			}
		}
		return Arrays.copyOf(positions, count);
	}

	private static boolean runs(LispVal form) {
		return form instanceof LispCons && !isDefinition(form, LispNames.DEFUN)
				&& !isDefinition(form, LispNames.DEFMACRO);
	}

	private static boolean isDefinition(LispVal form, String definer) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head && definer.equals(head.name());
	}

	/** The largest of the sorted {@code positions} below {@code limit}, or -1. */
	private static int lastBelow(int[] positions, int limit) {
		int at = Arrays.binarySearch(positions, limit);
		int index = (at >= 0 ? at : -at - 1) - 1;
		return index >= 0 ? positions[index] : -1;
	}

	private static String plain(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static void collectNames(LispVal form, Set<String> names) {
		LispVal cursor = form;
		while (cursor instanceof LispCons cons) {
			collectNames(cons.car(), names);
			cursor = cons.cdr();
		}
		if (cursor instanceof LispSymbol symbol) {
			names.add(plain(symbol.name()));
		}
	}

	/**
	 * Who mentions whom in the compiled program, every name without its package: two
	 * packages' same-named functions are one node, which only widens the reach.
	 */
	private static final class ReachGraph {

		private final Map<String, Set<String>> mentions = new HashMap<>();

		private final Map<String, Set<String>> mentionedBy = new HashMap<>();

		private final Set<String> reachedByRunningForms;

		private final Map<String, List<Integer>> runningFormsMentioning = new HashMap<>();

		private final int[] callsFunctionValues;

		ReachGraph(List<LispVal> out, ClosRegistry registry) {
			IMPLICIT_CALLS.forEach((operator, generics) -> generics.forEach(g -> edge(operator, g)));
			for (int i = 0; i < out.size(); i++) {
				LispVal form = out.get(i);
				Set<String> names = new HashSet<>();
				collectNames(form, names);
				if (runs(form)) {
					for (String name : names) {
						this.runningFormsMentioning.computeIfAbsent(name, n -> new ArrayList<>()).add(i);
					}
				}
				else if (isDefinition(form, LispNames.DEFUN) && ((LispCons) form).cdr() instanceof LispCons named
						&& named.car() instanceof LispSymbol defined) {
					String function = plain(defined.name());
					names.forEach(name -> edge(function, name));
				}
			}
			for (ClosRegistry.GenericInfo generic : registry.generics().values()) {
				String name = plain(generic.name());
				generic.methods().values().forEach(m -> edge(name, plain(m.functionName())));
				for (ClosRegistry.MethodChange change : generic.methodChanges()) {
					if (change.method() != null) {
						edge(name, plain(change.method().functionName()));
					}
				}
				if (name.startsWith(ClosRegistry.SETF_FUNCTION_PREFIX)) {
					edge(name.substring(ClosRegistry.SETF_FUNCTION_PREFIX.length()), name);
				}
			}
			for (ClosRegistry.ClassInfo info : registry.classes().values()) {
				String name = plain(info.name());
				edge(name, "%MAKE-" + name);
			}
			this.reachedByRunningForms = closure(this.runningFormsMentioning.keySet(), this.mentions);
			this.callsFunctionValues = positionsOf(closure(CALLS_FUNCTION_VALUES, this.mentionedBy));
		}

		private void edge(String from, String to) {
			this.mentions.computeIfAbsent(from, n -> new HashSet<>()).add(to);
			this.mentionedBy.computeIfAbsent(to, n -> new HashSet<>()).add(from);
		}

		/**
		 * The names a chain of {@code edges} leads to from {@code start}, itself
		 * included.
		 */
		private static Set<String> closure(Set<String> start, Map<String, Set<String>> edges) {
			Set<String> seen = new HashSet<>(start);
			Deque<String> queue = new ArrayDeque<>(start);
			while (!queue.isEmpty()) {
				for (String next : edges.getOrDefault(queue.pop(), Set.of())) {
					if (seen.add(next)) {
						queue.push(next);
					}
				}
			}
			return seen;
		}

		private int[] positionsOf(Set<String> names) {
			TreeSet<Integer> positions = new TreeSet<>();
			for (String name : names) {
				positions.addAll(this.runningFormsMentioning.getOrDefault(name, List.of()));
			}
			return positions.stream().mapToInt(Integer::intValue).toArray();
		}

		/**
		 * The sorted positions of the running forms that can call a generic: those whose
		 * mentions lead to it, and, from the first of them on, those that call a function
		 * value, which an earlier form may have made of it.
		 */
		int[] positionsReaching(ClosRegistry.GenericInfo generic) {
			String name = plain(generic.name());
			if (!this.reachedByRunningForms.contains(name)) {
				return new int[0];
			}
			int[] direct = positionsOf(closure(Set.of(name), this.mentionedBy));
			TreeSet<Integer> positions = new TreeSet<>();
			for (int position : direct) {
				positions.add(position);
			}
			for (int position : this.callsFunctionValues) {
				if (position > direct[0]) {
					positions.add(position);
				}
			}
			return positions.stream().mapToInt(Integer::intValue).toArray();
		}

	}

}
