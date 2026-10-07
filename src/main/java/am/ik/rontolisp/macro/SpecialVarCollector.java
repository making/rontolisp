package am.ik.rontolisp.macro;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;

/**
 * Collects the names of <em>special</em> (dynamically bound) variables in a program.
 * Shared by the interpreter and both compilers so all three agree on which names get
 * dynamic-extent binding under {@code let}/{@code let*} instead of a fresh lexical slot.
 *
 * <p>
 * A name is PROCLAIMED special when Common Lisp would proclaim it: it is the name of a
 * top-level {@code defvar}/{@code defparameter}/{@code defconstant}, or it appears in a
 * {@code (special ...)} clause of a top-level {@code declaim} or {@code proclaim}. Every
 * binding of a proclaimed name is dynamic and every reference reads its dynamic binding.
 * A local {@code (declare (special x))} makes only the binding it names and the
 * references in its body special ({@link am.ik.rontolisp.SpecialDeclarations}): the
 * interpreter reads the declarations of each binding form it evaluates
 * ({@link #collectProclaimed} is its special set), and the compile paths rename the other
 * bindings of such a name apart first ({@code compiler.SpecialDeclarationScoping}), after
 * which every occurrence of it is special and it joins the set {@link #collect} answers.
 * The earmuffs convention ({@code *x*}) is a style hint, not the mechanism: a variable is
 * special because it was declared, not because of its name.
 *
 * <p>
 * Runs against the resolved, top-level-flattened program before macro expansion turns
 * {@code declaim}/{@code proclaim} into {@code nil}, alongside the compiler's
 * {@code GlobalVarCollector} (special variables are a subset of the globals it collects;
 * each still gets a global backing store, and dynamic binding is save/restore over that
 * store on the compile path). Lives in the shared AST package because the interpreter --
 * which may not depend on the {@code compiler} package -- needs it too.
 */
public final class SpecialVarCollector {

	/**
	 * The stream specials every program gets for free, in the order they are minted as
	 * globals. A {@link SequencedSet}, not a {@code Set.of}: a {@code progv} makes
	 * {@link #collectDynamicallyBound} sweep this whole set into its result, and that
	 * result fixes the order both compilers mint their static fields in --
	 * {@code ImmutableCollections} re-salts its iteration order once per JVM process, so
	 * a {@code Set.of} here emitted a different-but-equivalent class on every compile
	 * (.kb/emitted-output-determinism.md).
	 */
	private static final SequencedSet<String> SEEDED_STREAM_SPECIALS = Collections
		.unmodifiableSequencedSet(new LinkedHashSet<>(
				List.of(LispNames.STANDARD_OUTPUT_VAR, LispNames.STANDARD_INPUT_VAR, LispNames.ERROR_OUTPUT_VAR)));

	private SpecialVarCollector() {
	}

	/**
	 * Returns the set of special-variable names declared by the given top-level forms:
	 * the proclaimed ones and, on the compile paths, the locally declared ones too --
	 * once {@code compiler.SpecialDeclarationScoping} has renamed a locally declared
	 * name's lexical bindings apart, every occurrence left of it is special.
	 * @param topLevelExprs the top-level forms
	 * @return the special variable names in declaration order
	 */
	public static LinkedHashSet<String> collect(List<LispVal> topLevelExprs) {
		LinkedHashSet<String> specials = new LinkedHashSet<>();
		for (LispVal expr : topLevelExprs) {
			collectForm(expr, specials);
			collectLocalDeclares(expr, specials);
		}
		return specials;
	}

