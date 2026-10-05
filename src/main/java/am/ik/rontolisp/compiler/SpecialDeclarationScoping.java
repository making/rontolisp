package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.SpecialDeclarations;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.macro.SpecialVarCollector;
import org.jspecify.annotations.Nullable;

/**
 * Scopes the program's LOCAL special declarations for the compile paths, which decide a
 * variable's specialness by its name: every binding of a name that a local
 * {@code (declare (special ...))} names -- and nothing proclaims special -- which is NOT
 * covered by such a declaration is lexical in CL (CLHS 3.3.4), so it is renamed apart,
 * with the references it scopes, to a fresh name. After the pass every occurrence of such
 * a name left in the program is special: a binding a declaration names, a reference a
 * declaration covers, or a free reference (an undefined variable, which CL
 * implementations treat as special). The compilers then bind and read the name through
 * its special store like a proclaimed one, and a closure over a lexical binding of it
 * captures the renamed variable -- cl-ppcre's matcher closures capture {@code end-string}
 * this way while the scanner's {@code end-string} is a special binding. The interpreter
 * reads the same declarations as it evaluates ({@link SpecialDeclarations}).
 *
 * <p>
 * Runs on the user-macro-expanded program, before the lambda lists are desugared. The
 * core binding forms and the forms with unevaluated symbol positions ({@code case} keys,
 * {@code typecase} types, {@code tagbody} tags, declarations) are walked as they are; any
 * other built-in macro that names a tracked name is walked through its expansion, which
 * replaces it only where a binding was renamed. A program no local declaration names a
 * variable in comes back as the same list, and so does every form of one that names no
 * renamed binding (the cons-identity rule, {@code .kb/source-positions.md}).
 */
public final class SpecialDeclarationScoping {

	/**
	 * The built-in macros whose expansion depends on the class registry the compilers
	 * expand them with: walked as calls (their arguments are forms), never through a
	 * registry-less expansion.
	 */
	private static final Set<String> REGISTRY_EXPANDED = Set.of(LispNames.ERROR, LispNames.CERROR, LispNames.WARN,
			LispNames.SIGNAL, LispNames.MAKE_CONDITION, LispNames.DEFINE_CONDITION, LispNames.DEFTYPE);

	/**
	 * The built-in macros every argument of which is a form, binding nothing: walked as
	 * calls, so a compiler's own handling of the form (the format directive compiler, the
	 * place expanders) still sees it.
	 */
	private static final Set<String> ARGUMENT_FORMS = Set.of(LispNames.WHEN, LispNames.UNLESS, LispNames.AND,
			LispNames.OR, LispNames.PROG1, LispNames.PROG2, LispNames.SETF, LispNames.PSETQ, LispNames.INCF,
			LispNames.DECF, LispNames.PUSH, LispNames.POP, LispNames.PUSHNEW, LispNames.REMF, LispNames.ROTATEF,
			LispNames.FORMAT, LispNames.TIME, LispNames.MULTIPLE_VALUE_LIST, LispNames.MULTIPLE_VALUE_CALL,
			LispNames.NTH_VALUE, LispNames.WRITE_CHAR, LispNames.EVAL_WHEN, LispNames.WITH_COMPILATION_UNIT,
			LispNames.IGNORE_ERRORS, LispNames.COMPLEMENT, LispNames.DOCUMENTATION);

	private SpecialDeclarationScoping() {
	}

	/**
	 * Renames apart the lexical bindings of the names the program's local special
	 * declarations name.
	 * @param program the top-level forms
	 * @return the program with those bindings renamed, or {@code program} itself when
	 * nothing needed renaming
	 */
	public static List<LispVal> scope(List<LispVal> program) {
		Set<String> tracked = SpecialVarCollector.collectLocallyDeclaredOnly(program);
		if (tracked.isEmpty()) {
			return program;
		}
		Walker walker = new Walker(tracked);
		List<LispVal> result = null;
		for (int i = 0; i < program.size(); i++) {
			LispVal form = program.get(i);
			LispVal walked = walker.walk(form, Scope.EMPTY);
			if (walked != form && result == null) {
				result = new ArrayList<>(program);
			}
			if (result != null) {
				result.set(i, walked);
			}
		}
		return result == null ? program : result;
	}

	/**
	 * The tracked names a binding form makes visible, innermost first: a renamed lexical
	 * binding ({@code renamed} its new symbol) or a special one ({@code renamed} null:
	 * the name stays, and so do the references it scopes).
	 */
	private record Scope(String name, @Nullable LispSymbol renamed, @Nullable Scope parent) {

		static final Scope EMPTY = new Scope("", null, null);

