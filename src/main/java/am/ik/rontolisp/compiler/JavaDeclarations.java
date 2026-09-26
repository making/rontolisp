package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import org.jspecify.annotations.Nullable;

/**
 * Lowers {@code (declare (type (java:object "C") v))} to what the site resolver reads:
 * every reference to {@code v} that is the receiver or an argument of a {@code java:}
 * site in the declaration's scope becomes {@code (the (java:object "C") v)}. The resolver
 * ({@link JavaSiteResolver}) then needs nothing but the site form itself, so the
 * interpreter and the JVM compiler resolve a site from the same text however each walks
 * scopes -- the declaration's scope is decided HERE, once, for both.
 * <p>
 * The walk knows the special forms that bind ({@code let}, {@code let*}, {@code lambda},
 * {@code defun}, {@code defmacro}, {@code defmethod}, {@code handler-case},
 * {@code locally}) and expands every built-in and user macro to see what it binds, but it
 * never replaces a macro form: only the {@code java:} sites it rewrites change, found
 * again in the original form by identity. Where it cannot tell what a form binds (a
 * {@code macrolet}, a built-in operator with no expansion here) it drops every
 * declaration below that form -- a site that loses its type is resolved at run time,
 * never against the wrong class. A declared type is trusted, like every declaration a
 * backend acts on ({@code .kb/declarations-type-checks.md}).
 * <p>
 * Only a form that mentions {@code java:object} is walked ({@link #mentionsJavaObject}),
 * and a form nothing is rewritten in comes back as the same object.
 */
public final class JavaDeclarations {

	private static final String JAVA_OBJECT = LispNames.JAVA_OBJECT_QUALIFIED;

	private static final String JAVA_PREFIX = LispNames.JAVA_PKG + ":";

	private final LispMacroExpander.@Nullable UserMacroHook userMacros;

	// Every site rewritten, by the identity of the original site cons.
	private final IdentityHashMap<LispCons, LispCons> rewrites = new IdentityHashMap<>();

	// Whether a cons (as a whole tree) mentions a java: symbol.
	private final IdentityHashMap<LispCons, Boolean> mentionsJava = new IdentityHashMap<>();

	private JavaDeclarations(LispMacroExpander.@Nullable UserMacroHook userMacros) {
		this.userMacros = userMacros;
	}

	/**
	 * Whether a form mentions the {@code java:object} symbol anywhere -- the gate every
	 * caller asks before {@link #lower}, so a program that declares no host type is never
	 * walked.
	 * @param form a form
	 * @return true when it does
	 */
	public static boolean mentionsJavaObject(LispVal form) {
		return mentions(form, JAVA_OBJECT, new IdentityHashMap<>());
	}

	private static boolean mentions(LispVal form, String name, IdentityHashMap<LispCons, Boolean> seen) {
		LispVal current = form;
		while (current instanceof LispCons cons) {
			if (seen.put(cons, Boolean.TRUE) != null) {
				return false;
			}
			if (mentions(cons.car(), name, seen)) {
				return true;
			}
			current = cons.cdr();
		}
		return current instanceof LispSymbol sym && name.equals(sym.name());
	}

	/**
	 * Lowers the declarations of one top-level form.
	 * @param form a package-resolved top-level form
	 * @param userMacros expands a user macro call one step (the interpreter's), or
	 * {@code null} where every user macro is already expanded (the compile path)
	 * @return the form with its declared {@code java:} receivers and arguments wrapped,
	 * or {@code form} itself when nothing was
	 */
	public static LispVal lower(LispVal form, LispMacroExpander.@Nullable UserMacroHook userMacros) {
		if (!(form instanceof LispCons) || !mentionsJavaObject(form)) {
			return form;
		}
		JavaDeclarations pass = new JavaDeclarations(userMacros);
		pass.walk(form, Map.of());
		if (pass.rewrites.isEmpty()) {
			return form;
		}
		return pass.rebuild(form, new IdentityHashMap<>());
	}

	// --- the scope walk ---

	private void walk(LispVal form, Map<String, String> scope) {
		if (!(form instanceof LispCons cons) || !mentionsJava(cons)) {
			return;
		}
		try {
			walkCons(cons, scope);
		}
		catch (RuntimeException ex) {
			// A malformed form or an expansion that fails: whatever it binds is unknown,
			// so its subforms are walked with no declaration in scope.
			if (!scope.isEmpty()) {
				walkElements(cons, 0, Map.of());
			}
		}
	}

