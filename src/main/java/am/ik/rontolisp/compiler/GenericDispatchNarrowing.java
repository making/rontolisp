package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.ArgumentShapes.Shape;
import am.ik.rontolisp.macro.DispatchNarrower;
import org.jspecify.annotations.Nullable;

/**
 * Drops the dispatcher branches of a generic function that NO call site in the program
 * can select -- the defgeneric twin of {@link DeadTypeBranchPruner}: that pass prunes a
 * {@code typecase} clause no argument shape can satisfy, this one prunes a dispatcher
 * branch the same way, and the tree-shakers then drop the unreferenced method-body defuns
 * (and everything only they reach) by the reachability they already have. The motivating
 * case is chipz's {@code decompress}: the program's one call is
 * {@code (chipz:decompress nil 'chipz:gzip <ub8-vector>)}, and the artifact carried all
 * 18 {@code %DECOMPRESS} variants for it ({@code .todo}-332's inventory).
 *
 * <h2>What makes a drop sound</h2>
 *
 * The same whole-program agreement {@link DeadTypeBranchPruner} needs, plus the edges
 * specific to dispatch:
 * <ul>
 * <li>the analysis DECLINES entirely under {@link RuntimeNameProducers#anyNameResolvable}
 * (a data evaluator can call anything with anything) and for async programs (the async
 * lowering synthesizes closures this AST scan cannot attribute);</li>
 * <li>a generic taken as a VALUE -- {@code #'g} outside the direct {@code funcall} /
 * {@code apply} target position, its name in quoted data or a macro call's arguments, or
 * (in a program holding a symbol BUILDER) a string/keyword literal spelling it -- keeps
 * every branch. The vocabulary deliberately mirrors the funcall-dispatch gate's probes
 * ({@code .kb/optimize-dead-code-elimination.md}): a name that gate could resolve into a
 * registry row at run time is a name this pass never narrows;</li>
 * <li>generics whose call sites are SYNTHESIZED after this analysis runs are excluded
 * wholesale: {@code cl}-symbol names (print-object, the initialization protocol, every
 * shadowable built-in), accessor/writer generics ({@code structAccessors} entries and
 * slot base names -- the ambiguous {@code slot-value} fallback calls a reader generic the
 * AST never spells), {@code %}-internal names, the gray-stream packages, any generic with
 * a nested (non-top-level) method defun, and short-form method combinations;</li>
 * <li>satisfiability leans permissive exactly like {@link ArgumentShapes#maySatisfy}: an
 * EQL specializer, an unrecognized type name, and an INSTANCE shape against any type name
 * keep the branch.</li>
 * </ul>
 *
 * <h2>The liveness fixpoint</h2>
 *
 * A site inside a method body counts only while that method is selectable, and a site
 * inside a helper defun counts only while the helper is reachable -- chipz's
 * {@code %decompress-from-pathname} calls {@code decompress} with unknown shapes, but
 * only the (dead) pathname methods call IT. The fixpoint is deliberately shallow: the
 * only units allowed to be dead are the method-body defuns of narrowable generics and the
 * plain defuns whose every head-position reference sits inside such method bodies;
 * everything else is live from the start, because machinery emitted after this analysis
 * (expansions of {@code make-instance} / {@code setf} / {@code slot-value}, the appended
 * runtimes, the builtin wrappers) can call constructors and internal helpers by names the
 * analyzed AST never spells. For the same reason, parameter shapes are joined over call
 * sites only for those two unit kinds -- their callers are all visible by construction --
 * and every other parameter stays UNKNOWN.
 */
public final class GenericDispatchNarrowing implements DispatchNarrower {

	/**
	 * Creates a narrower with no analysis yet: every branch is selectable until
	 * {@link #analyze} runs.
	 */
	public GenericDispatchNarrowing() {
	}

	/** Canonical generic name -> live call-site shape vectors. Null until analyzed. */
	private @Nullable Map<String, List<List<Shape>>> narrowed;