		/** The new symbol of the innermost binding of {@code name}, or null. */
		@Nullable LispSymbol lookup(String name) {
			for (Scope s = this; s != null; s = s.parent) {
				if (s.name.equals(name)) {
					return s.renamed;
				}
			}
			return null;
		}

		Scope with(String name, @Nullable LispSymbol renamed) {
			return new Scope(name, renamed, this);
		}

	}

	private static final class Walker {

		private final Set<String> tracked;

		/** Per form, whether it names a tracked symbol anywhere. */
		private final Map<LispCons, Boolean> mentions = new IdentityHashMap<>();

		private int counter;

		Walker(Set<String> tracked) {
			this.tracked = tracked;
		}

		LispVal walk(LispVal form, Scope scope) {
			if (form instanceof LispSymbol sym) {
				if (!this.tracked.contains(sym.name())) {
					return form;
				}
				LispSymbol renamed = scope.lookup(sym.name());
				return renamed != null ? renamed : form;
			}
			if (!(form instanceof LispCons cons) || !mentionsTracked(cons)) {
				return form;
			}
			try {
				return walkCons(cons, scope);
			}
			catch (RuntimeException ex) {
				throw SourceProvenance.noteFailure(cons, ex);
			}
		}

		private LispVal walkCons(LispCons cons, Scope scope) {
			if (!(cons.car() instanceof LispSymbol head)) {
				return walkList(cons, scope);
			}
			if (!cons.isProperList()) {
				return walkList(cons, scope);
			}
			return switch (head.name()) {
				case LispNames.QUOTE, LispNames.UNSPELLED_QUOTE, LispNames.GO, LispNames.DECLAIM, LispNames.PROCLAIM,
						LispNames.DEFMACRO, LispNames.IN_PACKAGE ->
					cons;
				case LispNames.FUNCTION -> walkFunction(cons, scope);
				case LispNames.LAMBDA -> walkLambda(cons, 1, scope);
				case LispNames.DEFUN -> walkLambda(cons, 2, scope);
				case LispNames.LET -> walkLet(cons, false, scope);
				case LispNames.LET_STAR -> walkLet(cons, true, scope);
				case LispNames.PROG -> walkLet(cons, false, scope);
				case LispNames.PROG_STAR -> walkLet(cons, true, scope);
				case LispNames.LOCALLY -> walkBodyFrom(cons, 1, scope);
				case LispNames.FLET, LispNames.LABELS -> walkLocalFunctions(cons, scope);
				case LispNames.MACROLET -> walkBodyFrom(cons, 2, scope);
				case LispNames.SYMBOL_MACROLET -> walkSymbolMacrolet(cons, scope);
				case LispNames.MULTIPLE_VALUE_BIND -> walkMultipleValueBind(cons, scope);
				case LispNames.DESTRUCTURING_BIND -> walkDestructuringBind(cons, scope);
				case LispNames.DOLIST, LispNames.DOTIMES -> walkDolist(cons, scope);
				case LispNames.DO -> walkDo(cons, false, scope);
				case LispNames.DO_STAR -> walkDo(cons, true, scope);
				case LispNames.HANDLER_CASE -> walkHandlerCase(cons, scope);
				case LispNames.HANDLER_BIND -> walkHandlerBind(cons, scope);
				case LispNames.CASE, LispNames.ECASE, LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE,
						LispNames.CTYPECASE ->
					walkClauses(cons, scope);
				case LispNames.BLOCK, LispNames.FN_BLOCK_INTERNAL, LispNames.RETURN_FROM, LispNames.THE ->
					walkFrom(cons, 2, scope);
				case LispNames.CHECK_TYPE -> walkPositions(cons, 1, 2, scope);
				case LispNames.TAGBODY -> walkTagbody(cons, 1, scope);
				case LispNames.DECLARE -> walkDeclaration(cons, scope);
				case LispNames.MULTIPLE_VALUE_SETQ -> walkMultipleValueSetq(cons, scope);
				default -> walkOther(cons, head, scope);
			};
		}

		/**
		 * A form no arm above knows: a built-in macro walks through its expansion, which
		 * replaces it only where the walk renamed something; a call (or a special form
		 * whose arguments are forms) walks its arguments.
		 */
		private LispVal walkOther(LispCons cons, LispSymbol head, Scope scope) {
			String name = head.name();
			if (LispNames.COND.equals(name)) {
				// Every element of a clause is a form, its test included.
				List<LispVal> parts = cons.toList();
				for (int i = 1; i < parts.size(); i++) {
					if (parts.get(i) instanceof LispCons clause) {
						parts.set(i, walkList(clause, scope));
					}
				}
				return LispCons.rebuiltList(cons, parts);
			}
			if (!REGISTRY_EXPANDED.contains(name) && !ARGUMENT_FORMS.contains(name)) {
				LispVal expansion = expansionOf(cons, name);
				if (expansion != null && expansion != cons) {
					LispVal walked = walk(expansion, scope);
					return walked == expansion ? cons : SourceProvenance.inherit(cons, walked);
				}
			}
			LispVal args = walkList(cons.cdr(), scope);
			return args == cons.cdr() ? cons : LispCons.rebuilt(cons, head, args);
		}

