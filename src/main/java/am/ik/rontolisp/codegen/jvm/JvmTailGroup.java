package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ClassDefinition;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * A tail group: functions the compiler can name whose tail calls to each other form a
 * cycle -- top-level {@code defun}s, or the functions of one {@code labels} form -- whose
 * methods hold the code of the whole group, so each such call is a jump and a mutual
 * recursion runs in constant stack, as a self tail call does ({@link JvmSelfTailCall},
 * {@code .kb/jvm-self-tail-calls.md}, "Mutual tail calls").
 *
 * <p>
 * Each member compiles exactly as it would alone, in a method of its own. A call to a
 * sibling on the tail mark evaluates the sibling's physical arguments, stores them into
 * the parameter slots -- and, for a {@code labels} function, the sibling's closure into
 * the environment slot -- and branches to a label the member's own code never binds. Once
 * every program body is compiled the group is laid out ({@link #finish}): a member a call
 * names gets the group rooted at it as its method -- its own code at the entry, the
 * members it reaches after it, each such label landing on its target's first instruction
 * -- and every other member keeps its own code, each label landing on an epilogue that
 * calls the sibling's method directly: the call it was, minus the dispatcher.
 */
final class JvmTailGroup {

	/** Whether the members are {@code labels} closures (slot 0 their environment). */
	private final boolean closures;

	private final List<Member> members = new ArrayList<>();

	private JvmTailGroup(boolean closures) {
		this.closures = closures;
	}

	/**
	 * One function of a group.
	 */
	static final class Member {

		final JvmTailGroup group;

		/**
		 * What the debug report and a message call it: the defun's name, the
		 * {@code labels} function's variable.
		 */
		final String name;

		/** The {@code labels} variable holding the closure, or null for a defun. */
		final @Nullable String variable;

		/**
		 * The registry entry a call names: a defun's from the start, a {@code labels}
		 * function's once Pass 2c registers its method.
		 */
		JvmLispCompiler.@Nullable FunctionInfo function;

		/**
		 * A {@code labels} function's lambda, recorded where it is compiled as a value.
		 */
		JvmLispCompiler.@Nullable LambdaInfo lambda;

		/** The member's own method, once compiled. */
		JvmLispCompiler.@Nullable Ctx ctx;

		/** The member's tail calls to a sibling, each waiting on its own label. */
		final List<Jump> jumps = new ArrayList<>();

		private Member(JvmTailGroup group, String name, @Nullable String variable) {
			this.group = group;
			this.name = name;
			this.variable = variable;
		}

		/**
		 * {@return the slot of the first parameter: past the environment for a closure}
		 */
		int firstParamSlot() {
			return this.group.closures ? 1 : 0;
		}

		/**
		 * {@return the physical parameter count}
		 */
		int paramCount() {
			JvmLispCompiler.LambdaInfo info = this.lambda;
			if (info != null) {
				return info.paramNames().size();
			}
			return java.util.Objects.requireNonNull(this.function).paramCount();
		}

		/**
		 * {@return the slot of the value-tail depth, past the parameters} Only a member
		 * of a group that may bounce has one ({@link JvmTailBounce}): every
		 * {@code labels} function, and the defuns of a group whose registry entries are
		 * {@code bounceVisible}.
		 */
		int depthSlot() {
			return firstParamSlot() + paramCount();
		}

		/**
		 * {@return whether the last parameter takes the rest list}
		 */
		boolean variadic() {
			JvmLispCompiler.LambdaInfo info = this.lambda;
			return info != null ? info.variadic() : java.util.Objects.requireNonNull(this.function).variadic();
		}

		/**
		 * {@return the physical optionals}
		 */
		int optionals() {
			JvmLispCompiler.LambdaInfo info = this.lambda;
			return info != null ? info.optionals() : java.util.Objects.requireNonNull(this.function).optionals();
		}

		/**
		 * {@return the arguments a call must pass at least}
		 */
		int required() {
			return this.paramCount() - (this.variadic() ? 1 : 0) - this.optionals();
		}

		/**
		 * {@return whether a call passing {@code count} arguments binds the parameters}
		 */
		boolean accepts(int count) {
			return count >= this.required() && (this.variadic() || count <= this.required() + this.optionals());
		}

		/**
		 * {@return whether the member's shape is known, so a call can jump to it}
		 */
		boolean known() {
			return this.group.closures ? this.lambda != null : this.function != null;
		}

	}

	/**
	 * A tail call to a sibling, waiting on its own label.
	 *
	 * @param exit the label the jump branches to
	 * @param target the sibling
	 * @param site the source site current at the call, which an epilogue keeps
	 */
	record Jump(MethodCode.Label exit, Member target, int site) {
	}

	/**
	 * {@return the members}
	 */
	List<Member> members() {
		return this.members;
	}

	// --- finding the groups
	// --------------------------------------------------------------

	/**
	 * The groups among a program's top-level defuns: each set whose tail calls to each
	 * other -- by name, or through a literal {@code #'name} -- form a cycle. Found before
	 * the registry is: the members' registry entries, whose descriptors depend on whether
	 * the group bounces ({@link JvmTailBounce#bouncingDefuns}), are the caller's to set.
	 * @param defuns the defuns, in program order
	 * @param specials the special variables, whose binding ends a tail
	 * @return the groups, in the order of their first member
	 */
	static List<JvmTailGroup> ofDefuns(List<JvmLispCompiler.DefunDecl> defuns, Set<String> specials) {
		Map<String, Integer> index = new LinkedHashMap<>();
		for (int i = 0; i < defuns.size(); i++) {
			index.put(defuns.get(i).name(), i);
		}
		List<Set<Integer>> edges = new ArrayList<>(defuns.size());
		for (int i = 0; i < defuns.size(); i++) {
			Set<Integer> out = new LinkedHashSet<>();
			edges.add(out);
			List<LispVal> body = defuns.get(i).bodyExprs();
			if (body.isEmpty()) {
				continue;
			}
			int self = i;
			tailCalls(body.get(body.size() - 1), specials, Set.of(), (call, locals) -> {
				String callee = calleeName(call);
				if (callee != null && !locals.contains(callee)) {
					Integer target = index.get(callee);
					if (target != null && target != self) {
						out.add(target);
					}
				}
			});
		}
		List<JvmTailGroup> groups = new ArrayList<>();
		for (List<Integer> cycle : cycles(edges)) {
			JvmTailGroup group = new JvmTailGroup(false);
			for (int i : cycle) {
				group.members.add(new Member(group, defuns.get(i).name(), null));
			}
			groups.add(group);
		}
		return groups;
	}

	/**
	 * The groups among the functions of one {@code labels} form, read off its expansion
	 * ({@link LispMacroExpander#expandLabels}): each set whose tail calls to each other
	 * through their variables form a cycle. The expansion is fresh per compilation of the
	 * form, so the lambda forms identify the members of this instance.
	 * @param expansion the expansion,
	 * {@code (let (vars) (setq var (lambda ...))... body)}
	 * @param specials the special variables, whose binding ends a tail
	 * @return each member's lambda form mapped to its member; empty when there is no
	 * cycle
	 */
	static Map<LispCons, Member> ofLabels(LispVal expansion, Set<String> specials) {
		if (!(expansion instanceof LispCons let) || !(let.car() instanceof LispSymbol head)
				|| !LispNames.LET.equals(head.name()) || !let.isProperList()) {
			return Map.of();
		}
		List<LispVal> parts = let.toList();
		Map<String, Integer> index = new LinkedHashMap<>();
		List<LispCons> lambdas = new ArrayList<>();
		for (int k = 2; k < parts.size(); k++) {
			if (parts.get(k) instanceof LispCons setq && setq.isProperList() && setq.car() instanceof LispSymbol op
					&& LispNames.SETQ.equals(op.name())) {
				List<LispVal> assignment = setq.toList();
				if (assignment.size() == 3 && assignment.get(1) instanceof LispSymbol var
						&& LispMacroExpander.isLabelsFunctionVariable(var.name())
						&& assignment.get(2) instanceof LispCons lambda && lambda.isProperList()
						&& lambda.car() instanceof LispSymbol lambdaHead && LispNames.LAMBDA.equals(lambdaHead.name())
						&& !index.containsKey(var.name())) {
					index.put(var.name(), lambdas.size());
					lambdas.add(lambda);
				}
			}
		}
		if (lambdas.size() < 2) {
			return Map.of();
		}
		List<Set<Integer>> edges = new ArrayList<>(lambdas.size());
		for (int i = 0; i < lambdas.size(); i++) {
			Set<Integer> out = new LinkedHashSet<>();
			edges.add(out);
			List<LispVal> lambdaParts = lambdas.get(i).toList();
			if (lambdaParts.size() < 3 || bindsSpecialParameter(lambdaParts.get(1), specials)) {
				// A parameter named like a special is bound around the whole body
				// (LambdaLists.toNative), so no call in it is a tail call.
				continue;
			}
			int self = i;
			tailCalls(lambdaParts.get(lambdaParts.size() - 1), specials, Set.of(), (call, locals) -> {
				List<LispVal> callParts = call.toList();
				if (call.car() instanceof LispSymbol op && LispNames.FUNCALL.equals(op.name()) && callParts.size() > 1
						&& callParts.get(1) instanceof LispSymbol var) {
					Integer target = index.get(var.name());
					if (target != null && target != self) {
						out.add(target);
					}
				}
			});
		}
		List<String> vars = new ArrayList<>(index.keySet());
		if (Boolean.getBoolean("rontolisp.jvm.debug-tail-groups")) {
			// Every labels form with a tail call to a sibling, cycle or not: how often a
			// program does it at all.
			StringBuilder calls = new StringBuilder();
			for (int i = 0; i < edges.size(); i++) {
				for (int target : edges.get(i)) {
					calls.append(' ').append(vars.get(i)).append("->").append(vars.get(target));
				}
			}
			if (!calls.isEmpty()) {
				System.err.println("[tail-calls] labels" + calls);
			}
		}
		Map<LispCons, Member> found = new IdentityHashMap<>();
		for (List<Integer> cycle : cycles(edges)) {
			JvmTailGroup group = new JvmTailGroup(true);
			for (int i : cycle) {
				Member member = new Member(group, vars.get(i), vars.get(i));
				group.members.add(member);
				found.put(lambdas.get(i), member);
			}
		}
		return found;
	}

	/**
	 * The function a tail call names: its head, or a literal {@code #'name} funcalled.
	 */
	private static @Nullable String calleeName(LispCons call) {
		if (!(call.car() instanceof LispSymbol sym)) {
			return null;
		}
		String head = sym.name();
		if (!LispNames.FUNCALL.equals(head)) {
			return head;
		}
		List<LispVal> parts = call.toList();
		return parts.size() > 1 ? FunctionDesignators.literalName(parts.get(1)) : null;
	}

	/**
	 * Visits each call in tail position of a form whose value is a function's result:
	 * where the emitter's tail mark reaches it -- through {@code if}, {@code progn}, a
	 * {@code let}/{@code let*} that binds no special, the blocks and a
	 * {@code return}/{@code return-from} reached through them, an inline lambda's body,
	 * and the pass-through lowerings ({@link JvmExprCompiler#compileExpansion}) -- a call
	 * with a computed head included. An exit out of a loop body is not followed. A form
	 * it does not know ends the walk, so a disagreement with the emitter only loses a
	 * group, keeps a member whose call is not a jump, or keeps a tail through a value a
	 * call in a defun that then bounces nowhere ({@link JvmTailBounce#bouncingDefuns});
	 * it can never make a call a jump or a bounce, which only the mark does.
	 * @param form the form
	 * @param specials the special variables
	 * @param locals the local function names in scope, which a call head means instead of
	 * a defun
	 * @param visit receives each call and the local function names in scope at it
	 */
	static void tailCalls(LispVal form, Set<String> specials, Set<String> locals,
			BiConsumer<LispCons, Set<String>> visit) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return;
		}
		if (!(cons.car() instanceof LispSymbol op)) {
			if (cons.car() instanceof LispCons head && head.car() instanceof LispSymbol lambda
					&& LispNames.LAMBDA.equals(lambda.name()) && head.isProperList()) {
				// An inline lambda: its body runs in place (JvmLambdaCompiler), unless
				// a parameter binds a special around it.
				List<LispVal> lambdaParts = head.toList();
				if (lambdaParts.size() > 1 && !mentionsSpecial(lambdaParts.get(1), specials)) {
					last(lambdaParts, 2, specials, locals, visit);
				}
				return;
			}
			// A computed head: a call too.
			visit.accept(cons, locals);
			return;
		}
		List<LispVal> parts = cons.toList();
		switch (op.name()) {
			case LispNames.IF -> {
				if (parts.size() > 2) {
					tailCalls(parts.get(2), specials, locals, visit);
				}
				if (parts.size() > 3) {
					tailCalls(parts.get(3), specials, locals, visit);
				}
			}
			case LispNames.PROGN, LispNames.LOCALLY, LispNames.AND, LispNames.OR, LispNames.BLOCK_INTERNAL ->
				last(parts, 1, specials, locals, visit);
			case LispNames.BLOCK, LispNames.FN_BLOCK_INTERNAL, LispNames.WHEN, LispNames.UNLESS ->
				last(parts, 2, specials, locals, visit);
			case LispNames.LET, LispNames.LET_STAR -> {
				if (parts.size() > 1 && !bindsSpecial(parts.get(1), specials)) {
					last(parts, 2, specials, locals, visit);
				}
			}
			case LispNames.RETURN -> {
				if (parts.size() == 2) {
					tailCalls(parts.get(1), specials, locals, visit);
				}
			}
			case LispNames.RETURN_FROM, LispNames.THE -> {
				if (parts.size() == 3) {
					tailCalls(parts.get(2), specials, locals, visit);
				}
			}
			case LispNames.MULTIPLE_VALUE_BIND, LispNames.DESTRUCTURING_BIND -> {
				if (parts.size() > 2 && !mentionsSpecial(parts.get(1), specials)) {
					last(parts, 3, specials, locals, visit);
				}
			}
			case LispNames.SYMBOL_MACROLET -> last(parts, 2, specials, locals, visit);
			case LispNames.WITH_SLOTS, LispNames.WITH_ACCESSORS -> last(parts, 3, specials, locals, visit);
			case LispNames.MULTIPLE_VALUE_CALL -> {
				// The expansion's call: a literal designator stays in it, any other is
				// a value in a temporary (LispMacroExpander.expandMultipleValueCall).
				if (parts.size() > 1) {
					visit.accept(new LispCons(new LispSymbol(LispNames.FUNCALL), cons.cdr()), locals);
				}
			}
			case LispNames.COND -> clauses(parts, 1, specials, locals, visit);
			case LispNames.CASE, LispNames.ECASE, LispNames.TYPECASE, LispNames.ETYPECASE ->
				clauses(parts, 2, specials, locals, visit);
			case LispNames.FLET, LispNames.LABELS -> {
				Set<String> shadowed = new HashSet<>(locals);
				if (parts.size() > 1 && parts.get(1) instanceof LispCons definitions && definitions.isProperList()) {
					for (LispVal definition : definitions.toList()) {
						if (definition instanceof LispCons def && def.car() instanceof LispSymbol name) {
							shadowed.add(name.name());
						}
					}
				}
				last(parts, 2, specials, shadowed, visit);
			}
			default -> visit.accept(cons, locals);
		}
	}

	private static void last(List<LispVal> parts, int from, Set<String> specials, Set<String> locals,
			BiConsumer<LispCons, Set<String>> visit) {
		if (parts.size() > from) {
			tailCalls(parts.get(parts.size() - 1), specials, locals, visit);
		}
	}

	private static void clauses(List<LispVal> parts, int from, Set<String> specials, Set<String> locals,
			BiConsumer<LispCons, Set<String>> visit) {
		for (int i = from; i < parts.size(); i++) {
			if (parts.get(i) instanceof LispCons clause && clause.isProperList()) {
				List<LispVal> clauseParts = clause.toList();
				if (clauseParts.size() > 1) {
					tailCalls(clauseParts.get(clauseParts.size() - 1), specials, locals, visit);
				}
			}
		}
	}

	// The expansion's lambda list is the physical one: a symbol in it named like a
	// special is a required or rest parameter, which binds it dynamically.
	private static boolean bindsSpecialParameter(LispVal lambdaList, Set<String> specials) {
		for (LispVal node = lambdaList; node instanceof LispCons cell; node = cell.cdr()) {
			if (cell.car() instanceof LispSymbol sym && specials.contains(sym.name())) {
				return true;
			}
		}
		return false;
	}

	// A lambda list or a destructuring pattern, at any depth: a symbol named like a
	// special binds it dynamically around the body (an init form's mention is a
	// conservative stop).
	private static boolean mentionsSpecial(LispVal tree, Set<String> specials) {
		if (tree instanceof LispSymbol sym) {
			return specials.contains(sym.name());
		}
		for (LispVal node = tree; node instanceof LispCons cell; node = cell.cdr()) {
			if (mentionsSpecial(cell.car(), specials)) {
				return true;
			}
			if (cell.cdr() instanceof LispSymbol dotted && specials.contains(dotted.name())) {
				return true;
			}
		}
		return false;
	}

	private static boolean bindsSpecial(LispVal bindings, Set<String> specials) {
		if (!(bindings instanceof LispCons list) || !list.isProperList()) {
			return false;
		}
		for (LispVal binding : list.toList()) {
			LispVal name = binding instanceof LispCons pair ? pair.car() : binding;
			if (name instanceof LispSymbol sym && specials.contains(sym.name())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The cycles of a directed graph: its strongly connected components of two nodes or
	 * more (Tarjan's, iterative), each in ascending node order, in the order of their
	 * first node.
	 * @param edges each node's successors
	 * @return the cycles
	 */
	static List<List<Integer>> cycles(List<? extends Set<Integer>> edges) {
		int n = edges.size();
		int[] order = new int[n];
		int[] low = new int[n];
		Arrays.fill(order, -1);
		boolean[] onStack = new boolean[n];
		Deque<Integer> stack = new ArrayDeque<>();
		List<List<Integer>> successors = new ArrayList<>(n);
		for (Set<Integer> out : edges) {
			successors.add(new ArrayList<>(out));
		}
		int counter = 0;
		List<List<Integer>> found = new ArrayList<>();
		for (int root = 0; root < n; root++) {
			if (order[root] >= 0) {
				continue;
			}
			Deque<int[]> calls = new ArrayDeque<>();
			order[root] = low[root] = counter++;
			stack.push(root);
			onStack[root] = true;
			calls.push(new int[] { root, 0 });
			while (!calls.isEmpty()) {
				int[] frame = calls.peek();
				int v = frame[0];
				List<Integer> next = successors.get(v);
				if (frame[1] < next.size()) {
					int w = next.get(frame[1]++);
					if (order[w] < 0) {
						order[w] = low[w] = counter++;
						stack.push(w);
						onStack[w] = true;
						calls.push(new int[] { w, 0 });
					}
					else if (onStack[w]) {
						low[v] = Math.min(low[v], order[w]);
					}
					continue;
				}
				calls.pop();
				if (!calls.isEmpty()) {
					int u = calls.peek()[0];
					low[u] = Math.min(low[u], low[v]);
				}
				if (low[v] == order[v]) {
					List<Integer> component = new ArrayList<>();
					int w;
					do {
						w = stack.pop();
						onStack[w] = false;
						component.add(w);
					}
					while (w != v);
					if (component.size() > 1) {
						component.sort(null);
						found.add(component);
					}
				}
			}
		}
		found.sort(java.util.Comparator.comparingInt(component -> component.get(0)));
		return found;
	}

	// --- the jump
	// --------------------------------------------------------------------------

	/**
	 * Emits a tail call to a sibling as a jump: the arguments evaluate, left to right,
	 * into the sibling's physical parameter sequence ({@link JvmPhysicalArgs}); for a
	 * {@code labels} function its closure -- the variable's value, which nothing but the
	 * expansion assigns -- replaces the environment; then the arguments land in the
	 * parameter slots and control branches to a label the group binds when it is laid out
	 * ({@link #finish}).
	 * @param target the sibling
	 * @param args the call's arguments
	 * @param ctx the member being emitted, its stack empty
	 * @param className the class being generated
	 * @return whether the call was emitted (as a jump); false leaves nothing emitted
	 */
	static boolean emitJump(Member target, List<LispVal> args, JvmLispCompiler.Ctx ctx, String className) {
		Member from = ctx.tailMember;
		if (from == null || !target.known() || !target.accepts(args.size())) {
			// A count the lambda list rules out keeps the call, which signals; so does a
			// sibling whose lambda list is not known.
			return false;
		}
		List<Runnable> emitters = new ArrayList<>(args.size());
		for (LispVal arg : args) {
			emitters.add(() -> JvmExprCompiler.compileExpr(arg, ctx, className));
		}
		JvmPhysicalArgs.emit(ctx, className, target.required(), target.optionals(), target.variadic(), emitters);
		String variable = target.variable;
		if (variable != null) {
			JvmExprCompiler.compileExpr(new LispSymbol(variable), ctx, className);
			ctx.body.checkcast(ctx.objectArrayClass);
			ctx.body.astore(ctx.closureEnvSlot);
		}
		if (ctx.depthSlot >= 0) {
			// The value-tail depth goes on unchanged -- a jump adds no frame -- into the
			// sibling's slot, which its parameter count places (JvmTailBounce). The
			// arguments are on the stack, so no store below can clobber it first.
			ctx.body.iload(JvmTailBounce.depthSlot(ctx));
			ctx.body.istore(target.depthSlot());
		}
		for (int i = target.paramCount() - 1; i >= 0; i--) {
			ctx.body.astore(target.firstParamSlot() + i);
		}
		MethodCode.Label exit = ctx.body.newLabel();
		ctx.body.goto_(exit);
		from.jumps.add(new Jump(exit, target, ctx.siteCurrent));
		return true;
	}

	/**
	 * The sibling a {@code labels} member's {@code (funcall var ...)} names, when it is
	 * one.
	 * @param designator the funcall's designator
	 * @param ctx the member being emitted
	 * @return the sibling, or null
	 */
	static @Nullable Member siblingByVariable(LispVal designator, JvmLispCompiler.Ctx ctx) {
		Member from = ctx.tailMember;
		if (from == null || !from.group.closures || !(designator instanceof LispSymbol sym)) {
			return null;
		}
		String name = sym.name();
		// The sibling's variable as this closure captured it, which no binding here
		// shadows.
		if (!ctx.captures.containsKey(name) || ctx.locals.containsKey(name) || ctx.rawLocals.containsKey(name)) {
			return null;
		}
		for (Member member : from.group.members) {
			if (member != from && name.equals(member.variable)) {
				return member;
			}
		}
		return null;
	}

	/**
	 * The sibling a defun member's direct call names, when it is one.
	 * @param callee the callee's registry entry
	 * @param ctx the member being emitted
	 * @return the sibling, or null
	 */
	static @Nullable Member siblingByFunction(JvmLispCompiler.FunctionInfo callee, JvmLispCompiler.Ctx ctx) {
		Member from = ctx.tailMember;
		if (from == null || from.group.closures) {
			return null;
		}
		for (Member member : from.group.members) {
			if (member != from && member.function == callee) {
				return member;
			}
		}
		return null;
	}

	// --- laying the group out
	// --------------------------------------------------------------

	/**
	 * {@return whether every member is compiled, so the group can be laid out}
	 */
	boolean complete() {
		for (Member member : this.members) {
			if (member.ctx == null) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A member's method as its group lays it out: the code of the members its jumps
	 * reach, its own first.
	 *
	 * @param body the method's code
	 * @param lines its line table
	 */
	record LaidOut(MethodCode body, List<ClassDefinition.Line> lines) {
	}

	/**
	 * Lays the group out once every program body is compiled. A member a call names --
	 * {@code called}: the program's own code, or a Java caller, calls its method directly
	 * -- gets the group ROOTED at it as its method: its own code first, at the method's
	 * entry, then the code of every member its jumps reach, each jump branching to its
	 * target's first instruction ({@link JvmLispCompiler.Ctx#laidOut}). The method's
	 * entry is the one way into every cycle through the members, so its control flow
	 * stays reducible -- a cycle with a second entry is what Graal's on-stack replacement
	 * cannot compile ("Multiple OnStackReplacementNodes generated"), which leaves a
	 * long-running loop interpreted. When the jumps themselves form such a cycle (one
	 * that does not pass the root), that root's method dispatches on a member index
	 * instead: each jump stores its target's index and re-enters a switch, which is then
	 * the cycle's one entry.
	 *
	 * <p>
	 * A member nothing calls directly -- a method function only its generic function's
	 * dispatcher reaches, a {@code labels} function, which is only ever called through
	 * the arity dispatcher -- keeps its own code, each jump landing on an epilogue that
	 * calls the sibling's method: one frame on the way into a rooted method, whose loop
	 * then runs in constant stack. So that no cycle runs through such members alone, a
	 * member of each cycle they would form is rooted too. (A rooted copy of every member
	 * would put the whole group into every method a dispatcher names.)
	 *
	 * <p>
	 * When a rooted method would cross {@code limit} bytecodes -- or a member was never
	 * compiled -- every member keeps its own code that way.
	 * @param className the class being generated
	 * @param limit the most bytecodes a rooted method may take
	 * @param called whether a member's method is called directly
	 */
	void finish(String className, int limit, java.util.function.Predicate<Member> called) {
		Map<Member, LaidOut> rooted = new IdentityHashMap<>();
		if (this.complete()) {
			for (Member root : this.roots(called)) {
				LaidOut laidOut = this.rootAt(root);
				if (laidOut.body().size() > limit) {
					rooted.clear();
					break;
				}
				rooted.put(root, laidOut);
			}
		}
		// The rooted layouts copy the members' bodies as compiled; only then may the
		// others gain their epilogues.
		for (Member member : this.members) {
			JvmLispCompiler.Ctx ctx = member.ctx;
			if (ctx == null) {
				continue;
			}
			LaidOut laidOut = rooted.get(member);
			if (laidOut != null) {
				ctx.laidOut = laidOut;
			}
			else {
				this.emitEpilogues(member, className);
			}
		}
		if (Boolean.getBoolean("rontolisp.jvm.debug-tail-groups")) {
			StringBuilder report = new StringBuilder("[tail-group] ").append(this.closures ? "labels" : "defuns");
			for (Member member : this.members) {
				LaidOut laidOut = rooted.get(member);
				report.append(' ')
					.append(member.name)
					.append(laidOut == null ? "" : " (rooted, " + laidOut.body().size() + " B)");
			}
			System.err.println(report);
		}
	}

	/**
	 * The members whose methods are the group rooted at them, in member order: those a
	 * call names, then, while the jumps among the rest still form a cycle, the first
	 * member of each.
	 */
	private List<Member> roots(java.util.function.Predicate<Member> called) {
		Set<Member> roots = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (Member member : this.members) {
			if (called.test(member)) {
				roots.add(member);
			}
		}
		while (true) {
			List<Member> rest = new ArrayList<>();
			Map<Member, Integer> position = new IdentityHashMap<>();
			for (Member member : this.members) {
				if (!roots.contains(member)) {
					position.put(member, rest.size());
					rest.add(member);
				}
			}
			List<Set<Integer>> edges = new ArrayList<>(rest.size());
			for (Member member : rest) {
				Set<Integer> out = new LinkedHashSet<>();
				for (Jump jump : member.jumps) {
					Integer target = position.get(jump.target());
					if (target != null) {
						out.add(target);
					}
				}
				edges.add(out);
			}
			List<List<Integer>> cycles = cycles(edges);
			if (cycles.isEmpty()) {
				List<Member> ordered = new ArrayList<>();
				for (Member member : this.members) {
					if (roots.contains(member)) {
						ordered.add(member);
					}
				}
				return ordered;
			}
			for (List<Integer> cycle : cycles) {
				roots.add(rest.get(cycle.get(0)));
			}
		}
	}

	/**
	 * The group rooted at {@code root}: the members its jumps reach, its own code first,
	 * laid out of the members' compiled bodies, which stay as they were.
	 */
	private LaidOut rootAt(Member root) {
		List<Member> order = this.reachable(root);
		Map<Member, Integer> position = new IdentityHashMap<>();
		for (Member member : order) {
			position.put(member, position.size());
		}
		boolean direct = reducible(order, position);
		MethodCode body = new MethodCode();
		MethodCode.Label[] heads = new MethodCode.Label[order.size()];
		for (int i = 0; i < heads.length; i++) {
			heads[i] = body.newLabel();
		}
		MethodCode.@Nullable Label top = null;
		MethodCode.@Nullable Label[] reentries = new MethodCode.Label[order.size()];
		int memberSlot = -1;
		if (!direct) {
			// Past every member's parameters -- and value-tail depth -- which a jump has
			// just stored when it stores the index.
			int params = 0;
			for (Member member : order) {
				params = Math.max(params, member.paramCount());
			}
			memberSlot = (this.closures ? 1 : 0) + params + 1;
			body.iconst_0().istore(memberSlot);
			top = body.newBoundLabel();
			emitEntry(body, memberSlot, heads);
		}
		List<ClassDefinition.Line> lines = new ArrayList<>();
		for (int i = 0; i < order.size(); i++) {
			JvmLispCompiler.Ctx ctx = java.util.Objects.requireNonNull(order.get(i).ctx);
			int base = body.position();
			body.labelBinding(heads[i]);
			Map<MethodCode.Label, MethodCode.Label> landing = new IdentityHashMap<>();
			for (Jump jump : order.get(i).jumps) {
				int target = java.util.Objects.requireNonNull(position.get(jump.target()));
				MethodCode.Label land = heads[target];
				if (!direct) {
					MethodCode.Label reentry = reentries[target];
					if (reentry == null) {
						reentry = body.newLabel();
						reentries[target] = reentry;
					}
					land = reentry;
				}
				landing.put(jump.exit(), land);
			}
			body.append(ctx.body, landing);
			if (ctx.bouncingBodies.contains(ctx.body)) {
				// The layout holds a member's bounce, so the class's trampoline must be
				// written while this method is kept (JvmTailBounce).
				ctx.bouncingBodies.add(body);
			}
			List<ClassDefinition.Line> own = ctx.lines();
			// A member whose first instruction has no site of its own must not report
			// the site the member before it ended in.
			if (!lines.isEmpty() && lines.getLast().lineNumber() != JvmSourceSites.NO_SITE
					&& (own.isEmpty() || own.getFirst().position() > 0)) {
				lines.add(new ClassDefinition.Line(base, JvmSourceSites.NO_SITE));
			}
			for (ClassDefinition.Line line : own) {
				lines.add(new ClassDefinition.Line(base + line.position(), line.lineNumber()));
			}
		}
		for (int i = 0; i < reentries.length; i++) {
			MethodCode.Label reentry = reentries[i];
			if (reentry != null) {
				body.labelBinding(reentry);
				body.loadConstant(i).istore(memberSlot).goto_(java.util.Objects.requireNonNull(top));
			}
		}
		body.checkComplete();
		return new LaidOut(body, lines);
	}

	/** The members {@code root}'s jumps reach, itself first, then in member order. */
	private List<Member> reachable(Member root) {
		Set<Member> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<Member> pending = new ArrayDeque<>();
		seen.add(root);
		pending.add(root);
		while (!pending.isEmpty()) {
			for (Jump jump : pending.removeFirst().jumps) {
				if (seen.add(jump.target())) {
					pending.add(jump.target());
				}
			}
		}
		List<Member> order = new ArrayList<>();
		order.add(root);
		for (Member member : this.members) {
			if (member != root && seen.contains(member)) {
				order.add(member);
			}
		}
		return order;
	}

	/**
	 * Whether the members' jumps, entered at the first of {@code order}, make reducible
	 * control flow: every edge back to a member a depth-first walk from the root is still
	 * exploring returns to one that dominates where it leaves from. Each member is
	 * entered only at its first instruction, so the members' graph decides it.
	 */
	private static boolean reducible(List<Member> order, Map<Member, Integer> position) {
		int n = order.size();
		List<List<Integer>> successors = new ArrayList<>(n);
		List<Set<Integer>> predecessors = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			predecessors.add(new LinkedHashSet<>());
		}
		for (int i = 0; i < n; i++) {
			Set<Integer> out = new LinkedHashSet<>();
			for (Jump jump : order.get(i).jumps) {
				int target = java.util.Objects.requireNonNull(position.get(jump.target()));
				out.add(target);
				predecessors.get(target).add(i);
			}
			successors.add(new ArrayList<>(out));
		}
		// Dominators, iterated to the fixed point.
		BitSet[] dominators = new BitSet[n];
		for (int i = 0; i < n; i++) {
			dominators[i] = new BitSet(n);
			if (i == 0) {
				dominators[i].set(0);
			}
			else {
				dominators[i].set(0, n);
			}
		}
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int v = 1; v < n; v++) {
				BitSet next = new BitSet(n);
				next.set(0, n);
				for (int p : predecessors.get(v)) {
					next.and(dominators[p]);
				}
				next.set(v);
				if (!next.equals(dominators[v])) {
					dominators[v] = next;
					changed = true;
				}
			}
		}
		// Each edge back onto the walk's path must return to a dominator.
		boolean[] onPath = new boolean[n];
		boolean[] visited = new boolean[n];
		Deque<int[]> walk = new ArrayDeque<>();
		walk.push(new int[] { 0, 0 });
		visited[0] = true;
		onPath[0] = true;
		while (!walk.isEmpty()) {
			int[] frame = walk.peek();
			List<Integer> next = successors.get(frame[0]);
			if (frame[1] < next.size()) {
				int w = next.get(frame[1]++);
				if (onPath[w]) {
					if (!dominators[frame[0]].get(w)) {
						return false;
					}
				}
				else if (!visited[w]) {
					visited[w] = true;
					onPath[w] = true;
					walk.push(new int[] { w, 0 });
				}
				continue;
			}
			onPath[frame[0]] = false;
			walk.pop();
		}
		return true;
	}

	/**
	 * The switch of a layout that dispatches: the member index picks the head. Two
	 * members test it against zero, the first falling through to its head; more bisect
	 * the index range.
	 */
	private static void emitEntry(MethodCode body, int memberSlot, MethodCode.Label[] heads) {
		if (heads.length == 2) {
			body.iload(memberSlot).ifne(heads[1]);
			return;
		}
		bisect(body, memberSlot, heads, 0, heads.length - 1);
	}

	private static void bisect(MethodCode body, int memberSlot, MethodCode.Label[] heads, int lo, int hi) {
		if (lo == hi) {
			body.goto_(heads[lo]);
			return;
		}
		int mid = (lo + hi) >>> 1;
		MethodCode.Label upper = body.newLabel();
		body.iload(memberSlot).loadConstant(mid + 1).if_icmpge(upper);
		bisect(body, memberSlot, heads, lo, mid);
		body.labelBinding(upper);
		bisect(body, memberSlot, heads, mid + 1, hi);
	}

	/**
	 * Binds each of a member's jump labels to an epilogue in its own method: the
	 * arguments the jump stored -- and a closure's environment -- reloaded and passed to
	 * the sibling's method, whose answer is the member's, at the site of the call.
	 */
	private void emitEpilogues(Member member, String className) {
		JvmLispCompiler.Ctx ctx = java.util.Objects.requireNonNull(member.ctx);
		for (Jump jump : member.jumps) {
			ctx.body.labelBinding(jump.exit());
			ctx.restoreSite(jump.site());
			Member target = jump.target();
			JvmLispCompiler.FunctionInfo callee = java.util.Objects.requireNonNull(target.function);
			if (this.closures) {
				ctx.body.aload(ctx.closureEnvSlot);
			}
			for (int i = 0; i < target.paramCount(); i++) {
				ctx.body.aload(target.firstParamSlot() + i);
			}
			if (callee.bounceVisible()) {
				// The depth the jump stored: a call handing its bounce on adds no value
				// tail (JvmTailBounce).
				ctx.body.iload(target.depthSlot());
			}
			ctx.body.invokestatic(callee.methodref());
			if (callee.bounceVisible() && !ctx.passesBounces) {
				// The sibling may answer a bounce, and this is the member's tail: its own
				// callers drive the bounce whenever they check for one -- a lambda's
				// always, a defun's when the group's may bounce, which this callee's
				// does (JvmTailBounce).
				JvmTailBounce.emitUnwrap(ctx, className);
			}
			ctx.body.areturn();
		}
	}

}