	/**
	 * Records the special names a single form proclaims (a {@code defvar}-family form or
	 * a {@code declaim}/{@code proclaim} carrying {@code (special ...)} clauses), and the
	 * seeded stream specials it binds, into the given set. Used both by
	 * {@link #collect(List)} and by the interpreter, which discovers specials
	 * incrementally as it evaluates top-level forms. A local special declaration is not
	 * one ({@link #collectLocallyDeclared}).
	 * @param form the form to inspect
	 * @param out the set to add discovered special names to
	 */
	public static void collectForm(LispVal form, Set<String> out) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return;
		}
		// The seeded stream specials: `*standard-output*` / `*standard-input*` /
		// `*error-output*` are special by contract (the stream-argument-less print and
		// read families and `warn` read them at call time), but registering them
		// unconditionally would force the dynamic-binding machinery onto every program.
		// So each becomes special exactly when the program binds it somewhere --
		// directly or via `(with-output-to-string (*standard-output*) ...)` /
		// `(with-input-from-string (*standard-input* s) ...)` -- which is also the only
		// case whose behavior differs from the plain-stdio default.
		out.addAll(collectDynamicallyBound(List.of(form), SEEDED_STREAM_SPECIALS));
		collectProclaimed(form, out);
	}

	/**
	 * Records the names a form's local {@code (declare (special ...))} declarations name,
	 * anywhere inside it (quoted data skipped). Such a name is special only where a
	 * declaration covers it; the interpreter keeps the set so that a free reference to
	 * one outside every declaration -- an undefined variable CL implementations treat as
	 * special -- still reads an active dynamic binding.
	 * @param form the form to inspect
	 * @param out the set to add the names to
	 */
	public static void collectLocallyDeclared(LispVal form, Set<String> out) {
		collectLocalDeclares(form, out);
	}

	/**
	 * The names the program's local special declarations name and nothing proclaims
	 * special: the names whose OTHER bindings are lexical, which the compile paths rename
	 * apart ({@code compiler.SpecialDeclarationScoping}). A {@code cl} symbol is never
	 * one -- every standard variable is proclaimed special, by the backends if not by the
	 * program.
	 * @param program the program's top-level forms
	 * @return those names, in first-declaration order
	 */
	public static LinkedHashSet<String> collectLocallyDeclaredOnly(List<LispVal> program) {
		LinkedHashSet<String> local = new LinkedHashSet<>();
		for (LispVal form : program) {
			collectLocalDeclares(form, local);
		}
		if (local.isEmpty()) {
			return local;
		}
		Set<String> proclaimed = new HashSet<>();
		for (LispVal form : program) {
			collectProclaimed(form, proclaimed);
		}
		local.removeIf(name -> proclaimed.contains(name) || PackageRegistry.isClSymbol(name)
				|| PackageRegistry.isClSymbol(member(name)));
		return local;
	}

	/**
	 * The special names a program DECLARES ({@link #collectDeclared(LispVal, Set)} over
	 * every form): what is known of the specials before the compile paths' late passes
	 * declare the standard variables a program reads -- enough for the lambda-list
	 * desugaring, which needs to know a supplied-p variable named like one
	 * ({@code LambdaLists.desugarProgram}).
	 * @param topLevelExprs the top-level forms
	 * @return the declared special names
	 */
	public static Set<String> collectDeclared(List<LispVal> topLevelExprs) {
		Set<String> declared = new java.util.HashSet<>();
		for (LispVal expr : topLevelExprs) {
			collectDeclared(expr, declared);
		}
		return declared;
	}

	/**
	 * Records the special names a single form DECLARES -- {@link #collectProclaimed} and
	 * {@link #collectLocallyDeclared} together, without the seeded stream specials a
	 * binding makes special. One linear walk, no macro expansion.
	 * @param form the form to inspect
	 * @param out the set to add discovered special names to
	 */
	public static void collectDeclared(LispVal form, Set<String> out) {
		collectProclaimed(form, out);
		collectLocalDeclares(form, out);
	}

	/**
	 * Records the special names a single form PROCLAIMS: the name of a
	 * {@code defvar}-family form and the {@code (special ...)} clauses of a
	 * {@code declaim}/{@code proclaim}.
	 * @param form the form to inspect
	 * @param out the set to add discovered special names to
	 */
	public static void collectProclaimed(LispVal form, Set<String> out) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return;
		}
		List<LispVal> parts = cons.toList();
		switch (head.name()) {
			case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
				if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol name) {
					out.add(name.name());
				}
			}
			case LispNames.DECLAIM -> {
				// (declaim (special *a* *b*) (type integer *c*) ...): each argument is a
				// declaration specifier; collect the ones headed by `special`.
				for (int i = 1; i < parts.size(); i++) {
					addSpecialClause(parts.get(i), out);
				}
			}
			case LispNames.PROCLAIM -> {
				// (proclaim '(special *x*)): the single argument is a quoted specifier.
				if (parts.size() >= 2 && parts.get(1) instanceof LispCons quoted && quoted.car() instanceof LispSymbol q
						&& LispNames.QUOTE.equals(q.name()) && quoted.cdr() instanceof LispCons rest) {
					addSpecialClause(rest.car(), out);
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Returns the subset of {@code specials} that is <em>dynamically bound</em> somewhere
	 * in the program -- appears as a binding name of a {@code let}/{@code let*} or as a
	 * parameter of a {@code lambda}/{@code defun} (directly, or inside the expansion of a
	 * built-in binding macro such as {@code do}/{@code
	 * dolist}/{@code loop}/{@code multiple-value-bind}/{@code flet}; user macros are
	 * already expanded in the compile-path program this runs on). The JVM compiler gives
	 * only these names a thread-scoped (ThreadLocal) dynamic store; a special never bound
	 * keeps its plain static-field representation, so the common read stays a single
	 * {@code getstatic}. Over-collection is harmless (the bound representation is
	 * correct, just slower); under-collection is a loud compile error in
	 * {@code JvmLetCompiler}, never a silent process-global binding.
	 * @param topLevelExprs the fully macro-expanded top-level forms
	 * @param specials the special-variable names ({@link #collect}). ITERATION ORDER
	 * REACHES EMITTED BYTES: a {@code progv} anywhere in the program makes the fallback
	 * below copy this set wholesale into the result, which is the order both compilers
	 * mint their global fields in -- hence {@link SequencedSet}, which no {@code Set.of}
	 * satisfies (.kb/emitted-output-determinism.md)
	 * @return the names that are dynamically bound, in first-binding order, with the ones
	 * only the {@code progv} fallback found appended in {@code specials} order
	 */
	public static LinkedHashSet<String> collectDynamicallyBound(List<LispVal> topLevelExprs,
			SequencedSet<String> specials) {
		LinkedHashSet<String> bound = new LinkedHashSet<>();
		if (specials.isEmpty()) {
			return bound;
		}
		for (LispVal expr : topLevelExprs) {
			collectBoundForm(expr, specials, bound);
		}
		// progv binds a RUNTIME-computed list of symbols, so no static walk can name the
		// specials it touches: every special of the program becomes dynamically bound
		// (the make-thread rule -- over-collection is only a small read cost;
		// under-collection here would be a silent process-global binding on the compile
		// paths).
		if (bound.size() < specials.size()) {
			for (LispVal expr : topLevelExprs) {
				if (usesProgv(expr)) {
					bound.addAll(specials);
					break;
				}
			}
		}
		return bound;
	}

	/**
	 * The specials of a program whose BOUND-NESS it probes and nothing but an assignment
	 * or a binding gives a value: no {@code defvar}, {@code defparameter} or
	 * {@code defconstant} with a value names one anywhere, and a {@code boundp} call
	 * names it literally -- or some {@code boundp} call computes its argument, which can
	 * name any of them. A {@code cl} symbol is never one (the backends seed the standard
	 * variables they declare). The compile paths carry the bound-ness of such a special
	 * in its variable when a binding can change it (the caller keeps the dynamically
	 * bound ones): the eval mirror they answer every other name from is written by no
	 * binding, and for good by a store inside one, so {@code (boundp '*x*)} of a
	 * {@code (defvar *x*)} must be T inside {@code (let ((*x* 1)) ...)} and NIL again
	 * after it. A special whose definer gives it a value is bound from that form on; only
	 * a binding made before the form ran could tell the two apart.
	 * @param forms the program's forms, the injected runtime's included
	 * @param specials the program's special-variable names
	 * @return those specials, in {@code specials} order
	 */
	public static LinkedHashSet<String> collectProbedValueless(Collection<LispVal> forms,
			SequencedSet<String> specials) {
		if (specials.isEmpty()) {
			return new LinkedHashSet<>();
		}
		Set<String> probed = new HashSet<>();
		boolean computed = false;
		for (LispVal form : forms) {
			computed |= LispMacroExpander.boundpProbes(form, probed);
		}
		if (!computed && probed.isEmpty()) {
			return new LinkedHashSet<>();
		}
		LinkedHashSet<String> out = collectValueless(forms, specials);
		if (!computed) {
			out.retainAll(probed);
		}
		return out;
	}

	/**
	 * The specials of a program that nothing but an assignment or a binding gives a
	 * value: no {@code defvar} with a value, {@code defparameter} or {@code defconstant}
	 * names one anywhere (scope-blind) -- a {@code (defvar *x*)}, a
	 * {@code (declaim (special *x*))}, a local {@code (declare (special x))} -- and it is
	 * not a {@code cl} symbol (the backends seed the standard variables they declare).
	 * Such a special is unbound until a store or a binding gives it a value: the compile
	 * paths start its variable as the UNBOUND marker, which a store overwrites and a
	 * binding saves and restores, and a read of the marker signals the
	 * {@code unbound-variable} naming it, as the interpreter's read does.
	 * @param forms the program's forms, the injected runtime's included
	 * @param specials the program's special-variable names
	 * @return those specials, in {@code specials} order
	 */
	public static LinkedHashSet<String> collectValueless(Collection<LispVal> forms, SequencedSet<String> specials) {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		if (specials.isEmpty()) {
			return out;
		}
		Set<String> valued = new HashSet<>();
		for (LispVal form : forms) {
			collectValued(form, valued);
		}
		for (String name : specials) {
			if (!valued.contains(name) && !isClName(name)) {
				out.add(name);
			}
		}
		return out;
	}

	/**
	 * The specials a {@code progv} short of values can leave without a value: in a
	 * program that calls {@code progv}, every special but a {@code cl} symbol, since the
	 * symbols a {@code progv} binds are run-time values and any special may be among
	 * them. A symbol past the end of the values is unbound for the extent, so the compile
	 * paths bind each of these to the UNBOUND marker there and test every read of it, as
	 * they do for {@link #collectValueless}'s from the start. A {@code cl} symbol (the
	 * standard variables the backends seed, whose values the runtime also reads outside
	 * any compiled read) is bound to nil instead.
	 * @param program the program's forms
	 * @param specials the program's special-variable names
	 * @return those specials, in {@code specials} order; empty without a {@code progv}
	 */
	public static LinkedHashSet<String> collectProgvUnbindable(Collection<LispVal> program,
			SequencedSet<String> specials) {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		if (specials.isEmpty() || !LispMacroExpander.programCallsProgv(program)) {
			return out;
		}
		for (String name : specials) {
			if (!isClName(name)) {
				out.add(name);
			}
		}
		return out;
	}

	/** Whether the name, or its member name, is a {@code cl} symbol. */
	private static boolean isClName(String name) {
		return PackageRegistry.isClSymbol(name) || PackageRegistry.isClSymbol(member(name));
	}

	/**
	 * The specials a literal {@code boundp} will answer from their variable, read off the
	 * program BEFORE the compile path injects its runtime:
	 * {@link #collectProbedValueless} within the dynamically bound specials. The eval
	 * gate needs the set, and the gate decides what is injected; the injected runtime
	 * spells no probe the program does not, so the set the compilers take after injection
	 * holds this one (they check).
	 * @param program the program's forms
	 * @param reports the condition {@code :report} lambdas, probed like the program
	 * @param specials the program's special-variable names
	 * @param everyBound whether every special is runtime-bindable by name (the JVM's
	 * {@code make-thread} hand-over)
	 * @return those specials, in {@code specials} order
	 */
	public static LinkedHashSet<String> collectProbedValuelessBound(List<LispVal> program, Collection<LispVal> reports,
			SequencedSet<String> specials, boolean everyBound) {
		List<LispVal> probed = new ArrayList<>(program);
		probed.addAll(reports);
		LinkedHashSet<String> tracked = collectProbedValueless(probed, specials);
		if (!tracked.isEmpty() && !everyBound) {
			tracked.retainAll(collectDynamicallyBound(program, specials));
		}
		return tracked;
	}

	/**
	 * The globals a literal {@code (boundp 'G)} names that no {@code defvar} with a
	 * value, {@code defparameter} or {@code defconstant} names anywhere, {@code cl}
	 * symbols aside -- the names whose bound-ness nothing but an assignment gives. In a
	 * program without the eval mirror their variable carries it (an UNBOUND marker until
	 * the first store), which no binding changes for a name that is never bound; a
	 * dynamically bound special is {@link #collectProbedValueless}'s.
	 * @param forms the program's forms, the injected runtime's included
	 * @param globals the candidate names: the program's globals
	 * @return those globals, in {@code globals} order
	 */
	public static LinkedHashSet<String> collectLiterallyProbedValueless(Collection<LispVal> forms,
			SequencedSet<String> globals) {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		Set<String> probed = new HashSet<>();
		for (LispVal form : forms) {
			LispMacroExpander.boundpProbes(form, probed);
		}
		probed.retainAll(globals);
		if (probed.isEmpty()) {
			return out;
		}
		Set<String> valued = new HashSet<>();
		for (LispVal form : forms) {
			collectValued(form, valued);
		}
		for (String name : globals) {
			if (probed.contains(name) && !valued.contains(name) && !PackageRegistry.isClSymbol(name)
					&& !PackageRegistry.isClSymbol(member(name))) {
				out.add(name);
			}
		}
		return out;
	}

	/**
	 * Records the name of every {@code defvar} with a value, {@code defparameter} and
	 * {@code defconstant} in the form, quoted data skipped.
	 */
	private static void collectValued(LispVal form, Set<String> out) {
		while (form instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				String h = head.name();
				if (LispNames.QUOTE.equals(h)) {
					return;
				}
				if ((LispNames.DEFVAR.equals(h) || LispNames.DEFPARAMETER.equals(h) || LispNames.DEFCONSTANT.equals(h))
						&& cons.cdr() instanceof LispCons nameCell && nameCell.car() instanceof LispSymbol name
						&& (!LispNames.DEFVAR.equals(h) || nameCell.cdr() instanceof LispCons)) {
					out.add(name.name());
				}
			}
			collectValued(cons.car(), out);
			form = cons.cdr();
		}
	}

	/** Whether the form contains a {@code progv} head anywhere outside quoted data. */
	private static boolean usesProgv(LispVal form) {
		while (form instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				String h = head.name();
				if (LispNames.QUOTE.equals(h)) {
					return false;
				}
				if (LispNames.PROGV.equals(h)) {
					return true;
				}
			}
			if (usesProgv(cons.car())) {
				return true;
			}
			form = cons.cdr();
		}
		return false;
	}

	private static void collectBoundForm(LispVal form, Set<String> specials, Set<String> out) {
		// The cdr is walked in the loop, so a long list costs no stack.
		while (form instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				String h = head.name();
				if (LispNames.QUOTE.equals(h)) {
					return;
				}
				if (LispNames.LET.equals(h) || LispNames.LET_STAR.equals(h)) {
					List<LispVal> parts = cons.toList();
					if (parts.size() >= 2) {
						LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
						if (bindings instanceof LispCons bindingsCons) {
							for (LispVal binding : bindingsCons.toList()) {
								if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol name) {
									if (specials.contains(name.name())) {
										out.add(name.name());
									}
									collectBoundForm(pair.cdr(), specials, out);
								}
							}
						}
						for (int i = 2; i < parts.size(); i++) {
							collectBoundForm(parts.get(i), specials, out);
						}
					}
					return;
				}
				// A parameter named like a special binds it dynamically, as a let would
				// (LambdaLists.toNative lowers it into one on the compile paths; the
				// interpreter binds it itself).
				boolean lambda = LispNames.LAMBDA.equals(h);
				if ((lambda || LispNames.DEFUN.equals(h)) && cons.isProperList()) {
					List<LispVal> parts = cons.toList();
					int listIndex = lambda ? 1 : 2;
					if (parts.size() > listIndex) {
						collectParameters(parts.get(listIndex), specials, out);
						for (int i = listIndex + 1; i < parts.size(); i++) {
							collectBoundForm(parts.get(i), specials, out);
						}
					}
					return;
				}
				// A write-to-string keyword BINDS the printer variable it names: the
				// Pass-2
				// lowering (LispMacroExpander.expandWriteToStringKeywords) turns the call
				// into a let of the variable, which this walk must see -- the one binding
				// form no expansion step above reveals. Without this, a program whose
				// only
				// binding of *print-length* is (write-to-string x :length 1) failed the
				// JVM compile with "dynamically bound here but has no thread-local
				// store".
				if (LispNames.WRITE_TO_STRING.equals(h) && cons.isProperList() && cons.toList().size() > 2) {
					LispVal lowered = null;
					try {
						lowered = LispMacroExpander.expandWriteToStringKeywords(cons);
					}
					catch (RuntimeException ignored) {
						// A malformed call is the expression compiler's error to report.
					}
					if (lowered != null) {
						collectBoundForm(lowered, specials, out);
						return;
					}
				}
				// Binding sugar (do/dolist/dotimes/loop/multiple-value-bind/with-*/...)
				// reveals its lets one expansion step at a time; a form the expander
				// rejects (it may validate shapes the compiler checks later) is walked
				// raw instead -- no binding macro both fails to expand AND binds.
				LispVal expansion = null;
				try {
					expansion = LispMacroExpander.expandBuiltinMacro(cons);
				}
				catch (RuntimeException ignored) {
				}
				if (expansion != null && expansion != cons) {
					collectBoundForm(expansion, specials, out);
					return;
				}
			}
			collectBoundForm(cons.car(), specials, out);
			form = cons.cdr();
		}
	}

	/**
	 * Records the special names a lambda list binds -- a required or rest parameter, an
	 * {@code &optional}/{@code &key}/{@code &aux} variable, a supplied-p variable -- and
	 * walks the default forms, in lambda-list order. Any shape: the raw list a reader
	 * wrote (an interpreter's top-level form, a lambda an {@code flet} expansion built)
	 * or the compilers' desugared one.
	 */
	private static void collectParameters(LispVal lambdaList, Set<String> specials, Set<String> out) {
		for (LispVal node = lambdaList; node instanceof LispCons cell; node = cell.cdr()) {
			LispVal param = cell.car();
			if (param instanceof LispSymbol sym) {
				addIfSpecial(sym, specials, out);
				continue;
			}
			if (!(param instanceof LispCons spec)) {
				continue;
			}
			// (var default supplied-p), ((:keyword var) default supplied-p), (var init)
			if (spec.car() instanceof LispSymbol var) {
				addIfSpecial(var, specials, out);
			}
			else if (spec.car() instanceof LispCons keyed && keyed.cdr() instanceof LispCons varCell
					&& varCell.car() instanceof LispSymbol var) {
				addIfSpecial(var, specials, out);
			}
			if (spec.cdr() instanceof LispCons defaultCell) {
				collectBoundForm(defaultCell.car(), specials, out);
				if (defaultCell.cdr() instanceof LispCons suppliedCell
						&& suppliedCell.car() instanceof LispSymbol suppliedP) {
					addIfSpecial(suppliedP, specials, out);
				}
			}
		}
	}

	// A lambda-list keyword is no binding, and the specials never spell one.
	private static void addIfSpecial(LispSymbol name, Set<String> specials, Set<String> out) {
		if (specials.contains(name.name())) {
			out.add(name.name());
		}
	}

	/**
	 * Walks the form for local {@code (declare (special ...))} clauses (skipping quoted
	 * data) and records their names.
	 */
	private static void collectLocalDeclares(LispVal form, Set<String> out) {
		while (form instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				if (LispNames.QUOTE.equals(head.name())) {
					return;
				}
				if (LispNames.DECLARE.equals(member(head.name()))) {
					List<LispVal> parts = cons.toList();
					for (int i = 1; i < parts.size(); i++) {
						addSpecialClause(parts.get(i), out);
					}
					return;
				}
			}
			collectLocalDeclares(cons.car(), out);
			form = cons.cdr();
		}
	}

	/** Strips a package qualifier: {@code pkg::special} matches like {@code special}. */
	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	/**
	 * Adds the names in a {@code (special a b ...)} declaration specifier to the set. The
	 * clause head is matched package-insensitively: {@code special} is not a registered
	 * {@code cl} symbol, so under {@code (in-package p)} the resolver spells it
	 * {@code p::special}.
	 */
	private static void addSpecialClause(LispVal spec, Set<String> out) {
		if (spec instanceof LispCons clause && clause.car() instanceof LispSymbol op
				&& LispNames.SPECIAL.equals(member(op.name()))) {
			List<LispVal> names = clause.toList();
			for (int i = 1; i < names.size(); i++) {
				if (names.get(i) instanceof LispSymbol name) {
					out.add(name.name());
				}
			}
		}
	}

}