	private void walkCons(LispCons cons, Map<String, String> scope) {
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
			if (!scope.isEmpty()) {
				rewriteSite(cons, operator, scope);
			}
			return;
		}
		List<LispVal> parts = cons.toList();
		switch (name) {
			case LispNames.QUOTE, LispNames.DECLARE, LispNames.DECLAIM, LispNames.PROCLAIM, LispNames.GO -> {
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
			case LispNames.LOCALLY -> walkBody(parts.subList(1, parts.size()), Set.of(), scope, false);
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
			case LispNames.BLOCK, LispNames.FN_BLOCK_INTERNAL, LispNames.RETURN_FROM -> walkElements(cons, 2, scope);
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
			case LispNames.MACROLET -> walkElements(cons, 1, Map.of());
			// Binding operators without a pure expansion here: the same.
			case LispNames.WITH_PACKAGE_ITERATOR, LispNames.DEFSTRUCT, LispNames.DEFCLASS, LispNames.DEFGENERIC,
					LispNames.DEFTYPE, LispNames.DEFINE_SETF_EXPANDER, LispNames.DEFSETF, LispNames.DEFINE_MODIFY_MACRO,
					LispNames.DEFINE_COMPILER_MACRO, LispNames.DEFINE_SYMBOL_MACRO,
					LispNames.USOCKET_WITH_CLIENT_SOCKET_QUALIFIED, LispNames.USOCKET_WITH_CONNECTED_SOCKET_QUALIFIED,
					LispNames.USOCKET_WITH_SERVER_SOCKET_QUALIFIED, LispNames.USOCKET_WITH_SOCKET_LISTENER_QUALIFIED,
					LispNames.WITH_ARENA_QUALIFIED ->
				walkElements(cons, 1, Map.of());
			default -> walkOther(cons, name, scope);
		}
	}

	private void walkOther(LispCons cons, String name, Map<String, String> scope) {
		LispVal expansion = builtinExpansion(cons, name);
		if (expansion == null) {
			LispMacroExpander.UserMacroHook hook = this.userMacros;
			LispVal user = hook == null ? null : hook.expandOneStep(cons);
			if (user != null && user != cons) {
				expansion = user;
			}
		}
		if (expansion != null) {
			walk(expansion, scope);
			return;
		}
		if (PackageRegistry.specialOperatorNames().contains(name) && !NON_BINDING_OPERATORS.contains(name)) {
			// A built-in operator this walk has no expansion for may bind: nothing
			// declared outside reaches in.
			walkElements(cons, 1, Map.of());
			return;
		}
		// A function call: every argument is a form in this scope.
		walkElements(cons, 1, scope);
	}

	/** The built-in operators that bind nothing: their arguments are forms in scope. */
	private static final Set<String> NON_BINDING_OPERATORS = Set.of(LispNames.IF, LispNames.PROGN, LispNames.WHILE,
			LispNames.PROGV, LispNames.UNWIND_PROTECT, LispNames.CATCH, LispNames.THROW, LispNames.RETURN,
			LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT, LispNames.IN_PACKAGE, LispNames.DEFPACKAGE,
			LispNames.MULTIPLE_VALUE_PROG1, LispNames.LOAD_TIME_VALUE, LispNames.PSETF, LispNames.SHIFTF,
			LispNames.WARN, LispNames.MAKE_INSTANCE, LispNames.SLOT_VALUE, LispNames.CHANGE_CLASS,
			LispNames.MAKE_SEQUENCE, LispNames.TYPEP, LispNames.SLOT_BOUNDP, LispNames.SLOT_MAKUNBOUND,
			LispNames.SLOT_EXISTS_P, LispNames.PRINT_UNREADABLE_OBJECT);

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

	private void walkElements(LispCons cons, int from, Map<String, String> scope) {
		LispVal current = cons;
		int index = 0;
		while (current instanceof LispCons cell) {
			if (index++ >= from) {
				walk(cell.car(), scope);
			}
			current = cell.cdr();
		}
	}

	private void walkFunction(LispVal lambdaList, List<LispVal> body, Map<String, String> scope) {
		Set<String> bound = new HashSet<>();
		collectBound(lambdaList, bound);
		// Default forms run in the parameters' scope; declarations never reach them here.
		walk(lambdaList, without(scope, bound));
		walkBody(body, bound, scope, true);
	}