	@Override
	public boolean branchSelectable(String genericName, List<ClosRegistry.Specializer> specializers) {
		Map<String, List<List<Shape>>> map = this.narrowed;
		if (map == null) {
			return true;
		}
		List<List<Shape>> vectors = map.get(ClosRegistry.normalize(genericName));
		if (vectors == null) {
			return true;
		}
		return anySelects(vectors, specializers);
	}

	private static boolean anySelects(List<List<Shape>> vectors, List<ClosRegistry.Specializer> specializers) {
		for (List<Shape> vector : vectors) {
			if (maySelect(vector, specializers)) {
				return true;
			}
		}
		return false;
	}

	/** Whether one call site's argument shapes may satisfy a branch's specializers. */
	private static boolean maySelect(List<Shape> vector, List<ClosRegistry.Specializer> specializers) {
		for (int i = 0; i < specializers.size(); i++) {
			Shape shape = i < vector.size() ? vector.get(i) : Shape.UNKNOWN;
			if (!maySatisfySpecializer(shape, specializers.get(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a value of this shape could satisfy one parameter specializer. Permissive
	 * on everything undecidable: EQL compares values, not shapes; a CLASS is satisfied
	 * only by an instance (no literal shape can become one); a TYPE name defers to
	 * {@link ArgumentShapes#maySatisfy}, except that an INSTANCE may satisfy ANY type
	 * name -- a gray-stream instance IS a {@code stream} here, and a struct-name
	 * specializer (which the shape lattice does not know) tests instances too.
	 */
	private static boolean maySatisfySpecializer(Shape shape, ClosRegistry.Specializer spec) {
		return switch (spec.kind()) {
			case DEFAULT, EQL -> true;
			case CLASS -> shape == Shape.UNKNOWN || shape == Shape.INSTANCE;
			case TYPE -> shape == Shape.UNKNOWN || shape == Shape.INSTANCE
					|| ArgumentShapes.maySatisfy(shape, new LispSymbol(Objects.requireNonNull(spec.name())));
		};
	}

	@Override
	public void analyze(List<LispVal> program, ClosRegistry registry, Map<String, Integer> structAccessors) {
		if (RuntimeNameProducers.anyNameResolvable(program) || usesAsync(program)) {
			return;
		}
		this.narrowed = new Analysis(program, registry, structAccessors).run();
	}

	/** The async operators whose lowering this AST-level analysis cannot follow. */
	private static boolean usesAsync(List<LispVal> program) {
		for (LispVal form : program) {
			if (mentionsAsyncHead(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentionsAsyncHead(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head) {
			String member = memberOf(head.name());
			if (LispNames.ASYNC_DEFUN.equals(member) || LispNames.ASYNC_LAMBDA.equals(member)
					|| LispNames.AWAIT.equals(member)) {
				return true;
			}
		}
		return mentionsAsyncHead(cons.car()) || mentionsAsyncHead(cons.cdr());
	}

	private static String memberOf(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static String packageOf(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? "" : qn.pkg();
	}

	/** Site vectors per generic are capped; past the cap the generic stays open. */
	private static final int MAX_VECTORS = 64;

	/** Fixpoint rounds are capped; past the cap the whole analysis fails open. */
	private static final int MAX_ROUNDS = 25;

	/** The packages whose generics are dispatched by rewritten/spliced machinery. */
	private static final Set<String> EXCLUDED_PACKAGES = Set.of("GRAY-STREAMS", "TRIVIAL-GRAY-STREAMS", "SB-GRAY");

	/** One whole-program analysis run. */
	private static final class Analysis {

		private final List<LispVal> program;

		private final ClosRegistry registry;

		private final Map<String, Shape> returns;

		private final Map<String, Shape> globals;

		private final boolean symbolBuilder;

		/** Top-level defuns by exact name. */
		private final Map<String, Unit> units = new LinkedHashMap<>();

		/** Member name -> units spelled with it (for value-reference escapes). */
		private final Map<String, List<Unit>> unitsByMember = new HashMap<>();

		/** Canonical generic name -> narrowing state (candidates only). */
		private final Map<String, GenericState> candidates = new LinkedHashMap<>();

		/** GenericInfo identity -> state, for call-head resolution via findGeneric. */
		private final Map<ClosRegistry.GenericInfo, GenericState> byInfo = new IdentityHashMap<>();

		private final Set<String> macroNames = new HashSet<>();

		/** The non-defun top-level code the fixpoint walks as always-live roots. */
		private final List<LispVal> rootForms = new ArrayList<>();

		/** Registry-carried data whose symbols must escape (deftype expansions). */
		private final List<LispVal> escapeOnlyData = new ArrayList<>();

		private final Set<String> rootShadowed;

		private Analysis(List<LispVal> program, ClosRegistry registry, Map<String, Integer> structAccessors) {
			this.program = program;
			this.registry = registry;
			this.returns = ArgumentShapes.returnShapes(program);
			this.globals = ArgumentShapes.globals(program, this.returns);
			this.symbolBuilder = RuntimeNameProducers.anySymbolBuilder(program);
			this.collectUnits();
			this.rootShadowed = ArgumentShapes.shadowedNames(this.rootForms);
			this.collectCandidates(structAccessors);
		}

		private static final class Unit {

			private final String name;

			private final @Nullable LispVal lambdaList;

			private final List<LispVal> body;

			private final Set<String> shadowed;

			/**
			 * Null = parameters unknown; set only for units whose callers are all
			 * visible.
			 */
			private @Nullable List<Shape> paramShapes;

			private boolean live;

			/**
			 * Referenced as a value (quoted data, {@code #'}, builder-visible literal).
			 */
			private boolean valueRef;

			/** Every head-position reference sits inside a candidate method body. */
			private boolean confined;

			/** The candidate generic whose method body this defun is, if any. */
			private @Nullable GenericState methodOf;

			private ClosRegistry.@Nullable MethodInfo method;

			private Unit(String name, @Nullable LispVal lambdaList, List<LispVal> body) {
				this.name = name;
				this.lambdaList = lambdaList;
				this.body = body;
				this.shadowed = ArgumentShapes.shadowedNames(body);
			}

		}

		private static final class GenericState {

			private final ClosRegistry.GenericInfo info;

			private boolean escaped;

			/** A call-next-method body may re-call the chain with NEW arguments. */
			private final boolean usesNext;

			private final List<List<Shape>> vectors = new ArrayList<>();

			private GenericState(ClosRegistry.GenericInfo info) {
				this.info = info;
				this.usesNext = info.methods().values().stream().anyMatch(ClosRegistry.MethodInfo::usesNext);
			}

		}

		private void collectUnits() {
			for (LispVal form : this.program) {
				if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
						&& cons.cdr() instanceof LispCons rest) {
					if (LispNames.DEFUN.equals(head.name()) && rest.car() instanceof LispSymbol name
							&& rest.cdr() instanceof LispCons afterName) {
						Unit unit = new Unit(name.name(), afterName.car(), listElements(afterName.cdr()));
						this.units.put(name.name(), unit);
						this.unitsByMember.computeIfAbsent(memberOf(name.name()), k -> new ArrayList<>()).add(unit);
						continue;
					}
					if (LispNames.DEFMACRO.equals(head.name()) && rest.car() instanceof LispSymbol name) {
						this.macroNames.add(name.name());
						this.macroNames.add(memberOf(name.name()));
					}
				}
				this.rootForms.add(form);
			}
			// Condition :report forms run from signal machinery synthesized after this
			// analysis; walk them as always-live root code. Deftype expansions are
			// spliced as type TESTS wherever a runtime typep needs them; their symbols
			// escape (a (satisfies f) names a function this AST never calls).
			this.rootForms.addAll(this.registry.conditionReports().values());
			for (String name : this.registry.deftypeNames()) {
				LispVal expansion = this.registry.findDeftype(name);
				if (expansion != null) {
					this.escapeOnlyData.add(expansion);
				}
			}
		}

		private void collectCandidates(Map<String, Integer> structAccessors) {
			Set<String> accessorMembers = new HashSet<>();
			for (String accessor : structAccessors.keySet()) {
				accessorMembers.add(memberOf(accessor));
			}
			Set<String> slotMembers = new HashSet<>();
			for (ClosRegistry.ClassInfo info : this.registry.classes().values()) {
				for (ClosRegistry.SlotSpec slot : info.slots()) {
					slotMembers.add(memberOf(slot.baseName()));
				}
			}
			Set<String> lowered = ShadowedBuiltins.loweredBuiltinFunctions();
			for (ClosRegistry.GenericInfo info : this.registry.generics().values()) {
				String member = memberOf(info.name());
				if (member.startsWith("%") || PackageRegistry.isClSymbol(member) || lowered.contains(member)
						|| lowered.contains(info.name()) || info.methodCombination() != null
						|| accessorMembers.contains(member) || slotMembers.contains(member)
						|| EXCLUDED_PACKAGES.contains(packageOf(info.name())) || this.units.containsKey(info.name())) {
					continue;
				}
				// Every method body must be an attributable top-level defun; a nested
				// defmethod compiles to a closure setq this scan cannot follow.
				GenericState state = new GenericState(info);
				boolean attributable = true;
				for (ClosRegistry.MethodInfo method : info.methods().values()) {
					Unit unit = this.units.get(method.functionName());
					if (unit == null) {
						attributable = false;
						break;
					}
					unit.methodOf = state;
					unit.method = method;
				}
				if (!attributable) {
					for (Unit unit : this.units.values()) {
						if (unit.methodOf == state) {
							unit.methodOf = null;
							unit.method = null;
						}
					}
					continue;
				}
				this.candidates.put(ClosRegistry.normalize(info.name()), state);
				this.byInfo.put(info, state);
			}
		}

		private @Nullable Map<String, List<List<Shape>>> run() {
			if (this.candidates.isEmpty()) {
				return null;
			}
			// Phase 1: the global escape scan, over the WHOLE program (dead code
			// included): the funcall-dispatch gate's probes read the emitted module, and
			// an emitted-but-dead body's literals still arm them.
			for (LispVal form : this.program) {
				this.escapeScan(form);
			}
			for (LispVal form : this.registry.conditionReports().values()) {
				this.escapeScan(form);
			}
			for (LispVal datum : this.escapeOnlyData) {
				this.escapeQuotedData(datum);
			}
			// Phase 2: classify which plain defuns may be dead, and seed liveness.
			Map<String, Set<Unit>> callers = this.callerIndex();
			for (Unit unit : this.units.values()) {
				if (unit.methodOf != null) {
					unit.live = unit.live || unit.methodOf.escaped;
					continue;
				}
				Set<Unit> callingUnits = callers.get(unit.name);
				unit.confined = !unit.valueRef && callingUnits != null && !callingUnits.isEmpty()
						&& callingUnits.stream().allMatch(caller -> caller.methodOf != null);
				unit.live = unit.live || !unit.confined;
			}
			// Phase 3: the round-based fixpoint. Everything is monotone -- the live set
			// grows, parameter shapes and site vectors only widen -- so the state
			// stabilizes; the round cap is a backstop that fails toward "no narrowing".
			for (int round = 0; round < MAX_ROUNDS; round++) {
				if (!this.round()) {
					Map<String, List<List<Shape>>> result = new HashMap<>();
					for (Map.Entry<String, GenericState> entry : this.candidates.entrySet()) {
						GenericState state = entry.getValue();
						if (!state.escaped) {
							result.put(entry.getKey(), List.copyOf(state.vectors));
						}
					}
					return result.isEmpty() ? null : result;
				}
			}
			return null;
		}

		/** Head-position references of each defun name, by the unit containing them. */
		private Map<String, Set<Unit>> callerIndex() {
			Map<String, Set<Unit>> callers = new HashMap<>();
			for (Unit unit : this.units.values()) {
				for (LispVal form : unit.body) {
					this.collectHeads(form, unit, callers);
				}
			}
			for (LispVal form : this.rootForms) {
				this.collectHeads(form, null, callers);
			}
			return callers;
		}

		private void collectHeads(LispVal form, @Nullable Unit within, Map<String, Set<Unit>> callers) {
			if (!(form instanceof LispCons cons)) {
				return;
			}
			if (cons.car() instanceof LispSymbol head) {
				if (LispNames.QUOTE.equals(head.name())) {
					return;
				}
				String target = head.name();
				if ((LispNames.FUNCALL.equals(target) || LispNames.APPLY.equals(target))
						&& cons.cdr() instanceof LispCons targetCell) {
					String named = targetName(targetCell.car());
					if (named != null) {
						target = named;
					}
				}
				Unit callee = this.units.get(target);
				if (callee != null) {
					// A root-position reference is represented by the callee itself
					// (methodOf null), which fails the all-methods test below.
					callers.computeIfAbsent(target, k -> new HashSet<>()).add(within != null ? within : callee);
				}
			}
			this.collectHeads(cons.car(), within, callers);
			LispVal rest = cons.cdr();
			while (rest instanceof LispCons cell) {
				this.collectHeads(cell.car(), within, callers);
				rest = cell.cdr();
			}
		}

		/** The symbol a literal funcall/apply target names, or null. */
		private static @Nullable String targetName(LispVal target) {
			if (target instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& cons.cdr() instanceof LispCons cell && cell.car() instanceof LispSymbol named
					&& (LispNames.FUNCTION.equals(head.name()) || LispNames.QUOTE.equals(head.name()))) {
				return named.name();
			}
			return null;
		}

		// -------------------------------------------------------------------------
		// Phase 1: escapes
		// -------------------------------------------------------------------------

		private void escapeScan(LispVal form) {
			if (form instanceof LispString str) {
				if (this.symbolBuilder) {
					this.escapeSpelledName(str.value());
				}
				return;
			}
			if (form instanceof LispSymbol sym) {
				if (sym.isKeyword() && this.symbolBuilder) {
					this.escapeMember(memberOf(sym.name().substring(1)));
				}
				return;
			}
			if (form instanceof LispArray array) {
				for (LispVal element : array.data()) {
					this.escapeQuotedData(element);
				}
				return;
			}
			if (form instanceof am.ik.rontolisp.LispInstance instance) {
				// A folded #S(...) literal: its slots are data like a quoted list's.
				for (int i = 0; i < instance.slotCount(); i++) {
					this.escapeQuotedData(instance.slot(i));
				}
				return;
			}
			if (!(form instanceof LispCons cons)) {
				return;
			}
			if (cons.car() instanceof LispSymbol head) {
				String name = head.name();
				if (LispNames.QUOTE.equals(name)) {
					this.escapeQuotedData(cons.cdr());
					return;
				}
				if (LispNames.FUNCTION.equals(name)) {
					if (cons.cdr() instanceof LispCons cell && cell.car() instanceof LispSymbol named) {
						this.escapeMember(memberOf(named.name()));
					}
					else {
						this.escapeQuotedData(cons.cdr());
					}
					return;
				}
				if (LispNames.MACROLET.equals(name) || LispNames.SYMBOL_MACROLET.equals(name)
						|| this.macroNames.contains(name) || this.macroNames.contains(memberOf(name))) {
					// A macro's arguments (and a macrolet's whole scope) are template
					// fragments an expansion this scan never sees may re-arrange.
					this.escapeQuotedData(cons.cdr());
					return;
				}
				if ((LispNames.FUNCALL.equals(name) || LispNames.APPLY.equals(name))
						&& cons.cdr() instanceof LispCons targetCell && targetName(targetCell.car()) != null) {
					// The literal target is a call site (recorded by the fixpoint walk),
					// not a value escape.
					LispVal rest = targetCell.cdr();
					while (rest instanceof LispCons cell) {
						this.escapeScan(cell.car());
						rest = cell.cdr();
					}
					return;
				}
			}
			this.escapeScan(cons.car());
			LispVal rest = cons.cdr();
			while (rest instanceof LispCons cell) {
				this.escapeScan(cell.car());
				rest = cell.cdr();
			}
		}

		/** Every symbol/string in a quoted datum could reach funcall as a designator. */
		private void escapeQuotedData(LispVal datum) {
			if (datum instanceof LispSymbol sym) {
				if (sym.isKeyword()) {
					if (this.symbolBuilder) {
						this.escapeMember(memberOf(sym.name().substring(1)));
					}
				}
				else {
					this.escapeMember(memberOf(sym.name()));
				}
				return;
			}
			if (datum instanceof LispString str) {
				if (this.symbolBuilder) {
					this.escapeSpelledName(str.value());
				}
				return;
			}
			if (datum instanceof LispArray array) {
				for (LispVal element : array.data()) {
					this.escapeQuotedData(element);
				}
				return;
			}
			if (datum instanceof am.ik.rontolisp.LispInstance instance) {
				for (int i = 0; i < instance.slotCount(); i++) {
					this.escapeQuotedData(instance.slot(i));
				}
				return;
			}
			if (datum instanceof LispCons cons) {
				this.escapeQuotedData(cons.car());
				this.escapeQuotedData(cons.cdr());
			}
		}

		/** A string literal a symbol builder could turn into a designator. */
		private void escapeSpelledName(String literal) {
			int lastColon = literal.lastIndexOf(':');
			this.escapeMember(lastColon >= 0 ? literal.substring(lastColon + 1) : literal);
		}

		private void escapeMember(String member) {
			for (GenericState state : this.candidates.values()) {
				if (memberOf(state.info.name()).equals(member)) {
					state.escaped = true;
				}
			}
			List<Unit> valueRefd = this.unitsByMember.get(member);
			if (valueRefd != null) {
				for (Unit unit : valueRefd) {
					unit.valueRef = true;
					unit.live = true;
					unit.paramShapes = null;
				}
			}
		}

		// -------------------------------------------------------------------------
		// Phase 3: the fixpoint round
		// -------------------------------------------------------------------------

		/** Joined call shapes recorded during the current round, by callee name. */
		private final Map<String, List<Shape>> callJoins = new HashMap<>();

		/** Site vectors recorded during the current round, by generic state. */
		private final Map<GenericState, List<List<Shape>>> roundVectors = new IdentityHashMap<>();

		/** Runs one full rescan; answers whether any state changed. */
		private boolean round() {
			this.callJoins.clear();
			this.roundVectors.clear();
			Map<String, Shape> rootEnv = this.env(this.rootShadowed);
			for (LispVal form : this.rootForms) {
				this.walk(form, rootEnv);
			}
			for (Unit unit : this.units.values()) {
				if (!unit.live) {
					continue;
				}
				Map<String, Shape> env = this.env(unit.shadowed);
				env.putAll(
						ArgumentShapes.bind(unit.lambdaList, unit.paramShapes == null ? List.of() : unit.paramShapes));
				for (String shadowedName : unit.shadowed) {
					env.put(shadowedName, Shape.UNKNOWN);
				}
				for (LispVal form : unit.body) {
					this.walk(form, env);
				}
			}
			return this.applyRound();
		}

		private Map<String, Shape> env(Set<String> shadowed) {
			Map<String, Shape> env = new HashMap<>();
			this.globals.forEach((name, shape) -> env.put(name, shadowed.contains(name) ? Shape.UNKNOWN : shape));
			return env;
		}

		/** Merges the round's observations into the fixpoint state. */
		private boolean applyRound() {
			boolean changed = false;
			for (Map.Entry<GenericState, List<List<Shape>>> entry : this.roundVectors.entrySet()) {
				GenericState state = entry.getKey();
				for (List<Shape> vector : entry.getValue()) {
					if (!state.vectors.contains(vector)) {
						state.vectors.add(vector);
						changed = true;
					}
				}
				if (state.vectors.size() > MAX_VECTORS && !state.escaped) {
					state.escaped = true;
					changed = true;
				}
			}
			for (Unit unit : this.units.values()) {
				GenericState state = unit.methodOf;
				if (state != null) {
					boolean selectable = state.escaped
							|| anySelects(state.vectors, Objects.requireNonNull(unit.method).specializers());
					if (selectable && !unit.live) {
						unit.live = true;
						changed = true;
					}
					List<Shape> params = state.escaped || state.usesNext || unit.valueRef ? null : methodParams(state);
					if (unit.live && !Objects.equals(unit.paramShapes, params)) {
						unit.paramShapes = params;
						changed = true;
					}
					continue;
				}
				if (!unit.confined) {
					continue;
				}
				// A confined helper: live once a live unit called it, parameters joined
				// over the observed calls (every caller is a scanned method body).
				List<Shape> observed = this.callJoins.get(unit.name);
				if (observed != null) {
					if (!unit.live) {
						unit.live = true;
						changed = true;
					}
					if (!Objects.equals(unit.paramShapes, observed)) {
						unit.paramShapes = List.copyOf(observed);
						changed = true;
					}
				}
			}
			return changed;
		}

		/**
		 * A method defun's positional shapes: the leading {@code %next-method} thunk is
		 * unknown, the rest the position-wise join over the generic's site vectors -- a
		 * position a shorter vector does not supply joins with UNKNOWN, because that call
		 * fills the parameter from its default form instead.
		 */
		private static List<Shape> methodParams(GenericState state) {
			int max = 0;
			for (List<Shape> vector : state.vectors) {
				max = Math.max(max, vector.size());
			}
			List<Shape> joined = new ArrayList<>();
			joined.add(Shape.UNKNOWN);
			for (int i = 0; i < max; i++) {
				Shape shape = null;
				for (List<Shape> vector : state.vectors) {
					Shape at = i < vector.size() ? vector.get(i) : Shape.UNKNOWN;
					shape = shape == null ? at : ArgumentShapes.join(shape, at);
				}
				joined.add(shape == null ? Shape.UNKNOWN : shape);
			}
			return joined;
		}

		private void recordCall(String callee, List<Shape> shapes) {
			this.callJoins.merge(callee, shapes, Analysis::joinShapes);
		}

		private static List<Shape> joinShapes(List<Shape> a, List<Shape> b) {
			List<Shape> joined = new ArrayList<>(Math.max(a.size(), b.size()));
			for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
				joined.add(ArgumentShapes.join(i < a.size() ? a.get(i) : Shape.UNKNOWN,
						i < b.size() ? b.get(i) : Shape.UNKNOWN));
			}
			return joined;
		}

		private void recordSite(GenericState state, List<Shape> shapes) {
			this.roundVectors.computeIfAbsent(state, k -> new ArrayList<>()).add(List.copyOf(shapes));
		}

		private void walk(LispVal form, Map<String, Shape> env) {
			if (!(form instanceof LispCons cons)) {
				return;
			}
			if (cons.car() instanceof LispSymbol head) {
				String name = head.name();
				if (LispNames.QUOTE.equals(name) || LispNames.FUNCTION.equals(name) || LispNames.MACROLET.equals(name)
						|| LispNames.SYMBOL_MACROLET.equals(name) || this.macroNames.contains(name)
						|| this.macroNames.contains(memberOf(name))) {
					return;
				}
				if (cons.cdr() instanceof LispCons rest) {
					if (LispNames.LET.equals(name) || LispNames.LET_STAR.equals(name) || LispNames.DO.equals(name)
							|| LispNames.DO_STAR.equals(name)) {
						this.walkBindingForm(name, rest, env);
						return;
					}
					if (LispNames.LAMBDA.equals(name)) {
						Map<String, Shape> inner = new HashMap<>(env);
						inner.putAll(ArgumentShapes.bind(rest.car(), List.of()));
						this.walkAll(rest.cdr(), inner);
						return;
					}
					if (LispNames.FLET.equals(name) || LispNames.LABELS.equals(name)) {
						LispVal locals = rest.car();
						while (locals instanceof LispCons cell) {
							if (cell.car() instanceof LispCons local && local.cdr() instanceof LispCons afterName) {
								Map<String, Shape> inner = new HashMap<>(env);
								inner.putAll(ArgumentShapes.bind(afterName.car(), List.of()));
								this.walkAll(afterName.cdr(), inner);
							}
							locals = cell.cdr();
						}
						this.walkAll(rest.cdr(), env);
						return;
					}
					if (LispNames.FUNCALL.equals(name) || LispNames.APPLY.equals(name)) {
						String target = targetName(rest.car());
						if (target != null) {
							List<Shape> shapes = this.shapes(rest.cdr(), env);
							if (LispNames.APPLY.equals(name) && !shapes.isEmpty()) {
								// The last argument spreads: its own shape says nothing
								// about the positions its elements fill.
								shapes = shapes.subList(0, shapes.size() - 1);
							}
							this.recordResolved(target, shapes);
							this.walkAll(rest.cdr(), env);
							return;
						}
					}
				}
				ClosRegistry.GenericInfo info = this.registry.findGeneric(name);
				GenericState state = info == null ? null : this.byInfo.get(info);
				if (state != null) {
					this.recordSite(state, this.shapes(cons.cdr(), env));
				}
				else if (this.units.containsKey(name)) {
					this.recordCall(name, this.shapes(cons.cdr(), env));
				}
				this.walkAll(cons.cdr(), env);
				return;
			}
			this.walk(cons.car(), env);
			this.walkAll(cons.cdr(), env);
		}

		/** A funcall/apply target resolved to a name: a generic site or a unit call. */
		private void recordResolved(String target, List<Shape> shapes) {
			ClosRegistry.GenericInfo info = this.registry.findGeneric(target);
			GenericState state = info == null ? null : this.byInfo.get(info);
			if (state != null) {
				this.recordSite(state, shapes);
			}
			else if (this.units.containsKey(target)) {
				this.recordCall(target, shapes);
			}
		}

		private void walkAll(LispVal tail, Map<String, Shape> env) {
			LispVal rest = tail;
			while (rest instanceof LispCons cell) {
				this.walk(cell.car(), env);
				rest = cell.cdr();
			}
		}

		/** {@code (let ((var init) ...) body...)} and its three siblings. */
		private void walkBindingForm(String head, LispCons rest, Map<String, Shape> env) {
			boolean sequential = LispNames.LET_STAR.equals(head) || LispNames.DO_STAR.equals(head);
			boolean stepped = LispNames.DO.equals(head) || LispNames.DO_STAR.equals(head);
			Map<String, Shape> bound = new HashMap<>();
			Map<String, Shape> evalEnv = env;
			LispVal bindings = rest.car();
			while (bindings instanceof LispCons cell) {
				if (cell.car() instanceof LispCons pair) {
					this.walkAll(pair.cdr(), evalEnv);
					LispVal init = pair.cdr() instanceof LispCons initCell ? initCell.car() : null;
					if (pair.car() instanceof LispSymbol var) {
						bound.put(var.name(), stepped || init == null ? Shape.UNKNOWN
								: ArgumentShapes.of(init, evalEnv, this.returns));
					}
				}
				else if (cell.car() instanceof LispSymbol var) {
					bound.put(var.name(), Shape.UNKNOWN);
				}
				if (sequential) {
					evalEnv = new HashMap<>(env);
					evalEnv.putAll(bound);
				}
				bindings = cell.cdr();
			}
			Map<String, Shape> inner = new HashMap<>(env);
			inner.putAll(bound);
			this.walkAll(rest.cdr(), inner);
		}

		private List<Shape> shapes(LispVal tail, Map<String, Shape> env) {
			List<Shape> shapes = new ArrayList<>();
			LispVal rest = tail;
			while (rest instanceof LispCons cell) {
				shapes.add(ArgumentShapes.of(cell.car(), env, this.returns));
				rest = cell.cdr();
			}
			return shapes;
		}

		private static List<LispVal> listElements(LispVal tail) {
			List<LispVal> elements = new ArrayList<>();
			LispVal rest = tail;
			while (rest instanceof LispCons cell) {
				elements.add(cell.car());
				rest = cell.cdr();
			}
			return elements;
		}

	}

}
