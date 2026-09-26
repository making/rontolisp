package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.macro.SpecialVarCollector;
import org.jspecify.annotations.Nullable;

/**
 * Lowers what the program text says about a variable's host type onto the {@code java:}
 * sites that read it: every reference to a typed variable that is the receiver or an
 * argument of a {@code java:} site becomes {@code (the <spec> v)}. The resolver
 * ({@link JavaSiteResolver}) then needs nothing but the site form itself, so the
 * interpreter and the JVM compiler resolve a site from the same text however each walks
 * scopes -- a variable's type and scope are decided HERE, once, for both.
 * <p>
 * Three things type a variable:
 * <ul>
 * <li>{@code (declare (type (java:object "C") v))} in a binding form's body (or a
 * {@code locally}), for the body;</li>
 * <li>a {@code let}/{@code let*} binding whose variable is lexical and never assigned in
 * its scope takes its initializer's static type ({@link JavaSiteResolver#typeOf}),
 * spelled as the narrowest {@code java:object} specifier that covers it
 * ({@link JavaSiteResolver#specOf}) -- Clojure's local inference;</li>
 * <li>{@code (declaim (type (java:object "C") v))} / {@code (proclaim '(type ...))}, for
 * every form after it in program order, wherever {@code v} is not rebound. A
 * {@code defvar}'s initial value types nothing: any form may assign it.</li>
 * </ul>
 * <p>
 * The walk knows the special forms that bind ({@code let}, {@code let*}, {@code lambda},
 * {@code defun}, {@code defmacro}, {@code defmethod}, {@code handler-case},
 * {@code locally}) and expands every built-in and user macro to see what it binds, but it
 * never replaces a macro form: only the {@code java:} sites it rewrites change, found
 * again in the original form by identity. Where it cannot tell what a form binds (a
 * {@code macrolet}, a built-in operator with no expansion here) it drops every type below
 * that form, and where it cannot tell whether a form assigns a variable it takes the
 * variable as assigned -- a site that loses its type is resolved at run time, never
 * against the wrong class. A declared type is trusted, like every declaration a backend
 * acts on ({@code .kb/declarations-type-checks.md}).
 * <p>
 * One instance serves one program, fed every top-level form in order ({@link #lower}): it
 * keeps the proclaimed types and the names proclaimed special so far, so the interpreter
 * (form by form) and the compiler (over the whole program) see the same proclamations at
 * every site. A form that mentions no {@code java:} symbol is not walked, and a form
 * nothing is rewritten in comes back as the same object.
 */
public final class JavaDeclarations {

	private static final String JAVA_PREFIX = LispNames.JAVA_PKG + ":";

	/** The most nested macro expansions the assignment scan follows. */
	private static final int SCAN_EXPANSION_LIMIT = 512;

	private final JavaSiteResolver resolver;

	// Names proclaimed special by the forms lowered so far: never inferred, since a
	// dynamic binding may be assigned by any function it calls.
	private final Set<String> specials = new HashSet<>();

	// (declaim (type (java:object "C") v)) so far: name -> specifier.
	private final Map<String, LispVal> globals = new HashMap<>();

	/**
	 * @param lookup where the classes the program names are described (the one its sites
	 * resolve against)
	 */
	public JavaDeclarations(JavaClassLookup lookup) {
		this.resolver = new JavaSiteResolver(lookup);
	}

	/**
	 * Whether a form mentions a {@code java:} symbol anywhere.
	 * @param form a form
	 * @return true when it does
	 */
	public static boolean mentionsJava(LispVal form) {
		return mentions(form, new IdentityHashMap<>());
	}

	private static boolean mentions(LispVal form, IdentityHashMap<LispCons, Boolean> seen) {
		LispVal current = form;
		while (current instanceof LispCons cons) {
			if (seen.put(cons, Boolean.TRUE) != null) {
				return false;
			}
			if (mentions(cons.car(), seen)) {
				return true;
			}
			current = cons.cdr();
		}
		return current instanceof LispSymbol sym && sym.name().startsWith(JAVA_PREFIX);
	}

	/**
	 * Lowers one top-level form -- every top-level form of the program must pass here, in
	 * order, whether it mentions {@code java:} or not (its special proclamations count
	 * for the forms after it).
	 * @param form a package-resolved top-level form
	 * @param userMacros expands a user macro call one step (the interpreter's), or
	 * {@code null} where every user macro is already expanded (the compile path)
	 * @return the form with its typed {@code java:} receivers and arguments wrapped, or
	 * {@code form} itself when nothing was
	 */
	public synchronized LispVal lower(LispVal form, LispMacroExpander.@Nullable UserMacroHook userMacros) {
		Walk walk = new Walk(userMacros);
		walk.topLevel(form);
		if (walk.rewrites.isEmpty()) {
			return form;
		}
		return walk.rebuild(form, new IdentityHashMap<>());
	}

