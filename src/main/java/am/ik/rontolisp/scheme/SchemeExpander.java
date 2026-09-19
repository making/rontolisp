package am.ik.rontolisp.scheme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Expands {@code syntax-rules} macros ({@code define-syntax}, {@code let-syntax},
 * {@code letrec-syntax}) BEFORE the lowering, so every pre-scan of {@link SchemeLowering}
 * -- the {@code set!} census, the {@code defun}-or-variable decision, the internal
 * definitions of a body -- sees the expanded program, and no backend learns a macro.
 *
 * <p>
 * Hygiene is by renaming. Every identifier a template introduces becomes an ALIAS: a
 * fresh symbol of the same spelling that remembers the identifier it stands for and the
 * environment the macro was defined in. An alias bound by a binding form becomes a fresh
 * generated variable, so it captures nothing the user wrote; an alias left free resolves
 * where the macro was defined -- a keyword to the lowering's identity-compared core
 * symbol, a variable to its emitted name. Every LOCAL variable of the program is renamed
 * too, so no Common Lisp binding can capture the name a free alias is emitted as. The
 * output holds no alias: only user symbols, core symbols and generated symbols
 * ({@code .kb/scheme-frontend.md}, "Macros").
 *
 * <p>
 * The walk mirrors the lowering's scoping of every binding form. It runs only over a file
 * (or a session) that spells a syntax definition, so any other program is lowered exactly
 * as before.
 */
final class SchemeExpander {

	/** What the expander needs from the lowering. */
	interface Host {

		/**
		 * The keyword a plain identifier names at the top level, or {@code null}.
		 * @param identifier a user identifier
		 * @return its core, or {@code null} when it is not a keyword there
		 */
		@Nullable Core keyword(LispSymbol identifier);

		/**
		 * The identity-compared symbol the lowering reads as a keyword wherever it
		 * stands, or {@code null} for a keyword refused by name.
		 * @param core the keyword
		 * @return its symbol
		 */
		@Nullable LispSymbol coreSymbol(Core core);

		/**
		 * The keyword an identity-compared core symbol means, or {@code null} for any
		 * other symbol.
		 * @param identifier a symbol
		 * @return its core, or {@code null}
		 */
		@Nullable Core coreOf(LispSymbol identifier);

		LispSymbol fresh(String kind);

		LispReadException error(String message, LispCons form);

		<T extends LispVal> T inherit(LispCons original, T rewritten);

		/**
		 * Refuses a top-level syntax definition over an imported name where the standard
		 * does.
		 * @param identifier the defined name
		 * @param form the definition
		 */
		void checkTopLevelDefinition(LispSymbol identifier, LispCons form);

	}

	/** What an identifier means to the expander. */
	private sealed interface Meaning {

	}

	private record Keyword(Core core) implements Meaning {
	}

	private record Macro(String name, SyntaxRules rules, Env env) implements Meaning {
	}

	/** A variable, emitted as {@code symbol}: a renamed local or a top-level name. */
	private record Variable(LispSymbol symbol) implements Meaning {
	}

	private static final class Env {

		private final @Nullable Env parent;

		private final Map<Object, Meaning> frame = new HashMap<>();

		Env(@Nullable Env parent) {
			this.parent = parent;
		}

	}

	/**
	 * What an alias stands for.
	 *
	 * @param original the identifier the template spelled (itself possibly an alias)
	 * @param env where the macro was defined
	 * @param key the alias's own binding key
	 */
	private record Alias(LispSymbol original, Env env, AliasKey key) {
	}

	private record AliasKey(int id) {
	}

	private static final int MAX_DEPTH = 1_000;

	private final Host host;

	// Outlives each buffer of a session: its macros and top-level names.
	private final Env global = new Env(null);

	private final Map<LispSymbol, Alias> aliases = new IdentityHashMap<>();

	private int aliasCounter;

	private int depth;

	SchemeExpander(Host host) {
		this.host = host;
	}

	/**
	 * Whether a top-level syntax definition of this lowering defined the name.
	 * @param name the identifier's spelling
	 * @return whether it names a macro
	 */
	boolean definesSyntax(String name) {
		return this.global.frame.get(name) instanceof Macro;
	}

	/**
	 * Whether a program spelling these datums needs the expander: it names a syntax
	 * definition keyword (or {@code syntax-error}), directly or through an import rename.
	 * @param datums the program's datums
	 * @param keyword the lowering's top-level keyword lookup
	 * @return whether to expand
	 */
	static boolean needed(List<LispVal> datums, java.util.function.Function<LispSymbol, @Nullable Core> keyword) {
		for (LispVal datum : datums) {
			if (spellsSyntaxDefinition(datum, keyword)) {
				return true;
			}
		}
		return false;
	}