		/**
		 * The expansion the compilers' analyses read a binding macro through
		 * ({@code FreeVarAnalyzer}), or null for a form that is no built-in macro.
		 */
		private static @Nullable LispVal expansionOf(LispCons cons, String name) {
			try {
				return switch (name) {
					case LispNames.WITH_OPEN_STREAM -> LispMacroExpander.expandWithOpenStream(cons, true);
					case LispNames.DO_SYMBOLS -> LispMacroExpander.expandDoSymbols(cons, false);
					case LispNames.DO_EXTERNAL_SYMBOLS -> LispMacroExpander.expandDoSymbols(cons, true);
					case LispNames.WITH_MUTEX_QUALIFIED, LispNames.WITH_LOCK_HELD_QUALIFIED,
							LispNames.WITH_RECURSIVE_LOCK_HELD_QUALIFIED ->
						LispMacroExpander.expandWithMutex(cons);
					default -> {
						LispVal expansion = LispMacroExpander.expandBuiltinMacro(cons);
						yield expansion != null ? expansion : LispMacroExpander.expandUiopMacro(cons, true);
					}
				};
			}
			catch (RuntimeException malformed) {
				// The compiler reports a malformed form; walked as a call here.
				return null;
			}
		}

		/** Every element of a (possibly dotted) list, walked as a form. */
		private LispVal walkList(LispVal list, Scope scope) {
			if (!(list instanceof LispCons)) {
				return walk(list, scope);
			}
			// The cdr direction is a loop, so a long list costs no stack.
			List<LispCons> cells = new ArrayList<>();
			List<LispVal> cars = new ArrayList<>();
			LispVal tail = list;
			while (tail instanceof LispCons c) {
				cells.add(c);
				cars.add(walk(c.car(), scope));
				tail = c.cdr();
			}
			LispVal result = walk(tail, scope);
			for (int i = cells.size() - 1; i >= 0; i--) {
				result = LispCons.rebuilt(cells.get(i), cars.get(i), result);
			}
			return result;
		}

		/** The form with its elements from {@code from} on walked as forms. */
		private LispVal walkFrom(LispCons cons, int from, Scope scope) {
			List<LispVal> parts = cons.toList();
			for (int i = from; i < parts.size(); i++) {
				parts.set(i, walk(parts.get(i), scope));
			}
			return LispCons.rebuiltList(cons, parts);
		}

		/** The form with its elements in {@code [from, to)} walked as forms. */
		private LispVal walkPositions(LispCons cons, int from, int to, Scope scope) {
			List<LispVal> parts = cons.toList();
			for (int i = from; i < Math.min(to, parts.size()); i++) {
				parts.set(i, walk(parts.get(i), scope));
			}
			return LispCons.rebuiltList(cons, parts);
		}

		private LispVal walkFunction(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() == 2 && parts.get(1) instanceof LispCons lambda && lambda.car() instanceof LispSymbol op
					&& LispNames.LAMBDA.equals(op.name())) {
				parts.set(1, walk(lambda, scope));
				return LispCons.rebuiltList(cons, parts);
			}
			return cons;
		}