	/**
	 * What is in scope at a form: the variables bound or typed around it (a {@code null}
	 * type is a binding that shadows a proclaimed one), and whether the proclaimed types
	 * reach it.
	 */
	private static final class Scope {

		static final Scope TOP = new Scope(Map.of(), true);

		// Below a form this walk cannot see into: nothing declared outside reaches in.
		static final Scope NONE = new Scope(Map.of(), false);

		final Map<String, @Nullable LispVal> vars;

		final boolean proclaimed;

		Scope(Map<String, @Nullable LispVal> vars, boolean proclaimed) {
			this.vars = vars;
			this.proclaimed = proclaimed;
		}

	}

	private @Nullable LispVal lookup(Scope scope, String name) {
		if (scope.vars.containsKey(name)) {
			return scope.vars.get(name);
		}
		return scope.proclaimed ? this.globals.get(name) : null;
	}

	private boolean typesNothing(Scope scope) {
		if (scope.proclaimed && !this.globals.isEmpty()) {
			return false;
		}
		for (LispVal spec : scope.vars.values()) {
			if (spec != null) {
				return false;
			}
		}
		return true;
	}

	/** The scope inside a form that binds {@code names}, then types {@code types}. */
	private static Scope bind(Scope scope, Set<String> names, Map<String, LispVal> types) {
		if (names.isEmpty() && types.isEmpty()) {
			return scope;
		}
		Map<String, @Nullable LispVal> vars = new HashMap<>(scope.vars);
		for (String name : names) {
			vars.put(name, null);
		}
		vars.putAll(types);
		return new Scope(vars, scope.proclaimed);
	}

	/** One top-level form's walk. */
	private final class Walk {

		private final LispMacroExpander.@Nullable UserMacroHook userMacros;

		// Every site rewritten, by the identity of the original site cons.
		private final IdentityHashMap<LispCons, LispCons> rewrites = new IdentityHashMap<>();

		// Whether a cons (as a whole tree) mentions a java: symbol.
		private final IdentityHashMap<LispCons, Boolean> mentionsJava = new IdentityHashMap<>();

		// Each macro form's expansion, expanded once: the walk, the initializer typing
		// and the assignment scan must all see the same objects (a site the walk
		// rewrote in an expansion is found there again by identity).
		private final IdentityHashMap<LispCons, LispVal> expansions = new IdentityHashMap<>();

		// The expansions an initializer's typing walked, so each is walked once.
		private final IdentityHashMap<LispVal, Boolean> walkedExpansions = new IdentityHashMap<>();

		Walk(LispMacroExpander.@Nullable UserMacroHook userMacros) {
			this.userMacros = userMacros;
		}