	private static boolean spellsSyntaxDefinition(LispVal datum,
			java.util.function.Function<LispSymbol, @Nullable Core> keyword) {
		return switch (datum) {
			case LispSymbol symbol -> {
				Core core = keyword.apply(symbol);
				yield core == Core.DEFINE_SYNTAX || core == Core.LET_SYNTAX || core == Core.LETREC_SYNTAX
						|| core == Core.SYNTAX_ERROR || symbol.name().equals("define-syntax")
						|| symbol.name().equals("let-syntax") || symbol.name().equals("letrec-syntax");
			}
			case LispCons cons ->
				spellsSyntaxDefinition(cons.car(), keyword) || spellsSyntaxDefinition(cons.cdr(), keyword);
			case LispArray array -> {
				for (LispVal element : array.data()) {
					if (spellsSyntaxDefinition(element, keyword)) {
						yield true;
					}
				}
				yield false;
			}
			case null, default -> false;
		};
	}

	/**
	 * Expands a program's top-level forms, or one buffer of a session: syntax definitions
	 * are consumed, {@code begin}s spliced, every macro use expanded.
	 * @param datums the forms after the leading imports
	 * @return the expanded forms
	 */
	List<LispVal> topLevel(List<LispVal> datums) {
		// Like the lowering's pre-scan: a top-level definition's name is a variable of
		// the whole file, not a keyword it may shadow.
		for (LispVal datum : datums) {
			if (datum instanceof LispCons form && isPlainIdentifier(form.car())) {
				Core core = keywordOf(form, this.global);
				if (core == Core.DEFINE) {
					LispSymbol name = definedName(form);
					if (name != null && isPlainIdentifier(name)) {
						this.global.frame.put(key(name), new Variable(name));
					}
				}
			}
		}
		List<LispVal> out = new ArrayList<>();
		Deque<LispVal> queue = new ArrayDeque<>(datums);
		while (!queue.isEmpty()) {
			LispVal datum = headExpanded(queue.poll(), this.global);
			if (!(datum instanceof LispCons form)) {
				try {
					out.add(expression(datum, this.global));
				}
				catch (NotAVariableException ex) {
					// A bare top-level atom: no cons to position it by.
					throw new LispReadException(String.valueOf(ex.getMessage()));
				}
				continue;
			}
			Core core = keywordOf(form, this.global);
			if (core == Core.BEGIN) {
				pushFront(queue, elements(form.cdr(), form));
				continue;
			}
			if (core == Core.DEFINE_SYNTAX) {
				defineSyntax(form, this.global, true);
				continue;
			}
			if (core == Core.DEFINE) {
				out.add(define(form, this.global, true));
			}
			else if (core == Core.DEFINE_VALUES) {
				out.add(defineValues(form, this.global, true));
			}
			else if (core == Core.DEFINE_RECORD_TYPE || core == Core.IMPORT || core == Core.DEFINE_LIBRARY) {
				out.add(strip(form));
			}
			else {
				out.add(expression(form, this.global));
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ identifiers

	private Object key(LispSymbol identifier) {
		Alias alias = this.aliases.get(identifier);
		return alias != null ? alias.key() : identifier.name();
	}

	private boolean isAlias(LispVal datum) {
		return datum instanceof LispSymbol symbol && this.aliases.containsKey(symbol);
	}

	private static boolean isPlainIdentifier(LispVal datum) {
		return SyntaxRules.isIdentifier(datum);
	}

	private LispSymbol base(LispSymbol identifier) {
		LispSymbol current = identifier;
		for (Alias alias = this.aliases.get(current); alias != null; alias = this.aliases.get(current)) {
			current = alias.original();
		}
		return current;
	}

	private @Nullable Meaning resolve(LispSymbol identifier, Env env) {
		// A keyword the lowering spliced in itself (an include's begin) means that
		// keyword wherever it stands.
		Core spliced = this.host.coreOf(identifier);
		if (spliced != null) {
			return new Keyword(spliced);
		}
		Object key = key(identifier);
		for (Env scope = env; scope != null; scope = scope.parent) {
			Meaning meaning = scope.frame.get(key);
			if (meaning != null) {
				return meaning;
			}
		}
		Alias alias = this.aliases.get(identifier);
		if (alias != null) {
			return resolve(alias.original(), alias.env());
		}
		Core core = this.host.keyword(identifier);
		return core == null ? null : new Keyword(core);
	}

	// What free-identifier=? compares: the binding, or the spelling of a free name.
	private Object denotation(LispSymbol identifier, Env env) {
		Meaning meaning = resolve(identifier, env);
		return meaning != null ? meaning : "free " + base(identifier).name();
	}

	private @Nullable Core keywordOf(LispCons form, Env env) {
		return form.car() instanceof LispSymbol head && isPlainIdentifier(head)
				&& resolve(head, env) instanceof Keyword keyword ? keyword.core() : null;
	}

	private LispSymbol alias(LispSymbol identifier, Env env) {
		LispSymbol alias = new LispSymbol(identifier.name());
		this.aliases.put(alias, new Alias(identifier, env, new AliasKey(++this.aliasCounter)));
		return alias;
	}

	// A binding occurrence: every local is renamed to a generated variable.
	private LispSymbol bind(LispSymbol identifier, Env env) {
		LispSymbol renamed = this.host.fresh("V");
		env.frame.put(key(identifier), new Variable(renamed));
		return renamed;
	}

	// A top-level definition: a user name stays itself -- other files reach it -- while a
	// name the template introduced is a hidden global only that expansion can spell.
	private LispSymbol bindTopLevel(LispSymbol identifier, LispCons form) {
		if (isAlias(identifier)) {
			LispSymbol hidden = this.host.fresh("G");
			this.global.frame.put(key(identifier), new Variable(hidden));
			return hidden;
		}
		this.global.frame.put(key(identifier), new Variable(identifier));
		return identifier;
	}

	// ------------------------------------------------------------------ expressions

	private LispVal expression(LispVal datum, Env env) {
		return switch (datum) {
			case LispSymbol identifier when isPlainIdentifier(identifier) -> reference(identifier, env, null);
			case LispCons form -> form(form, env);
			case LispArray vector -> strip(vector);
			default -> datum;
		};
	}

	private LispVal reference(LispSymbol identifier, Env env, @Nullable LispCons form) {
		Meaning meaning = resolve(identifier, env);
		return switch (meaning) {
			case Variable variable -> variable.symbol();
			case Keyword keyword -> keywordSymbol(identifier, keyword.core());
			case Macro macro -> {
				if (form != null) {
					throw this.host.error("the macro " + macro.name() + " is not a variable", form);
				}
				throw new NotAVariableException("the macro " + macro.name() + " is not a variable");
			}
			case null -> base(identifier);
		};
	}

	// A keyword as the lowering must see it: a user's spelling stays (the lowering
	// resolves it the same way), an alias becomes the identity-compared core symbol.
	private LispSymbol keywordSymbol(LispSymbol identifier, Core core) {
		if (!isAlias(identifier)) {
			return identifier;
		}
		LispSymbol symbol = this.host.coreSymbol(core);
		return symbol != null ? symbol : base(identifier);
	}

	private LispVal form(LispCons form, Env env) {
		try {
			return unpositionedForm(form, env);
		}
		catch (NotAVariableException ex) {
			throw this.host.error(String.valueOf(ex.getMessage()), form);
		}
	}

	private LispVal unpositionedForm(LispCons form, Env env) {
		if (form.car() instanceof LispSymbol head && isPlainIdentifier(head)) {
			Meaning meaning = resolve(head, env);
			if (meaning instanceof Macro macro) {
				LispVal expansion = expand(macro, form, env);
				if (++this.depth > MAX_DEPTH) {
					this.depth = 0;
					throw this.host.error("the expansion of " + macro.name() + " does not terminate", form);
				}
				try {
					return expression(expansion, env);
				}
				finally {
					this.depth = Math.max(0, this.depth - 1);
				}
			}
			if (meaning instanceof Keyword keyword) {
				try {
					return keywordForm(keyword.core(), keywordSymbol(head, keyword.core()), form, env);
				}
				catch (MalformedException ex) {
					// Left to the lowering, which names what is wrong with it.
					return strip(form);
				}
			}
		}
		return mapList(form, element -> expression(element, env));
	}

	/** A macro keyword in value position, positioned by the innermost enclosing form. */
	private static final class NotAVariableException extends RuntimeException {

		NotAVariableException(String message) {
			super(message, null, false, false);
		}

	}

	/** A shape the lowering reports better than the expander could. */
	private static final class MalformedException extends RuntimeException {

		MalformedException() {
			super(null, null, false, false);
		}

	}

	private LispVal keywordForm(Core core, LispSymbol head, LispCons form, Env env) {
		List<LispVal> parts = parts(form);
		return switch (core) {
			case QUOTE -> rebuild(form, head, List.of(strip(single(parts))));
			case QUASIQUOTE -> rebuild(form, head, List.of(quasi(single(parts), 1, env)));
			case LAMBDA -> {
				atLeast(parts, 3);
				Env inner = new Env(env);
				List<LispVal> out = new ArrayList<>();
				out.add(formals(parts.get(1), inner));
				out.addAll(body(parts.subList(2, parts.size()), new Env(inner)));
				yield rebuild(form, head, out);
			}
			case CASE_LAMBDA -> {
				// (case-lambda (formals body...)...): each clause is a lambda of its own.
				List<LispVal> out = new ArrayList<>();
				for (LispVal clause : parts.subList(1, parts.size())) {
					List<LispVal> clauseParts = elementsOrMalformed(clause);
					atLeast(clauseParts, 2);
					Env inner = new Env(env);
					List<LispVal> rewritten = new ArrayList<>();
					rewritten.add(formals(clauseParts.get(0), inner));
					rewritten.addAll(body(clauseParts.subList(1, clauseParts.size()), new Env(inner)));
					out.add(inheritList(clause, rewritten));
				}
				yield rebuild(form, head, out);
			}
			case BEGIN -> parts.size() == 1 ? rebuild(form, head, List.of())
					: rebuild(form, head, body(parts.subList(1, parts.size()), new Env(env)));
			case LET -> let(form, head, parts, env);
			case LET_STAR -> {
				atLeast(parts, 3);
				Env inner = env;
				List<LispVal> bindings = new ArrayList<>();
				for (LispVal binding : elementsOrMalformed(parts.get(1))) {
					List<LispVal> pair = pair(binding);
					LispVal init = expression(pair.get(1), inner);
					inner = new Env(inner);
					bindings.add(inherit(binding, SchemeBuiltins.list(bindIdentifier(pair.get(0), inner), init)));
				}
				List<LispVal> out = new ArrayList<>();
				out.add(inheritList(parts.get(1), bindings));
				out.addAll(body(parts.subList(2, parts.size()), new Env(inner)));
				yield rebuild(form, head, out);
			}
			case LETREC, LETREC_STAR -> {
				atLeast(parts, 3);
				Env inner = new Env(env);
				List<LispVal> specs = elementsOrMalformed(parts.get(1));
				List<LispVal> names = new ArrayList<>();
				for (LispVal binding : specs) {
					names.add(bindIdentifier(pair(binding).get(0), inner));
				}
				List<LispVal> bindings = new ArrayList<>();
				for (int i = 0; i < specs.size(); i++) {
					bindings.add(inherit(specs.get(i),
							SchemeBuiltins.list(names.get(i), expression(pair(specs.get(i)).get(1), inner))));
				}
				List<LispVal> out = new ArrayList<>();
				out.add(inheritList(parts.get(1), bindings));
				out.addAll(body(parts.subList(2, parts.size()), new Env(inner)));
				yield rebuild(form, head, out);
			}
			case LET_VALUES, LET_STAR_VALUES -> letValues(form, head, parts, env, core == Core.LET_STAR_VALUES);
			case DO -> doLoop(form, head, parts, env);
			case CASE -> {
				atLeast(parts, 2);
				List<LispVal> out = new ArrayList<>();
				out.add(expression(parts.get(1), env));
				for (LispVal clauseDatum : parts.subList(2, parts.size())) {
					List<LispVal> clause = elementsOrMalformed(clauseDatum);
					if (clause.isEmpty()) {
						throw new MalformedException();
					}
					List<LispVal> rewritten = new ArrayList<>();
					LispVal data = clause.get(0);
					rewritten.add(data instanceof LispSymbol keyword && isPlainIdentifier(keyword)
							? expression(keyword, env) : strip(data));
					for (LispVal expression : clause.subList(1, clause.size())) {
						rewritten.add(expression(expression, env));
					}
					out.add(inheritList(clauseDatum, rewritten));
				}
				yield rebuild(form, head, out);
			}
			case COND -> {
				List<LispVal> out = new ArrayList<>();
				for (LispVal clauseDatum : parts.subList(1, parts.size())) {
					List<LispVal> clause = elementsOrMalformed(clauseDatum);
					List<LispVal> rewritten = new ArrayList<>();
					for (LispVal expression : clause) {
						rewritten.add(expression(expression, env));
					}
					out.add(inheritList(clauseDatum, rewritten));
				}
				yield rebuild(form, head, out);
			}
			case GUARD -> {
				// (guard (var clause...) body...): var is bound in the clauses only.
				atLeast(parts, 3);
				List<LispVal> spec = elementsOrMalformed(parts.get(1));
				if (spec.isEmpty()) {
					throw new MalformedException();
				}
				Env inner = new Env(env);
				List<LispVal> rewrittenSpec = new ArrayList<>();
				rewrittenSpec.add(bindIdentifier(spec.get(0), inner));
				for (LispVal clauseDatum : spec.subList(1, spec.size())) {
					List<LispVal> rewritten = new ArrayList<>();
					for (LispVal expression : elementsOrMalformed(clauseDatum)) {
						rewritten.add(expression(expression, inner));
					}
					rewrittenSpec.add(inheritList(clauseDatum, rewritten));
				}
				List<LispVal> out = new ArrayList<>();
				out.add(inheritList(parts.get(1), rewrittenSpec));
				out.addAll(body(parts.subList(2, parts.size()), new Env(env)));
				yield rebuild(form, head, out);
			}
			case PARAMETERIZE -> {
				// (parameterize ((param value)...) body...): both halves of a binding are
				// expressions, and the body is a <body> of its own.
				atLeast(parts, 3);
				List<LispVal> bindings = new ArrayList<>();
				for (LispVal binding : elementsOrMalformed(parts.get(1))) {
					bindings.add(inheritList(binding, pair(binding).stream().map(e -> expression(e, env)).toList()));
				}
				List<LispVal> out = new ArrayList<>();
				out.add(inheritList(parts.get(1), bindings));
				out.addAll(body(parts.subList(2, parts.size()), new Env(env)));
				yield rebuild(form, head, out);
			}
			case DEFINE_RECORD_TYPE, IMPORT, DEFINE_LIBRARY, INCLUDE, INCLUDE_CI -> strip(form);
			case DEFINE_SYNTAX -> throw this.host
				.error("a syntax definition is only allowed at the top level or at the head of a body", form);
			case LET_SYNTAX, LETREC_SYNTAX -> {
				atLeast(parts, 3);
				Env inner = new Env(env);
				Env definitions = core == Core.LET_SYNTAX ? env : inner;
				for (LispVal binding : elementsOrMalformed(parts.get(1))) {
					List<LispVal> pair = pair(binding);
					if (!(binding instanceof LispCons bindingForm) || !isPlainIdentifier(pair.get(0))) {
						throw this.host.error("a syntax binding is (keyword transformer)", form);
					}
					LispSymbol name = (LispSymbol) pair.get(0);
					inner.frame.put(key(name), transformer(name, pair.get(1), definitions, bindingForm));
				}
				// A body of its own, like let's (R7RS 4.3.1): its definitions are local.
				LispSymbol let = Objects.requireNonNull(this.host.coreSymbol(Core.LET));
				List<LispVal> out = new ArrayList<>();
				out.add(LispNil.INSTANCE);
				out.addAll(body(parts.subList(2, parts.size()), new Env(inner)));
				yield rebuild(form, let, out);
			}
			case SYNTAX_RULES ->
				throw this.host.error("syntax-rules is only allowed as a syntax definition's transformer", form);
			case SYNTAX_ERROR -> throw syntaxError(form, parts);
			case ELLIPSIS, UNDERSCORE -> throw this.host.error("misplaced " + head.name(), form);
			default -> {
				List<LispVal> out = new ArrayList<>();
				for (LispVal element : parts.subList(1, parts.size())) {
					out.add(expression(element, env));
				}
				yield rebuild(form, head, out);
			}
		};
	}

	// (syntax-error "message" args...): the message and the arguments, as written.
	private LispReadException syntaxError(LispCons form, List<LispVal> parts) {
		if (parts.size() < 2 || !(parts.get(1) instanceof LispString message)) {
			return this.host.error("syntax-error needs a message string", form);
		}
		StringBuilder text = new StringBuilder(message.value());
		for (LispVal argument : parts.subList(2, parts.size())) {
			text.append(' ').append(written(strip(argument)));
		}
		return this.host.error(text.toString(), form);
	}

	// A datum as Scheme's write spells it, for a message.
	static String written(LispVal datum) {
		return switch (datum) {
			case LispSymbol symbol -> symbol.name();
			case LispNil nil -> "()";
			case LispCons cons -> {
				StringBuilder text = new StringBuilder("(");
				LispVal rest = cons;
				while (rest instanceof LispCons cell) {
					text.append(written(cell.car()));
					rest = cell.cdr();
					if (rest instanceof LispCons) {
						text.append(' ');
					}
				}
				if (rest != LispNil.INSTANCE) {
					text.append(" . ").append(written(rest));
				}
				yield text.append(')').toString();
			}
			default -> datum.print();
		};
	}

	private LispVal let(LispCons form, LispSymbol head, List<LispVal> parts, Env env) {
		if (parts.size() >= 2 && isPlainIdentifier(parts.get(1))) {
			atLeast(parts, 4);
			List<LispVal> specs = elementsOrMalformed(parts.get(2));
			List<LispVal> inits = new ArrayList<>();
			for (LispVal binding : specs) {
				inits.add(expression(pair(binding).get(1), env));
			}
			Env named = new Env(env);
			LispSymbol name = bind((LispSymbol) parts.get(1), named);
			Env inner = new Env(named);
			List<LispVal> bindings = new ArrayList<>();
			for (int i = 0; i < specs.size(); i++) {
				bindings.add(inherit(specs.get(i),
						SchemeBuiltins.list(bindIdentifier(pair(specs.get(i)).get(0), inner), inits.get(i))));
			}
			List<LispVal> out = new ArrayList<>();
			out.add(name);
			out.add(inheritList(parts.get(2), bindings));
			out.addAll(body(parts.subList(3, parts.size()), new Env(inner)));
			return rebuild(form, head, out);
		}
		atLeast(parts, 3);
		List<LispVal> specs = elementsOrMalformed(parts.get(1));
		Env inner = new Env(env);
		List<LispVal> inits = new ArrayList<>();
		for (LispVal binding : specs) {
			inits.add(expression(pair(binding).get(1), env));
		}
		List<LispVal> bindings = new ArrayList<>();
		for (int i = 0; i < specs.size(); i++) {
			bindings.add(inherit(specs.get(i),
					SchemeBuiltins.list(bindIdentifier(pair(specs.get(i)).get(0), inner), inits.get(i))));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(inheritList(parts.get(1), bindings));
		out.addAll(body(parts.subList(2, parts.size()), new Env(inner)));
		return rebuild(form, head, out);
	}

	private LispVal letValues(LispCons form, LispSymbol head, List<LispVal> parts, Env env, boolean sequential) {
		atLeast(parts, 3);
		List<LispVal> clauses = elementsOrMalformed(parts.get(1));
		Env inner = new Env(env);
		List<LispVal> inits = new ArrayList<>();
		List<LispVal> formals = new ArrayList<>();
		for (LispVal clause : clauses) {
			List<LispVal> pair = pair(clause);
			if (sequential) {
				inits.add(expression(pair.get(1), inner));
				inner = new Env(inner);
				formals.add(formals(pair.get(0), inner));
			}
			else {
				inits.add(expression(pair.get(1), env));
			}
		}
		if (!sequential) {
			for (LispVal clause : clauses) {
				formals.add(formals(pair(clause).get(0), inner));
			}
		}
		List<LispVal> rewritten = new ArrayList<>();
		for (int i = 0; i < clauses.size(); i++) {
			rewritten.add(inherit(clauses.get(i), SchemeBuiltins.list(formals.get(i), inits.get(i))));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(inheritList(parts.get(1), rewritten));
		out.addAll(body(parts.subList(2, parts.size()), new Env(inner)));
		return rebuild(form, head, out);
	}

	// (do ((var init step)...) (test result...) body...): the inits outside, the rest
	// inside the variables' scope.
	private LispVal doLoop(LispCons form, LispSymbol head, List<LispVal> parts, Env env) {
		atLeast(parts, 3);
		List<LispVal> specs = elementsOrMalformed(parts.get(1));
		List<List<LispVal>> triples = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		for (LispVal spec : specs) {
			List<LispVal> triple = elementsOrMalformed(spec);
			if (triple.size() < 2 || triple.size() > 3) {
				throw new MalformedException();
			}
			triples.add(triple);
			inits.add(expression(triple.get(1), env));
		}
		Env inner = new Env(env);
		List<LispVal> variables = new ArrayList<>();
		for (List<LispVal> triple : triples) {
			variables.add(bindIdentifier(triple.get(0), inner));
		}
		List<LispVal> rewritten = new ArrayList<>();
		for (int i = 0; i < triples.size(); i++) {
			List<LispVal> triple = triples.get(i);
			List<LispVal> spec = new ArrayList<>(List.of(variables.get(i), inits.get(i)));
			if (triple.size() == 3) {
				spec.add(expression(triple.get(2), inner));
			}
			rewritten.add(inheritList(specs.get(i), spec));
		}
		List<LispVal> exit = new ArrayList<>();
		for (LispVal expression : elementsOrMalformed(parts.get(2))) {
			exit.add(expression(expression, inner));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(inheritList(parts.get(1), rewritten));
		out.add(inheritList(parts.get(2), exit));
		for (LispVal expression : parts.subList(3, parts.size())) {
			out.add(expression(expression, inner));
		}
		return rebuild(form, head, out);
	}

	// Quasiquote: data but for the unquoted expressions of the outermost level.
	private LispVal quasi(LispVal template, int depth, Env env) {
		if (template instanceof LispArray vector) {
			List<LispVal> elements = new ArrayList<>();
			for (LispVal element : vector.data()) {
				elements.add(quasi(element, depth, env));
			}
			return new LispArray(new int[] { elements.size() }, elements.toArray(new LispVal[0]));
		}
		if (!(template instanceof LispCons cons)) {
			return strip(template);
		}
		if (cons.car() instanceof LispSymbol head && cons.cdr() instanceof LispCons rest
				&& rest.cdr() == LispNil.INSTANCE) {
			String name = base(head).name();
			if (name.equals("unquote") || name.equals("unquote-splicing")) {
				LispVal inner = depth == 1 ? expression(rest.car(), env) : quasi(rest.car(), depth - 1, env);
				return this.host.inherit(cons, SchemeBuiltins.list(base(head), inner));
			}
			if (name.equals("quasiquote")) {
				return this.host.inherit(cons, SchemeBuiltins.list(base(head), quasi(rest.car(), depth + 1, env)));
			}
		}
		return this.host.inherit(cons, new LispCons(quasi(cons.car(), depth, env), quasi(cons.cdr(), depth, env)));
	}

	// ------------------------------------------------------------------ bodies

	/**
	 * A body: its forms' heads expanded until the definitions show, {@code begin}s
	 * spliced, syntax definitions consumed, every internal definition bound before any
	 * value is expanded (letrec* semantics, as the lowering binds them).
	 */
	private List<LispVal> body(List<LispVal> forms, Env env) {
		List<LispVal> pending = new ArrayList<>();
		Deque<LispVal> queue = new ArrayDeque<>(forms);
		while (!queue.isEmpty()) {
			LispVal datum = headExpanded(queue.poll(), env);
			if (datum instanceof LispCons form) {
				Core core = keywordOf(form, env);
				if (core == Core.BEGIN && form.cdr() instanceof LispCons) {
					pushFront(queue, elements(form.cdr(), form));
					continue;
				}
				if (core == Core.DEFINE_SYNTAX) {
					defineSyntax(form, env, false);
					continue;
				}
				if (core == Core.DEFINE) {
					LispSymbol name = definedName(form);
					if (name != null) {
						bind(name, env);
					}
				}
				else if (core == Core.DEFINE_VALUES && parts(form).size() == 3) {
					formals(parts(form).get(1), env);
				}
			}
			pending.add(datum);
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal datum : pending) {
			Core core = datum instanceof LispCons form ? keywordOf(form, env) : null;
			if (core == Core.DEFINE) {
				out.add(define((LispCons) datum, env, false));
			}
			else if (core == Core.DEFINE_VALUES) {
				out.add(defineValues((LispCons) datum, env, false));
			}
			else {
				out.add(expression(datum, env));
			}
		}
		return out;
	}

	// A form whose head is a macro use, expanded until it is not.
	private LispVal headExpanded(LispVal datum, Env env) {
		LispVal current = datum;
		int steps = 0;
		while (current instanceof LispCons form && form.car() instanceof LispSymbol head && isPlainIdentifier(head)
				&& resolve(head, env) instanceof Macro macro) {
			if (++steps > MAX_DEPTH) {
				throw this.host.error("the expansion of " + macro.name() + " does not terminate", form);
			}
			current = expand(macro, form, env);
		}
		return current;
	}

	private @Nullable LispSymbol definedName(LispCons form) {
		if (!(form.cdr() instanceof LispCons rest)) {
			return null;
		}
		LispVal target = rest.car();
		if (target instanceof LispCons signature) {
			target = signature.car();
		}
		return isPlainIdentifier(target) ? (LispSymbol) target : null;
	}

	// (define name value) or (define (name . formals) body...), its name already bound
	// in a body; at the top level bound here, in order.
	private LispVal define(LispCons form, Env env, boolean topLevel) {
		List<LispVal> parts = parts(form);
		LispSymbol head = keywordSymbol((LispSymbol) form.car(), Core.DEFINE);
		LispSymbol name = definedName(form);
		if (name == null || parts.size() < 2) {
			return strip(form);
		}
		LispSymbol emitted = topLevel ? bindTopLevel(name, form) : (LispSymbol) reference(name, env, form);
		if (parts.get(1) instanceof LispCons signature) {
			Env inner = new Env(env);
			LispVal formals = formals(signature.cdr(), inner);
			List<LispVal> out = new ArrayList<>();
			out.add(this.host.inherit(signature, new LispCons(emitted, formals)));
			out.addAll(body(parts.subList(2, parts.size()), new Env(inner)));
			return rebuild(form, head, out);
		}
		List<LispVal> out = new ArrayList<>();
		out.add(emitted);
		for (LispVal value : parts.subList(2, parts.size())) {
			out.add(expression(value, env));
		}
		return rebuild(form, head, out);
	}

	private LispVal defineValues(LispCons form, Env env, boolean topLevel) {
		List<LispVal> parts = parts(form);
		if (parts.size() != 3) {
			return strip(form);
		}
		LispSymbol head = keywordSymbol((LispSymbol) form.car(), Core.DEFINE_VALUES);
		LispVal formals = topLevel ? topLevelFormals(parts.get(1), form) : renamedFormals(parts.get(1), env);
		return rebuild(form, head, List.of(formals, expression(parts.get(2), env)));
	}

	private LispVal topLevelFormals(LispVal datum, LispCons form) {
		if (datum instanceof LispCons cons) {
			return this.host.inherit(cons,
					new LispCons(topLevelFormals(cons.car(), form), topLevelFormals(cons.cdr(), form)));
		}
		return isPlainIdentifier(datum) ? bindTopLevel((LispSymbol) datum, form) : datum;
	}

	// The formals of a define-values in a body, bound during the scan: their emitted
	// names.
	private LispVal renamedFormals(LispVal datum, Env env) {
		if (datum instanceof LispCons cons) {
			return this.host.inherit(cons,
					new LispCons(renamedFormals(cons.car(), env), renamedFormals(cons.cdr(), env)));
		}
		return isPlainIdentifier(datum) ? reference((LispSymbol) datum, env, null) : datum;
	}

	private void defineSyntax(LispCons form, Env env, boolean topLevel) {
		List<LispVal> parts = parts(form);
		if (parts.size() != 3 || !isPlainIdentifier(parts.get(1))) {
			throw this.host.error("a syntax definition is (define-syntax keyword transformer)", form);
		}
		LispSymbol name = (LispSymbol) parts.get(1);
		if (topLevel && !isAlias(name)) {
			this.host.checkTopLevelDefinition(name, form);
		}
		env.frame.put(key(name), transformer(name, parts.get(2), env, form));
	}

	private Macro transformer(LispSymbol name, LispVal spec, Env env, LispCons form) {
		if (!(spec instanceof LispCons specForm && keywordOf(specForm, env) == Core.SYNTAX_RULES)) {
			throw this.host.error("only syntax-rules transformers are supported", form);
		}
		try {
			return new Macro(base(name).name(), SyntaxRules.parse(parts(specForm).subList(1, parts(specForm).size())),
					env);
		}
		catch (IllegalArgumentException ex) {
			throw this.host.error(String.valueOf(ex.getMessage()), specForm);
		}
	}

	private LispVal expand(Macro macro, LispCons form, Env env) {
		Map<Object, LispSymbol> renamed = new HashMap<>();
		LispVal expansion;
		try {
			expansion = macro.rules().expand(form, new SyntaxRules.Identifiers() {

				@Override
				public boolean isEllipsis(LispSymbol identifier) {
					return isAuxiliary(identifier, macro.env(), Core.ELLIPSIS, "...");
				}

				@Override
				public boolean isUnderscore(LispSymbol identifier) {
					return isAuxiliary(identifier, macro.env(), Core.UNDERSCORE, "_");
				}

				@Override
				public boolean matchesLiteral(LispSymbol input, LispSymbol literal) {
					return denotation(input, env).equals(denotation(literal, macro.env()));
				}

				@Override
				public Object key(LispSymbol identifier) {
					return SchemeExpander.this.key(identifier);
				}

				@Override
				public LispSymbol rename(LispSymbol identifier) {
					return renamed.computeIfAbsent(key(identifier), k -> alias(identifier, macro.env()));
				}

				@Override
				public LispCons cons(LispVal car, LispVal cdr) {
					return SchemeExpander.this.host.inherit(form, new LispCons(car, cdr));
				}

			});
		}
		catch (IllegalArgumentException ex) {
			throw this.host.error(String.valueOf(ex.getMessage()), form);
		}
		if (expansion == null) {
			throw this.host.error("no syntax-rules clause of " + macro.name() + " matches " + written(strip(form)),
					form);
		}
		return expansion;
	}

	// `...` and `_`: the keyword where the macro was defined, or the free spelling.
	private boolean isAuxiliary(LispSymbol identifier, Env env, Core core, String spelling) {
		Meaning meaning = resolve(identifier, env);
		return meaning instanceof Keyword keyword ? keyword.core() == core
				: meaning == null && base(identifier).name().equals(spelling);
	}

	// ------------------------------------------------------------------ formals and data

	// Binds every identifier of a formals list (proper, dotted or a lone rest symbol).
	private LispVal formals(LispVal datum, Env env) {
		if (datum instanceof LispCons cons) {
			LispVal car = isPlainIdentifier(cons.car()) ? bind((LispSymbol) cons.car(), env) : strip(cons.car());
			return this.host.inherit(cons, new LispCons(car, formals(cons.cdr(), env)));
		}
		return isPlainIdentifier(datum) ? bind((LispSymbol) datum, env) : datum;
	}

	private LispVal bindIdentifier(LispVal datum, Env env) {
		if (!isPlainIdentifier(datum)) {
			throw new MalformedException();
		}
		return bind((LispSymbol) datum, env);
	}

	/** A datum with every alias replaced by the identifier it stands for. */
	private LispVal strip(LispVal datum) {
		return switch (datum) {
			case LispSymbol symbol -> base(symbol);
			case LispCons cons -> this.host.inherit(cons, new LispCons(strip(cons.car()), strip(cons.cdr())));
			case LispArray vector -> {
				LispVal[] elements = new LispVal[vector.data().length];
				boolean changed = false;
				for (int i = 0; i < elements.length; i++) {
					elements[i] = strip(vector.data()[i]);
					changed |= elements[i] != vector.data()[i];
				}
				yield changed ? new LispArray(new int[] { elements.length }, elements) : vector;
			}
			default -> datum;
		};
	}

	// ------------------------------------------------------------------ list helpers

	private LispVal mapList(LispCons form, java.util.function.UnaryOperator<LispVal> function) {
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = form;
		while (rest instanceof LispCons cell) {
			elements.add(function.apply(cell.car()));
			rest = cell.cdr();
		}
		LispVal tail = strip(rest);
		for (int i = elements.size() - 1; i >= 0; i--) {
			tail = new LispCons(elements.get(i), tail);
		}
		return this.host.inherit(form, tail);
	}

	private LispVal rebuild(LispCons form, LispSymbol head, List<LispVal> rest) {
		return this.host.inherit(form, new LispCons(head, SchemeBuiltins.listOf(new ArrayList<>(rest))));
	}

	private LispVal inheritList(LispVal original, List<LispVal> elements) {
		LispVal list = SchemeBuiltins.listOf(new ArrayList<>(elements));
		return original instanceof LispCons cons ? this.host.inherit(cons, list) : list;
	}

	private <T extends LispVal> T inherit(LispVal original, T rewritten) {
		return original instanceof LispCons cons ? this.host.inherit(cons, rewritten) : rewritten;
	}

	private static void pushFront(Deque<LispVal> queue, List<LispVal> elements) {
		for (int i = elements.size() - 1; i >= 0; i--) {
			queue.push(elements.get(i));
		}
	}

	private List<LispVal> elements(LispVal list, LispCons form) {
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = list;
		while (rest instanceof LispCons cell) {
			elements.add(cell.car());
			rest = cell.cdr();
		}
		if (rest != LispNil.INSTANCE) {
			throw this.host.error("expected a proper list, got " + strip(list).print(), form);
		}
		return elements;
	}

	private static List<LispVal> elementsOrMalformed(LispVal list) {
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = list;
		while (rest instanceof LispCons cell) {
			elements.add(cell.car());
			rest = cell.cdr();
		}
		if (rest != LispNil.INSTANCE) {
			throw new MalformedException();
		}
		return elements;
	}

	private static List<LispVal> parts(LispCons form) {
		return elementsOrMalformed(form);
	}

	private static List<LispVal> pair(LispVal binding) {
		List<LispVal> pair = elementsOrMalformed(binding);
		if (pair.size() != 2) {
			throw new MalformedException();
		}
		return pair;
	}

	private static LispVal single(List<LispVal> parts) {
		if (parts.size() != 2) {
			throw new MalformedException();
		}
		return parts.get(1);
	}

	private static void atLeast(List<LispVal> parts, int size) {
		if (parts.size() < size) {
			throw new MalformedException();
		}
	}

}
