package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The arms of a lowered program and of the run-time library ({@code clojure.lisp}) for a
 * kind of value only some programs can make, and their strip. Every verb that must
 * understand such a value carries an arm for it: a {@code cond} clause, an {@code if} or
 * a disjunct of an {@code or} whose test is one of the family's {@link Family#tests}, or
 * a view from its {@link Family#views} wrapping a form the verb already had, answering
 * that form's value for anything that is no such value. A program that cannot make one --
 * it names none of the family's {@link Family#producers}, the only way one comes to exist
 * -- gets every arm of the family stripped before the library splice: a test folds to
 * false (its clause, its {@code if} branch or its disjunct goes), a view to its first
 * argument, and an {@link Family#aliases} helper to the plain one it stands for. What is
 * left is exactly the form the verb lowered to before the kind existed, so such a program
 * compiles to the same bytes, and the kind's runtime is pruned with nothing left to reach
 * it. A binding-depth counter ({@link Family#depths}) is the fourth shape: the
 * {@code let} pair each binding site adds to rebind it, and the program's definition of
 * it, go from a program that never reads it. A special a load switches
 * ({@link Family#switches}) is the fifth: its switching statements, rebinding pairs and
 * definition go the same way.
 *
 * <p>
 * The shape every arm keeps, which {@link #strip} checks: a test's arguments, and a
 * view's arguments past the first, are variables or {@code car}/{@code cdr} reads of one
 * (they are dropped, so they must have no effect), and a test stands only where it folds
 * -- a {@code cond} clause's test, an {@code if}'s test, an {@code or}'s disjunct. The
 * interpreter and a session keep the arms: what a later input builds is unknown there.
 */
public final class ClojureArms {

	private ClojureArms() {
	}

	/**
	 * A kind of value with arms: its tests, views and aliases, and what makes one.
	 */
	public enum Family {

		/**
		 * The namespace {@code ns} and {@code in-ns} switch {@code *ns*} to, and the
		 * rebinding of it around a required namespace's load: only a read of {@code *ns*}
		 * sees either ({@link Family#switches}). Ahead of {@link #NAMESPACE}, whose
		 * producer a switch names.
		 */
		NS_SWITCH("ns-switch", Set.of(ClojureCoreSpecials.NS.name())),

		/** The rebinding of {@code *file*} around a required namespace's load. */
		FILE_SWITCH("file-switch", Set.of(ClojureCoreSpecials.FILE.name())),

		/** The rebinding of {@code *source-path*} around a required namespace's load. */
		SOURCE_PATH_SWITCH("source-path-switch", Set.of(ClojureCoreSpecials.SOURCE_PATH.name())),

		/**
		 * Sorted maps and sets: no literal makes one, only the constructor and the
		 * constructors' values.
		 */
		SORTED("sorted-collection",
				Set.of("RONTOLISP::%CLOJURE-SORTED-P", "RONTOLISP::%CLOJURE-SORTED-MAP-P",
						"RONTOLISP::%CLOJURE-SORTED-SET-P"),
				Set.of("RONTOLISP::%CLOJURE-SORTED-KEY", "RONTOLISP::%CLOJURE-SORTED-ITEMS",
						"RONTOLISP::%CLOJURE-SORTED-HASHED", "RONTOLISP::%CLOJURE-SORTED-SHRUNK",
						"RONTOLISP::%CLOJURE-SORTED-REWRAP"),
				Map.of("RONTOLISP::%CLOJURE-IS-SET", "RONTOLISP::%CLOJURE-SET-P", "RONTOLISP::%CLOJURE-IS-REVERSIBLE",
						"RONTOLISP::%CLOJURE-IS-VECTOR"),
				Set.of("RONTOLISP::%CLOJURE-SORTED-MAKE", "RONTOLISP::%CLOJURE-SORTED-MAP-V",
						"RONTOLISP::%CLOJURE-SORTED-MAP-BY-V", "RONTOLISP::%CLOJURE-SORTED-SET-V",
						"RONTOLISP::%CLOJURE-SORTED-SET-BY-V"),
				Set.of()),

		/**
		 * The unbound root of a declared-never-defined name or a value-less {@code def}:
		 * only the store a file starts with (or a session's declare) makes one.
		 */
		UNBOUND("unbound-root", Set.of("RONTOLISP::%CLOJURE-UNBOUND-P"), Set.of(), Map.of(),
				Set.of("RONTOLISP::%CLOJURE-UNBOUND"), Set.of()),

		/**
		 * A stream value, which the printer and {@code str} spell as the oracle's
		 * {@code #object} of the host class its kind is: only a read of {@code *out*},
		 * {@code *in*} or {@code *err*} as a value, a {@code java.io.StringWriter}, a
		 * reader over a {@code java.io.StringReader} and a {@code clojure.java.io/reader}
		 * hand one to the program. The Common Lisp constructors the library and the
		 * binding forms call are no producer: naming one would splice the library into a
		 * Common Lisp program.
		 */
		STREAM("stream", Set.of("RONTOLISP::%CLOJURE-STREAM-P"), Set.of(), Map.of(),
				Set.of("RONTOLISP::%CLOJURE-OUT", "RONTOLISP::%CLOJURE-IN", "RONTOLISP::%CLOJURE-ERR",
						"RONTOLISP::%CLOJURE-STRING-WRITER", "RONTOLISP::%CLOJURE-STRING-READER",
						"RONTOLISP::%CLOJURE-READER"),
				Set.of()),

		/**
		 * The binding depth of the {@code clojure.core} specials {@code clojure.main}
		 * does not bind ({@code *out*}, {@code *in*}, {@code *err*}, {@code *agent*} and
		 * a few flags, {@link ClojureCoreSpecials#COUNTERS}), which {@code thread-bound?}
		 * reads through a var site of one and {@code set!} tests: every binding of the
		 * special rebinds its counter one deeper, and only those two read it.
		 */
		STREAM_DEPTH("stream-binding-depth", Set.of(), Set.of(), Map.of(), Set.of(), ClojureCoreSpecials.COUNTERS),

		/**
		 * The printer's reading of {@code *print-length*}, {@code *print-level*} and
		 * {@code *print-readably*}: the cut of a collection past the length, the
		 * {@code #} of one past the level, the depth each nested member is written one
		 * deeper at, and the readable switch. Only a program naming one of the three can
		 * set it away from its root, under which the printer writes what it wrote without
		 * them.
		 */
		PRINT_FLAGS("print-flags", Set.of("RONTOLISP::%CLOJURE-PRINT-CUT-P", "RONTOLISP::%CLOJURE-PRINT-DEEP-P"),
				Set.of("RONTOLISP::%CLOJURE-PRINT-READABLE"),
				Map.of("RONTOLISP::%CLOJURE-WRITE-NESTED", "RONTOLISP::%CLOJURE-WRITE"),
				ClojureCoreSpecials.PRINT_FLAGS, Set.of()),

		/**
		 * The printer's reading of {@code *print-meta*}: the {@code ^m} it writes ahead
		 * of a value carrying metadata. Its root is false, so only a program naming it
		 * can print one.
		 */
		PRINT_META("print-meta", Set.of("RONTOLISP::%CLOJURE-PRINT-META-P"), Set.of(), Map.of(),
				ClojureCoreSpecials.PRINT_META, Set.of()),

		/**
		 * A map the printer writes as {@code #:ns{...}} under
		 * {@code *print-namespace-maps*} (true at the root): one whose every key is a
		 * keyword or symbol of one namespace. Only a qualified keyword or symbol makes
		 * one -- a literal of either ({@link Family#qualifiedIdents}), or one of the
		 * library functions that build a keyword or symbol from a computed spelling, or
		 * call one that does (the constructors, {@code find-keyword} and the reader).
		 */
		NAMESPACE_MAP("namespace-map", Set.of("RONTOLISP::%CLOJURE-PRINT-NS-MAP-P"), Set.of(), Map.of(), Set.of(
				"RONTOLISP::%CLOJURE-KEYWORD-1", "RONTOLISP::%CLOJURE-KEYWORD-2", "RONTOLISP::%CLOJURE-SYMBOL-1",
				"RONTOLISP::%CLOJURE-SYMBOL-2", "RONTOLISP::%CLOJURE-FIND-KEYWORD",
				"RONTOLISP::%CLOJURE-FIND-KEYWORD-2", "RONTOLISP::%CLOJURE-FIND-KEYWORD-V", "RONTOLISP::%CLOJURE-READ",
				"RONTOLISP::%CLOJURE-READ-V", "RONTOLISP::%CLOJURE-READ-OPTS", "RONTOLISP::%CLOJURE-READ-FROM",
				"RONTOLISP::%CLOJURE-READ-STRING", "RONTOLISP::%CLOJURE-READ-STRING-V",
				"RONTOLISP::%CLOJURE-READ-STRING-OPTS", "RONTOLISP::%CLOJURE-RD-ANON-FN", "RONTOLISP::%CLOJURE-RD-ARG",
				"RONTOLISP::%CLOJURE-RD-ARG-PARAM", "RONTOLISP::%CLOJURE-RD-ATOM",
				"RONTOLISP::%CLOJURE-RD-BUILD-RECORD", "RONTOLISP::%CLOJURE-RD-DISPATCH", "RONTOLISP::%CLOJURE-RD-FORM",
				"RONTOLISP::%CLOJURE-RD-KEYWORD", "RONTOLISP::%CLOJURE-RD-MAP", "RONTOLISP::%CLOJURE-RD-META",
				"RONTOLISP::%CLOJURE-RD-RECORD", "RONTOLISP::%CLOJURE-RD-REQUIRED", "RONTOLISP::%CLOJURE-RD-SEQ",
				"RONTOLISP::%CLOJURE-RD-SET", "RONTOLISP::%CLOJURE-RD-SYMBOL", "RONTOLISP::%CLOJURE-RD-SYMBOLIC",
				"RONTOLISP::%CLOJURE-RD-WRAP"), Set.of(), true),

		/**
		 * An exception or a runtime error, which {@code class} reads the class of: only a
		 * program defining the exception reader {@code C%E-PARTS} -- the exception
		 * runtime of one that builds an exception, the catch runtime's of one that
		 * catches -- can hold one.
		 */
		EXCEPTION("exception", Set.of("RONTOLISP::%CLOJURE-EXCEPTION-P"), Set.of(), Map.of(), Set.of("C%E-PARTS"),
				Set.of()),

		/**
		 * A namespace, which the printer, {@code str} and {@code class} spell: only a
		 * read of {@code *ns*} (whose root and switches build one), {@code the-ns} and
		 * {@code find-ns} hand one to the program.
		 */
		NAMESPACE("namespace", Set.of("RONTOLISP::%CLOJURE-NS-OBJECT-P"), Set.of(), Map.of(),
				Set.of(ClojureCoreSpecials.NS_OBJECT, "RONTOLISP::%CLOJURE-THE-NS", "RONTOLISP::%CLOJURE-FIND-NS"),
				Set.of());

		private final String label;

		/** The arm tests. */
		final Set<String> tests;

		/** The views, each answering its first argument for a value of no such kind. */
		final Set<String> views;

		/** The kind-aware helpers, to the plain one each stands for without the kind. */
		final Map<String, String> aliases;

		/** What makes a value of the kind. */
		final Set<String> producers;

		/**
		 * The binding-depth counters: a {@code let}/{@code let*} pair rebinding one a
		 * level deeper ({@code (counter (+ counter 1))}) and the program's top-level
		 * {@code (defvar counter 0)} are arms, and any other mention of the counter reads
		 * it, which is what keeps them.
		 */
		final Set<String> depths;

		/**
		 * Whether a qualified keyword or symbol literal is a producer too: a
		 * {@code :C%KEYWORD} wrapper over a spelling with a namespace, built or quoted,
		 * or a quoted symbol whose demangled name has one.
		 */
		final boolean qualifiedIdents;

		/**
		 * The specials a load switches: the program's top-level
		 * {@code (defvar special value)}, a statement {@code (setq special value)} and a
		 * {@code let}/{@code let*} pair {@code (special value)} -- each over a value with
		 * no effect, a string or an interned namespace, or the special itself -- are
		 * arms, and any other mention of the special reads it, which is what keeps them.
		 * A {@code let} left without a pair is its body.
		 */
		final Set<String> switches;

		Family(String label, Set<String> switches) {
			this(label, Set.of(), Set.of(), Map.of(), Set.of(), Set.of(), false, switches);
		}

		Family(String label, Set<String> tests, Set<String> views, Map<String, String> aliases, Set<String> producers,
				Set<String> depths) {
			this(label, tests, views, aliases, producers, depths, false);
		}

		Family(String label, Set<String> tests, Set<String> views, Map<String, String> aliases, Set<String> producers,
				Set<String> depths, boolean qualifiedIdents) {
			this(label, tests, views, aliases, producers, depths, qualifiedIdents, Set.of());
		}

		Family(String label, Set<String> tests, Set<String> views, Map<String, String> aliases, Set<String> producers,
				Set<String> depths, boolean qualifiedIdents, Set<String> switches) {
			this.label = label;
			this.tests = tests;
			this.views = views;
			this.aliases = aliases;
			this.producers = producers;
			this.depths = depths;
			this.qualifiedIdents = qualifiedIdents;
			this.switches = switches;
		}

		/**
		 * Whether a name is one of the family's producers.
		 * @param symbolName the symbol name, in its canonical spelling
		 * @return {@code true} when naming it may make a value of the kind
		 */
		public boolean isProducer(String symbolName) {
			return this.producers.contains(symbolName);
		}

	}

	/** A folded test while the walk runs: never a form of its own. */
	private static final LispVal FALSE = new LispSymbol("%ARM-FOLDED%");

	/**
	 * What a program says about one family.
	 *
	 * @param builds whether it names a producer, so a value of the kind may exist
	 * @param arms whether it carries an arm the strip would fold
	 */
	public record Scan(boolean builds, boolean arms) {

		/**
		 * Whether the program's arms go: it carries one and builds no sorted collection.
		 * @return {@code true} when {@link #strip} applies
		 */
		public boolean strips() {
			return this.arms && !this.builds;
		}

	}

	/**
	 * Scans the forms for a producer and for an arm of the family, in one walk.
	 * @param forms the top-level forms
	 * @param family the kind
	 * @return what they say
	 */
	public static Scan scan(List<LispVal> forms, Family family) {
		boolean[] found = new boolean[2];
		for (LispVal form : forms) {
			scanInto(form, family, found, false);
			if (found[0] && found[1]) {
				break;
			}
		}
		return new Scan(found[0], found[1]);
	}

	private static void scanInto(LispVal form, Family family, boolean[] found, boolean quoted) {
		if (isDepthDefinition(form, family) || isSwitchDefinition(form, family) || isSwitchSetq(form, family)) {
			found[1] = true;
			return;
		}
		LispVal rest = form;
		if ((!family.depths.isEmpty() || !family.switches.isEmpty()) && isLet(form)
				&& ((LispCons) form).cdr() instanceof LispCons bindings) {
			LispVal pairs = bindings.car();
			while (pairs instanceof LispCons cell) {
				if (isDepthPair(cell.car(), family) || isSwitchPair(cell.car(), family)) {
					found[1] = true;
				}
				else {
					scanInto(cell.car(), family, found, quoted);
				}
				pairs = cell.cdr();
			}
			rest = bindings.cdr();
		}
		boolean quotedArgs = quoted || form instanceof LispCons head && head.car() instanceof LispSymbol quote
				&& quote.name().equals("QUOTE");
		while (rest instanceof LispCons cons) {
			if (family.qualifiedIdents && isQualifiedKeyword(cons)) {
				found[0] = true;
			}
			scanInto(cons.car(), family, found, quotedArgs);
			rest = cons.cdr();
		}
		if (rest instanceof LispSymbol symbol) {
			String name = symbol.name();
			if (family.producers.contains(name) || family.depths.contains(name) || family.switches.contains(name)) {
				found[0] = true;
			}
			else if (family.tests.contains(name) || family.views.contains(name) || family.aliases.containsKey(name)) {
				found[1] = true;
			}
			else if (quoted && family.qualifiedIdents && name.startsWith("c%") && hasNamespace(name.substring(2))) {
				found[0] = true;
			}
		}
	}

	/**
	 * Whether the cell starts a keyword wrapper over a spelling with a namespace:
	 * {@code (:C%KEYWORD "ns/name")}, quoted or the tail of its {@code LIST}
	 * construction.
	 */
	private static boolean isQualifiedKeyword(LispCons cell) {
		return cell.car() instanceof LispSymbol tag && tag.name().equals(":C%KEYWORD")
				&& cell.cdr() instanceof LispCons next && next.car() instanceof LispString spelling
				&& hasNamespace(spelling.value());
	}

	/**
	 * Whether a keyword or symbol spelling has a namespace: a slash, except the lone
	 * slash (the symbol {@code /}), split at the first like the oracle.
	 */
	private static boolean hasNamespace(String spelling) {
		return spelling.indexOf('/') >= 0 && !spelling.equals("/");
	}

	/**
	 * The forms with every arm of the family folded away: what they lower to when no
	 * value of the kind can exist. A form with no arm is answered itself (cons identity
	 * kept, the source-position rule); an arm in a shape the fold cannot take signals,
	 * since leaving it would splice the kind's runtime after all.
	 * @param forms the top-level forms
	 * @param family the kind
	 * @return the forms without their arms
	 */
	public static List<LispVal> strip(List<LispVal> forms, Family family) {
		Stripper stripper = new Stripper(family);
		List<LispVal> out = new ArrayList<>(forms.size());
		boolean changed = false;
		for (LispVal form : forms) {
			if (isDepthDefinition(form, family) || isSwitchDefinition(form, family) || isSwitchSetq(form, family)) {
				changed = true;
				continue;
			}
			LispVal walked = stripper.walkCode(form);
			changed |= walked != form;
			out.add(walked);
		}
		return changed ? out : forms;
	}

	/** One family's fold over code. */
	private record Stripper(Family family) {

		private LispVal walkCode(LispVal form) {
			LispVal walked = walk(form);
			if (walked == FALSE) {
				throw new IllegalStateException(
						"a " + this.family.label + " test where it cannot fold: " + form.print());
			}
			return walked;
		}

		private LispVal walk(LispVal form) {
			if (!(form instanceof LispCons cons)) {
				return form;
			}
			if (cons.car() instanceof LispSymbol head) {
				String name = head.name();
				if (name.equals("QUOTE")) {
					return form;
				}
				if (this.family.tests.contains(name)) {
					requirePure(cons.cdr(), form);
					return FALSE;
				}
				if (this.family.views.contains(name)) {
					if (!(cons.cdr() instanceof LispCons args)) {
						throw new IllegalStateException(
								"a " + this.family.label + " view without an argument: " + form.print());
					}
					requirePure(args.cdr(), form);
					return walkCode(args.car());
				}
				String alias = this.family.aliases.get(name);
				if (alias != null) {
					return LispCons.rebuilt(cons, new LispSymbol(alias), walkElements(cons.cdr()));
				}
				if (name.equals("FUNCTION") && cons.cdr() instanceof LispCons fn
						&& fn.car() instanceof LispSymbol target && this.family.aliases.containsKey(target.name())) {
					return LispCons.rebuilt(cons, head,
							LispCons.rebuilt(fn, new LispSymbol(this.family.aliases.get(target.name())), fn.cdr()));
				}
				if (isSwitchSetq(form, this.family)) {
					throw new IllegalStateException(
							"a " + this.family.label + " switch where it cannot go: " + form.print());
				}
				switch (name) {
					case "LET", "LET*":
						return walkLet(cons);
					case "PROGN":
						return walkProgn(cons);
					case "LAMBDA":
						return walkLambda(cons);
					case "OR":
						return walkOr(cons);
					case "COND":
						return walkCond(cons);
					case "IF":
						return walkIf(cons);
					default:
						break;
				}
			}
			return LispCons.rebuilt(cons, walkCode(cons.car()), walkElements(cons.cdr()));
		}

		/**
		 * The rest of a list, every element walked as code, down the cdr in a loop so a
		 * long list costs no stack; a dotted tail is kept as it is.
		 */
		private LispVal walkElements(LispVal rest) {
			List<LispCons> cells = new ArrayList<>();
			LispVal run = rest;
			while (run instanceof LispCons cell) {
				cells.add(cell);
				run = cell.cdr();
			}
			LispVal tail = run;
			for (int i = cells.size() - 1; i >= 0; i--) {
				LispCons cell = cells.get(i);
				tail = LispCons.rebuilt(cell, walkCode(cell.car()), tail);
			}
			return tail;
		}

		/**
		 * {@code (let (pairs...) body...)}: a pair rebinding one of the family's
		 * binding-depth counters or switching one of its specials goes; the rest is
		 * walked as code. A {@code let} the fold of a switch leaves with no pair and one
		 * body form is that form.
		 */
		private LispVal walkLet(LispCons form) {
			LispCons walked = form;
			if ((!this.family.depths.isEmpty() || !this.family.switches.isEmpty())
					&& form.cdr() instanceof LispCons bindings && bindings.car() instanceof LispCons pairs) {
				List<LispVal> kept = new ArrayList<>();
				LispVal run = pairs;
				while (run instanceof LispCons cell) {
					if (!isDepthPair(cell.car(), this.family) && !isSwitchPair(cell.car(), this.family)) {
						kept.add(cell.car());
					}
					run = cell.cdr();
				}
				// pairs is a non-empty list, so an empty kept means the fold dropped them
				// all; a load's switches leave the body alone (a binding always keeps
				// its own pair beside a counter's)
				if (kept.isEmpty() && !this.family.switches.isEmpty() && bindings.cdr() instanceof LispCons body
						&& body.cdr() instanceof LispNil) {
					return walkCode(body.car());
				}
				walked = LispCons.rebuilt(form, form.car(),
						LispCons.rebuilt(bindings, LispCons.rebuiltList(pairs, kept), bindings.cdr()));
			}
			return LispCons.rebuilt(walked, walkCode(walked.car()), walkElements(walked.cdr()));
		}

		/**
		 * {@code (progn statement...)}: a switch among the statements before the last
		 * goes; a {@code progn} it leaves with one form is that form.
		 */
		private LispVal walkProgn(LispCons form) {
			LispVal statements = withoutSwitches(form.cdr(), true);
			if (statements == form.cdr()) {
				return LispCons.rebuilt(form, form.car(), walkElements(form.cdr()));
			}
			if (statements instanceof LispCons only && only.cdr() instanceof LispNil) {
				return walkCode(only.car());
			}
			return LispCons.rebuilt(form, form.car(), walkElements(statements));
		}

		/**
		 * {@code (lambda params statement...)}: a switch among the body's statements
		 * goes, a last one too (only a namespace's init, whose value nobody reads, ends
		 * in one) unless it is the whole body.
		 */
		private LispVal walkLambda(LispCons form) {
			if (!(form.cdr() instanceof LispCons params)) {
				return form;
			}
			LispVal body = withoutSwitches(params.cdr(), false);
			if (body == params.cdr()) {
				return LispCons.rebuilt(form, form.car(), walkElements(form.cdr()));
			}
			return LispCons.rebuilt(form, form.car(),
					LispCons.rebuilt(params, walkCode(params.car()), walkElements(body)));
		}

		/**
		 * The statements without the family's switches (the last statement kept when
		 * asked, and always when nothing else is left), or the statements themselves when
		 * there is none to drop.
		 */
		private LispVal withoutSwitches(LispVal statements, boolean keepLast) {
			if (this.family.switches.isEmpty()) {
				return statements;
			}
			List<LispVal> kept = new ArrayList<>();
			boolean dropped = false;
			LispVal run = statements;
			while (run instanceof LispCons cell) {
				boolean last = !(cell.cdr() instanceof LispCons);
				if (isSwitchSetq(cell.car(), this.family) && !(last && (keepLast || kept.isEmpty()))) {
					dropped = true;
				}
				else {
					kept.add(cell.car());
				}
				run = cell.cdr();
			}
			return dropped ? LispCons.rebuiltList((LispCons) statements, kept) : statements;
		}

		/**
		 * {@code (or ...)}: a folded disjunct goes; one left stands alone, none is false.
		 */
		private LispVal walkOr(LispCons form) {
			List<LispVal> elements = new ArrayList<>();
			elements.add(form.car());
			boolean folded = false;
			LispVal run = form.cdr();
			while (run instanceof LispCons cell) {
				LispVal walked = walk(cell.car());
				if (walked == FALSE) {
					folded = true;
				}
				else {
					elements.add(walked);
				}
				run = cell.cdr();
			}
			if (folded && elements.size() == 1) {
				return FALSE;
			}
			if (folded && elements.size() == 2) {
				return elements.get(1);
			}
			return LispCons.rebuiltList(form, elements);
		}

		/** {@code (cond ...)}: a clause whose test folds goes. */
		private LispVal walkCond(LispCons form) {
			List<LispVal> clauses = new ArrayList<>();
			clauses.add(form.car());
			LispVal run = form.cdr();
			while (run instanceof LispCons cell) {
				LispVal clause = cell.car();
				if (clause instanceof LispCons parts) {
					LispVal test = walk(parts.car());
					if (test != FALSE) {
						clauses.add(LispCons.rebuilt(parts, test, walkElements(parts.cdr())));
					}
				}
				else {
					clauses.add(clause);
				}
				run = cell.cdr();
			}
			return LispCons.rebuiltList(form, clauses);
		}

		/** {@code (if test then else)}: a folded test leaves the else form, or nil. */
		private LispVal walkIf(LispCons form) {
			if (!(form.cdr() instanceof LispCons testCell)) {
				return form;
			}
			LispVal test = walk(testCell.car());
			if (test != FALSE) {
				return LispCons.rebuilt(form, form.car(),
						LispCons.rebuilt(testCell, test, walkElements(testCell.cdr())));
			}
			if (testCell.cdr() instanceof LispCons thenCell && thenCell.cdr() instanceof LispCons elseCell) {
				return walkCode(elseCell.car());
			}
			return LispNil.INSTANCE;
		}

		/**
		 * Refuses an argument a fold would drop with an effect: anything but a variable,
		 * a constant or a {@code car}/{@code cdr} read of one.
		 */
		private void requirePure(LispVal args, LispVal form) {
			LispVal run = args;
			while (run instanceof LispCons cell) {
				if (!isPure(cell.car())) {
					throw new IllegalStateException(
							"a " + this.family.label + " arm over an argument with an effect: " + form.print());
				}
				run = cell.cdr();
			}
		}

	}

	/**
	 * Whether the statement switches a special some family switches
	 * ({@link Family#switches}): what a program reading none of them sheds.
	 * @param statement a lowered statement
	 * @return {@code true} for a switch
	 */
	static boolean isSwitch(LispVal statement) {
		for (Family family : Family.values()) {
			if (isSwitchSetq(statement, family)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A statement as a program reading none of the switched specials compiles it: every
	 * switch in it folded away ({@link Family#switches}).
	 * @param statement a lowered statement, itself no switch
	 * @return the statement without its switches
	 */
	static LispVal withoutSwitches(LispVal statement) {
		LispVal out = statement;
		for (Family family : Family.values()) {
			if (!family.switches.isEmpty()) {
				out = new Stripper(family).walkCode(out);
			}
		}
		return out;
	}

	/**
	 * Whether a name is one the arms of some family spell (a test, a view, an alias, a
	 * producer or a binding-depth counter): a program naming one goes through the strip.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} for a family's name
	 */
	public static boolean isFamilyName(String symbolName) {
		for (Family family : Family.values()) {
			if (family.tests.contains(symbolName) || family.views.contains(symbolName)
					|| family.aliases.containsKey(symbolName) || family.producers.contains(symbolName)
					|| family.depths.contains(symbolName) || family.switches.contains(symbolName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the top-level form is the program's definition of one of the family's
	 * counters, {@code (defvar counter 0)}: it goes with the pairs.
	 */
	private static boolean isDepthDefinition(LispVal form, Family family) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head && head.name().equals("DEFVAR")
				&& cons.cdr() instanceof LispCons name && name.car() instanceof LispSymbol counter
				&& family.depths.contains(counter.name()) && name.cdr() instanceof LispCons init
				&& init.car() instanceof LispInteger zero && zero.value() == 0 && init.cdr() instanceof LispNil;
	}

	/**
	 * Whether the top-level form is the program's definition of one of the family's
	 * switched specials, {@code (defvar special value)} over a switch value: it goes with
	 * the switches.
	 */
	private static boolean isSwitchDefinition(LispVal form, Family family) {
		LispVal value = switchedValue(form, "DEFVAR", family);
		return value instanceof LispString || isInternedNamespace(value);
	}

	/**
	 * Whether the form is a statement switching one of the family's specials to an
	 * interned namespace, {@code (setq special (%clojure-ns-object "name"))}: the shape
	 * only {@code ns} and {@code in-ns} write, never a {@code set!}.
	 */
	private static boolean isSwitchSetq(LispVal form, Family family) {
		return isInternedNamespace(switchedValue(form, "SETQ", family));
	}

	/**
	 * The value a {@code (head special value)} form of one of the family's specials
	 * assigns, or null for any other form.
	 */
	private static @Nullable LispVal switchedValue(LispVal form, String head, Family family) {
		if (family.switches.isEmpty() || !(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !op.name().equals(head) || !(cons.cdr() instanceof LispCons pair)
				|| !(pair.car() instanceof LispSymbol special) || !family.switches.contains(special.name())
				|| !(pair.cdr() instanceof LispCons valueCell) || !(valueCell.cdr() instanceof LispNil)) {
			return null;
		}
		return valueCell.car();
	}

	/**
	 * Whether the binding pair rebinds one of the family's specials to a value with no
	 * effect: {@code (special value)} over the special itself, a string or an interned
	 * namespace. Nothing reads the special where the strip applies, so even a
	 * {@code binding} of the program's own of that shape goes unobserved.
	 */
	private static boolean isSwitchPair(LispVal pair, Family family) {
		if (family.switches.isEmpty() || !(pair instanceof LispCons cell) || !(cell.car() instanceof LispSymbol special)
				|| !family.switches.contains(special.name()) || !(cell.cdr() instanceof LispCons valueCell)
				|| !(valueCell.cdr() instanceof LispNil)) {
			return false;
		}
		LispVal value = valueCell.car();
		return value instanceof LispString || value instanceof LispSymbol self && self.name().equals(special.name())
				|| isInternedNamespace(value);
	}

	/**
	 * Whether the form interns a namespace by a literal name:
	 * {@code (%clojure-ns-object "name")}.
	 */
	private static boolean isInternedNamespace(@Nullable LispVal value) {
		return value instanceof LispCons call && call.car() instanceof LispSymbol fn
				&& fn.name().equals(ClojureCoreSpecials.NS_OBJECT) && call.cdr() instanceof LispCons arg
				&& arg.car() instanceof LispString && arg.cdr() instanceof LispNil;
	}

	private static boolean isLet(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& (head.name().equals("LET") || head.name().equals("LET*"));
	}

	/**
	 * Whether the binding pair rebinds one of the family's counters a level deeper:
	 * {@code (counter (+ counter 1))}, the one shape a binding site writes.
	 */
	private static boolean isDepthPair(LispVal pair, Family family) {
		if (!(pair instanceof LispCons cell) || !(cell.car() instanceof LispSymbol counter)
				|| !family.depths.contains(counter.name()) || !(cell.cdr() instanceof LispCons initCell)
				|| !(initCell.cdr() instanceof LispNil) || !(initCell.car() instanceof LispCons init)) {
			return false;
		}
		return init.car() instanceof LispSymbol plus && plus.name().equals("+") && init.cdr() instanceof LispCons a
				&& a.car() instanceof LispSymbol read && read.name().equals(counter.name())
				&& a.cdr() instanceof LispCons b && b.car() instanceof LispInteger one && one.value() == 1
				&& b.cdr() instanceof LispNil;
	}

	private static boolean isPure(LispVal arg) {
		if (arg instanceof LispSymbol || arg instanceof LispString || arg instanceof LispInteger
				|| arg instanceof LispChar || arg instanceof LispNil || arg instanceof LispTrue) {
			return true;
		}
		if (arg instanceof LispCons cons && cons.car() instanceof LispSymbol head && cons.cdr() instanceof LispCons only
				&& only.cdr() instanceof LispNil) {
			String name = head.name();
			boolean read = name.equals("CAR") || name.equals("CDR") || name.equals("CADR") || name.equals("CDDR")
					|| name.equals("CADDR") || name.equals("CADDDR");
			return read && isPure(only.car());
		}
		return false;
	}

}
