package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The lowering a Clojure program keeps for its run where its target lowers as the program
 * runs -- the interpreter, which has the lowering beside it. {@code eval}, a
 * {@code resolve} of a computed symbol, a {@code require} of computed specs, a
 * {@code load-string} of a computed string and the rest of the lowering verbs over
 * computed arguments ({@link #ENTRIES}) reach the run-time library's
 * {@code %clojure-lower-at-run-time}; the interpreter answers that function through
 * {@link #lower}, which lowers the call of the verb over the arguments as literals, like
 * a session's buffer of one datum, in the namespace {@code *ns*} holds, and evaluates
 * what it lowers to. A compiled program carries no lowering, so the library function
 * refuses there.
 * <p>
 * Such a program lowers open ({@code ClojureLowering#open}) from the start: every
 * decision code no pre-scan sees could miss takes its open shape, so what it hands the
 * lowering as it runs joins it the way a session's later buffer does.
 */
public final class ClojureRunTime {

	/**
	 * The run-time library's functions that hand a lowering verb to the lowering as the
	 * program runs: {@code %clojure-lower-at-run-time} and every function reaching it. A
	 * program whose run-time code reaches one ({@link #reachesAny}) lowers at run time,
	 * so its target, where it lowers then, lowers it open.
	 */
	public static final Set<String> ENTRIES = Set.of("RONTOLISP::%CLOJURE-LOWER-AT-RUN-TIME",
			"RONTOLISP::%CLOJURE-EVAL", "RONTOLISP::%CLOJURE-RESOLVE", "RONTOLISP::%CLOJURE-FIND-VAR",
			"RONTOLISP::%CLOJURE-FIND-VAR-V", "RONTOLISP::%CLOJURE-FIND-VAR-RESOLVE", "RONTOLISP::%CLOJURE-NS-RESOLVE",
			"RONTOLISP::%CLOJURE-NS-RESOLVE-V", "RONTOLISP::%CLOJURE-NS-RESOLVE-IN",
			"RONTOLISP::%CLOJURE-NS-COMPUTED-HERE", "RONTOLISP::%CLOJURE-LOAD-STRING-HERE",
			"RONTOLISP::%CLOJURE-LOAD-STRING-V", "RONTOLISP::%CLOJURE-LOAD-FILE-HERE",
			"RONTOLISP::%CLOJURE-LOAD-FILE-V", "RONTOLISP::%CLOJURE-LOAD-READER", "RONTOLISP::%CLOJURE-LOAD-READER-V",
			"RONTOLISP::%CLOJURE-LOAD-HERE", "RONTOLISP::%CLOJURE-LOAD-V", "RONTOLISP::%CLOJURE-REQUIRING-RESOLVE-HERE",
			"RONTOLISP::%CLOJURE-REQUIRING-RESOLVE-V", "RONTOLISP::%CLOJURE-ALIAS-HERE", "RONTOLISP::%CLOJURE-ALIAS-V",
			"RONTOLISP::%CLOJURE-REFER-HERE", "RONTOLISP::%CLOJURE-REFER-V", "RONTOLISP::%CLOJURE-NS-UNMAP-HERE",
			"RONTOLISP::%CLOJURE-NS-UNMAP-V", "RONTOLISP::%CLOJURE-NS-UNALIAS-HERE", "RONTOLISP::%CLOJURE-NS-UNALIAS-V",
			"RONTOLISP::%CLOJURE-LOAD-LIBS-V", "RONTOLISP::%CLOJURE-REQUIRE-V", "RONTOLISP::%CLOJURE-USE-V",
			"RONTOLISP::%CLOJURE-IN-NS-V");

	/**
	 * The marker of a run-time value standing in a datum as itself, over the core form
	 * the running program evaluates to the very object: a function, an atom or a host
	 * object has no datum, and the oracle's compiler embeds one as a constant. Matched by
	 * identity, like the reader's markers, so no symbol a program spells is one.
	 */
	static final LispSymbol EMBEDDED = new LispSymbol("%embedded");

	/**
	 * The running program's half of decoding the values a lowering verb takes.
	 */
	public interface Host {

		/**
		 * The metadata of each value, in order (nil for a value with none), or null when
		 * none can be read: a symbol {@code with-meta} answered decodes with its metadata
		 * as reader metadata.
		 * @param values the values
		 * @return the metadata maps, or null
		 */
		@Nullable List<LispVal> metasOf(List<LispVal> values);

		/**
		 * The core form evaluating to the value itself, one no pass a form takes looks
		 * into: what a value no datum spells lowers to where it stands.
		 * @param value the value
		 * @return the form
		 */
		LispVal constant(LispVal value);

	}

	private final ClojureLowering lowering;

	private final ClojureJavaClasses javaClasses;

	ClojureRunTime(ClojureLowering lowering, ClojureJavaClasses javaClasses) {
		this.lowering = lowering;
		this.javaClasses = javaClasses;
	}

	/**
	 * One call of a lowering verb as the program runs, lowered: {@code eval}'s form
	 * itself, or the call of the verb over the arguments as literals, in the namespace
	 * {@code *ns*} names. What it defines joins the lowering, so a later call sees it.
	 * @param verb the verb, as the run-time library names it ({@code eval},
	 * {@code resolve}, {@code require}, {@code load-string} ...)
	 * @param args the arguments, as the run-time library holds them, each a form already
	 * (a lazy seq realized)
	 * @param namespace the name of the namespace {@code *ns*} holds
	 * @param host the running program's half of decoding the arguments
	 * @return the forms to evaluate, in order: the entry that echoes is the call's own,
	 * its last form's value the call's
	 * @throws LispReadException when the call does not lower, in the words a refusal
	 * names while the program lowers
	 */
	public List<ClojureTopLevel> lower(String verb, List<LispVal> args, String namespace, Host host) {
		return ClojureHostClasses.lowering(this.javaClasses,
				() -> this.lowering.lowerAtRunTime(verb, args, namespace, host));
	}

	/**
	 * The statements handing a definition's root to a table that never calls it: the
	 * present expander a {@code defmacro} notes, a redefinition's validate-and-notify and
	 * a fn's class name. A validator or a watch meets a root only through the var, whose
	 * object names the root itself, and the class-name table answers names.
	 */
	private static final Set<String> ROOT_NOTES = Set.of(ClojureStateLowering.NOTE_MACRO_EXPANDER.name(),
			ClojureStateLowering.VAR_DEFN_NOTIFY.name(), ClojureValueMethodLowering.NOTE_FN_CLASS);

	/**
	 * Whether a whole program's run-time code reaches a run-time library function handing
	 * a lowering verb to the lowering ({@link #ENTRIES}): the top-level forms and,
	 * transitively, every {@code defun} a reached form names. A function runs only where
	 * a reached form names it -- the run-time library calls none the lowering defines but
	 * the ex-info, protocol and hierarchy runtimes', which reach no verb -- so a verb a
	 * macro body names, or a helper only macro bodies call, runs while the program
	 * lowers: the expanders sit in the note behind each {@code defmacro} and, only for a
	 * run-time expansion or a read of a macro's root, in the macro runtime's
	 * {@code C%MACRO-EXPANDER}. Neither the notes ({@link #ROOT_NOTES}) nor a core var a
	 * namespace's mapping lists is a use: the program names a verb by calling it, taking
	 * it as a value or taking its var, never by reading a mapping, whose core vars are
	 * the lowering's own (a verb taken out of one and called over computed arguments
	 * refuses).
	 * @param forms the top-level forms of a whole program
	 * @param mapped the core var forms a namespace's mapping lists, by identity
	 * @return {@code true} when one is reached
	 */
	static boolean reachesAny(List<LispVal> forms, Set<LispVal> mapped) {
		Map<String, List<LispVal>> definitions = new HashMap<>();
		Deque<LispVal> pending = new ArrayDeque<>();
		Deque<LispVal> top = new ArrayDeque<>(forms);
		while (!top.isEmpty()) {
			LispVal form = top.pop();
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head) {
				if (head.name().equals("PROGN")) {
					List<LispVal> members = new ArrayList<>();
					for (LispVal rest = cons.cdr(); rest instanceof LispCons cell; rest = cell.cdr()) {
						members.add(cell.car());
					}
					for (int i = members.size() - 1; i >= 0; i--) {
						top.push(members.get(i));
					}
					continue;
				}
				if (head.name().equals("DEFUN") && cons.cdr() instanceof LispCons named
						&& named.car() instanceof LispSymbol name) {
					definitions.computeIfAbsent(name.name(), ignored -> new ArrayList<>()).add(form);
					continue;
				}
			}
			pending.push(form);
		}
		Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<String> reached = new HashSet<>();
		while (!pending.isEmpty()) {
			LispVal form = pending.pop();
			if (form instanceof LispCons cons) {
				if (!seen.add(cons) || mapped.contains(cons)
						|| cons.car() instanceof LispSymbol head && ROOT_NOTES.contains(head.name())) {
					continue;
				}
				LispVal rest = cons;
				while (rest instanceof LispCons cell && (cell == cons || seen.add(cell))) {
					pending.push(cell.car());
					rest = cell.cdr();
				}
				pending.push(rest);
			}
			else if (form instanceof LispSymbol symbol) {
				if (ENTRIES.contains(symbol.name())) {
					return true;
				}
				List<LispVal> defined = definitions.get(symbol.name());
				if (defined != null && reached.add(symbol.name())) {
					pending.addAll(defined);
				}
			}
		}
		return false;
	}

	/**
	 * The datum one call of a lowering verb at run time lowers: {@code eval}'s form
	 * decoded, or {@code (clojure.core/verb 'arg ...)} -- spelled through
	 * {@code clojure.core}, so no program macro or definition captures the head -- with
	 * each argument as the literal the verb takes while the program lowers. A reader read
	 * to its end ({@code load-reader}) loads its text like {@code load-string}.
	 * @param ctx the lowering, its run-time host in place for the decoding
	 * @param verb the verb
	 * @param args the arguments
	 * @return the datum
	 */
	static LispVal datum(ClojureLowering ctx, String verb, List<LispVal> args) {
		if (verb.equals("eval")) {
			if (args.size() != 1) {
				throw new LispReadException("Wrong number of args (" + args.size() + ") passed to: clojure.core/eval");
			}
			return ClojureMacroLowering.decodeDatum(ctx, args.get(0));
		}
		List<LispVal> items = new ArrayList<>();
		items.add(
				new LispSymbol(ClojureCoreNames.NAMESPACE + "/" + (verb.equals("load-reader") ? "load-string" : verb)));
		for (LispVal arg : args) {
			items.add(literal(ctx, arg));
		}
		LispVal call = ClojureLowerUtil.list(items);
		if (verb.equals("require") || verb.equals("use") || verb.equals("refer")) {
			// below the top of its datum, a library no root holds fails where the
			// call runs, the oracle's FileNotFoundException, rather than as the
			// datum lowers
			return ClojureLowerUtil.list(new LispSymbol(ClojureCoreNames.NAMESPACE + "/let"),
					ClojureLowerUtil.list(ClojureReader.VECTOR), call);
		}
		return call;
	}

	/**
	 * A run-time value as the literal argument it stands for: a namespace its name's
	 * symbol (every lowering verb takes one for the namespace), a symbol or a collection
	 * quoted, a string, number, character, keyword, nil or boolean as itself, a value no
	 * datum spells as itself.
	 */
	private static LispVal literal(ClojureLowering ctx, LispVal value) {
		String ns = namespaceObjectName(value);
		LispVal datum = ns != null ? new LispSymbol(ns) : ClojureMacroLowering.decodeDatum(ctx, value);
		if (datum instanceof LispSymbol symbol) {
			String name = symbol.name();
			if (name.startsWith(":") || name.equals("nil") || name.equals("true") || name.equals("false")) {
				return datum;
			}
		}
		else if (!(datum instanceof LispCons cons) || cons.car() == EMBEDDED) {
			return datum;
		}
		return ClojureLowerUtil.list(new LispSymbol("quote"), datum);
	}

	/** The name of a namespace object {@code (:C%NS-OBJECT "name")}, or null. */
	private static @Nullable String namespaceObjectName(LispVal value) {
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol tag && tag.name().equals(":C%NS-OBJECT")
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispString name) {
			return name.value();
		}
		return null;
	}

}
