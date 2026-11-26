package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The tail-call groups of the Scheme lowering: procedures whose tail calls to each other
 * form a cycle, lowered as ONE function holding each member's body under a label, so
 * every tail call among them is a jump; each member itself calls it with its index
 * ({@code .kb/scheme-frontend.md}, "Tail-call groups"). At the top level the function is
 * a {@code defun} and each member its own {@code defun}; in a body it is a {@code lambda}
 * in a variable of the body and each member a {@code lambda} calling it.
 *
 * <p>
 * One slice of {@link SchemeLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class SchemeGroupLowering {

	private SchemeGroupLowering() {
	}

	/**
	 * Finds the tail-call groups. The top-level procedures that may call each other in a
	 * cycle -- by a cheap scan of the call heads, blind to scope -- are lowered as a
	 * group once, as a probe, and what their bodies actually JUMP to decides: a set the
	 * jumps do not hold together is settled again as the cycles they do form. A program
	 * with no such cycle is lowered exactly as before.
	 */
	static void declareGroups(SchemeLowering s, List<LispVal> forms) {
		List<SchemeLowering.Member> members = new ArrayList<>();
		for (LispVal datum : forms) {
			if (datum instanceof LispCons form && s.syntaxOf(form, s.global) == Core.DEFINE) {
				SchemeLowering.Definition definition = s.definition(form);
				if (definition.procedure() && definition.caseLambda() == null
						&& s.global.bindings
							.get(s.name(definition.name())) instanceof SchemeLowering.GlobalFunction function
						&& function.clauses().isEmpty()) {
					members.add(new SchemeLowering.Member(form, definition, function));
				}
			}
		}
		for (SchemeLowering.Group group : groups(s, members, s.global)) {
			for (SchemeLowering.Member member : group.members) {
				s.groups.put(s.name(member.definition().name()), group);
			}
		}
	}

	/**
	 * The groups among the procedures a scope defines: the ones that may call each other
	 * in a cycle -- by a cheap scan of the call heads, blind to scope -- are lowered as a
	 * group once, as a probe, and what their bodies actually JUMP to decides. Empty, and
	 * nothing numbered, when there is no such cycle.
	 */
	static List<SchemeLowering.Group> groups(SchemeLowering s, List<SchemeLowering.Member> members,
			SchemeLowering.Scope home) {
		if (members.size() < 2) {
			return List.of();
		}
		Map<String, Integer> index = new HashMap<>();
		for (int i = 0; i < members.size(); i++) {
			index.putIfAbsent(members.get(i).definition().name().name(), i);
		}
		List<Set<Integer>> mentions = new ArrayList<>();
		for (SchemeLowering.Member member : members) {
			Set<String> heads = new HashSet<>();
			collectCallHeads(member.definition().body(), heads);
			Set<Integer> callees = new HashSet<>();
			for (String head : heads) {
				Integer callee = index.get(head);
				if (callee != null) {
					callees.add(callee);
				}
			}
			mentions.add(callees);
		}
		List<SchemeLowering.Group> out = new ArrayList<>();
		for (List<Integer> cycle : cycles(mentions)) {
			settleGroup(s, cycle.stream().map(members::get).toList(), home, out);
		}
		return out;
	}

	// A candidate the jumps do not hold together is settled again as the cycles they do
	// form.
	private static void settleGroup(SchemeLowering s, List<SchemeLowering.Member> candidate, SchemeLowering.Scope home,
			List<SchemeLowering.Group> out) {
		Snapshot snapshot = snapshot(s);
		LoweredGroup probe;
		try {
			probe = lowerGroup(s, candidate, home);
		}
		catch (RuntimeException ex) {
			// The real lowering reports it, positioned, where the form stands.
			return;
		}
		finally {
			restore(s, snapshot);
		}
		List<List<Integer>> cycles = cycles(probe.jumps());
		if (cycles.size() == 1 && cycles.get(0).size() == candidate.size()) {
			out.add(new SchemeLowering.Group(candidate));
			return;
		}
		for (List<Integer> cycle : cycles) {
			settleGroup(s, cycle.stream().map(candidate::get).toList(), home, out);
		}
	}

	/** What a lowering attempt changes that a discarded one must put back. */
	private record Snapshot(int counter, int closures, List<LispVal> hoisted,
			Map<LispCons, SchemeRecordLowering.InternalRecord> internalRecords, Set<String> internalRecordNames,
			Map<LispCons, LispVal> caseLambdas, String enclosing) {
	}

	private static Snapshot snapshot(SchemeLowering s) {
		return new Snapshot(s.counter, s.closures, new ArrayList<>(s.hoisted), new IdentityHashMap<>(s.internalRecords),
				new HashSet<>(s.internalRecordNames), new IdentityHashMap<>(s.caseLambdas), s.enclosing);
	}

	private static void restore(SchemeLowering s, Snapshot snapshot) {
		s.counter = snapshot.counter();
		s.closures = snapshot.closures();
		s.hoisted.clear();
		s.hoisted.addAll(snapshot.hoisted());
		s.internalRecords.clear();
		s.internalRecords.putAll(snapshot.internalRecords());
		s.internalRecordNames.clear();
		s.internalRecordNames.addAll(snapshot.internalRecordNames());
		s.caseLambdas.clear();
		s.caseLambdas.putAll(snapshot.caseLambdas());
		s.enclosing = snapshot.enclosing();
	}

	/**
	 * The group's {@code defun}: {@code (defun G (W C1 .. Cn) (let ((R nil)) (tagbody TOP
	 * (if (= W 1) (go L1) ..) L0 (let ((a C1) ..) body0) (go END) L1 .. END) R))}.
	 * {@code W} picks the member to run; the carriers are shared by position, as many as
	 * the widest member takes. Every member rebinds its variables from them per entry, so
	 * a jump is plain {@code setq}s of carriers no argument can mention, and a closure
	 * captures its own entry's binding.
	 *
	 * <p>
	 * A jump to itself goes to the member's label. A jump to another member also sets
	 * {@code W} and goes to {@code TOP}, the one entry of every cycle: jumping straight
	 * into another member's label would give the loop an entry per member. The layout is
	 * measured (.kb/scheme-frontend.md, "Tail-call groups"): a stub per member that sets
	 * {@code W} cost wasm a dispatch round per jump, and the members as an {@code if}
	 * chain under {@code TOP} ran the metacircular evaluator 15% slower on the JVM.
	 */
	static LoweredGroup lowerGroup(SchemeLowering s, List<SchemeLowering.Member> members, SchemeLowering.Scope home) {
		boolean topLevel = home == s.global;
		List<SchemeLowering.Formals> formals = new ArrayList<>();
		int width = 0;
		for (SchemeLowering.Member member : members) {
			SchemeLowering.Formals parsed = s.formals(Objects.requireNonNull(member.definition().formals()),
					member.form());
			formals.add(parsed);
			width = Math.max(width, parsed.all().size());
		}
		LispSymbol function = s.fresh("G");
		LispSymbol which = s.fresh("W");
		List<LispSymbol> carriers = new ArrayList<>();
		for (int i = 0; i < width; i++) {
			carriers.add(s.fresh("C"));
		}
		LispSymbol result = s.fresh("R");
		SchemeLowering.Exit exit = new SchemeLowering.Exit(result);
		List<LispSymbol> labels = new ArrayList<>();
		for (int i = 0; i < members.size(); i++) {
			labels.add(s.fresh("L"));
		}
		LispSymbol top = s.fresh("L");
		LispSymbol end = s.fresh("L");
		LispVal dispatch = null;
		for (int i = members.size() - 1; i >= 1; i--) {
			// A numeric =, not EQL: the JVM backend compiles EQL on an untyped
			// variable to a generic call, which cost the metacircular evaluator a third
			// of its run time as the entry dispatch of every non-tail m-eval.
			LispVal test = SchemeLowering.list(SchemeLowering.symbol("="), which, new LispInteger(i));
			LispVal jump = SchemeLowering.list(SchemeLowering.symbol("GO"), labels.get(i));
			dispatch = dispatch == null ? SchemeLowering.list(SchemeLowering.symbol("IF"), test, jump)
					: SchemeLowering.list(SchemeLowering.symbol("IF"), test, jump, dispatch);
		}
		List<LispVal> tagbody = new ArrayList<>(
				List.of(SchemeLowering.symbol("TAGBODY"), top, Objects.requireNonNull(dispatch)));
		List<Set<Integer>> jumps = new ArrayList<>();
		String enclosing = s.enclosing;
		for (int i = 0; i < members.size(); i++) {
			SchemeLowering.Member member = members.get(i);
			List<SchemeLowering.Target> targets = new ArrayList<>();
			for (int j = 0; j < members.size(); j++) {
				SchemeLowering.Formals parsed = formals.get(j);
				SchemeLowering.Target target = new SchemeLowering.Target(members.get(j).binding(),
						new SchemeLowering.LoopShape(i == j ? labels.get(j) : top,
								carriers.subList(0, parsed.all().size()), parsed.rest() != null, false),
						home, List.of());
				if (i != j) {
					target.presets = List.of(which, new LispInteger(j));
				}
				targets.add(target);
			}
			if (topLevel) {
				s.enclosing = s.name(member.definition().name());
			}
			SchemeLowering.Scope inner = new SchemeLowering.Scope(home);
			List<LispVal> pairs = new ArrayList<>();
			List<LispSymbol> all = formals.get(i).all();
			for (int j = 0; j < all.size(); j++) {
				pairs.add(SchemeLowering.list(s.bind(all.get(j), inner), carriers.get(j)));
			}
			List<LispVal> statements = SchemeBindingLowering.body(s, member.definition().body(), SchemeLowering.Context
				.storing(new SchemeLowering.Scope(inner), new SchemeLowering.Destination(result, targets, exit)));
			Set<Integer> jumped = new HashSet<>();
			collectJumps(SchemeLowering.listOf(statements), new GroupLabels(i, labels.get(i), which), jumped);
			jumps.add(jumped);
			tagbody.add(labels.get(i));
			// A PROGN even around one statement: a bare symbol in a tagbody is a label.
			tagbody
				.add(pairs.isEmpty() ? new LispCons(SchemeLowering.symbol("PROGN"), SchemeLowering.listOf(statements))
						: new LispCons(SchemeLowering.symbol("LET"),
								new LispCons(SchemeLowering.listOf(pairs), SchemeLowering.listOf(statements))));
			if (i < members.size() - 1) {
				tagbody.add(SchemeLowering.list(SchemeLowering.symbol("GO"), end));
			}
		}
		s.enclosing = enclosing;
		tagbody.add(end);
		List<LispVal> lambdaList = new ArrayList<>();
		lambdaList.add(which);
		lambdaList.addAll(carriers);
		LispVal body = SchemeLowering.exiting(exit,
				SchemeLowering.list(SchemeLowering.symbol("LET"),
						SchemeLowering.list(SchemeLowering.list(result, LispNil.INSTANCE)),
						SchemeLowering.listOf(tagbody), result));
		return new LoweredGroup(function, SchemeLowering.listOf(lambdaList), body, jumps);
	}

	/**
	 * The groups among the procedures a body or a {@code letrec} binds, by the form
	 * defining each member. A candidate is a variable bound to a syntactic
	 * {@code lambda}; one that is ever assigned (by spelling, like the hub's
	 * {@code collectAssigned}) or bound twice is no member.
	 */
	static Map<LispCons, SchemeLowering.Group> internalGroups(SchemeLowering s, List<SchemeLowering.Member> candidates,
			Set<String> alsoBound, SchemeLowering.Scope scope) {
		if (candidates.size() < 2) {
			return Map.of();
		}
		Map<String, Integer> counts = new HashMap<>();
		for (SchemeLowering.Member candidate : candidates) {
			counts.merge(candidate.definition().name().name(), 1, Integer::sum);
		}
		List<SchemeLowering.Member> members = new ArrayList<>();
		for (SchemeLowering.Member candidate : candidates) {
			String name = candidate.definition().name().name();
			if (counts.getOrDefault(name, 0) == 1 && !alsoBound.contains(name)
					&& !s.assignedNames.contains(s.name(candidate.definition().name()))
					&& candidate.binding() instanceof SchemeLowering.Variable) {
				members.add(candidate);
			}
		}
		Map<LispCons, SchemeLowering.Group> out = new IdentityHashMap<>();
		for (SchemeLowering.Group group : groups(s, members, scope)) {
			for (SchemeLowering.Member member : group.members) {
				out.put(member.form(), group);
			}
		}
		return out;
	}

	static SchemeLowering.Member member(SchemeLowering.Group group, LispVal form) {
		for (SchemeLowering.Member member : group.members) {
			if (member.form() == form) {
				return member;
			}
		}
		throw new IllegalStateException("not a member of its group");
	}

	// A member's own defun: enters the group at its label, nil in the carriers it does
	// not take.
	static LispVal groupEntry(SchemeLowering s, SchemeLowering.Group group, SchemeLowering.Member member) {
		Entry entry = entry(s, group, member);
		return SchemeLowering.inherit(member.form(),
				SchemeLowering.list(SchemeLowering.symbol("DEFUN"), member.function().symbol(), entry.lambdaList(),
						new LispCons(Objects.requireNonNull(group.function), entry.arguments())));
	}

	// A member of a body's group: a lambda calling the group's, which the first member's
	// definition assigns to its variable ahead of its own.
	static List<LispVal> internalGroupDefinition(SchemeLowering s, SchemeLowering.Group group,
			SchemeLowering.Member member, SchemeLowering.Scope scope, List<LispVal> pairs) {
		List<LispVal> out = new ArrayList<>();
		if (group.function == null) {
			LoweredGroup lowered = lowerGroup(s, group.members, scope);
			group.function = lowered.function();
			pairs.add(SchemeLowering.list(lowered.function(), LispNil.INSTANCE));
			s.closures++;
			out.add(SchemeLowering.inherit(group.members.get(0).form(), SchemeLowering.list(
					SchemeLowering.symbol("SETQ"), lowered.function(),
					SchemeLowering.list(SchemeLowering.symbol("LAMBDA"), lowered.lambdaList(), lowered.body()))));
		}
		s.closures++;
		Entry entry = entry(s, group, member);
		out.add(SchemeLowering.inherit(member.form(),
				SchemeLowering.list(SchemeLowering.symbol("SETQ"), s.variableSymbol(member.definition().name(), scope),
						SchemeLowering.list(SchemeLowering.symbol("LAMBDA"), entry.lambdaList(),
								new LispCons(SchemeLowering.symbol("FUNCALL"),
										new LispCons(Objects.requireNonNull(group.function), entry.arguments()))))));
		return out;
	}

	/** The group's function and, per member, the members its body jumps to. */
	record LoweredGroup(LispSymbol function, LispVal lambdaList, LispVal body, List<Set<Integer>> jumps) {
	}

	private record Entry(LispVal lambdaList, LispVal arguments) {
	}

	// A member's parameters, and what it hands the group's function after it: its
	// index, its parameters, nil in the carriers it does not take.
	private static Entry entry(SchemeLowering s, SchemeLowering.Group group, SchemeLowering.Member member) {
		int width = 0;
		for (SchemeLowering.Member other : group.members) {
			width = Math.max(width,
					s.formals(Objects.requireNonNull(other.definition().formals()), other.form()).all().size());
		}
		SchemeLowering.Formals formals = s.formals(Objects.requireNonNull(member.definition().formals()),
				member.form());
		List<LispSymbol> parameters = new ArrayList<>();
		for (LispSymbol formal : formals.all()) {
			parameters.add(s.cl(formal));
		}
		List<LispVal> arguments = new ArrayList<>();
		arguments.add(new LispInteger(group.members.indexOf(member)));
		arguments.addAll(parameters);
		while (arguments.size() < width + 1) {
			arguments.add(LispNil.INSTANCE);
		}
		return new Entry(SchemeLowering.lambdaList(parameters, formals.rest() != null),
				SchemeLowering.listOf(arguments));
	}

	/**
	 * How a member's jumps are spelled: {@code (go self)} to itself, {@code (setq .. W j
	 * ..)} before the {@code (go TOP)} to member {@code j}.
	 */
	private record GroupLabels(int member, LispSymbol self, LispSymbol which) {
	}

	// The members the jumps in the form go to.
	private static void collectJumps(LispVal form, GroupLabels labels, Set<Integer> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && head.name().equals("GO") && cons.cdr() instanceof LispCons rest
				&& labels.self().equals(rest.car())) {
			out.add(labels.member());
			return;
		}
		if (cons.car() instanceof LispSymbol head && head.name().equals("SETQ")) {
			LispVal pair = cons.cdr();
			while (pair instanceof LispCons variable && variable.cdr() instanceof LispCons value) {
				if (labels.which().equals(variable.car()) && value.car() instanceof LispInteger member) {
					out.add((int) member.value());
				}
				pair = value.cdr();
			}
		}
		LispVal rest = cons;
		while (rest instanceof LispCons cell) {
			collectJumps(cell.car(), labels, out);
			rest = cell.cdr();
		}
	}

	// Every symbol heading a call-shaped datum, by spelling: mentionsCall's walk.
	private static void collectCallHeads(List<LispVal> body, Set<String> out) {
		for (LispVal datum : body) {
			LispVal rest = datum;
			boolean head = true;
			while (rest instanceof LispCons cell) {
				if (head && cell.car() instanceof LispSymbol symbol) {
					out.add(symbol.name());
				}
				if (cell.car() instanceof LispCons) {
					collectCallHeads(List.of(cell.car()), out);
				}
				head = false;
				rest = cell.cdr();
			}
		}
	}

	/**
	 * The strongly connected sets of more than one node (Tarjan), each sorted, ordered by
	 * their smallest node.
	 */
	private static List<List<Integer>> cycles(List<Set<Integer>> graph) {
		int size = graph.size();
		int[] order = new int[size];
		int[] low = new int[size];
		boolean[] onStack = new boolean[size];
		java.util.Arrays.fill(order, -1);
		java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
		List<List<Integer>> out = new ArrayList<>();
		int[] next = { 0 };
		for (int node = 0; node < size; node++) {
			if (order[node] < 0) {
				connect(node, new Tarjan(graph, order, low, onStack, stack, next, out));
			}
		}
		out.sort(java.util.Comparator.comparingInt(cycle -> cycle.get(0)));
		return out;
	}

	private record Tarjan(List<Set<Integer>> graph, int[] order, int[] low, boolean[] onStack,
			java.util.ArrayDeque<Integer> stack, int[] next, List<List<Integer>> out) {
	}

	private static void connect(int node, Tarjan state) {
		int[] order = state.order();
		int[] low = state.low();
		order[node] = state.next()[0];
		low[node] = state.next()[0];
		state.next()[0]++;
		state.stack().push(node);
		state.onStack()[node] = true;
		for (int successor : state.graph().get(node)) {
			if (order[successor] < 0) {
				connect(successor, state);
				low[node] = Math.min(low[node], low[successor]);
			}
			else if (state.onStack()[successor]) {
				low[node] = Math.min(low[node], order[successor]);
			}
		}
		if (low[node] == order[node]) {
			List<Integer> component = new ArrayList<>();
			int member;
			do {
				member = state.stack().pop();
				state.onStack()[member] = false;
				component.add(member);
			}
			while (member != node);
			if (component.size() > 1) {
				component.sort(null);
				state.out().add(component);
			}
		}
	}

}