		/**
		 * {@code (lambda lambda-list body...)} /
		 * {@code (defun name lambda-list body...)}: the parameters bind in order, a
		 * default form seeing the ones before it, under the body's declarations -- a
		 * function body's, read through the {@code block} a {@code defmethod} wraps it
		 * in.
		 */
		private LispVal walkLambda(LispCons cons, int listIndex, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() <= listIndex) {
				return cons;
			}
			List<LispVal> body = parts.subList(listIndex + 1, parts.size());
			Set<String> declared = SpecialDeclarations.leading(body, true);
			Scope[] inner = { scope };
			LispVal lambdaList = walkLambdaList(parts.get(listIndex), declared, inner, false);
			List<LispVal> out = new ArrayList<>(parts.subList(0, listIndex));
			out.add(lambdaList);
			out.addAll(walkBody(body, freeScope(declared, inner[0])));
			return LispCons.rebuiltList(cons, out);
		}

		/**
		 * Walks a lambda list -- an ordinary one, or with {@code destructuring} a
		 * destructuring one, whose required and rest positions may hold patterns --
		 * binding each variable in {@code scope[0]} as it goes.
		 */
		private LispVal walkLambdaList(LispVal list, Set<String> declared, Scope[] scope, boolean destructuring) {
			if (list instanceof LispSymbol rest) {
				// A dotted tail, or a whole-list variable: a rest parameter.
				return bind(rest, declared, scope);
			}
			if (!(list instanceof LispCons)) {
				return list;
			}
			List<LispCons> cells = new ArrayList<>();
			List<LispVal> cars = new ArrayList<>();
			String section = "";
			LispVal tail = list;
			while (tail instanceof LispCons c) {
				cells.add(c);
				LispVal element = c.car();
				LispVal walked = element;
				if (element instanceof LispSymbol sym && sym.name().startsWith("&")) {
					section = sym.name();
				}
				else if (LispNames.LAMBDA_OPTIONAL.equals(section)) {
					walked = walkOptional(element, declared, scope, destructuring, false);
				}
				else if (LispNames.LAMBDA_KEY.equals(section)) {
					walked = walkOptional(element, declared, scope, destructuring, true);
				}
				else if (LispNames.LAMBDA_AUX.equals(section)) {
					walked = walkAux(element, declared, scope);
				}
				else if (element instanceof LispSymbol sym) {
					walked = bind(sym, declared, scope);
				}
				else if (destructuring && element instanceof LispCons pattern) {
					walked = walkLambdaList(pattern, declared, scope, true);
				}
				cars.add(walked);
				tail = c.cdr();
			}
			LispVal result = tail instanceof LispSymbol dotted ? bind(dotted, declared, scope) : tail;
			for (int i = cells.size() - 1; i >= 0; i--) {
				result = LispCons.rebuilt(cells.get(i), cars.get(i), result);
			}
			return result;
		}

		/**
		 * An {@code &optional} or {@code &key} entry: {@code var}, {@code (var default)},
		 * {@code (var default supplied-p)}, and for a key {@code ((:k var) ...)}. A
		 * renamed key variable keeps its keyword, spelled out.
		 */
		private LispVal walkOptional(LispVal entry, Set<String> declared, Scope[] scope, boolean destructuring,
				boolean key) {
			if (entry instanceof LispSymbol var) {
				LispVal bound = bind(var, declared, scope);
				if (key && bound != var) {
					return list(list(keywordFor(var), bound));
				}
				return bound;
			}
			if (!(entry instanceof LispCons spec) || !spec.isProperList()) {
				return entry;
			}
			List<LispVal> parts = spec.toList();
			if (parts.size() > 1) {
				parts.set(1, walk(parts.get(1), scope[0]));
			}
			LispVal head = parts.get(0);
			if (head instanceof LispSymbol var) {
				LispVal bound = bind(var, declared, scope);
				parts.set(0, key && bound != var ? list(keywordFor(var), bound) : bound);
			}
			else if (key && head instanceof LispCons keyed && keyed.isProperList() && keyed.toList().size() == 2) {
				List<LispVal> keyParts = keyed.toList();
				if (keyParts.get(1) instanceof LispSymbol var) {
					keyParts.set(1, bind(var, declared, scope));
				}
				else if (destructuring && keyParts.get(1) instanceof LispCons pattern) {
					keyParts.set(1, walkLambdaList(pattern, declared, scope, true));
				}
				parts.set(0, LispCons.rebuiltList(keyed, keyParts));
			}
			else if (destructuring && head instanceof LispCons pattern) {
				parts.set(0, walkLambdaList(pattern, declared, scope, true));
			}
			if (parts.size() > 2 && parts.get(2) instanceof LispSymbol suppliedP) {
				parts.set(2, bind(suppliedP, declared, scope));
			}
			return LispCons.rebuiltList(spec, parts);
		}

		/** An {@code &aux} entry: {@code var} or {@code (var init)}. */
		private LispVal walkAux(LispVal entry, Set<String> declared, Scope[] scope) {
			if (entry instanceof LispSymbol var) {
				return bind(var, declared, scope);
			}
			if (!(entry instanceof LispCons spec) || !spec.isProperList()) {
				return entry;
			}
			List<LispVal> parts = spec.toList();
			if (parts.size() > 1) {
				parts.set(1, walk(parts.get(1), scope[0]));
			}
			if (parts.get(0) instanceof LispSymbol var) {
				parts.set(0, bind(var, declared, scope));
			}
			return LispCons.rebuiltList(spec, parts);
		}

		/**
		 * Binds a variable in {@code scope[0]}: a tracked name the form's declarations
		 * name is a special binding and keeps its name; any other tracked name is a
		 * lexical binding and gets a fresh one.
		 */
		private LispVal bind(LispSymbol var, Set<String> declared, Scope[] scope) {
			String name = var.name();
			if (!this.tracked.contains(name)) {
				return var;
			}
			if (declared.contains(name)) {
				scope[0] = scope[0].with(name, null);
				return var;
			}
			LispSymbol renamed = new LispSymbol(name + "%" + (this.counter++));
			scope[0] = scope[0].with(name, renamed);
			return renamed;
		}

		/**
		 * The body's scope: a tracked name the declarations name and the form does not
		 * bind is special in the body (a free declaration), whatever binds it further
		 * out.
		 */
		private Scope freeScope(Set<String> declared, Scope scope) {
			Scope body = scope;
			for (String name : declared) {
				if (this.tracked.contains(name)) {
					body = body.with(name, null);
				}
			}
			return body;
		}

		/**
		 * The body forms walked in {@code scope}, the declarations among them included.
		 */
		private List<LispVal> walkBody(List<LispVal> body, Scope scope) {
			List<LispVal> out = new ArrayList<>(body.size());
			for (LispVal form : body) {
				out.add(walk(form, scope));
			}
			return out;
		}

		/**
		 * {@code (locally body...)}, {@code (macrolet (defs) body...)}: the body from
		 * {@code from} on, under its free declarations; the elements before it as they
		 * are.
		 */
		private LispVal walkBodyFrom(LispCons cons, int from, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < from) {
				return cons;
			}
			List<LispVal> body = parts.subList(from, parts.size());
			Scope inner = freeScope(SpecialDeclarations.leading(body, false), scope);
			List<LispVal> out = new ArrayList<>(parts.subList(0, from));
			out.addAll(walkBody(body, inner));
			return LispCons.rebuiltList(cons, out);
		}

		/**
		 * {@code let}/{@code let*}, and {@code prog}/{@code prog*}, whose body is a
		 * {@code tagbody}'s: a parallel binding evaluates every init in the outer scope,
		 * a sequential one each in the scope of the bindings before it.
		 */
		private LispVal walkLet(LispCons cons, boolean sequential, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2) {
				return cons;
			}
			boolean prog = cons.car() instanceof LispSymbol op
					&& (LispNames.PROG.equals(op.name()) || LispNames.PROG_STAR.equals(op.name()));
			List<LispVal> body = parts.subList(2, parts.size());
			Set<String> declared = SpecialDeclarations.leading(body, false);
			Scope[] inner = { scope };
			LispVal bindings = parts.get(1);
			if (bindings instanceof LispCons bindingCells && bindingCells.isProperList()) {
				List<LispVal> entries = bindingCells.toList();
				List<LispVal> walked = new ArrayList<>(entries.size());
				// The variables of a parallel binding bind after every init is walked.
				List<int[]> pending = new ArrayList<>();
				for (int i = 0; i < entries.size(); i++) {
					LispVal entry = entries.get(i);
					if (entry instanceof LispSymbol var) {
						walked.add(var);
						pending.add(new int[] { i, -1 });
					}
					else if (entry instanceof LispCons pair && pair.car() instanceof LispSymbol
							&& pair.isProperList()) {
						List<LispVal> pairParts = pair.toList();
						for (int k = 1; k < pairParts.size(); k++) {
							pairParts.set(k, walk(pairParts.get(k), sequential ? inner[0] : scope));
						}
						walked.add(LispCons.rebuiltList(pair, pairParts));
						pending.add(new int[] { i, 0 });
					}
					else {
						walked.add(entry);
						continue;
					}
					if (sequential) {
						bindEntry(walked, pending.removeLast(), declared, inner);
					}
				}
				for (int[] p : pending) {
					bindEntry(walked, p, declared, inner);
				}
				parts.set(1, LispCons.rebuiltList(bindingCells, walked));
			}
			Scope bodyScope = freeScope(declared, inner[0]);
			List<LispVal> out = new ArrayList<>(parts.subList(0, 2));
			if (prog) {
				for (LispVal form : body) {
					out.add(form instanceof LispCons ? walk(form, bodyScope) : form);
				}
			}
			else {
				out.addAll(walkBody(body, bodyScope));
			}
			return LispCons.rebuiltList(cons, out);
		}

		/** Binds the variable of a binding-list entry, renaming it in place. */
		private void bindEntry(List<LispVal> walked, int[] at, Set<String> declared, Scope[] scope) {
			LispVal entry = walked.get(at[0]);
			if (at[1] < 0) {
				walked.set(at[0], bind((LispSymbol) entry, declared, scope));
				return;
			}
			LispCons pair = (LispCons) entry;
			LispVal var = bind((LispSymbol) pair.car(), declared, scope);
			if (var != pair.car()) {
				walked.set(at[0], LispCons.rebuilt(pair, var, pair.cdr()));
			}
		}

		/** {@code (flet|labels ((name lambda-list body...)...) body...)}. */
		private LispVal walkLocalFunctions(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2) {
				return cons;
			}
			if (parts.get(1) instanceof LispCons defs && defs.isProperList()) {
				List<LispVal> walkedDefs = new ArrayList<>();
				for (LispVal def : defs.toList()) {
					if (def instanceof LispCons defCons && defCons.isProperList() && defCons.toList().size() >= 2) {
						// The definition shapes a lambda: (name lambda-list body...).
						walkedDefs.add(walkLambda(defCons, 1, scope));
					}
					else {
						walkedDefs.add(def);
					}
				}
				parts.set(1, LispCons.rebuiltList(defs, walkedDefs));
			}
			List<LispVal> body = parts.subList(2, parts.size());
			Scope inner = freeScope(SpecialDeclarations.leading(body, false), scope);
			List<LispVal> out = new ArrayList<>(parts.subList(0, 2));
			out.addAll(walkBody(body, inner));
			return LispCons.rebuiltList(cons, out);
		}

		/**
		 * {@code (symbol-macrolet ((symbol expansion)...) body...)}: a symbol macro named
		 * like a tracked variable is no variable, so the body keeps its name.
		 */
		private LispVal walkSymbolMacrolet(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2) {
				return cons;
			}
			Scope inner = scope;
			if (parts.get(1) instanceof LispCons defs && defs.isProperList()) {
				List<LispVal> walkedDefs = new ArrayList<>();
				for (LispVal def : defs.toList()) {
					if (def instanceof LispCons defCons && defCons.isProperList() && defCons.toList().size() == 2
							&& defCons.car() instanceof LispSymbol sym) {
						walkedDefs.add(walkPositions(defCons, 1, 2, scope));
						if (this.tracked.contains(sym.name())) {
							inner = inner.with(sym.name(), null);
						}
					}
					else {
						walkedDefs.add(def);
					}
				}
				parts.set(1, LispCons.rebuiltList(defs, walkedDefs));
			}
			List<LispVal> body = parts.subList(2, parts.size());
			Scope bodyScope = freeScope(SpecialDeclarations.leading(body, false), inner);
			List<LispVal> out = new ArrayList<>(parts.subList(0, 2));
			out.addAll(walkBody(body, bodyScope));
			return LispCons.rebuiltList(cons, out);
		}

		/** {@code (multiple-value-bind (vars...) values-form body...)}. */
		private LispVal walkMultipleValueBind(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 3) {
				return cons;
			}
			List<LispVal> body = parts.subList(3, parts.size());
			Set<String> declared = SpecialDeclarations.leading(body, false);
			parts.set(2, walk(parts.get(2), scope));
			Scope[] inner = { scope };
			if (parts.get(1) instanceof LispCons vars && vars.isProperList()) {
				List<LispVal> walkedVars = new ArrayList<>();
				for (LispVal var : vars.toList()) {
					walkedVars.add(var instanceof LispSymbol sym ? bind(sym, declared, inner) : var);
				}
				parts.set(1, LispCons.rebuiltList(vars, walkedVars));
			}
			List<LispVal> out = new ArrayList<>(parts.subList(0, 3));
			out.addAll(walkBody(body, freeScope(declared, inner[0])));
			return LispCons.rebuiltList(cons, out);
		}

		/** {@code (destructuring-bind pattern form body...)}. */
		private LispVal walkDestructuringBind(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 3) {
				return cons;
			}
			List<LispVal> body = parts.subList(3, parts.size());
			Set<String> declared = SpecialDeclarations.leading(body, false);
			parts.set(2, walk(parts.get(2), scope));
			Scope[] inner = { scope };
			parts.set(1, walkLambdaList(parts.get(1), declared, inner, true));
			List<LispVal> out = new ArrayList<>(parts.subList(0, 3));
			out.addAll(walkBody(body, freeScope(declared, inner[0])));
			return LispCons.rebuiltList(cons, out);
		}

		/** {@code (dolist|dotimes (var form [result]) body...)}. */
		private LispVal walkDolist(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons spec) || !spec.isProperList()
					|| !(spec.car() instanceof LispSymbol var)) {
				return walkFrom(cons, 1, scope);
			}
			List<LispVal> body = parts.subList(2, parts.size());
			Set<String> declared = SpecialDeclarations.leading(body, false);
			List<LispVal> specParts = spec.toList();
			if (specParts.size() > 1) {
				specParts.set(1, walk(specParts.get(1), scope));
			}
			Scope[] inner = { scope };
			specParts.set(0, bind(var, declared, inner));
			Scope bodyScope = freeScope(declared, inner[0]);
			for (int i = 2; i < specParts.size(); i++) {
				specParts.set(i, walk(specParts.get(i), bodyScope));
			}
			List<LispVal> out = new ArrayList<>(parts.subList(0, 1));
			out.add(LispCons.rebuiltList(spec, specParts));
			out.addAll(walkBody(body, bodyScope));
			return LispCons.rebuiltList(cons, out);
		}

		/** {@code (do|do* ((var init [step])...) (end-test result...) body...)}. */
		private LispVal walkDo(LispCons cons, boolean sequential, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 3 || !(parts.get(1) instanceof LispCons || parts.get(1) instanceof LispNil)) {
				return walkFrom(cons, 1, scope);
			}
			List<LispVal> body = parts.subList(3, parts.size());
			Set<String> declared = SpecialDeclarations.leading(body, false);
			Scope[] inner = { scope };
			List<LispVal> specs = parts.get(1) instanceof LispCons specCells && specCells.isProperList()
					? new ArrayList<>(specCells.toList()) : new ArrayList<>();
			List<Integer> pending = new ArrayList<>();
			for (int i = 0; i < specs.size(); i++) {
				LispVal spec = specs.get(i);
				if (spec instanceof LispCons specCons && specCons.isProperList() && specCons.toList().size() >= 2) {
					List<LispVal> specParts = specCons.toList();
					specParts.set(1, walk(specParts.get(1), sequential ? inner[0] : scope));
					specs.set(i, LispCons.rebuiltList(specCons, specParts));
				}
				if (sequential) {
					specs.set(i, bindDoVariable(specs.get(i), declared, inner));
				}
				else {
					pending.add(i);
				}
			}
			for (int i : pending) {
				specs.set(i, bindDoVariable(specs.get(i), declared, inner));
			}
			Scope bodyScope = freeScope(declared, inner[0]);
			// The steps run in the scope of every variable.
			for (int i = 0; i < specs.size(); i++) {
				if (specs.get(i) instanceof LispCons specCons && specCons.isProperList()
						&& specCons.toList().size() >= 3) {
					specs.set(i, walkPositions(specCons, 2, 3, bodyScope));
				}
			}
			if (parts.get(1) instanceof LispCons specCells && specCells.isProperList()) {
				parts.set(1, LispCons.rebuiltList(specCells, specs));
			}
			parts.set(2, walk(parts.get(2), bodyScope));
			List<LispVal> out = new ArrayList<>(parts.subList(0, 3));
			for (LispVal form : body) {
				out.add(form instanceof LispCons ? walk(form, bodyScope) : form);
			}
			return LispCons.rebuiltList(cons, out);
		}

		private LispVal bindDoVariable(LispVal spec, Set<String> declared, Scope[] scope) {
			if (spec instanceof LispSymbol var) {
				return bind(var, declared, scope);
			}
			if (spec instanceof LispCons specCons && specCons.car() instanceof LispSymbol var) {
				LispVal bound = bind(var, declared, scope);
				return bound == var ? spec : LispCons.rebuilt(specCons, bound, specCons.cdr());
			}
			return spec;
		}

		/**
		 * {@code (handler-case form (type ([var]) body...)...)}: a clause binds its
		 * condition variable; a {@code :no-error} clause has a lambda list.
		 */
		private LispVal walkHandlerCase(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2) {
				return cons;
			}
			parts.set(1, walk(parts.get(1), scope));
			for (int i = 2; i < parts.size(); i++) {
				if (!(parts.get(i) instanceof LispCons clause) || !clause.isProperList()
						|| clause.toList().size() < 2) {
					continue;
				}
				List<LispVal> clauseParts = clause.toList();
				List<LispVal> body = clauseParts.subList(2, clauseParts.size());
				Set<String> declared = SpecialDeclarations.leading(body, false);
				Scope[] inner = { scope };
				if (clause.car() instanceof LispSymbol kw && ":NO-ERROR".equals(kw.name())) {
					clauseParts.set(1, walkLambdaList(clauseParts.get(1), declared, inner, false));
				}
				else if (clauseParts.get(1) instanceof LispCons varList && varList.isProperList()
						&& varList.car() instanceof LispSymbol var) {
					LispVal bound = bind(var, declared, inner);
					clauseParts.set(1, bound == var ? varList : LispCons.rebuilt(varList, bound, varList.cdr()));
				}
				List<LispVal> out = new ArrayList<>(clauseParts.subList(0, 2));
				out.addAll(walkBody(body, freeScope(declared, inner[0])));
				parts.set(i, LispCons.rebuiltList(clause, out));
			}
			return LispCons.rebuiltList(cons, parts);
		}

		/** {@code (handler-bind ((type handler)...) body...)}: the types are data. */
		private LispVal walkHandlerBind(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2) {
				return cons;
			}
			if (parts.get(1) instanceof LispCons clauses && clauses.isProperList()) {
				List<LispVal> walked = new ArrayList<>();
				for (LispVal clause : clauses.toList()) {
					walked.add(clause instanceof LispCons clauseCons && clauseCons.isProperList()
							? walkFrom(clauseCons, 1, scope) : clause);
				}
				parts.set(1, LispCons.rebuiltList(clauses, walked));
			}
			for (int i = 2; i < parts.size(); i++) {
				parts.set(i, walk(parts.get(i), scope));
			}
			return LispCons.rebuiltList(cons, parts);
		}

		/** The {@code case}/{@code typecase} families: a clause head is data. */
		private LispVal walkClauses(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2) {
				return cons;
			}
			parts.set(1, walk(parts.get(1), scope));
			for (int i = 2; i < parts.size(); i++) {
				if (parts.get(i) instanceof LispCons clause && clause.isProperList()) {
					parts.set(i, walkFrom(clause, 1, scope));
				}
			}
			return LispCons.rebuiltList(cons, parts);
		}

		/** {@code (tagbody tag-or-form...)}: a tag is data. */
		private LispVal walkTagbody(LispCons cons, int from, Scope scope) {
			List<LispVal> parts = cons.toList();
			for (int i = from; i < parts.size(); i++) {
				if (parts.get(i) instanceof LispCons) {
					parts.set(i, walk(parts.get(i), scope));
				}
			}
			return LispCons.rebuiltList(cons, parts);
		}

		/** {@code (multiple-value-setq (vars...) form)}: the variables are places. */
		private LispVal walkMultipleValueSetq(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			if (parts.size() != 3) {
				return cons;
			}
			if (parts.get(1) instanceof LispCons vars && vars.isProperList()) {
				List<LispVal> walked = new ArrayList<>();
				for (LispVal var : vars.toList()) {
					walked.add(walk(var, scope));
				}
				parts.set(1, LispCons.rebuiltList(vars, walked));
			}
			parts.set(2, walk(parts.get(2), scope));
			return LispCons.rebuiltList(cons, parts);
		}

		/**
		 * A {@code (declare ...)} form: the variables a type, {@code ignore},
		 * {@code ignorable} or {@code dynamic-extent} specifier names follow their
		 * bindings' renaming; a {@code special} specifier keeps every name, which it is
		 * about; the function-naming and optimization specifiers are left alone.
		 */
		private LispVal walkDeclaration(LispCons cons, Scope scope) {
			List<LispVal> parts = cons.toList();
			for (int i = 1; i < parts.size(); i++) {
				if (!(parts.get(i) instanceof LispCons spec) || !spec.isProperList()
						|| !(spec.car() instanceof LispSymbol op)) {
					continue;
				}
				String specifier = member(op.name());
				int from = switch (specifier) {
					case LispNames.SPECIAL, "OPTIMIZE", "INLINE", "NOTINLINE", "FTYPE", "DECLARATION" -> -1;
					case "TYPE" -> 2;
					default -> 1;
				};
				if (from < 0) {
					continue;
				}
				List<LispVal> specParts = spec.toList();
				for (int k = from; k < specParts.size(); k++) {
					if (specParts.get(k) instanceof LispSymbol) {
						specParts.set(k, walk(specParts.get(k), scope));
					}
				}
				parts.set(i, LispCons.rebuiltList(spec, specParts));
			}
			return LispCons.rebuiltList(cons, parts);
		}

		private boolean mentionsTracked(LispCons form) {
			Boolean known = this.mentions.get(form);
			if (known != null) {
				return known;
			}
			boolean found = false;
			LispVal tail = form;
			while (tail instanceof LispCons cell && !found) {
				LispVal element = cell.car();
				found = element instanceof LispSymbol sym ? this.tracked.contains(sym.name())
						: element instanceof LispCons inner && mentionsTracked(inner);
				tail = cell.cdr();
			}
			if (!found && tail instanceof LispSymbol dotted) {
				found = this.tracked.contains(dotted.name());
			}
			this.mentions.put(form, found);
			return found;
		}

		// The keyword LambdaLists derives for a key variable spelled without one.
		private static LispSymbol keywordFor(LispSymbol var) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(var.name());
			return new LispSymbol(":" + (qn == null ? var.name() : qn.member()));
		}

		private static String member(String name) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
			return qn == null ? name : qn.member();
		}

		private static LispVal list(LispVal... elements) {
			LispVal result = LispNil.INSTANCE;
			for (int i = elements.length - 1; i >= 0; i--) {
				result = new LispCons(elements[i], result);
			}
			return result;
		}

	}

}
