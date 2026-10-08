package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * {@code read-string} and {@code read} of the Clojure lowering: calls of the run-time
 * reader in {@code clojure.lisp}, which reads the source reader's language from a string
 * or a character input stream and answers what a quote of the same text answers. Each
 * call carries its namespace context -- the namespace and its aliases, which {@code ::kw}
 * resolves against -- and a program that reads registers its record classes once, ahead
 * of everything else, so a record literal reads back as the record.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureReadLowering {

	private static final LispSymbol STANDARD_INPUT = new LispSymbol("*STANDARD-INPUT*");

	private ClojureReadLowering() {
	}

	/**
	 * A reading verb in call position, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		if (name.equals("read-string")) {
			ClojureCoreLowering.arity(name, n, 1, 2);
			ctx.usedReader = true;
			if (n == 1) {
				return worker("READ-STRING", ctx.lower(items.get(1)), context(ctx));
			}
			return worker("READ-STRING-OPTS", ctx.lower(items.get(1)), ctx.lower(items.get(2)), context(ctx));
		}
		if (!name.equals("read")) {
			return null;
		}
		ClojureCoreLowering.arity(name, n, 0, 4);
		ctx.usedReader = true;
		return switch (n) {
			case 0 ->
				worker("READ", STANDARD_INPUT, ClojureLowering.TRUE_CONST, ClojureLowering.NIL_CONST, context(ctx));
			case 1 -> worker("READ", ctx.lower(items.get(1)), ClojureLowering.TRUE_CONST, ClojureLowering.NIL_CONST,
					context(ctx));
			case 2 -> worker("READ-OPTS", ctx.lower(items.get(1)), ctx.lower(items.get(2)), context(ctx));
			case 3 ->
				worker("READ", ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)), context(ctx));
			default -> {
				// the recursive flag evaluates in order with the rest and is ignored
				List<LispVal> bindings = new ArrayList<>();
				List<LispSymbol> temps = new ArrayList<>();
				for (int i = 1; i <= 4; i++) {
					LispSymbol temp = ctx.freshTemp();
					temps.add(temp);
					bindings.add(ClojureLowerUtil.list(temp, ctx.lower(items.get(i))));
				}
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings),
						worker("READ", temps.get(0), temps.get(1), temps.get(2), context(ctx)));
			}
		};
	}

	/**
	 * {@code (read-string text)} over an already lowered text, in the current namespace's
	 * context: how a boundary crossing reads an {@code :s-expr} the host handed over
	 * ({@link ClojureWasmLowering}).
	 * @param ctx the hub
	 * @param text the lowered string
	 * @return the read call
	 */
	static LispVal readStringOf(ClojureLowering ctx, LispVal text) {
		ctx.usedReader = true;
		return worker("READ-STRING", text, context(ctx));
	}

	/**
	 * A reading verb as a function value, or null when the name is none of them: a rest
	 * lambda over the {@code -v} worker, which checks the count at run time.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(ClojureLowering ctx, String name) {
		if (!name.equals("read-string") && !name.equals("read")) {
			return null;
		}
		ctx.usedReader = true;
		LispSymbol args = new LispSymbol(ClojureLowering.mangle("read-args"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args),
				worker(name.equals("read") ? "READ-V" : "READ-STRING-V", context(ctx), args));
	}

	/**
	 * The calling namespace's context as quoted data: {@code ("ns" ("alias" "full.ns")
	 * ...)}, the aliases wired so far (sorted, so the program text is stable).
	 */
	static LispVal context(ClojureLowering ctx) {
		List<LispVal> items = new ArrayList<>();
		items.add(LispString.literal(ctx.currentNs));
		for (Map.Entry<String, String> alias : new TreeMap<>(ctx.ns().aliases).entrySet()) {
			items.add(ClojureLowerUtil.list(LispString.literal(alias.getKey()), LispString.literal(alias.getValue())));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(items));
	}

	/**
	 * The registration of the record and deftype classes a record literal may name:
	 * {@code (rontolisp::%clojure-read-register '((class tag (field ...) record-p)
	 * ...))}, sorted by class name. Null when there is none.
	 * @param types the classes to register
	 * @return the form, or null
	 */
	static @Nullable LispVal registration(Iterable<ClojureLowering.TypeDef> types) {
		Map<String, ClojureLowering.TypeDef> byClass = new TreeMap<>();
		for (ClojureLowering.TypeDef type : types) {
			byClass.put(type.className(), type);
		}
		if (byClass.isEmpty()) {
			return null;
		}
		List<LispVal> entries = new ArrayList<>();
		for (ClojureLowering.TypeDef type : byClass.values()) {
			List<LispVal> fields = new ArrayList<>();
			for (String field : type.fields()) {
				fields.add(LispString.literal(field));
			}
			entries.add(ClojureLowerUtil.list(LispString.literal(type.className()),
					LispString.literal(type.tagSpelling()), ClojureLowerUtil.list(fields),
					type.record() ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST));
		}
		return ClojureLowerUtil.list(runtime("READ-REGISTER"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(entries)));
	}

	private static LispVal worker(String name, LispVal... args) {
		return new LispCons(runtime(name), ClojureLowerUtil.list(args));
	}

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name);
	}

}