		/**
		 * A top-level form, with a top-level {@code progn}/{@code eval-when} taken
		 * element by element -- as the compile path's flattened program has them -- so a
		 * proclamation inside counts from the next element on in both.
		 */
		void topLevel(LispVal form) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && cons.isProperList()) {
				int first = switch (head.name()) {
					case LispNames.PROGN -> 1;
					case LispNames.EVAL_WHEN -> 2;
					default -> -1;
				};
				if (first > 0) {
					List<LispVal> parts = cons.toList();
					for (int i = first; i < parts.size(); i++) {
						topLevel(parts.get(i));
					}
					return;
				}
			}
			SpecialVarCollector.collectDeclared(form, JavaDeclarations.this.specials);
			if (!(form instanceof LispCons cons) || mentionsJava(cons)) {
				walk(form, Scope.TOP);
			}
			else if (cons.car() instanceof LispSymbol head
					&& (LispNames.DECLAIM.equals(head.name()) || LispNames.PROCLAIM.equals(head.name()))) {
				// A type proclamation that names no host class ends one that did.
				walkCons(cons, Scope.TOP);
			}
		}

		// --- the scope walk ---

		private void walk(LispVal form, Scope scope) {
			if (!(form instanceof LispCons cons) || !mentionsJava(cons)) {
				return;
			}
			try {
				walkCons(cons, scope);
			}
			catch (RuntimeException ex) {
				// A malformed form or an expansion that fails: whatever it binds is
				// unknown, so its subforms are walked with no type in scope.
				if (!typesNothing(scope)) {
					walkElements(cons, 0, Scope.NONE);
				}
			}
		}

		private void walkCons(LispCons cons, Scope scope) {
			if (!(cons.car() instanceof LispSymbol head)) {
				// ((lambda ...) args): every element is a form.
				walkElements(cons, 0, scope);
				return;
			}
			if (!cons.isProperList()) {
				return;
			}
			String name = head.name();
			JavaSite.Operator operator = JavaSiteResolver.operatorOf(cons);
			if (operator != null) {
				walkElements(cons, 1, scope);
				if (!typesNothing(scope)) {
					rewriteSite(cons, operator, scope);
				}
				return;
			}
			List<LispVal> parts = cons.toList();
			switch (name) {
				case LispNames.QUOTE, LispNames.DECLARE, LispNames.GO -> {
				}
				case LispNames.DECLAIM -> {
					for (int i = 1; i < parts.size(); i++) {
						proclaim(parts.get(i));
					}
				}
				case LispNames.PROCLAIM -> {
					if (parts.size() == 2 && parts.get(1) instanceof LispCons quoted
							&& quoted.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
							&& quoted.cdr() instanceof LispCons rest) {
						proclaim(rest.car());
					}
				}
				case LispNames.FUNCTION -> {
					if (parts.size() == 2 && parts.get(1) instanceof LispCons lambda) {
						walk(lambda, scope);
					}
				}
				case LispNames.LAMBDA -> {
					if (parts.size() >= 2) {
						walkFunction(parts.get(1), parts.subList(2, parts.size()), scope);
					}
				}
				case LispNames.ASYNC_LAMBDA, LispNames.ASYNC_LAMBDA_QUALIFIED -> {
					if (parts.size() >= 2) {
						walkFunction(parts.get(1), parts.subList(2, parts.size()), scope);
					}
				}
				case LispNames.DEFUN, LispNames.DEFMACRO, LispNames.ASYNC_DEFUN, LispNames.ASYNC_DEFUN_QUALIFIED -> {
					if (parts.size() >= 3) {
						walkFunction(parts.get(2), parts.subList(3, parts.size()), scope);
					}
				}
				case LispNames.DEFMETHOD -> walkDefmethod(parts, scope);
				case LispNames.LET -> walkLet(parts, scope, false);
				case LispNames.LET_STAR -> walkLet(parts, scope, true);
				case LispNames.LOCALLY -> walkBody(parts.subList(1, parts.size()), Set.of(), Map.of(), scope, false);
				case LispNames.SETQ, LispNames.PSETQ -> {
					for (int i = 2; i < parts.size(); i += 2) {
						walk(parts.get(i), scope);
					}
				}
				case LispNames.THE -> {
					if (parts.size() == 3) {
						walk(parts.get(2), scope);
					}
				}
				case LispNames.BLOCK, LispNames.FN_BLOCK_INTERNAL, LispNames.RETURN_FROM ->
					walkElements(cons, 2, scope);
				case LispNames.TAGBODY -> {
					for (int i = 1; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons statement) {
							walk(statement, scope);
						}
					}
				}
				case LispNames.HANDLER_CASE -> walkHandlerCase(parts, scope);
				// Local macros whose expansions this walk cannot see: nothing declared
				// outside reaches in.
				case LispNames.MACROLET -> walkElements(cons, 1, Scope.NONE);
				// Binding operators without a pure expansion here: the same.
				case LispNames.WITH_PACKAGE_ITERATOR, LispNames.DEFSTRUCT, LispNames.DEFCLASS, LispNames.DEFGENERIC,
						LispNames.DEFTYPE, LispNames.DEFINE_SETF_EXPANDER, LispNames.DEFSETF,
						LispNames.DEFINE_MODIFY_MACRO, LispNames.DEFINE_COMPILER_MACRO, LispNames.DEFINE_SYMBOL_MACRO,
						LispNames.USOCKET_WITH_CLIENT_SOCKET_QUALIFIED,
						LispNames.USOCKET_WITH_CONNECTED_SOCKET_QUALIFIED,
						LispNames.USOCKET_WITH_SERVER_SOCKET_QUALIFIED,
						LispNames.USOCKET_WITH_SOCKET_LISTENER_QUALIFIED, LispNames.WITH_ARENA_QUALIFIED ->
					walkElements(cons, 1, Scope.NONE);
				default -> walkOther(cons, name, scope);
			}
		}

		private void walkOther(LispCons cons, String name, Scope scope) {
			LispVal expansion = expansion(cons, name);
			if (expansion != null) {
				this.walkedExpansions.put(expansion, Boolean.TRUE);
				walk(expansion, scope);
				return;
			}
			if (PackageRegistry.specialOperatorNames().contains(name) && !NON_BINDING_OPERATORS.contains(name)) {
				// A built-in operator this walk has no expansion for may bind: nothing
				// declared outside reaches in.
				walkElements(cons, 1, Scope.NONE);
				return;
			}
			// A function call: every argument is a form in this scope.
			walkElements(cons, 1, scope);
		}

		/**
		 * The expansion of a built-in or user macro form, expanded once per walk.
		 * @return the expansion, or {@code null} when the form is no macro form
		 */
		private @Nullable LispVal expansion(LispCons cons, String name) {
			if (this.expansions.containsKey(cons)) {
				return this.expansions.get(cons);
			}
			LispVal expansion = builtinExpansion(cons, name);
			if (expansion == null) {
				LispMacroExpander.UserMacroHook hook = this.userMacros;
				LispVal user = hook == null ? null : hook.expandOneStep(cons);
				if (user != null && user != cons) {
					expansion = user;
				}
			}
			this.expansions.put(cons, expansion);
			return expansion;
		}

		private void walkElements(LispCons cons, int from, Scope scope) {
			LispVal current = cons;
			int index = 0;
			while (current instanceof LispCons cell) {
				if (index++ >= from) {
					walk(cell.car(), scope);
				}
				current = cell.cdr();
			}
		}

		private void walkFunction(LispVal lambdaList, List<LispVal> body, Scope scope) {
			Set<String> bound = new HashSet<>();
			collectBound(lambdaList, bound);
			// Default forms run in the parameters' scope; declarations never reach them
			// here.
			walk(lambdaList, bind(scope, bound, Map.of()));
			walkBody(body, bound, Map.of(), scope, true);
		}

		private void walkDefmethod(List<LispVal> parts, Scope scope) {
			for (int i = 2; i < parts.size(); i++) {
				if (parts.get(i) instanceof LispCons || parts.get(i) instanceof LispNil) {
					Set<String> bound = new HashSet<>();
					collectBound(parts.get(i), bound);
					walkBody(parts.subList(i + 1, parts.size()), bound, Map.of(), scope, true);
					return;
				}
			}
		}

		/**
		 * A {@code let}/{@code let*}: each initializer walked in its scope, each variable
		 * typed by its initializer when that is known, the variable is lexical and
		 * nothing in its scope assigns it.
		 */
		private void walkLet(List<LispVal> parts, Scope scope, boolean sequential) {
			if (parts.size() < 2) {
				return;
			}
			List<LispVal> body = parts.subList(2, parts.size());
			List<LispVal> bindings = parts.get(1) instanceof LispCons list ? list.toList() : List.of();
			Set<String> bound = new HashSet<>();
			Map<String, LispVal> inferred = new HashMap<>();
			Scope initScope = scope;
			Set<String> assigned = null;
			for (int i = 0; i < bindings.size(); i++) {
				LispVal binding = bindings.get(i);
				LispSymbol var = null;
				LispVal init = null;
				if (binding instanceof LispSymbol symbol) {
					var = symbol;
				}
				else if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol symbol) {
					var = symbol;
					if (pair.cdr() instanceof LispCons initCell) {
						init = initCell.car();
						walk(init, initScope);
					}
				}
				if (var == null) {
					continue;
				}
				String name = var.name();
				bound.add(name);
				inferred.remove(name);
				if (init != null && !JavaDeclarations.this.specials.contains(name)) {
					JavaStaticType type = typeOfInit(init, initScope);
					LispVal spec = type instanceof JavaStaticType.Unknown ? null
							: JavaDeclarations.this.resolver.specOf(type);
					if (spec != null) {
						if (assigned == null) {
							// Where the variables are in scope: the later initializers of
							// a let*, the body of either.
							List<LispVal> region = new ArrayList<>();
							if (sequential) {
								for (int j = 1; j < bindings.size(); j++) {
									if (bindings.get(j) instanceof LispCons pair
											&& pair.cdr() instanceof LispCons rest) {
										region.add(rest.car());
									}
								}
							}
							region.addAll(body);
							assigned = assignedIn(region, boundNames(bindings));
						}
						if (!assigned.contains(name)) {
							inferred.put(name, spec);
						}
					}
				}
				if (sequential) {
					initScope = bind(scope, bound, inferred);
				}
			}
			walkBody(body, bound, inferred, scope, false);
		}

		/**
		 * The static type of a binding's initializer: a typed variable's type, or what
		 * the resolver reads off the initializer with its sites lowered -- after the
		 * macros it is a call of are expanded, as the compile path has them.
		 */
		private JavaStaticType typeOfInit(LispVal init, Scope scope) {
			LispVal form = init;
			for (int depth = 0; depth < SCAN_EXPANSION_LIMIT; depth++) {
				if (form instanceof LispSymbol sym && !sym.isKeyword()) {
					LispVal spec = lookup(scope, sym.name());
					return spec == null ? JavaStaticType.UNKNOWN
							: JavaDeclarations.this.resolver.typeOf(the(spec, sym));
				}
				if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head) || !cons.isProperList()
						|| JavaSiteResolver.operatorOf(cons) != null || TYPED_FORMS.contains(head.name())) {
					break;
				}
				LispVal expanded;
				try {
					expanded = expansion(cons, head.name());
				}
				catch (RuntimeException ex) {
					return JavaStaticType.UNKNOWN;
				}
				if (expanded == null) {
					break;
				}
				if (this.walkedExpansions.put(expanded, Boolean.TRUE) == null) {
					walk(expanded, scope);
				}
				form = expanded;
			}
			return JavaDeclarations.this.resolver.typeOf(rebuild(form, new IdentityHashMap<>()));
		}

		private void walkHandlerCase(List<LispVal> parts, Scope scope) {
			if (parts.size() > 1) {
				walk(parts.get(1), scope);
			}
			for (int i = 2; i < parts.size(); i++) {
				if (!(parts.get(i) instanceof LispCons clause) || !clause.isProperList()) {
					continue;
				}
				List<LispVal> clauseParts = clause.toList();
				Set<String> bound = new HashSet<>();
				if (clauseParts.size() > 1) {
					collectBound(clauseParts.get(1), bound);
				}
				walkBody(clauseParts.subList(Math.min(2, clauseParts.size()), clauseParts.size()), bound, Map.of(),
						scope, false);
			}
		}

		/**
		 * Walks a body in the scope a binding form gives it: the outer types minus the
		 * names it binds, plus the types inferred for them, plus the body-head
		 * declarations (bound and free alike), which win.
		 */
		private void walkBody(List<LispVal> body, Set<String> bound, Map<String, LispVal> inferred, Scope scope,
				boolean docstring) {
			Scope inner = bind(scope, bound, inferred);
			int start = 0;
			Map<String, LispVal> declared = new HashMap<>();
			for (int i = 0; i < body.size(); i++) {
				LispVal form = body.get(i);
				if (docstring && form instanceof LispString && i < body.size() - 1) {
					continue;
				}
				if (!(form instanceof LispCons decl) || !(decl.car() instanceof LispSymbol head)
						|| !LispNames.DECLARE.equals(head.name()) || !decl.isProperList()) {
					start = i;
					break;
				}
				readDeclaration(decl, declared);
				start = i + 1;
			}
			inner = bind(inner, Set.of(), declared);
			for (int i = start; i < body.size(); i++) {
				walk(body.get(i), inner);
			}
		}

		// --- the assignment scan ---

		/**
		 * The names among {@code names} a form in {@code region} may assign: the targets
		 * of every {@code setq}, {@code psetq} and {@code multiple-value-setq} after
		 * macro expansion, rebindings ignored (a conservative answer), and every name a
		 * form this scan cannot see into mentions.
		 */
		private Set<String> assignedIn(List<LispVal> region, Set<String> names) {
			Set<String> assigned = new HashSet<>();
			IdentityHashMap<LispCons, Boolean> seen = new IdentityHashMap<>();
			for (LispVal form : region) {
				scan(form, names, assigned, seen, 0);
			}
			return assigned;
		}

		private void scan(LispVal form, Set<String> names, Set<String> assigned,
				IdentityHashMap<LispCons, Boolean> seen, int expansionDepth) {
			if (!(form instanceof LispCons cons) || assigned.containsAll(names)
					|| seen.put(cons, Boolean.TRUE) != null) {
				return;
			}
			if (expansionDepth > SCAN_EXPANSION_LIMIT) {
				assigned.addAll(names);
				return;
			}
			try {
				scanCons(cons, names, assigned, seen, expansionDepth);
			}
			catch (RuntimeException ex) {
				mentioned(cons, names, assigned);
			}
		}

		private void scanCons(LispCons cons, Set<String> names, Set<String> assigned,
				IdentityHashMap<LispCons, Boolean> seen, int expansionDepth) {
			if (!(cons.car() instanceof LispSymbol head)) {
				scanElements(cons, 0, names, assigned, seen, expansionDepth);
				return;
			}
			if (!cons.isProperList()) {
				mentioned(cons, names, assigned);
				return;
			}
			String name = head.name();
			List<LispVal> parts = cons.toList();
			switch (name) {
				case LispNames.QUOTE, LispNames.DECLARE, LispNames.GO -> {
				}
				case LispNames.SETQ, LispNames.PSETQ -> {
					for (int i = 1; i < parts.size(); i += 2) {
						assignTo(parts.get(i), names, assigned);
						if (i + 1 < parts.size()) {
							scan(parts.get(i + 1), names, assigned, seen, expansionDepth);
						}
					}
				}
				case LispNames.MULTIPLE_VALUE_SETQ -> {
					if (parts.size() > 1) {
						LispVal current = parts.get(1);
						while (current instanceof LispCons cell) {
							assignTo(cell.car(), names, assigned);
							current = cell.cdr();
						}
					}
					scanElements(cons, 2, names, assigned, seen, expansionDepth);
				}
				// Proclaims the name special when it runs.
				case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
					if (parts.size() > 1) {
						assignTo(parts.get(1), names, assigned);
					}
					scanElements(cons, 2, names, assigned, seen, expansionDepth);
				}
				case LispNames.LET, LispNames.LET_STAR -> {
					if (parts.size() > 1 && parts.get(1) instanceof LispCons bindings) {
						for (LispVal binding : bindings.toList()) {
							if (binding instanceof LispCons pair) {
								scanElements(pair, 1, names, assigned, seen, expansionDepth);
							}
						}
					}
					scanElements(cons, 2, names, assigned, seen, expansionDepth);
				}
				default -> {
					if (SCAN_TRANSPARENT.contains(name)) {
						scanElements(cons, 1, names, assigned, seen, expansionDepth);
						return;
					}
					LispVal expansion = expansion(cons, name);
					if (expansion != null) {
						scan(expansion, names, assigned, seen, expansionDepth + 1);
					}
					else if (PackageRegistry.specialOperatorNames().contains(name)) {
						// An operator this scan cannot see into (a place-modifying one
						// among them): whatever it mentions may be assigned.
						mentioned(cons, names, assigned);
					}
					else {
						scanElements(cons, 1, names, assigned, seen, expansionDepth);
					}
				}
			}
		}

		private void scanElements(LispCons cons, int from, Set<String> names, Set<String> assigned,
				IdentityHashMap<LispCons, Boolean> seen, int expansionDepth) {
			LispVal current = cons;
			int index = 0;
			while (current instanceof LispCons cell) {
				if (index++ >= from) {
					scan(cell.car(), names, assigned, seen, expansionDepth);
				}
				current = cell.cdr();
			}
		}

		private static void assignTo(LispVal target, Set<String> names, Set<String> assigned) {
			if (target instanceof LispSymbol sym && names.contains(sym.name())) {
				assigned.add(sym.name());
			}
		}

		private static void mentioned(LispVal form, Set<String> names, Set<String> assigned) {
			IdentityHashMap<LispCons, Boolean> seen = new IdentityHashMap<>();
			List<LispVal> pending = new ArrayList<>();
			pending.add(form);
			while (!pending.isEmpty()) {
				LispVal current = pending.remove(pending.size() - 1);
				while (current instanceof LispCons cell && seen.put(cell, Boolean.TRUE) == null) {
					pending.add(cell.car());
					current = cell.cdr();
				}
				assignTo(current, names, assigned);
			}
		}

		// --- the site rewrite ---

		private boolean mentionsJava(LispCons cons) {
			Boolean known = this.mentionsJava.get(cons);
			if (known != null) {
				return known;
			}
			// Provisionally false, so a car that reaches back here terminates; the spine
			// is walked iteratively (a long list is not a deep one) with its own cycle
			// guard.
			this.mentionsJava.put(cons, Boolean.FALSE);
			boolean found = false;
			IdentityHashMap<LispCons, Boolean> spine = new IdentityHashMap<>();
			LispVal current = cons;
			while (current instanceof LispCons cell && spine.put(cell, Boolean.TRUE) == null) {
				LispVal car = cell.car();
				if (car instanceof LispSymbol sym ? sym.name().startsWith(JAVA_PREFIX)
						: car instanceof LispCons inner && mentionsJava(inner)) {
					found = true;
					break;
				}
				current = cell.cdr();
			}
			this.mentionsJava.put(cons, found);
			return found;
		}

		private void rewriteSite(LispCons site, JavaSite.Operator operator, Scope scope) {
			List<LispVal> parts = site.toList();
			// The positions whose static type the resolver reads: a call's receiver and
			// every argument; for java:field, the object.
			int firstArgument = switch (operator) {
				case NEW -> 2;
				case CALL, STATIC -> 3;
				case FIELD -> 3;
			};
			List<LispVal> rewritten = new ArrayList<>(parts);
			boolean changed = false;
			for (int i = 1; i < parts.size(); i++) {
				boolean typed = i >= firstArgument
						|| (i == 1 && (operator == JavaSite.Operator.CALL || operator == JavaSite.Operator.FIELD));
				if (typed && parts.get(i) instanceof LispSymbol var && !var.isKeyword()) {
					LispVal spec = lookup(scope, var.name());
					if (spec != null) {
						rewritten.set(i, the(spec, var));
						changed = true;
					}
				}
			}
			if (changed) {
				LispVal rebuilt = LispCons.rebuiltList(site, rewritten);
				if (rebuilt instanceof LispCons rebuiltCons) {
					this.rewrites.put(site, SourceProvenance.inherit(site, rebuiltCons));
				}
			}
		}

		// The original form with every rewritten site replaced, rebuilt only along the
		// paths that lead to one (quoted data is left alone). Elements recurse, the
		// spine does not.
		LispVal rebuild(LispVal form, IdentityHashMap<LispCons, LispVal> done) {
			if (!(form instanceof LispCons cons)) {
				return form;
			}
			LispVal known = done.get(cons);
			if (known != null) {
				return known;
			}
			done.put(cons, cons); // shared or circular structure is returned as it is
			LispCons source = this.rewrites.getOrDefault(cons, cons);
			LispVal result = source;
			if (!(source.car() instanceof LispSymbol head && LispNames.QUOTE.equals(head.name()))) {
				if (source.properLength() < 0) {
					result = LispCons.rebuilt(source, rebuild(source.car(), done), rebuild(source.cdr(), done));
				}
				else {
					List<LispVal> elements = new ArrayList<>();
					boolean changed = false;
					for (LispVal element : source.toList()) {
						LispVal rebuilt = rebuild(element, done);
						changed |= rebuilt != element;
						elements.add(rebuilt);
					}
					if (changed) {
						result = LispCons.rebuiltList(source, elements);
					}
				}
			}
			// The rebuilt cell stands for the original's source text (a site rewritten
			// and then rebuilt around a rewritten inner site included).
			result = SourceProvenance.inherit(cons, result);
			done.put(cons, result);
			return result;
		}

	}

	// --- declarations ---

	/**
	 * Records a proclamation's {@code java:object} type specifiers; a later type
	 * proclamation of the same name replaces an earlier one.
	 */
	private void proclaim(LispVal specifier) {
		Map<String, @Nullable LispVal> types = new HashMap<>();
		readTypeSpecifier(specifier, types);
		for (Map.Entry<String, @Nullable LispVal> entry : types.entrySet()) {
			LispVal spec = entry.getValue();
			if (spec == null) {
				this.globals.remove(entry.getKey());
			}
			else {
				this.globals.put(entry.getKey(), spec);
			}
		}
	}

	private static void readDeclaration(LispCons declare, Map<String, LispVal> declared) {
		List<LispVal> specifiers = declare.toList();
		for (int i = 1; i < specifiers.size(); i++) {
			Map<String, @Nullable LispVal> types = new HashMap<>();
			readTypeSpecifier(specifiers.get(i), types);
			types.forEach((name, spec) -> {
				if (spec != null) {
					declared.put(name, spec);
				}
			});
		}
	}

	/**
	 * The variables a {@code (type spec v...)} specifier types: to the specifier when it
	 * is a {@code java:object} one, to {@code null} when it is any other type.
	 */
	private static void readTypeSpecifier(LispVal specifier, Map<String, @Nullable LispVal> types) {
		if (!(specifier instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol kind)
				|| !"TYPE".equals(plainName(kind.name()))) {
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3) {
			return;
		}
		LispVal spec = JavaSiteResolver.javaObjectClass(parts.get(1)) != null ? parts.get(1) : null;
		for (int v = 2; v < parts.size(); v++) {
			if (parts.get(v) instanceof LispSymbol var && !var.isKeyword()) {
				types.put(var.name(), spec);
			}
		}
	}

	private static String plainName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	/** Every name a let's bindings bind. */
	private static Set<String> boundNames(List<LispVal> bindings) {
		Set<String> names = new LinkedHashSet<>();
		for (LispVal binding : bindings) {
			if (binding instanceof LispSymbol var) {
				names.add(var.name());
			}
			else if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol var) {
				names.add(var.name());
			}
		}
		return names;
	}

	/** Every symbol a lambda list (or a destructuring pattern) could bind. */
	private static void collectBound(LispVal list, Set<String> bound) {
		LispVal current = list;
		while (current instanceof LispCons cons) {
			collectBoundElement(cons.car(), bound);
			current = cons.cdr();
		}
		collectBoundElement(current, bound);
	}

	private static void collectBoundElement(LispVal element, Set<String> bound) {
		if (element instanceof LispSymbol sym) {
			if (!sym.name().startsWith("&") && !sym.isKeyword()) {
				bound.add(sym.name());
			}
		}
		else if (element instanceof LispCons nested) {
			collectBound(nested, bound);
		}
	}

	/** {@code (the <a fresh copy of spec> var)}. */
	private static LispCons the(LispVal spec, LispSymbol var) {
		return new LispCons(new LispSymbol(LispNames.THE),
				new LispCons(copy(spec), new LispCons(var, LispNil.INSTANCE)));
	}

	private static LispVal copy(LispVal spec) {
		if (!(spec instanceof LispCons cons)) {
			return spec;
		}
		List<LispVal> elements = new ArrayList<>();
		for (LispVal element : cons.toList()) {
			elements.add(copy(element));
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

	/** The built-in operators that bind nothing: their arguments are forms in scope. */
	private static final Set<String> NON_BINDING_OPERATORS = Set.of(LispNames.IF, LispNames.PROGN, LispNames.WHILE,
			LispNames.PROGV, LispNames.UNWIND_PROTECT, LispNames.CATCH, LispNames.THROW, LispNames.RETURN,
			LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT, LispNames.IN_PACKAGE, LispNames.DEFPACKAGE,
			LispNames.MULTIPLE_VALUE_PROG1, LispNames.LOAD_TIME_VALUE, LispNames.PSETF, LispNames.SHIFTF,
			LispNames.WARN, LispNames.MAKE_INSTANCE, LispNames.SLOT_VALUE, LispNames.CHANGE_CLASS,
			LispNames.MAKE_SEQUENCE, LispNames.TYPEP, LispNames.SLOT_BOUNDP, LispNames.SLOT_MAKUNBOUND,
			LispNames.SLOT_EXISTS_P, LispNames.PRINT_UNREADABLE_OBJECT);

	/**
	 * The operators the assignment scan reads every subform of as it is: none assigns a
	 * variable itself (PSETF and SHIFTF, which do, are not here).
	 */
	private static final Set<String> SCAN_TRANSPARENT = Set.of(LispNames.IF, LispNames.PROGN, LispNames.WHILE,
			LispNames.PROGV, LispNames.UNWIND_PROTECT, LispNames.CATCH, LispNames.THROW, LispNames.RETURN,
			LispNames.IN_PACKAGE, LispNames.DEFPACKAGE, LispNames.MULTIPLE_VALUE_PROG1, LispNames.MULTIPLE_VALUE_CALL,
			LispNames.LOAD_TIME_VALUE, LispNames.WARN, LispNames.MAKE_INSTANCE, LispNames.SLOT_VALUE,
			LispNames.CHANGE_CLASS, LispNames.MAKE_SEQUENCE, LispNames.TYPEP, LispNames.SLOT_BOUNDP,
			LispNames.SLOT_MAKUNBOUND, LispNames.SLOT_EXISTS_P, LispNames.PRINT_UNREADABLE_OBJECT, LispNames.FUNCTION,
			LispNames.LAMBDA, LispNames.ASYNC_LAMBDA, LispNames.ASYNC_LAMBDA_QUALIFIED, LispNames.DEFUN,
			LispNames.DEFMACRO, LispNames.ASYNC_DEFUN, LispNames.ASYNC_DEFUN_QUALIFIED, LispNames.DEFMETHOD,
			LispNames.LOCALLY, LispNames.THE, LispNames.BLOCK, LispNames.FN_BLOCK_INTERNAL, LispNames.RETURN_FROM,
			LispNames.TAGBODY, LispNames.HANDLER_CASE, LispNames.EVAL_WHEN, LispNames.DECLAIM, LispNames.PROCLAIM);

	/** The operators whose form {@link JavaSiteResolver#typeOf} reads itself. */
	private static final Set<String> TYPED_FORMS = Set.of(LispNames.THE, LispNames.QUOTE, LispNames.FUNCTION,
			LispNames.LAMBDA);

	private static @Nullable LispVal builtinExpansion(LispCons cons, String name) {
		LispVal expansion = switch (name) {
			case LispNames.WITH_OPEN_STREAM -> LispMacroExpander.expandWithOpenStream(cons, true);
			case LispNames.PROG -> LispMacroExpander.expandProg(cons, false);
			case LispNames.PROG_STAR -> LispMacroExpander.expandProg(cons, true);
			case LispNames.DO_SYMBOLS -> LispMacroExpander.expandDoSymbols(cons, false);
			case LispNames.DO_EXTERNAL_SYMBOLS -> LispMacroExpander.expandDoSymbols(cons, true);
			case LispNames.DO_ALL_SYMBOLS -> LispMacroExpander.expandDoAllSymbols(cons);
			case LispNames.WITH_MUTEX_QUALIFIED, LispNames.WITH_LOCK_HELD_QUALIFIED,
					LispNames.WITH_RECURSIVE_LOCK_HELD_QUALIFIED ->
				LispMacroExpander.expandWithMutex(cons);
			default -> LispMacroExpander.expandBuiltinMacro(cons);
		};
		if (expansion == null) {
			expansion = LispMacroExpander.expandUiopMacro(cons, true);
		}
		return expansion == cons ? null : expansion;
	}

}
