package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Deletes {@code case}/{@code ecase} arms inside third-party spliced definitions whose
 * keys can never {@code eql} any value the arm's subject can take -- the
 * caller-constant-keyword shape: a library whose entry point dispatches on a format
 * argument (chipz's {@code make-dstate}, three frames from the user's {@code 'chipz:gzip}
 * through {@code decompress}'s default method and {@code %decompress}) keeps its whole
 * other-format tree alive through that one {@code case} arm, and no other pass can see
 * through the call chain ({@code DeadTypeBranchPruner} joins argument TYPES, and
 * {@code 'chipz:gzip} / {@code 'chipz:bzip2} are both {@code SYMBOL}).
 *
 * <p>
 * The analysis is a monotone least fixpoint over per-parameter VALUE sets:
 * <ul>
 * <li>tracked functions are the program's single-{@code defun} names plus every method of
 * a program-defined {@code defgeneric}/{@code defmethod} family (a call to the generic
 * joins into every method's parameters -- dispatch is not modeled, which only widens the
 * sets); only REQUIRED parameters participate;</li>
 * <li>a value is a constant comparable under {@code eql} (a symbol -- including keywords,
 * {@code t}, {@code nil} -- an integer, a character), or NON-KEY: a value that provably
 * cannot {@code eql} any literal key (a struct/class instance, a function object).
 * Anything else is TOP;</li>
 * <li>values flow through direct calls, {@code funcall}/{@code apply} of a literal
 * {@code #'f}/{@code 'f} (an {@code apply} contributes nothing at and past its spread
 * argument's position), {@code let}/{@code let*} bindings, and expression results
 * ({@code case}/{@code cond}/{@code if} join their reachable arm tails; a call to a
 * {@code defstruct} constructor or {@code make-instance}/{@code make-condition} is
 * NON-KEY; a tail {@code (error ...)} contributes nothing because it cannot return);</li>
 * <li>a name that ESCAPES -- quoted data, a string literal spelling it, a bare
 * {@code #'f} outside {@code funcall}/{@code apply} head position, a {@code case} key, a
 * shadowing {@code flet}/{@code labels} local -- keeps its parameters and return TOP
 * (calls through the escaped value are invisible, and a call spelled with the name may
 * reach the shadowing local instead).</li>
 * </ul>
 *
 * An arm whose keys intersect no possible subject value never runs, so deleting it
 * preserves the behavior of EVERY execution -- including an {@code ecase} (the deleted
 * arm's keys still fall through to the error) -- which is a stronger guarantee than the
 * pruner's own carve-out. The one way to defeat the analysis is the same computed-name
 * forgery the pruner documents: a format symbol interned from computed strings reaches
 * the {@code case} with no textual occurrence and lands on the {@code (t (error ...))}
 * arm -- a loud typed error, never silent wrong output. {@code --dynamic} /
 * {@code --no-prune} skip the enclosing pruner pass and this one with it.
 *
 * <p>
 * Only forms inside prunable third-party system brackets are rewritten (the user program
 * and the bundled libraries stay byte-identical); the analysis itself reads the whole
 * program, so user call sites are what seed the constants. Runs inside
 * {@link LibraryDefunPruner#prune} after package resolution and BEFORE reference
 * collection, so a deleted arm's references never anchor a definition.
 */
final class ConstantCaseArmPruner {

	/** Join-set size above which a set collapses to TOP. */
	private static final int MAX_VALUES = 16;

	private static final int MAX_ITERATIONS = 40;

	private ConstantCaseArmPruner() {
	}

	/**
	 * The rewritten program, index-aligned with the input lists.
	 *
	 * @param forms the pre-resolution forms, arms deleted where proven dead
	 * @param resolved the resolved copy with the same arms deleted
	 */
	record Result(List<LispVal> forms, List<LispVal> resolved) {
	}

	/**
	 * Folds provably dead {@code case}/{@code ecase} arms out of third-party spliced
	 * forms. Returns the input lists unchanged when nothing folds.
	 * @param forms the pre-resolution top-level forms
	 * @param resolved the resolved copy, index-aligned 1:1
	 * @param prunableSystemAt whether the form at an index came from a prunable
	 * third-party system
	 * @return the (possibly rewritten) program
	 */
	static Result fold(List<LispVal> forms, List<LispVal> resolved, IntPredicate prunableSystemAt) {
		Analysis analysis = new Analysis(resolved);
		if (analysis.functions.isEmpty()) {
			return new Result(forms, resolved);
		}
		analysis.run();
		Set<LispCons> deadArms = analysis.collectDeadArms(prunableSystemAt);
		if (deadArms.isEmpty()) {
			return new Result(forms, resolved);
		}
		List<LispVal> outForms = new ArrayList<>(forms.size());
		List<LispVal> outResolved = new ArrayList<>(resolved.size());
		for (int i = 0; i < forms.size(); i++) {
			LispVal newResolved = FormRewriter.withoutArms(resolved.get(i), deadArms);
			if (newResolved == resolved.get(i)) {
				outForms.add(forms.get(i));
				outResolved.add(resolved.get(i));
			}
			else {
				outForms.add(FormRewriter.withoutArmsParallel(forms.get(i), resolved.get(i), deadArms));
				outResolved.add(newResolved);
			}
		}
		return new Result(outForms, outResolved);
	}

	// ------------------------------------------------------------------
	// Value lattice
	// ------------------------------------------------------------------

	/**
	 * A join set of the values an expression/parameter can take: {@code eql}-comparable
	 * constants (symbol names normalized to one colon spelling, boxed longs, boxed
	 * characters -- the Java types keep the kinds apart) plus a NON-KEY summary member.
	 * {@code top} absorbs everything.
	 */
	private static final class ValueSet {

		private boolean top;

		private boolean nonKey;

		private final Set<Object> constants = new LinkedHashSet<>();

		boolean addAll(ValueSet other) {
			if (this.top) {
				return false;
			}
			if (other.top) {
				return makeTop();
			}
			boolean changed = false;
			if (other.nonKey && !this.nonKey) {
				this.nonKey = true;
				changed = true;
			}
			for (Object c : other.constants) {
				changed |= addConstant(c);
				if (this.top) {
					return true;
				}
			}
			return changed;
		}

		boolean addConstant(Object constant) {
			if (this.top) {
				return false;
			}
			boolean changed = this.constants.add(constant);
			if (this.constants.size() > MAX_VALUES) {
				return makeTop();
			}
			return changed;
		}

		boolean makeTop() {
			if (this.top) {
				return false;
			}
			this.top = true;
			this.constants.clear();
			this.nonKey = false;
			return true;
		}

		boolean isTop() {
			return this.top;
		}

		boolean isEmpty() {
			return !this.top && !this.nonKey && this.constants.isEmpty();
		}

		/**
		 * Whether any possible value can {@code eql} one of the given case keys. NON-KEY
		 * values match no literal key by construction; a key kind the analysis does not
		 * compare (float/string/list) is absent from {@code keys} and is handled by the
		 * caller keeping the arm whenever the subject is TOP -- a known constant subject
		 * can never {@code eql} such a key (a string subject is already TOP).
		 */
		boolean mayMatchAnyKey(List<Object> keys) {
			if (this.top) {
				return true;
			}
			for (Object key : keys) {
				if (this.constants.contains(key)) {
					return true;
				}
			}
			return false;
		}

		static ValueSet ofTop() {
			ValueSet set = new ValueSet();
			set.top = true;
			return set;
		}

		static ValueSet ofConstant(Object constant) {
			ValueSet set = new ValueSet();
			set.constants.add(constant);
			return set;
		}

		static ValueSet ofNonKey() {
			ValueSet set = new ValueSet();
			set.nonKey = true;
			return set;
		}

		static ValueSet empty() {
			return new ValueSet();
		}

	}

	/**
	 * One tracked struct SLOT: the join set of every value the program can store into it.
	 * Shared along the {@code :include} chain -- a parent's accessor and a child's
	 * re-exposed accessor read the same storage, so they read the same set.
	 */
	private static final class SlotState {

		final ValueSet set = new ValueSet();

	}

	/** One slot of one struct's layout: the shared state plus THIS struct's initform. */
	private record SlotBinding(String member, SlotState state, LispVal initform) {
	}

	/** One tracked struct: its full slot chain (inherited first) and whether opaque. */
	private static final class StructFlow {

		final List<SlotBinding> slots = new ArrayList<>();

		boolean opaque;

	}

	/** One tracked constructor: which struct it makes and how its arguments map. */
	private static final class CtorState {

		final StructFlow struct;

		/** Required-parameter slot members in order; null = the keyword constructor. */
		final @Nullable List<String> boaRequired;

		/** The BOA lambda list has &-markers: every non-required slot goes TOP. */
		final boolean boaHasMarkers;

		CtorState(StructFlow struct, @Nullable List<String> boaRequired, boolean boaHasMarkers) {
			this.struct = struct;
			this.boaRequired = boaRequired;
			this.boaHasMarkers = boaHasMarkers;
		}

	}

	/** One tracked function: a defun, or one method of a generic family. */
	private static final class Fn {

		final List<String> requiredParams;

		final List<LispVal> body;

		final ValueSet[] paramSets;

		final ValueSet returnSet = new ValueSet();

		boolean escaped;

		Fn(List<String> requiredParams, List<LispVal> body) {
			this.requiredParams = requiredParams;
			this.body = body;
			this.paramSets = new ValueSet[requiredParams.size()];
			for (int i = 0; i < this.paramSets.length; i++) {
				this.paramSets[i] = new ValueSet();
			}
		}

		void escape() {
			this.escaped = true;
			for (ValueSet set : this.paramSets) {
				set.makeTop();
			}
			this.returnSet.makeTop();
		}

	}

	// ------------------------------------------------------------------
	// The analysis
	// ------------------------------------------------------------------

	private static final class Analysis {

		private final List<LispVal> resolved;

		/** Call-target name -> the functions a call by that name reaches. */
		final Map<String, List<Fn>> functions = new HashMap<>();

		/** Per top-level form, the tracked function bodies it defines. */
		private final Map<LispVal, List<Fn>> fnsByForm = new IdentityHashMap<>();

		/** Names whose call returns a fresh struct instance. */
		private final Set<String> instanceConstructors = new HashSet<>();

		/** Accessor spelling -> the slot it reads/writes. */
		private final Map<String, SlotState> slotsByAccessor = new HashMap<>();

		/** Constructor spelling -> its argument-to-slot mapping. */
		private final Map<String, CtorState> ctorsByName = new HashMap<>();

		/** Struct-name spelling -> flow, for make-instance over a literal struct name. */
		private final Map<String, StructFlow> structsByName = new HashMap<>();

		private boolean dirty;

		Analysis(List<LispVal> resolved) {
			this.resolved = resolved;
			collectFunctions();
			if (!this.functions.isEmpty()) {
				collectStructFlows();
				if (!this.slotsByAccessor.isEmpty() && this.resolved.stream().anyMatch(Analysis::mentionsRuntimeRead)) {
					// A runtime (read)/(read-from-string ...) can construct a #S literal
					// whose slot values this walk never saw stored.
					topAllSlots();
				}
				scanEscapes();
			}
		}

		private static boolean mentionsRuntimeRead(LispVal form) {
			if (form instanceof LispSymbol sym) {
				String member = LispSymbol.memberName(sym.name());
				return "READ".equals(member) || "READ-FROM-STRING".equals(member)
						|| "READ-PRESERVING-WHITESPACE".equals(member);
			}
			if (form instanceof LispCons cons) {
				return mentionsRuntimeRead(cons.car()) || mentionsRuntimeRead(cons.cdr());
			}
			return false;
		}

		// ----------------------------------------------------------------
		// Struct slot flow: which values can a slot ever hold
		// ----------------------------------------------------------------

		private void collectStructFlows() {
			Map<String, LispMacroExpander.StructSlotFlow> summaries = new HashMap<>();
			Set<String> duplicated = new HashSet<>();
			for (LispVal form : this.resolved) {
				LispMacroExpander.StructSlotFlow summary = LispMacroExpander.defstructSlotFlow(form);
				if (summary == null) {
					continue;
				}
				for (String spelling : summary.structSpellings()) {
					if (summaries.put(spelling, summary) != null) {
						duplicated.add(spelling);
					}
				}
			}
			Map<LispMacroExpander.StructSlotFlow, StructFlow> flows = new IdentityHashMap<>();
			for (LispMacroExpander.StructSlotFlow summary : new LinkedHashSet<>(summaries.values())) {
				StructFlow flow = resolveFlow(summary, summaries, flows, new HashSet<>());
				boolean redefined = summary.structSpellings().stream().anyMatch(duplicated::contains);
				if (redefined) {
					flow.opaque = true;
				}
				for (String spelling : summary.structSpellings()) {
					this.structsByName.put(spelling, flow);
				}
				for (SlotBinding binding : flow.slots) {
					for (String accessor : summary.accessorSpellings(binding.member())) {
						SlotState existing = this.slotsByAccessor.putIfAbsent(accessor, binding.state());
						if (existing != null && existing != binding.state()) {
							// Two structs generate the same accessor spelling: neither
							// mapping can be trusted.
							existing.set.makeTop();
							binding.state().set.makeTop();
						}
						if (this.functions.containsKey(accessor)) {
							// A defun of the accessor's name: a call is ambiguous.
							binding.state().set.makeTop();
						}
					}
				}
				for (LispMacroExpander.StructSlotFlow.CtorFlow ctor : summary.constructors()) {
					List<String> required = null;
					boolean markers = false;
					if (ctor.boaLambdaList() != null) {
						required = new ArrayList<>();
						LispVal rest = ctor.boaLambdaList();
						while (rest instanceof LispCons cell) {
							if (cell.car() instanceof LispSymbol param) {
								if (param.name().startsWith("&")) {
									markers = true;
									break;
								}
								required.add(LispSymbol.memberName(param.name()));
							}
							else {
								// A non-symbol in the required section: unmappable.
								flow.opaque = true;
								break;
							}
							rest = cell.cdr();
						}
						if (!(rest instanceof LispNil) && !markers) {
							flow.opaque = true;
						}
					}
					for (String spelling : ctor.spellings()) {
						if (this.ctorsByName.putIfAbsent(spelling, new CtorState(flow, required, markers)) != null
								|| this.functions.containsKey(spelling)) {
							// Ambiguous spelling (a second constructor, or a defun of the
							// same name): trust neither side.
							flow.opaque = true;
							escapeName(spelling);
						}
					}
				}
				if (flow.opaque) {
					topSlots(flow);
				}
			}
		}

		/**
		 * Builds the full slot chain of one struct: the parent chain's slots (their
		 * states SHARED, this child's {@code :include} overrides applied to the
		 * initforms) followed by the own slots. An unresolvable or opaque parent leaves
		 * the child tracking its own slots only -- the parent's slots simply stay
		 * untracked, which reads as unknown everywhere.
		 */
		private StructFlow resolveFlow(LispMacroExpander.StructSlotFlow summary,
				Map<String, LispMacroExpander.StructSlotFlow> summaries,
				Map<LispMacroExpander.StructSlotFlow, StructFlow> flows,
				Set<LispMacroExpander.StructSlotFlow> visiting) {
			StructFlow done = flows.get(summary);
			if (done != null) {
				return done;
			}
			StructFlow flow = new StructFlow();
			flows.put(summary, flow);
			if (!visiting.add(summary)) {
				flow.opaque = true;
				return flow;
			}
			flow.opaque = summary.opaque();
			if (summary.includeParent() != null) {
				LispMacroExpander.StructSlotFlow parent = summaries.get(summary.includeParent());
				if (parent != null) {
					StructFlow parentFlow = resolveFlow(parent, summaries, flows, visiting);
					if (parentFlow.opaque) {
						flow.opaque = true;
					}
					for (SlotBinding inherited : parentFlow.slots) {
						LispVal override = summary.includeOverrides().get(inherited.member());
						flow.slots.add(override == null ? inherited
								: new SlotBinding(inherited.member(), inherited.state(), override));
					}
				}
			}
			for (LispMacroExpander.StructSlotFlow.SlotFlow slot : summary.ownSlots()) {
				flow.slots.add(new SlotBinding(slot.base(), new SlotState(), slot.initform()));
			}
			visiting.remove(summary);
			return flow;
		}

		private void topSlots(StructFlow flow) {
			for (SlotBinding binding : flow.slots) {
				if (binding.state().set.makeTop()) {
					this.dirty = true;
				}
			}
		}

		private void topAllSlots() {
			for (SlotState slot : this.slotsByAccessor.values()) {
				if (slot.set.makeTop()) {
					this.dirty = true;
				}
			}
			for (StructFlow flow : this.structsByName.values()) {
				topSlots(flow);
			}
		}

		private void collectFunctions() {
			Set<String> defunNames = new HashSet<>();
			Set<String> methodNames = new HashSet<>();
			Set<String> duplicates = new HashSet<>();
			for (LispVal form : this.resolved) {
				String name = defunName(form);
				if (name != null && !defunNames.add(name)) {
					duplicates.add(name);
				}
			}
			for (LispVal form : this.resolved) {
				if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
						|| !cons.isProperList()) {
					continue;
				}
				List<LispVal> parts = cons.toList();
				switch (op.name()) {
					case LispNames.DEFUN -> {
						String name = defunName(form);
						if (name == null || duplicates.contains(name)
								|| PackageRegistry.isClSymbol(LispSymbol.memberName(name))) {
							continue;
						}
						List<String> params = parts.size() > 2 ? requiredParams(parts.get(2)) : null;
						if (params == null) {
							continue;
						}
						register(form, name, new Fn(params, parts.subList(3, parts.size())));
					}
					case LispNames.DEFGENERIC -> {
						if (parts.size() < 2 || !(parts.get(1) instanceof LispSymbol generic)
								|| PackageRegistry.isClSymbol(LispSymbol.memberName(generic.name()))) {
							continue;
						}
						methodNames.add(generic.name());
						for (LispVal option : parts.subList(2, parts.size())) {
							if (option instanceof LispCons optCons && optCons.isProperList()
									&& optCons.car() instanceof LispSymbol optOp && ":METHOD".equals(optOp.name())) {
								registerMethod(form, generic.name(), optCons.toList(), 1);
							}
						}
					}
					case LispNames.DEFMETHOD -> {
						if (parts.size() < 2 || !(parts.get(1) instanceof LispSymbol generic)
								|| PackageRegistry.isClSymbol(LispSymbol.memberName(generic.name()))) {
							continue;
						}
						methodNames.add(generic.name());
						registerMethod(form, generic.name(), parts, 2);
					}
					case LispNames.DEFSTRUCT -> {
						LispMacroExpander.StructDefinedNames summary = LispMacroExpander.defstructDefinedNames(form);
						if (summary != null) {
							Set<String> structSpellings = new HashSet<>(spellingsOf(summary.structName()));
							for (String instantiator : summary.instantiatorNames()) {
								if (!structSpellings.contains(instantiator)) {
									this.instanceConstructors.add(instantiator);
								}
							}
						}
					}
					default -> {
					}
				}
			}
			// A name defined BOTH as a defun and as a generic family cannot be
			// attributed to one body -- widen everything registered under it.
			for (String name : methodNames) {
				if (defunNames.contains(name)) {
					escapeName(name);
				}
			}
		}

		/**
		 * Registers one method: {@code parts[startAt...]} = qualifiers, lambda list,
		 * body.
		 */
		private void registerMethod(LispVal form, String generic, List<LispVal> parts, int startAt) {
			int i = startAt;
			while (i < parts.size() && parts.get(i) instanceof LispSymbol) {
				i++;
			}
			if (i >= parts.size()) {
				return;
			}
			List<String> params = requiredParams(parts.get(i));
			if (params == null) {
				return;
			}
			register(form, generic, new Fn(params, parts.subList(i + 1, parts.size())));
		}

		private void register(LispVal form, String name, Fn fn) {
			this.functions.computeIfAbsent(name, k -> new ArrayList<>()).add(fn);
			this.fnsByForm.computeIfAbsent(form, k -> new ArrayList<>()).add(fn);
		}

		/**
		 * The required parameter NAMES of a lambda list (a specialized {@code (p class)}
		 * pair contributes p), or null when the list is not parseable.
		 */
		private static @Nullable List<String> requiredParams(@Nullable LispVal lambdaList) {
			if (lambdaList instanceof LispNil) {
				return List.of();
			}
			if (!(lambdaList instanceof LispCons cons) || !cons.isProperList()) {
				return null;
			}
			List<String> params = new ArrayList<>();
			for (LispVal element : cons.toList()) {
				if (element instanceof LispSymbol sym) {
					if (sym.name().startsWith("&")) {
						break;
					}
					params.add(sym.name());
				}
				else if (element instanceof LispCons specialized && specialized.car() instanceof LispSymbol param) {
					params.add(param.name());
				}
				else {
					return null;
				}
			}
			return params;
		}

		private static @Nullable String defunName(LispVal form) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.DEFUN.equals(op.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name) {
				return name.name();
			}
			return null;
		}

		// ----------------------------------------------------------------
		// Escapes: a tracked name whose value leaves through data
		// ----------------------------------------------------------------

		private void scanEscapes() {
			for (LispVal form : this.resolved) {
				scanEscapes(form, false);
			}
		}

		private void scanEscapes(LispVal form, boolean asData) {
			switch (form) {
				case LispSymbol sym -> {
					if (asData) {
						escapeName(sym.name());
					}
				}
				case LispString str -> {
					String value = str.value().toUpperCase(java.util.Locale.ROOT);
					if (this.functions.containsKey(value)) {
						escapeName(value);
					}
					for (Map.Entry<String, List<Fn>> entry : this.functions.entrySet()) {
						if (LispSymbol.memberName(entry.getKey()).equals(value)) {
							entry.getValue().forEach(Fn::escape);
						}
					}
					for (Map.Entry<String, SlotState> entry : this.slotsByAccessor.entrySet()) {
						if (entry.getKey().equals(value) || LispSymbol.memberName(entry.getKey()).equals(value)) {
							entry.getValue().set.makeTop();
						}
					}
					for (Map.Entry<String, CtorState> entry : this.ctorsByName.entrySet()) {
						if (entry.getKey().equals(value) || LispSymbol.memberName(entry.getKey()).equals(value)) {
							topSlots(entry.getValue().struct);
						}
					}
				}
				case am.ik.rontolisp.LispInstance ignored ->
					// A #S literal stores slot values with no constructor call in sight.
					topAllSlots();
				case am.ik.rontolisp.LispArray array -> {
					// An array literal's elements are data: a symbol in one is as
					// designator-capable as one in a quoted list.
					for (LispVal element : array.data()) {
						scanEscapes(element, true);
					}
				}
				case LispCons cons -> {
					if (!(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
						for (LispVal rest = cons; rest instanceof LispCons cell; rest = cell.cdr()) {
							scanEscapes(cell.car(), asData);
						}
						return;
					}
					List<LispVal> parts = cons.toList();
					switch (LispSymbol.memberName(op.name())) {
						case LispNames.QUOTE -> scanEscapes(parts.size() > 1 ? parts.get(1) : LispNil.INSTANCE, true);
						case LispNames.FUNCTION -> {
							if (parts.size() > 1 && parts.get(1) instanceof LispSymbol fnName) {
								escapeName(fnName.name());
							}
							else if (parts.size() > 1 && parts.get(1) instanceof LispCons setter
									&& setter.car() instanceof LispSymbol setf
									&& "SETF".equals(LispSymbol.memberName(setf.name()))
									&& setter.cdr() instanceof LispCons cell
									&& cell.car() instanceof LispSymbol place) {
								// #'(setf acc): a first-class writer this walk cannot see
								// through.
								escapeName(place.name());
							}
							else if (parts.size() > 1) {
								scanEscapes(parts.get(1), false);
							}
						}
						case LispNames.FUNCALL, LispNames.APPLY -> {
							// The one sanctioned #'f / 'f position: the head argument,
							// where the call target is still statically known and its
							// arguments visible (mapped as a call site by the value
							// walk).
							int from = parts.size() > 1 && literalFunctionDesignator(parts.get(1)) != null ? 2 : 1;
							for (int i = from; i < parts.size(); i++) {
								scanEscapes(parts.get(i), false);
							}
						}
						case LispNames.CASE, LispNames.ECASE, LispNames.TYPECASE, LispNames.ETYPECASE -> {
							if (parts.size() > 1) {
								scanEscapes(parts.get(1), asData);
							}
							for (int i = 2; i < parts.size(); i++) {
								if (parts.get(i) instanceof LispCons clause) {
									// Keys / type specifiers are data; bodies are code.
									scanEscapes(clause.car(), true);
									for (LispVal rest = clause.cdr(); rest instanceof LispCons cell; rest = cell
										.cdr()) {
										scanEscapes(cell.car(), false);
									}
								}
							}
						}
						case LispNames.FLET, LispNames.LABELS -> {
							if (parts.size() > 1 && parts.get(1) instanceof LispCons locals && locals.isProperList()) {
								for (LispVal local : locals.toList()) {
									if (local instanceof LispCons localCons
											&& localCons.car() instanceof LispSymbol localName) {
										// A local function shadows the global of the same
										// name; a call spelled with the name may reach
										// either.
										escapeName(localName.name());
									}
									scanEscapes(local, false);
								}
							}
							for (int i = 2; i < parts.size(); i++) {
								scanEscapes(parts.get(i), false);
							}
						}
						case LispNames.DEFUN, LispNames.DEFGENERIC, LispNames.DEFMETHOD -> {
							// Skip the NAME position (a definition is not a reference).
							for (int i = 2; i < parts.size(); i++) {
								scanEscapes(parts.get(i), false);
							}
						}
						case "WITH-SLOTS", "WITH-ACCESSORS" -> {
							// Slot access outside the accessor spellings: stand the slot
							// tracking down rather than model the binding grammar.
							topAllSlots();
							for (int i = 1; i < parts.size(); i++) {
								scanEscapes(parts.get(i), false);
							}
						}
						default -> {
							for (LispVal part : parts) {
								scanEscapes(part, asData);
							}
						}
					}
				}
				default -> {
				}
			}
		}

		private void escapeName(String name) {
			List<Fn> fns = this.functions.get(name);
			if (fns != null) {
				fns.forEach(Fn::escape);
			}
			SlotState slot = this.slotsByAccessor.get(name);
			if (slot != null) {
				// The accessor as a VALUE: (setf (funcall it ...)) forms, computed
				// writes -- the slot can no longer claim to know its writers.
				slot.set.makeTop();
			}
			CtorState ctor = this.ctorsByName.get(name);
			if (ctor != null) {
				topSlots(ctor.struct);
			}
			// The struct NAME in data is deliberately NOT a slot escape: type-specifier
			// positions (typecase clause heads) put it there constantly, and a name alone
			// writes nothing -- every way to construct through a name at run time
			// (computed make-instance, a runtime read's #S) is a cliff of its own.
		}

		// ----------------------------------------------------------------
		// The fixpoint
		// ----------------------------------------------------------------

		void run() {
			for (int round = 0; round < MAX_ITERATIONS; round++) {
				this.dirty = false;
				for (LispVal form : this.resolved) {
					walkTopLevel(form, null);
				}
				if (!this.dirty) {
					return;
				}
			}
			// Did not converge inside the guard: drop every conclusion.
			for (List<Fn> fns : this.functions.values()) {
				fns.forEach(Fn::escape);
			}
			topAllSlots();
		}

		Set<LispCons> collectDeadArms(IntPredicate prunableSystemAt) {
			Set<LispCons> deadArms = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
			for (int i = 0; i < this.resolved.size(); i++) {
				if (prunableSystemAt.test(i)) {
					walkTopLevel(this.resolved.get(i), deadArms);
				}
			}
			return deadArms;
		}

		/**
		 * Walks one top-level form: a tracked definition's bodies under their parameter
		 * environments, everything else (the user program, the roots) under an empty one.
		 * With a non-null sink, additionally records provably dead arms.
		 */
		private void walkTopLevel(LispVal form, @Nullable Set<LispCons> deadArms) {
			List<Fn> fns = this.fnsByForm.get(form);
			if (fns == null) {
				Env env = Env.root();
				poisonSetqTargets(form, env);
				walk(form, env, deadArms);
				return;
			}
			for (Fn fn : fns) {
				Env env = Env.of(fn);
				for (LispVal bodyForm : fn.body) {
					poisonSetqTargets(bodyForm, env);
				}
				ValueSet last = ValueSet.ofConstant(nilConstant());
				for (LispVal bodyForm : fn.body) {
					last = walk(bodyForm, env, deadArms);
				}
				if (!fn.escaped && fn.returnSet.addAll(last)) {
					this.dirty = true;
				}
			}
		}

		private ValueSet walk(LispVal form, Env env, @Nullable Set<LispCons> deadArms) {
			return switch (form) {
				case LispSymbol sym -> {
					String name = sym.name();
					if (name.startsWith(":") || "NIL".equals(name) || "T".equals(name)) {
						yield ValueSet.ofConstant(symbolConstant(name));
					}
					ValueSet bound = env.lookup(name);
					yield bound != null ? bound : ValueSet.ofTop();
				}
				case LispInteger n -> ValueSet.ofConstant(n.value());
				case LispChar c -> ValueSet.ofConstant(charConstant(c.codePoint()));
				case LispNil ignored -> ValueSet.ofConstant(nilConstant());
				case LispTrue ignored -> ValueSet.ofConstant(symbolConstant("T"));
				case LispCons cons -> walkCons(cons, env, deadArms);
				// Strings, floats, arrays, ...: eql against a literal key is
				// identity-dependent -- stay conservative.
				default -> ValueSet.ofTop();
			};
		}

		private ValueSet walkCons(LispCons cons, Env env, @Nullable Set<LispCons> deadArms) {
			if (!(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
				// ((lambda ...) args) or a dotted/malformed form: walk everything.
				for (LispVal rest = cons; rest instanceof LispCons cell; rest = cell.cdr()) {
					walk(cell.car(), env, deadArms);
				}
				return ValueSet.ofTop();
			}
			List<LispVal> parts = cons.toList();
			String member = LispSymbol.memberName(op.name());
			switch (member) {
				case LispNames.QUOTE -> {
					return quotedValue(parts.size() > 1 ? parts.get(1) : LispNil.INSTANCE);
				}
				case LispNames.FUNCTION, LispNames.LAMBDA -> {
					// A function object can never eql a literal key. Walk a literal
					// lambda's body for the call sites it contains.
					if (LispNames.LAMBDA.equals(member)) {
						walkLambdaLike(parts, 1, env, deadArms);
					}
					else if (parts.size() > 1 && parts.get(1) instanceof LispCons lambda && lambda.isProperList()
							&& lambda.car() instanceof LispSymbol l && LispNames.LAMBDA.equals(l.name())) {
						walkLambdaLike(lambda.toList(), 1, env, deadArms);
					}
					return ValueSet.ofNonKey();
				}
				case LispNames.IF -> {
					walkAt(parts, 1, env, deadArms);
					ValueSet result = ValueSet.empty();
					result.addAll(walkAt(parts, 2, env, deadArms));
					result.addAll(
							parts.size() > 3 ? walk(parts.get(3), env, deadArms) : ValueSet.ofConstant(nilConstant()));
					return result;
				}
				case LispNames.WHEN, LispNames.UNLESS -> {
					walkAt(parts, 1, env, deadArms);
					ValueSet result = ValueSet.ofConstant(nilConstant());
					result.addAll(walkSequence(parts, 2, env, deadArms));
					return result;
				}
				case LispNames.PROGN, LispNames.LOCALLY -> {
					return walkSequence(parts, 1, env, deadArms);
				}
				case LispNames.THE -> {
					return walkAt(parts, 2, env, deadArms);
				}
				case LispNames.PROG1, LispNames.MULTIPLE_VALUE_PROG1 -> {
					ValueSet first = walkAt(parts, 1, env, deadArms);
					walkSequence(parts, 2, env, deadArms);
					return first;
				}
				case LispNames.PROG2 -> {
					walkAt(parts, 1, env, deadArms);
					ValueSet second = walkAt(parts, 2, env, deadArms);
					walkSequence(parts, 3, env, deadArms);
					return second;
				}
				case LispNames.LET, LispNames.LET_STAR -> {
					return walkLet(parts, LispNames.LET_STAR.equals(member), env, deadArms);
				}
				case LispNames.SETQ, LispNames.PSETQ -> {
					// Targets were poisoned to TOP up front (flow-insensitive).
					ValueSet last = ValueSet.ofConstant(nilConstant());
					for (int i = 2; i < parts.size(); i += 2) {
						last = walk(parts.get(i), env, deadArms);
					}
					return LispNames.PSETQ.equals(member) ? ValueSet.ofConstant(nilConstant()) : last;
				}
				case "SETF", "PSETF" -> {
					// Variable places were poisoned up front; a tracked SLOT place joins
					// the written value into the slot's set instead.
					ValueSet last = ValueSet.ofConstant(nilConstant());
					int i = 1;
					for (; i + 1 < parts.size(); i += 2) {
						ValueSet value = walk(parts.get(i + 1), env, deadArms);
						slotWritePlace(parts.get(i), value, env, deadArms);
						last = value;
					}
					if (i < parts.size()) {
						// A trailing place with no value form: malformed, stay wide.
						slotWritePlace(parts.get(i), ValueSet.ofTop(), env, deadArms);
					}
					return "PSETF".equals(member) ? ValueSet.ofConstant(nilConstant()) : last;
				}
				case "INCF", "DECF", "POP", "REMF" -> {
					if (parts.size() > 1) {
						slotWritePlace(parts.get(1), ValueSet.ofTop(), env, deadArms);
					}
					walkSequence(parts, 2, env, deadArms);
					return ValueSet.ofTop();
				}
				case "PUSH", "PUSHNEW" -> {
					walkAt(parts, 1, env, deadArms);
					if (parts.size() > 2) {
						slotWritePlace(parts.get(2), ValueSet.ofTop(), env, deadArms);
					}
					walkSequence(parts, 3, env, deadArms);
					return ValueSet.ofTop();
				}
				case "ROTATEF", "SHIFTF" -> {
					for (int i = 1; i < parts.size(); i++) {
						slotWritePlace(parts.get(i), ValueSet.ofTop(), env, deadArms);
						walk(parts.get(i), env, deadArms);
					}
					return ValueSet.ofTop();
				}
				case LispNames.DEFSTRUCT -> {
					// Slot initforms are walked at each defaulting CONSTRUCTOR call; the
					// definition itself computes nothing.
					return ValueSet.ofTop();
				}
				case LispNames.COND -> {
					ValueSet result = ValueSet.ofConstant(nilConstant());
					for (int i = 1; i < parts.size(); i++) {
						if (!(parts.get(i) instanceof LispCons clause)) {
							continue;
						}
						ValueSet test = walk(clause.car(), env, deadArms);
						if (clause.cdr() instanceof LispCons body) {
							ValueSet tail = ValueSet.ofConstant(nilConstant());
							for (LispVal rest = body; rest instanceof LispCons cell; rest = cell.cdr()) {
								tail = walk(cell.car(), env, deadArms);
							}
							result.addAll(tail);
						}
						else {
							result.addAll(test);
						}
					}
					return result;
				}
				case LispNames.AND, LispNames.OR -> {
					ValueSet result = ValueSet.ofConstant(nilConstant());
					for (int i = 1; i < parts.size(); i++) {
						result.addAll(walk(parts.get(i), env, deadArms));
					}
					return result;
				}
				case LispNames.CASE, LispNames.ECASE -> {
					return walkCase(parts, LispNames.ECASE.equals(member), env, deadArms);
				}
				case LispNames.TYPECASE, LispNames.ETYPECASE -> {
					walkAt(parts, 1, env, deadArms);
					ValueSet result = LispNames.TYPECASE.equals(member) ? ValueSet.ofConstant(nilConstant())
							: ValueSet.empty();
					for (int i = 2; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons clause && clause.cdr() instanceof LispCons body) {
							ValueSet tail = ValueSet.ofConstant(nilConstant());
							for (LispVal rest = body; rest instanceof LispCons cell; rest = cell.cdr()) {
								tail = walk(cell.car(), env, deadArms);
							}
							result.addAll(tail);
						}
					}
					return result;
				}
				case LispNames.BLOCK -> {
					ValueSet last = walkSequence(parts, 2, env, deadArms);
					return containsAnySymbol(cons, RETURN_NAMES) ? ValueSet.ofTop() : last;
				}
				case LispNames.RETURN_FROM, LispNames.RETURN -> {
					walkSequence(parts, LispNames.RETURN.equals(member) ? 1 : 2, env, deadArms);
					return ValueSet.empty();
				}
				case LispNames.TAGBODY -> {
					for (int i = 1; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons element) {
							walk(element, env, deadArms);
						}
					}
					return ValueSet.ofConstant(nilConstant());
				}
				case LispNames.GO -> {
					return ValueSet.empty();
				}
				case LispNames.CATCH, LispNames.PROGV -> {
					walkSequence(parts, 1, env, deadArms);
					return ValueSet.ofTop();
				}
				case LispNames.UNWIND_PROTECT -> {
					ValueSet first = walkAt(parts, 1, env, deadArms);
					walkSequence(parts, 2, env, deadArms);
					return first;
				}
				case LispNames.FLET, LispNames.LABELS -> {
					if (parts.size() > 1 && parts.get(1) instanceof LispCons locals && locals.isProperList()) {
						for (LispVal local : locals.toList()) {
							if (local instanceof LispCons localCons && localCons.isProperList()) {
								walkLambdaLike(localCons.toList(), 1, env, deadArms);
							}
						}
					}
					return walkSequence(parts, 2, env, deadArms);
				}
				case LispNames.MULTIPLE_VALUE_BIND, LispNames.DESTRUCTURING_BIND -> {
					walkAt(parts, 2, env, deadArms);
					Env inner = env.child();
					bindAllSymbols(parts.size() > 1 ? parts.get(1) : LispNil.INSTANCE, inner);
					return walkSequence(parts, 3, inner, deadArms);
				}
				case LispNames.DOLIST, LispNames.DOTIMES -> {
					Env inner = env.child();
					if (parts.size() > 1 && parts.get(1) instanceof LispCons spec && spec.isProperList()) {
						List<LispVal> specParts = spec.toList();
						if (!specParts.isEmpty() && specParts.get(0) instanceof LispSymbol var) {
							inner.bindTop(var.name());
						}
						for (int i = 1; i < specParts.size(); i++) {
							walk(specParts.get(i), env, deadArms);
						}
					}
					walkSequence(parts, 2, inner, deadArms);
					return ValueSet.ofTop();
				}
				case LispNames.DO, LispNames.DO_STAR -> {
					Env inner = env.child();
					if (parts.size() > 1 && parts.get(1) instanceof LispCons specs && specs.isProperList()) {
						for (LispVal spec : specs.toList()) {
							if (spec instanceof LispSymbol var) {
								inner.bindTop(var.name());
							}
							else if (spec instanceof LispCons specCons && specCons.car() instanceof LispSymbol var) {
								inner.bindTop(var.name());
								for (LispVal rest = specCons.cdr(); rest instanceof LispCons cell; rest = cell.cdr()) {
									walk(cell.car(), inner, deadArms);
								}
							}
						}
					}
					walkSequence(parts, 2, inner, deadArms);
					return ValueSet.ofTop();
				}
				case LispNames.LOOP, LispNames.MACROLET, LispNames.SYMBOL_MACROLET -> {
					// Binding structure not modeled: walk subforms under an opaque env
					// so call sites still contribute, conservatively.
					Env opaque = Env.opaque();
					for (int i = 1; i < parts.size(); i++) {
						walk(parts.get(i), opaque, deadArms);
					}
					return ValueSet.ofTop();
				}
				case LispNames.HANDLER_CASE, LispNames.RESTART_CASE, LispNames.IGNORE_ERRORS,
						LispNames.HANDLER_BIND -> {
					Env opaque = Env.opaque();
					for (int i = 1; i < parts.size(); i++) {
						walk(parts.get(i), opaque, deadArms);
					}
					return ValueSet.ofTop();
				}
				case LispNames.DECLARE, LispNames.DECLAIM -> {
					return ValueSet.ofConstant(nilConstant());
				}
				case LispNames.FUNCALL, LispNames.APPLY -> {
					String target = parts.size() > 1 ? literalFunctionDesignator(parts.get(1)) : null;
					if (target != null && this.functions.containsKey(target)) {
						return walkCall(target, parts, 2, LispNames.APPLY.equals(member), env, deadArms);
					}
					CtorState ctor = target == null ? null : this.ctorsByName.get(target);
					if (ctor != null) {
						return walkCtorCall(ctor, parts, 2, LispNames.APPLY.equals(member), env, deadArms);
					}
					walkSequence(parts, 1, env, deadArms);
					return ValueSet.ofTop();
				}
				case LispNames.ERROR -> {
					walkSequence(parts, 1, env, deadArms);
					// error never returns: a tail (error ...) contributes no value.
					return ValueSet.empty();
				}
				case LispNames.MAKE_INSTANCE, LispNames.MAKE_CONDITION -> {
					if (LispNames.MAKE_INSTANCE.equals(member)) {
						String className = parts.size() > 1 ? quotedSymbolName(parts.get(1)) : null;
						StructFlow struct = className == null ? null : this.structsByName.get(className);
						if (struct != null) {
							return walkKeywordInit(struct, parts, 2, env, deadArms);
						}
						if (className == null) {
							// A computed class can name a tracked struct; its initargs
							// are writes this walk cannot map.
							topAllSlots();
						}
					}
					walkSequence(parts, 1, env, deadArms);
					return ValueSet.ofNonKey();
				}
				default -> {
					CtorState ctor = this.ctorsByName.get(op.name());
					if (ctor != null && !this.functions.containsKey(op.name())) {
						return walkCtorCall(ctor, parts, 1, false, env, deadArms);
					}
					if (this.instanceConstructors.contains(op.name())) {
						if (ctor == null) {
							// An instantiator the slot summary could not attribute.
							topAllSlots();
						}
						walkSequence(parts, 1, env, deadArms);
						return ValueSet.ofNonKey();
					}
					SlotState slot = this.slotsByAccessor.get(op.name());
					if (slot != null && parts.size() == 2 && !this.functions.containsKey(op.name())) {
						// A slot READ: bounded by everything the program can store there,
						// whatever instance the argument holds.
						walkAt(parts, 1, env, deadArms);
						return slot.set;
					}
					if (this.functions.containsKey(op.name())) {
						return walkCall(op.name(), parts, 1, false, env, deadArms);
					}
					walkSequence(parts, 1, env, deadArms);
					return ValueSet.ofTop();
				}
			}
		}

		/**
		 * A call to a tracked struct constructor: contribute the argument values to the
		 * slots the constructor's mapping says they initialize, and the INITFORM values
		 * to every slot this call may leave defaulted.
		 */
		private ValueSet walkCtorCall(CtorState ctor, List<LispVal> parts, int firstArg, boolean apply, Env env,
				@Nullable Set<LispCons> deadArms) {
			List<ValueSet> argSets = new ArrayList<>(parts.size() - firstArg);
			for (int i = firstArg; i < parts.size(); i++) {
				argSets.add(walk(parts.get(i), env, deadArms));
			}
			StructFlow struct = ctor.struct;
			if (struct.opaque) {
				return ValueSet.ofNonKey();
			}
			if (apply) {
				// The spread hides positions and keywords alike.
				topSlots(struct);
				return ValueSet.ofNonKey();
			}
			if (ctor.boaRequired == null) {
				return walkSuppliedKeywords(struct, parts, firstArg, argSets);
			}
			Map<String, ValueSet> supplied = new HashMap<>();
			for (int i = 0; i < ctor.boaRequired.size() && i < argSets.size(); i++) {
				supplied.put(ctor.boaRequired.get(i), argSets.get(i));
			}
			for (SlotBinding binding : struct.slots) {
				ValueSet value = supplied.get(binding.member());
				if (value != null) {
					if (binding.state().set.addAll(value)) {
						this.dirty = true;
					}
				}
				else if (ctor.boaHasMarkers) {
					// An &optional/&key/&aux section may write this slot from forms the
					// mapping does not model.
					if (binding.state().set.makeTop()) {
						this.dirty = true;
					}
				}
				else if (!ctor.boaRequired.contains(binding.member())) {
					// A required-but-unsupplied slot means the call errors before the
					// body runs; everything else defaults to its initform.
					joinInitform(binding);
				}
			}
			return ValueSet.ofNonKey();
		}

		/** {@code (make-x :slot v ...)} / {@code (make-instance 'x :slot v ...)}. */
		private ValueSet walkKeywordInit(StructFlow struct, List<LispVal> parts, int firstArg, Env env,
				@Nullable Set<LispCons> deadArms) {
			List<ValueSet> argSets = new ArrayList<>(parts.size() - firstArg);
			for (int i = firstArg; i < parts.size(); i++) {
				argSets.add(walk(parts.get(i), env, deadArms));
			}
			if (struct.opaque) {
				return ValueSet.ofNonKey();
			}
			return walkSuppliedKeywords(struct, parts, firstArg, argSets);
		}

		private ValueSet walkSuppliedKeywords(StructFlow struct, List<LispVal> parts, int firstArg,
				List<ValueSet> argSets) {
			Map<String, ValueSet> supplied = new HashMap<>();
			for (int i = 0; i + 1 < argSets.size(); i += 2) {
				if (parts.get(firstArg + i) instanceof LispSymbol kw && kw.isKeyword()) {
					// The first occurrence of a keyword wins in CL; joining every
					// occurrence only widens.
					supplied.merge(LispSymbol.memberName(kw.name().substring(1)), argSets.get(i + 1),
							(a, b) -> joined(a, b));
				}
				else {
					// A computed keyword: no mapping can be trusted.
					topSlots(struct);
					return ValueSet.ofNonKey();
				}
			}
			if ((argSets.size() & 1) != 0) {
				// An odd initarg list errors at run time; contribute nothing extra.
				topSlots(struct);
				return ValueSet.ofNonKey();
			}
			for (SlotBinding binding : struct.slots) {
				ValueSet value = supplied.get(binding.member());
				if (value != null) {
					if (binding.state().set.addAll(value)) {
						this.dirty = true;
					}
				}
				else {
					joinInitform(binding);
				}
			}
			return ValueSet.ofNonKey();
		}

		private static ValueSet joined(ValueSet a, ValueSet b) {
			ValueSet out = new ValueSet();
			out.addAll(a);
			out.addAll(b);
			return out;
		}

		/**
		 * The initform's value joins the slot when a constructor call may leave the slot
		 * defaulted. Walked with no dead-arm sink: the initform's text lives in the
		 * DEFSTRUCT form, whose provenance is not this call site's.
		 */
		private void joinInitform(SlotBinding binding) {
			ValueSet value = walk(binding.initform(), Env.root(), null);
			if (binding.state().set.addAll(value)) {
				this.dirty = true;
			}
		}

		/**
		 * What a setf-family PLACE does to tracked slots: a direct accessor place joins
		 * the written value, the read-modify-write places {@code expandSetf} lowers onto
		 * an inner place recurse ({@code ldb}/{@code mask-field}/{@code getf} rewrite the
		 * inner place from a computed value, {@code the} passes the value through), a
		 * {@code slot-value} place writes by slot NAME, and any other cons place mutates
		 * an object without touching a tracked slot. Subexpressions are walked here so
		 * the caller does not walk the place as an expression as well.
		 */
		private void slotWritePlace(LispVal place, ValueSet value, Env env, @Nullable Set<LispCons> deadArms) {
			if (!(place instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
				return;
			}
			List<LispVal> parts = cons.toList();
			SlotState slot = this.slotsByAccessor.get(op.name());
			if (slot != null && parts.size() == 2) {
				walkAt(parts, 1, env, deadArms);
				if (slot.set.addAll(value)) {
					this.dirty = true;
				}
				return;
			}
			switch (LispSymbol.memberName(op.name())) {
				case "SLOT-VALUE" -> {
					walkSequence(parts, 1, env, deadArms);
					String member = parts.size() > 2 ? quotedSymbolMember(parts.get(2)) : null;
					if (member != null) {
						topSlotsNamed(member);
					}
					else {
						topAllSlots();
					}
				}
				case "LDB", "MASK-FIELD" -> {
					walkAt(parts, 1, env, deadArms);
					if (parts.size() > 2) {
						slotWritePlace(parts.get(2), ValueSet.ofTop(), env, deadArms);
					}
				}
				case "THE" -> {
					if (parts.size() > 2) {
						slotWritePlace(parts.get(2), value, env, deadArms);
					}
				}
				case "GETF" -> {
					if (parts.size() > 1) {
						slotWritePlace(parts.get(1), ValueSet.ofTop(), env, deadArms);
					}
					walkSequence(parts, 2, env, deadArms);
				}
				case "VALUES" -> {
					for (int i = 1; i < parts.size(); i++) {
						slotWritePlace(parts.get(i), ValueSet.ofTop(), env, deadArms);
					}
				}
				default ->
					// (aref ...), (gethash ...), (car ...), ...: object mutation, no
					// tracked slot changes hands.
					walkSequence(parts, 1, env, deadArms);
			}
		}

		private void topSlotsNamed(String member) {
			for (StructFlow flow : this.structsByName.values()) {
				for (SlotBinding binding : flow.slots) {
					if (binding.member().equals(member) && binding.state().set.makeTop()) {
						this.dirty = true;
					}
				}
			}
		}

		/** The symbol a literal {@code 'name} argument names, or null. */
		private static @Nullable String quotedSymbolName(LispVal form) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
					&& cons.cdr() instanceof LispCons cell && cell.car() instanceof LispSymbol sym) {
				return sym.name();
			}
			return null;
		}

		private static @Nullable String quotedSymbolMember(LispVal form) {
			String name = quotedSymbolName(form);
			return name == null ? null : LispSymbol.memberName(name);
		}

		/**
		 * A call to a tracked name: contribute argument values to every target's params.
		 */
		private ValueSet walkCall(String target, List<LispVal> parts, int firstArg, boolean apply, Env env,
				@Nullable Set<LispCons> deadArms) {
			int argCount = parts.size() - firstArg;
			// apply's LAST argument is spread: positions at and past it are unknown.
			int fixedArgs = apply ? argCount - 1 : argCount;
			List<ValueSet> argSets = new ArrayList<>(argCount);
			for (int i = firstArg; i < parts.size(); i++) {
				argSets.add(walk(parts.get(i), env, deadArms));
			}
			ValueSet result = ValueSet.empty();
			List<Fn> targets = this.functions.get(target);
			if (targets == null) {
				return ValueSet.ofTop();
			}
			for (Fn fn : targets) {
				result.addAll(fn.returnSet);
				if (fn.escaped) {
					continue;
				}
				for (int p = 0; p < fn.paramSets.length; p++) {
					boolean changed;
					if (p < fixedArgs) {
						changed = fn.paramSets[p].addAll(argSets.get(p));
					}
					else if (p < argCount || apply) {
						// The spread region: no static mapping.
						changed = fn.paramSets[p].makeTop();
					}
					else {
						// Fewer arguments than required parameters: the call errors
						// before the body runs and contributes nothing.
						changed = false;
					}
					if (changed) {
						this.dirty = true;
					}
				}
			}
			return result;
		}

		private ValueSet walkCase(List<LispVal> parts, boolean ecase, Env env, @Nullable Set<LispCons> deadArms) {
			ValueSet subject = walkAt(parts, 1, env, deadArms);
			ValueSet result = ValueSet.empty();
			boolean sawDefault = false;
			for (int i = 2; i < parts.size(); i++) {
				if (!(parts.get(i) instanceof LispCons clause)) {
					continue;
				}
				boolean isDefault = !ecase && isCaseDefaultHead(clause.car());
				sawDefault |= isDefault;
				boolean reachable = isDefault || subject.isTop() || subject.mayMatchAnyKey(caseKeysOf(clause.car()));
				if (reachable) {
					ValueSet tail = ValueSet.ofConstant(nilConstant());
					for (LispVal rest = clause.cdr(); rest instanceof LispCons cell; rest = cell.cdr()) {
						tail = walk(cell.car(), env, deadArms);
					}
					result.addAll(tail);
				}
				else if (deadArms != null && !subject.isEmpty()) {
					// Provably unreachable at the fixpoint: the arm never runs, so
					// deleting it preserves every execution. An empty subject set means
					// the subject expression itself cannot produce a value (dead code
					// upstream) -- leave that alone rather than judge it.
					deadArms.add(clause);
				}
			}
			if (!ecase && !sawDefault) {
				result.addAll(ValueSet.ofConstant(nilConstant()));
			}
			return result;
		}

		private static boolean isCaseDefaultHead(LispVal head) {
			return head instanceof LispTrue || head instanceof LispSymbol sym
					&& ("T".equals(sym.name()) || LispNames.OTHERWISE.equals(LispSymbol.memberName(sym.name())));
		}

		/** The eql-comparable constants of one case-clause head (a key or a key list). */
		private static List<Object> caseKeysOf(LispVal head) {
			List<Object> keys = new ArrayList<>();
			if (head instanceof LispCons list) {
				for (LispVal rest = list; rest instanceof LispCons cell; rest = cell.cdr()) {
					addKey(cell.car(), keys);
				}
			}
			else {
				addKey(head, keys);
			}
			return keys;
		}

		private static void addKey(LispVal key, List<Object> keys) {
			switch (key) {
				case LispSymbol sym -> keys.add(symbolConstant(sym.name()));
				case LispInteger n -> keys.add(n.value());
				case LispChar c -> keys.add(charConstant(c.codePoint()));
				case LispNil ignored -> keys.add(nilConstant());
				case LispTrue ignored -> keys.add(symbolConstant("T"));
				default -> {
					// A float/string/list key can never eql a tracked constant; see
					// mayMatchAnyKey.
				}
			}
		}

		private ValueSet walkLet(List<LispVal> parts, boolean sequential, Env env, @Nullable Set<LispCons> deadArms) {
			Env inner = env.child();
			if (parts.size() > 1 && parts.get(1) instanceof LispCons bindings && bindings.isProperList()) {
				for (LispVal binding : bindings.toList()) {
					if (binding instanceof LispSymbol var) {
						inner.bind(var.name(), ValueSet.ofConstant(nilConstant()));
					}
					else if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol var) {
						ValueSet init = pair.cdr() instanceof LispCons initCell
								? walk(initCell.car(), sequential ? inner : env, deadArms)
								: ValueSet.ofConstant(nilConstant());
						inner.bind(var.name(), init);
					}
				}
			}
			return walkSequence(parts, 2, inner, deadArms);
		}

		private void walkLambdaLike(List<LispVal> parts, int lambdaListAt, Env env, @Nullable Set<LispCons> deadArms) {
			Env inner = env.child();
			if (parts.size() > lambdaListAt) {
				bindAllSymbols(parts.get(lambdaListAt), inner);
			}
			walkSequence(parts, lambdaListAt + 1, inner, deadArms);
		}

		private static void bindAllSymbols(LispVal pattern, Env env) {
			switch (pattern) {
				case LispSymbol sym -> env.bindTop(sym.name());
				case LispCons cons -> {
					bindAllSymbols(cons.car(), env);
					bindAllSymbols(cons.cdr(), env);
				}
				default -> {
				}
			}
		}

		private ValueSet walkSequence(List<LispVal> parts, int from, Env env, @Nullable Set<LispCons> deadArms) {
			ValueSet last = ValueSet.ofConstant(nilConstant());
			for (int i = from; i < parts.size(); i++) {
				last = walk(parts.get(i), env, deadArms);
			}
			return last;
		}

		private ValueSet walkAt(List<LispVal> parts, int index, Env env, @Nullable Set<LispCons> deadArms) {
			return index < parts.size() ? walk(parts.get(index), env, deadArms) : ValueSet.ofConstant(nilConstant());
		}

		private static ValueSet quotedValue(LispVal datum) {
			return switch (datum) {
				case LispSymbol sym -> ValueSet.ofConstant(symbolConstant(sym.name()));
				case LispInteger n -> ValueSet.ofConstant(n.value());
				case LispChar c -> ValueSet.ofConstant(charConstant(c.codePoint()));
				case LispNil ignored -> ValueSet.ofConstant(nilConstant());
				case LispTrue ignored -> ValueSet.ofConstant(symbolConstant("T"));
				// Quoted structure can be eql to a key only through object sharing the
				// analysis does not model.
				default -> ValueSet.ofTop();
			};
		}

		/**
		 * Marks every assignment target in the tree TOP up front (flow-insensitive):
		 * {@code setq}/{@code psetq}/{@code multiple-value-setq}, a {@code setf}-family
		 * form whose place is a bare symbol, and every {@code rotatef}/{@code shiftf}
		 * argument. A non-symbol place mutates an object, not a lexical binding.
		 */
		private static void poisonSetqTargets(LispVal form, Env env) {
			if (!(form instanceof LispCons cons)) {
				return;
			}
			if (cons.car() instanceof LispSymbol op && cons.isProperList()) {
				String member = LispSymbol.memberName(op.name());
				List<LispVal> parts = cons.toList();
				switch (member) {
					case LispNames.QUOTE -> {
						return;
					}
					// A CONS place can still assign a variable inside it: setf-of-ldb
					// is read-modify-write over its integer argument -- cl-postgres's
					// generated read-uint4 does (setf (ldb (byte 8 24) result) ...) --
					// and setf-of-getf rewrites its plist variable. Poison every
					// symbol inside a non-symbol place.
					case LispNames.SETQ, LispNames.PSETQ, "SETF", "PSETF" -> {
						for (int i = 1; i < parts.size(); i += 2) {
							poisonPlace(parts.get(i), env);
						}
					}
					case "MULTIPLE-VALUE-SETQ" -> {
						if (parts.size() > 1) {
							poisonAllSymbols(parts.get(1), env);
						}
					}
					case "INCF", "DECF", "POP" -> {
						if (parts.size() > 1) {
							poisonPlace(parts.get(1), env);
						}
					}
					case "PUSH", "PUSHNEW" -> {
						if (parts.size() > 2) {
							poisonPlace(parts.get(2), env);
						}
					}
					case "ROTATEF", "SHIFTF" -> {
						for (int i = 1; i < parts.size(); i++) {
							poisonPlace(parts.get(i), env);
						}
					}
					default -> {
					}
				}
			}
			poisonSetqTargets(cons.car(), env);
			poisonSetqTargets(cons.cdr(), env);
		}

		/**
		 * Poisons the variables a place ASSIGNS. A bare symbol is the variable itself. A
		 * cons place mutates an object and leaves every binding unchanged -- with the
		 * read-modify-write exceptions {@code expandSetf} implements over an inner place:
		 * {@code ldb}/{@code mask-field} (arg 2), {@code getf} (arg 1), {@code the} (arg
		 * 2) and {@code values} (each subplace), which recurse. cl-postgres's generated
		 * {@code read-uint4} is the load-bearing case:
		 * {@code (setf (ldb (byte 8 24) result) ...)} assigns RESULT, and treating it as
		 * object mutation folded its callers' {@code ecase} arms; poisoning EVERY cons
		 * place instead cost the whole chipz fold ({@code (setf (dstate-checksum
		 * state) ...)} does not change what STATE is bound to).
		 */
		private static void poisonPlace(LispVal place, Env env) {
			if (place instanceof LispSymbol target) {
				env.poison(target.name());
				return;
			}
			if (place instanceof LispCons cons && cons.car() instanceof LispSymbol op && cons.isProperList()) {
				List<LispVal> parts = cons.toList();
				switch (LispSymbol.memberName(op.name())) {
					case "LDB", "MASK-FIELD", "THE" -> {
						if (parts.size() > 2) {
							poisonPlace(parts.get(2), env);
						}
					}
					case "GETF" -> {
						if (parts.size() > 1) {
							poisonPlace(parts.get(1), env);
						}
					}
					case "VALUES" -> {
						for (int i = 1; i < parts.size(); i++) {
							poisonPlace(parts.get(i), env);
						}
					}
					default -> {
					}
				}
			}
		}

		private static void poisonAllSymbols(LispVal pattern, Env env) {
			switch (pattern) {
				case LispSymbol sym -> env.poison(sym.name());
				case LispCons cons -> {
					poisonAllSymbols(cons.car(), env);
					poisonAllSymbols(cons.cdr(), env);
				}
				default -> {
				}
			}
		}

		private static final Set<String> RETURN_NAMES = Set.of(LispNames.RETURN, LispNames.RETURN_FROM);

		private static boolean containsAnySymbol(LispVal form, Set<String> names) {
			return switch (form) {
				case LispSymbol sym -> names.contains(LispSymbol.memberName(sym.name()));
				case LispCons cons -> containsAnySymbol(cons.car(), names) || containsAnySymbol(cons.cdr(), names);
				default -> false;
			};
		}

		/** {@code #'f} or {@code 'f} in funcall/apply head position, or null. */
		private static @Nullable String literalFunctionDesignator(LispVal arg) {
			if (arg instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& cons.cdr() instanceof LispCons cell && cell.car() instanceof LispSymbol name
					&& (LispNames.FUNCTION.equals(op.name()) || LispNames.QUOTE.equals(op.name()))) {
				return name.name();
			}
			return null;
		}

	}

	// ------------------------------------------------------------------
	// Environments
	// ------------------------------------------------------------------

	/**
	 * A lexical environment for the value walk: parameter names bound to their global
	 * join sets, locals to per-iteration sets. An OPAQUE env answers TOP for every name
	 * (used under binders whose structure is not modeled, e.g. {@code loop}). The poison
	 * set (assignment targets) is shared per top-level walk.
	 */
	private static final class Env {

		private final @Nullable Env parent;

		private final Map<String, ValueSet> bindings = new HashMap<>();

		private final Set<String> poisoned;

		private final boolean opaque;

		private Env(@Nullable Env parent, boolean opaque) {
			this.parent = parent;
			this.opaque = opaque;
			this.poisoned = parent != null ? parent.poisoned : new HashSet<>();
		}

		static Env of(Fn fn) {
			Env env = new Env(null, false);
			for (int i = 0; i < fn.requiredParams.size(); i++) {
				env.bindings.put(fn.requiredParams.get(i), fn.paramSets[i]);
			}
			return env;
		}

		static Env root() {
			return new Env(null, false);
		}

		static Env opaque() {
			return new Env(null, true);
		}

		Env child() {
			return new Env(this, this.opaque);
		}

		void bind(String name, ValueSet value) {
			this.bindings.put(name, value);
		}

		void bindTop(String name) {
			this.bindings.put(name, ValueSet.ofTop());
		}

		void poison(String name) {
			this.poisoned.add(name);
		}

		@Nullable ValueSet lookup(String name) {
			if (this.poisoned.contains(name)) {
				return ValueSet.ofTop();
			}
			for (Env env = this; env != null; env = env.parent) {
				ValueSet bound = env.bindings.get(name);
				if (bound != null) {
					return bound;
				}
				if (env.opaque) {
					return ValueSet.ofTop();
				}
			}
			return null;
		}

	}

	// ------------------------------------------------------------------
	// Constants
	// ------------------------------------------------------------------

	/**
	 * A symbol constant, normalized so {@code PKG:X} and {@code PKG::X} compare equal.
	 */
	private static Object symbolConstant(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : PackageRegistry.qualify(qn.pkg(), qn.member());
	}

	private static Object nilConstant() {
		return "NIL";
	}

	private static Object charConstant(int codePoint) {
		// Code points above the BMP collide after the cast; a collision only KEEPS an
		// arm, never deletes one.
		return Character.valueOf((char) codePoint);
	}

	private static Set<String> spellingsOf(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		if (qn == null) {
			return Set.of(name);
		}
		return Set.of(PackageRegistry.qualify(qn.pkg(), qn.member()),
				PackageRegistry.qualifyInternal(qn.pkg(), qn.member()));
	}

	// ------------------------------------------------------------------
	// Rewriting (shared with the pruner's gated-arm deletion)
	// ------------------------------------------------------------------

	/**
	 * Deletes clause conses (identified by IDENTITY in the resolved tree) from a form,
	 * rebuilding only the spine cells that change and inheriting source positions
	 * ({@code .kb/source-positions.md}). {@code withoutArmsParallel} applies the same
	 * deletions to the pre-resolution twin by walking both trees in lockstep; a
	 * structural mismatch between the twins keeps the original subtree (defensive: the
	 * resolver rewrites symbols, not structure, inside a definition body).
	 */
	static final class FormRewriter {

		private FormRewriter() {
		}

		static LispVal withoutArms(LispVal resolved, Set<LispCons> deadArms) {
			if (!(resolved instanceof LispCons cons)) {
				return resolved;
			}
			LispVal cdr = withoutArms(cons.cdr(), deadArms);
			if (cons.car() instanceof LispCons carCons && deadArms.contains(carCons)) {
				// The element itself is deleted: splice the cell out.
				return cdr;
			}
			LispVal car = withoutArms(cons.car(), deadArms);
			return SourceProvenance.inherit(cons, LispCons.rebuilt(cons, car, cdr));
		}

		static LispVal withoutArmsParallel(LispVal original, LispVal resolved, Set<LispCons> deadArms) {
			if (!(original instanceof LispCons origCons) || !(resolved instanceof LispCons resCons)) {
				return original;
			}
			LispVal cdr = withoutArmsParallel(origCons.cdr(), resCons.cdr(), deadArms);
			if (resCons.car() instanceof LispCons carCons && deadArms.contains(carCons)) {
				return cdr;
			}
			LispVal car = withoutArmsParallel(origCons.car(), resCons.car(), deadArms);
			return SourceProvenance.inherit(origCons, LispCons.rebuilt(origCons, car, cdr));
		}

	}

}