	private void walkDefmethod(List<LispVal> parts, Map<String, String> scope) {
		for (int i = 2; i < parts.size(); i++) {
			if (parts.get(i) instanceof LispCons || parts.get(i) instanceof LispNil) {
				Set<String> bound = new HashSet<>();
				collectBound(parts.get(i), bound);
				walkBody(parts.subList(i + 1, parts.size()), bound, scope, true);
				return;
			}
		}
	}

	private void walkLet(List<LispVal> parts, Map<String, String> scope, boolean sequential) {
		if (parts.size() < 2) {
			return;
		}
		Set<String> bound = new HashSet<>();
		Map<String, String> initScope = scope;
		if (parts.get(1) instanceof LispCons bindings) {
			for (LispVal binding : bindings.toList()) {
				if (binding instanceof LispSymbol var) {
					bound.add(var.name());
				}
				else if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol var) {
					if (pair.cdr() instanceof LispCons init) {
						walk(init.car(), initScope);
					}
					bound.add(var.name());
				}
				if (sequential) {
					initScope = without(scope, bound);
				}
			}
		}
		walkBody(parts.subList(2, parts.size()), bound, scope, false);
	}

	private void walkHandlerCase(List<LispVal> parts, Map<String, String> scope) {
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
			walkBody(clauseParts.subList(Math.min(2, clauseParts.size()), clauseParts.size()), bound, scope, false);
		}
	}

	/**
	 * Walks a body in the scope a binding form gives it: the outer declarations minus the
	 * names it binds, plus the body-head declarations (bound and free alike).
	 */
	private void walkBody(List<LispVal> body, Set<String> bound, Map<String, String> scope, boolean docstring) {
		Map<String, String> inner = without(scope, bound);
		int start = 0;
		Map<String, String> declared = new HashMap<>();
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
		if (!declared.isEmpty()) {
			inner = new HashMap<>(inner);
			inner.putAll(declared);
		}
		for (int i = start; i < body.size(); i++) {
			walk(body.get(i), inner);
		}
	}

	private static void readDeclaration(LispCons declare, Map<String, String> declared) {
		List<LispVal> specifiers = declare.toList();
		for (int i = 1; i < specifiers.size(); i++) {
			if (!(specifiers.get(i) instanceof LispCons specifier) || !specifier.isProperList()
					|| !(specifier.car() instanceof LispSymbol kind) || !"TYPE".equals(plainName(kind.name()))) {
				continue;
			}
			List<LispVal> parts = specifier.toList();
			if (parts.size() < 3) {
				continue;
			}
			String className = JavaSiteResolver.javaObjectClass(parts.get(1));
			if (className == null) {
				continue;
			}
			for (int v = 2; v < parts.size(); v++) {
				if (parts.get(v) instanceof LispSymbol var && !var.isKeyword()) {
					declared.put(var.name(), className);
				}
			}
		}
	}

	private static String plainName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
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

	private static Map<String, String> without(Map<String, String> scope, Set<String> names) {
		if (scope.isEmpty() || names.isEmpty()) {
			return scope;
		}
		Map<String, String> result = null;
		for (String name : names) {
			if (scope.containsKey(name)) {
				if (result == null) {
					result = new HashMap<>(scope);
				}
				result.remove(name);
			}
		}
		return result == null ? scope : result;
	}

	private boolean mentionsJava(LispCons cons) {
		Boolean known = this.mentionsJava.get(cons);
		if (known != null) {
			return known;
		}
		// Provisionally false, so a car that reaches back here terminates; the spine is
		// walked iteratively (a long list is not a deep one) with its own cycle guard.
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

	// --- the site rewrite ---

	private void rewriteSite(LispCons site, JavaSite.Operator operator, Map<String, String> scope) {
		List<LispVal> parts = site.toList();
		// The positions whose static type the resolver reads: a call's receiver and every
		// argument; for java:field, the object.
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
				String className = scope.get(var.name());
				if (className != null) {
					rewritten.set(i, the(className, var));
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

	private static LispCons the(String className, LispSymbol var) {
		LispVal spec = new LispCons(new LispSymbol(JAVA_OBJECT),
				new LispCons(new LispString(className), LispNil.INSTANCE));
		return new LispCons(new LispSymbol(LispNames.THE), new LispCons(spec, new LispCons(var, LispNil.INSTANCE)));
	}

	// The original form with every rewritten site replaced, rebuilt only along the
	// paths that lead to one (quoted data is left alone). Elements recurse, the spine
	// does not.
	private LispVal rebuild(LispVal form, IdentityHashMap<LispCons, LispVal> done) {
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
		done.put(cons, result);
		return result;
	}

}
