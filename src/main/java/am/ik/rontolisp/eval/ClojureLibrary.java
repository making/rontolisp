package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureArms;
import am.ik.rontolisp.clojure.ClojureCaseMapping;
import am.ik.rontolisp.macro.FormatRenderer;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The run-time half of the EXPERIMENTAL Clojure front end ({@code clojure.lisp} on the
 * classpath, plus the case mapping's tables {@link ClojureCaseMapping} generates from the
 * JDK and the regex engine's Unicode tables, {@code clojure-unicode.lisp}, generated from
 * it by {@code ClojureUnicodeTablesTest}): the printer behind
 * {@code println}/{@code print}/{@code pr}/{@code prn}, the string builders behind
 * {@code str}/{@code pr-str}, and the REPL echo. It is Common Lisp source like every
 * other shipped library, so no backend learns a Clojure name
 * ({@code .kb/clojure-frontend.md}).
 *
 * <p>
 * Consumers, the {@link UrlLibrary} shape:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time one of the {@code rontolisp::%clojure-} functions is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the interpreter running a whole program (the command line's run) evaluates
 * {@link #splice}'s library ahead of the program instead
 * ({@code LispEvaluator#clojureProgram});</li>
 * <li>the compile path calls {@link #process(List)} inside
 * {@code CompileFrontend.expand}: a program that references one of the functions -- only
 * a lowered Clojure program does -- gets the definitions prepended, and
 * {@link LibraryDefunPruner} drops the ones it does not reach.</li>
 * </ul>
 *
 * <p>
 * A program that builds no sorted collection has the sorted-collection arms of its own
 * forms and of the library stripped first ({@link ClojureArms}), so it is spliced and
 * compiled exactly as before sorted collections existed; likewise the unbound-root arms
 * of a program that makes no unbound var, and the stream binding-depth counters of one
 * that reads none. The interpreter running a whole program takes the same strip
 * ({@link #splice}); the library it loads on first use keeps every arm, for whatever a
 * session or a {@code load} reads next.
 */
public final class ClojureLibrary {

	private static final Map<String, List<LispVal>> FORMS = new ConcurrentHashMap<>();

	/**
	 * The {@code java:}-free bodies {@link #formsWithoutHostArms()} splices in place of
	 * the library's host arms, one {@code defun} per arm, each defining the same name and
	 * answering what the host arm answers for a value that is no host object:
	 * {@code %clojure-host-class} ({@code class} of a value of no Clojure kind) refuses,
	 * {@code %clojure-host-class-name} (the printer), {@code %clojure-host-string}
	 * ({@code str}), {@code %clojure-host-instance-p} ({@code inst?}, {@code uuid?},
	 * {@code uri?}, {@code class?}), {@code %clojure-host-class-instance-p}
	 * ({@code instance?} of a class value) and {@code %clojure-host-throwable} (an
	 * exception's view of a value) answer NIL, {@code %clojure-host-method}
	 * ({@code .getMessage} of a value that is no exception) refuses, and
	 * {@code %clojure-other-hash} ({@code hash} of a value of no Clojure kind) answers
	 * its identity hash.
	 */
	private static final String HOST_ARMS_WITHOUT_JAVA = """
			(defun rontolisp::%clojure-host-class (x)
			  (declare (ignore x))
			  (error "class needs a value of a known kind"))
			(defun rontolisp::%clojure-host-class-name (x)
			  (declare (ignore x))
			  nil)
			(defun rontolisp::%clojure-host-string (x)
			  (declare (ignore x))
			  nil)
			(defun rontolisp::%clojure-host-instance-p (x class-name)
			  (declare (ignore x class-name))
			  nil)
			(defun rontolisp::%clojure-host-class-instance-p (c x)
			  (declare (ignore c x))
			  nil)
			(defun rontolisp::%clojure-host-throwable (x)
			  (declare (ignore x))
			  nil)
			(defun rontolisp::%clojure-host-class-rows (name)
			  (declare (ignore name))
			  nil)
			(defun rontolisp::%clojure-host-print-method (x table)
			  (declare (ignore x table))
			  nil)
			(defun rontolisp::%clojure-host-method (x method)
			  (declare (ignore x))
			  (rontolisp::%clojure-illegal-argument-exception
			   (concatenate 'string "No matching field found: " method)))
			(defun rontolisp::%clojure-other-hash (x eq)
			  (declare (ignore eq))
			  (%identity-hash x))
			""";

	/**
	 * The bodies a target without the host (wasm) splices in place of the host arms a
	 * value made there still reaches, on top of {@link #HOST_ARMS_WITHOUT_JAVA}:
	 * {@code %clojure-host-string-new} ({@code (String. x)} of an {@code x} that is no
	 * byte array) refuses by name, since the class has the constructor, and
	 * {@code %clojure-agent-drain} (the sender-thread run: a send to an idle agent runs
	 * its queue on the sender's thread until it is empty, like the host drain before the
	 * shared pool), and {@code %clojure-tap-send} ({@code tap>}: each tap called with the
	 * value at the call, true). The pool's and the tap queue's own defuns go with them:
	 * nothing references them where the stand-ins stand, so the pruner drops them and no
	 * thread primitive reaches a module. A {@code java:} operator is a call-time error
	 * there and makes no host object, so every other host arm answers as it does in a
	 * program naming none.
	 */
	private static final String HOST_ARMS_WITHOUT_HOST = """
			(defun rontolisp::%clojure-host-string-new (x more)
			  (rontolisp::%clojure-no-method
			   x
			   (format nil "Constructor java.lang.String taking ~A args is not supported for class "
			           (+ 1 (length more)))
			   "String."))
			(defun rontolisp::%clojure-agent-drain (agent action)
			  (let* ((cell (car (cdr agent))) (state (aref cell 3)) (mine nil))
			    (rontolisp:with-mutex (rontolisp::%clojure-atom-guard)
			      (if action (setf (aref state 1) (append (aref state 1) (list action))))
			      (if (and (aref state 1) (not (aref state 0)) (not (aref state 2)))
			          (progn
			            (setf (aref state 0) t)
			            (setq mine t))))
			    (if mine
			        (rontolisp::%clojure-agent-pump agent))))
			(defun rontolisp::%clojure-tap-send (x)
			  (rontolisp::%clojure-tap-deliver
			   x (rontolisp:with-mutex (rontolisp::%clojure-tap-guard) rontolisp::%clojure-taps))
			  t)
			""";

	/**
	 * The stand-in a target that lowers as the program runs splices in place of the
	 * refusing {@code %clojure-lower-at-run-time}: a JVM class hands the verb, its
	 * arguments as forms carried as plain data ({@link #HOST_TRANSPORT}), the values they
	 * embed, the refusal and {@code *ns*} to the compiler it carries
	 * ({@code rontolisp::%host-lower-at-run-time}, {@code cli/JvmRunTimeLowering}), which
	 * lowers the call, compiles what it lowers to against the running class and runs it.
	 */
	private static final String HOST_LOWERING = """
			(defun rontolisp::%clojure-lower-at-run-time (verb args message)
			  (declare (ignore message))
			  (let ((consts (cons 0 nil)))
			    (rontolisp::%host-lower-at-run-time
			     verb
			     (rontolisp::%clojure-transport
			      (rontolisp::%clojure-run-time-arguments verb args) consts)
			     consts (function rontolisp::%clojure-refuse) rontolisp::%clojure-ns)))
			""";

	/**
	 * The arguments of a lowering verb as plain data -- lists, strings, symbols, numbers,
	 * characters -- which the compiler a JVM class carries reads back into the values the
	 * lowering decodes ({@code ClojureProgramLowering}): a string as the name of a fresh
	 * symbol (a string a program builds is a mutable character vector there, a symbol's
	 * name never), a vector {@code (:c%t-vector e ...)}, a map
	 * {@code (:c%t-map k v ...)}, a set {@code (:c%t-set e ...)}, a collection carrying
	 * metadata {@code (:c%t-meta datum meta)}, a keyword, an instant, a UUID, a decimal
	 * and a symbol carrying metadata as their own wrappers, and any other value -- a
	 * function, an atom, a record, a host object -- {@code (:c%t-embed i)}: the value
	 * itself, the {@code i}th of the constants cell {@code (count . reversed-values)},
	 * which the form lowered from it embeds.
	 */
	private static final String HOST_TRANSPORT = """
			(defun rontolisp::%clojure-transport (x consts)
			  (let ((datum (rontolisp::%clojure-transport-datum x consts)))
			    (if (or (consp x) (hash-table-p x) (and (vectorp x) (not (stringp x))))
			        (let ((meta (rontolisp::%clojure-meta x)))
			          (if meta
			              (list :c%t-meta datum (rontolisp::%clojure-transport meta consts))
			              datum))
			        datum)))
			(defun rontolisp::%clojure-transport-datum (x consts)
			  (cond ((stringp x) (symbol-name (make-symbol x)))
			        ((or (null x) (numberp x) (characterp x) (symbolp x)) x)
			        ((consp x)
			         (let ((tag (car x)))
			           (cond ((not (keywordp tag)) (rontolisp::%clojure-transport-list x consts))
			                 ((or (eq tag :c%keyword) (eq tag :c%inst) (eq tag :c%uuid)
			                      (eq tag :c%decimal))
			                  x)
			                 ((eq tag :c%symbol)
			                  (list :c%symbol (car (cdr x))
			                        (rontolisp::%clojure-transport (car (cdr (cdr x))) consts)))
			                 ((eq tag :c%set)
			                  (let ((acc nil))
			                    (maphash (lambda (k v)
			                               (declare (ignore v))
			                               (setq acc (cons (rontolisp::%clojure-transport k consts) acc)))
			                             (car (cdr x)))
			                    (cons :c%t-set acc)))
			                 (t (rontolisp::%clojure-transport-embed x consts)))))
			        ((hash-table-p x)
			         (let ((acc nil))
			           (maphash (lambda (k v)
			                      (setq acc (cons (rontolisp::%clojure-transport v consts)
			                                      (cons (rontolisp::%clojure-transport k consts) acc))))
			                    x)
			           (cons :c%t-map (nreverse acc))))
			        ((vectorp x)
			         (let ((acc nil))
			           (dotimes (i (length x))
			             (setq acc (cons (rontolisp::%clojure-transport (aref x i) consts) acc)))
			           (cons :c%t-vector (nreverse acc))))
			        (t (rontolisp::%clojure-transport-embed x consts))))
			(defun rontolisp::%clojure-transport-list (x consts)
			  (let ((acc nil) (p x))
			    (do () ((not (consp p)))
			      (setq acc (cons (rontolisp::%clojure-transport (car p) consts) acc))
			      (setq p (cdr p)))
			    (let ((out (if p (rontolisp::%clojure-transport p consts) nil)))
			      (dolist (e acc out)
			        (setq out (cons e out))))))
			(defun rontolisp::%clojure-transport-embed (x consts)
			  (let ((i (car consts)))
			    (rplaca consts (+ i 1))
			    (rplacd consts (cons x (cdr consts)))
			    (list :c%t-embed i)))
			""";

	private static final String HELPER_PREFIX = "%CLOJURE-";

	private static final String NAMESPACE_VAR_PREFIX = "c%clojure.";

	@Nullable private static volatile Set<String> functionNames;

	private ClojureLibrary() {
	}

	/**
	 * Returns the library definitions as the interpreter reads them.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		return FORMS.computeIfAbsent("default", ignored -> {
			List<LispVal> forms = new ArrayList<>(LispReader.readAllFromString(readSource("clojure.lisp")));
			forms.addAll(ClojureCaseMapping.runtimeForms());
			forms.addAll(LispReader.readAllFromString(readSource("clojure-unicode.lisp")));
			return List.copyOf(forms);
		});
	}

	private static String readSource(String name) {
		try (InputStream in = ClojureLibrary.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException(name + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the functions {@code clojure.lisp} defines.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} when the library defines the function
	 */
	public static boolean isClojureFunction(String symbolName) {
		return mayDefine(symbolName) && functionNames().contains(symbolName);
	}

	/**
	 * Whether the name can be one the library defines, answered from its spelling alone:
	 * every definition is a {@code rontolisp} {@code %clojure-} helper or a var of a
	 * {@code clojure.*} namespace ({@code clojure.test}'s {@code *testing-vars*}). The
	 * interpreter asks {@link #isClojureFunction} for every function name it resolves for
	 * the first time and the compile path for every symbol of a program, so any other
	 * name must not read the library ({@code .kb/source-language.md}, "The run-time
	 * libraries' name gate").
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code false} when the library cannot define the name
	 */
	static boolean mayDefine(String symbolName) {
		return symbolName.startsWith(NAMESPACE_VAR_PREFIX)
				|| LispSymbol.memberName(symbolName).startsWith(HELPER_PREFIX);
	}

	private static Set<String> functionNames() {
		Set<String> names = functionNames;
		if (names == null) {
			names = new HashSet<>();
			for (LispVal form : forms()) {
				if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			for (String name : names) {
				if (!mayDefine(name)) {
					throw new IllegalStateException("clojure.lisp defines " + name + ", which mayDefine refuses");
				}
			}
			functionNames = names;
		}
		return names;
	}

	/**
	 * The compile-path pre-pass: prepends the library definitions when the program
	 * references one of its functions, after stripping the arms of every family the
	 * program makes no value of. A program naming neither a library function nor an arm
	 * is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		return process(program, true);
	}

	/**
	 * {@link #process(List)} for a target: where the host is not (wasm, where a
	 * {@code java:} call is a call-time error) a family only the host makes a value of
	 * ({@link ClojureArms#needsHost}) has its arms stripped whatever the program names.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @param hostTarget whether the target is one where the host is (the JVM)
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, boolean hostTarget) {
		return process(program, hostTarget, false);
	}

	/**
	 * {@link #process(List, boolean)} for a program a JVM class lowers again as it runs
	 * ({@code cli/RunTimeImage}): what it lowers then may make a value of any kind and
	 * meet any arm, so it takes the whole library -- the host arms and every family's,
	 * nothing stripped from it or from the program -- and the hand-off of the lowering
	 * verbs to the compiler the class carries ({@link #HOST_LOWERING}) in place of their
	 * refusal.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @param hostTarget whether the target is one where the host is (the JVM)
	 * @param lowersAtRunTime whether the class lowers the program again as it runs
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, boolean hostTarget, boolean lowersAtRunTime) {
		return process(program, hostTarget, lowersAtRunTime, null);
	}

	/**
	 * {@link #process(List, boolean, boolean)} for a FRAGMENT of a class that lowers as
	 * it runs ({@code cli/JvmRunTimeLowering}): the classes before it hold the library
	 * the host spliced, so it takes only the definitions its forms reach that none of
	 * them holds -- transitively through the ones it takes, since one they hold is called
	 * there with everything it reaches (the host's pruner kept those too). The whole
	 * library spliced into every fragment was walked by every pass after this one once
	 * per verb ({@code .kb/jvm-run-time-lowering.md}, "Run time: the fragments").
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @param hostTarget whether the target is one where the host is (the JVM)
	 * @param lowersAtRunTime whether the class lowers the program again as it runs
	 * @param linked what the classes a fragment links against define, or {@code null} for
	 * a whole program
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, boolean hostTarget, boolean lowersAtRunTime,
			@Nullable LinkedDefinitions linked) {
		Splice splice;
		if (linked != null) {
			splice = new Splice(HostReferences.get().reached(program, linked), program);
		}
		else {
			splice = lowersAtRunTime ? new Splice(hostLoweringForms(), program) : splice(program, hostTarget);
		}
		if (splice.library().isEmpty()) {
			return splice.program();
		}
		List<LispVal> out = new ArrayList<>(splice.library());
		out.addAll(splice.program());
		return out;
	}

	/**
	 * The library names each definition of {@link #hostLoweringForms()} mentions, read
	 * once: what {@link LibraryDefunPruner} counts as a reference to a bundled definition
	 * -- the definition's symbol anywhere (quoted data included), a string containing its
	 * name, a FORMAT control's {@code ~/name/} directive naming it -- so a fragment takes
	 * every definition the pruner would keep of the ones it lacks.
	 */
	private static final class HostReferences {

		@Nullable private static volatile HostReferences instance;

		private final List<LispVal> forms;

		/** Each name to the forms defining it (a condition by its class name). */
		private final Map<String, List<Integer>> definitions = new HashMap<>();

		/** The names a string can contain: an upcased string holds no lowercase name. */
		private final List<String> upcasedNames = new ArrayList<>();

		private final List<Set<String>> mentions = new ArrayList<>();

		private HostReferences(List<LispVal> forms) {
			this.forms = forms;
			for (int i = 0; i < forms.size(); i++) {
				String name = definedName(forms.get(i));
				if (!name.isEmpty()) {
					this.definitions.computeIfAbsent(name, key -> new ArrayList<>()).add(i);
				}
			}
			for (String name : this.definitions.keySet()) {
				if (name.equals(name.toUpperCase(Locale.ROOT))) {
					// a string holding a name holds the prefix: the one test collect asks
					// first
					if (!name.contains(HELPER_PREFIX)) {
						throw new IllegalStateException("clojure.lisp defines " + name + ", which " + HELPER_PREFIX
								+ " does not find in a string");
					}
					this.upcasedNames.add(name);
				}
			}
			for (LispVal form : forms) {
				Set<String> names = new HashSet<>();
				collect(form, names);
				this.mentions.add(names);
			}
		}

		static HostReferences get() {
			HostReferences built = instance;
			if (built == null) {
				synchronized (HostReferences.class) {
					built = instance;
					if (built == null) {
						built = new HostReferences(hostLoweringForms());
						instance = built;
					}
				}
			}
			return built;
		}

		/**
		 * The definitions the program reaches that the linked classes do not hold, in
		 * library order.
		 */
		List<LispVal> reached(List<LispVal> program, LinkedDefinitions linked) {
			Set<String> pending = new HashSet<>();
			for (LispVal form : program) {
				collect(form, pending);
			}
			BitSet taken = new BitSet(this.forms.size());
			Deque<String> queue = new ArrayDeque<>(pending);
			Set<String> seen = new HashSet<>(pending);
			while (!queue.isEmpty()) {
				List<Integer> indexes = this.definitions.get(queue.pop());
				if (indexes == null) {
					continue;
				}
				for (int index : indexes) {
					if (!taken.get(index) && !linked.defines(this.forms.get(index))) {
						taken.set(index);
						for (String name : this.mentions.get(index)) {
							if (seen.add(name)) {
								queue.push(name);
							}
						}
					}
				}
			}
			List<LispVal> out = new ArrayList<>(taken.cardinality());
			for (int i = taken.nextSetBit(0); i >= 0; i = taken.nextSetBit(i + 1)) {
				out.add(this.forms.get(i));
			}
			return out;
		}

		private void collect(LispVal form, Set<String> out) {
			LispVal rest = form;
			while (rest instanceof LispCons cons) {
				collect(cons.car(), out);
				rest = cons.cdr();
			}
			if (rest instanceof LispSymbol symbol) {
				if (this.definitions.containsKey(symbol.name())) {
					out.add(symbol.name());
				}
			}
			else if (rest instanceof LispString string) {
				String value = string.value().toUpperCase(Locale.ROOT);
				if (value.contains(HELPER_PREFIX)) {
					for (String name : this.upcasedNames) {
						if (value.contains(name)) {
							out.add(name);
						}
					}
				}
				if (value.indexOf('~') >= 0) {
					for (String directive : FormatRenderer.functionDesignatorNames(value)) {
						for (String name : List.of(directive, LispSymbol.memberName(directive))) {
							if (this.definitions.containsKey(name)) {
								out.add(name);
							}
						}
					}
				}
			}
		}

	}

	/**
	 * The whole library with the lowering verbs handed to the compiler the class carries.
	 */
	private static List<LispVal> hostLoweringForms() {
		List<LispVal> library = forms();
		return FORMS.computeIfAbsent(HOST_LOWERING_KEY, ignored -> {
			List<LispVal> out = new ArrayList<>(standingIn(library, HOST_LOWERING));
			out.addAll(LispReader.readAllFromString(HOST_TRANSPORT));
			return List.copyOf(out);
		});
	}

	private static final String HOST_LOWERING_KEY = "host-lowering";

	/**
	 * {@link #process(List, boolean)} with its two halves apart: the library a WHOLE
	 * program splices, stripped of every family the program makes no value of, and the
	 * program with the same arms stripped. The interpreter runs a whole program this way
	 * ({@code LispEvaluator#clojureProgram}), evaluating the library ahead of the
	 * program, so a family's tests cost it nothing where no value of the family can
	 * exist.
	 * @param program the top-level forms of the whole program
	 * @param hostTarget whether the target is one where the host is (the JVM, the
	 * interpreter)
	 * @return the library (empty when the program names none of it) and the program
	 */
	public static Splice splice(List<LispVal> program, boolean hostTarget) {
		if (!referencesAny(program)) {
			return new Splice(List.of(), program);
		}
		// an arm names the library too, so the reference is asked again once they go
		List<LispVal> body = program;
		Set<ClojureArms.Family> made = EnumSet.noneOf(ClojureArms.Family.class);
		for (ClojureArms.Family family : ClojureArms.Family.values()) {
			ClojureArms.Scan scan = ClojureArms.scan(body, family, hostTarget);
			if (scan.builds() && (hostTarget || !ClojureArms.needsHost(family))
					&& ClojureArms.madeBeside(family, made)) {
				made.add(family);
			}
			else if (scan.arms()) {
				body = ClojureArms.strip(body, family);
			}
		}
		if (body != program && !referencesAny(body)) {
			return new Splice(List.of(), body);
		}
		return new Splice(library(hostTarget, hostTarget && usesJava(body), made), body);
	}

	/**
	 * A whole program's library and the program itself, each with the arms of the
	 * families the program makes no value of stripped.
	 *
	 * @param library the library definitions, empty when the program names none
	 * @param program the program's own forms
	 */
	public record Splice(List<LispVal> library, List<LispVal> program) {
	}

	private static boolean referencesAny(List<LispVal> program) {
		for (LispVal form : program) {
			if (references(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The library a program splices: with or without the host arms (and without the
	 * host's, where it is not), and with the arms of only the families whose values the
	 * program makes (one that builds no sorted collection takes no sorted-collection
	 * arm).
	 */
	private static List<LispVal> library(boolean host, boolean java, Set<ClojureArms.Family> made) {
		List<LispVal> library = java ? forms() : host ? formsWithoutHostArms() : formsWithoutHost();
		StringBuilder key = new StringBuilder(java ? "default" : host ? WITHOUT_HOST_ARMS : WITHOUT_HOST);
		List<ClojureArms.Family> stripped = new ArrayList<>();
		for (ClojureArms.Family family : ClojureArms.Family.values()) {
			if (!made.contains(family)) {
				key.append(" without-").append(family.name());
				stripped.add(family);
			}
		}
		if (stripped.isEmpty()) {
			return library;
		}
		return FORMS.computeIfAbsent(key.toString(), ignored -> {
			List<LispVal> out = library;
			for (ClojureArms.Family family : stripped) {
				out = ClojureArms.strip(out, family);
			}
			return List.copyOf(out);
		});
	}

	/**
	 * The library for a program with no {@code java:} operator: the host arms replaced by
	 * their {@code java:}-free bodies ({@link #HOST_ARMS_WITHOUT_JAVA}). No host object
	 * can exist there, so the answer is the same, while the {@code java:} reference would
	 * change the JVM output (the bridge, the host guards on every accessor) and is a
	 * call-time error on wasm. The interpreter keeps {@link #forms()}: its {@code java:}
	 * costs nothing.
	 */
	private static List<LispVal> formsWithoutHostArms() {
		// read first: a nested computeIfAbsent on one ConcurrentHashMap is refused
		List<LispVal> library = forms();
		return FORMS.computeIfAbsent(WITHOUT_HOST_ARMS, ignored -> standingIn(library, HOST_ARMS_WITHOUT_JAVA));
	}

	/**
	 * The library for a target without the host (wasm): {@link #formsWithoutHostArms()}
	 * with the host arms a value made there reaches replaced too
	 * ({@link #HOST_ARMS_WITHOUT_HOST}), so no {@code java:} operator, a call-time error
	 * there, is left for a program the host has no part in.
	 */
	private static List<LispVal> formsWithoutHost() {
		List<LispVal> library = formsWithoutHostArms();
		return FORMS.computeIfAbsent(WITHOUT_HOST, ignored -> standingIn(library, HOST_ARMS_WITHOUT_HOST));
	}

	private static final String WITHOUT_HOST_ARMS = "without-host-arms";

	private static final String WITHOUT_HOST = "without-host";

	// The library with each definition the stand-ins name replaced by its stand-in.
	private static List<LispVal> standingIn(List<LispVal> library, String source) {
		Map<String, LispVal> standIns = new HashMap<>();
		for (LispVal standIn : LispReader.readAllFromString(source)) {
			standIns.put(definedName(standIn), standIn);
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : library) {
			LispVal standIn = standIns.remove(definedName(form));
			out.add(standIn == null ? form : standIn);
		}
		if (!standIns.isEmpty()) {
			throw new IllegalStateException("clojure.lisp defines no host arm " + standIns.keySet());
		}
		return List.copyOf(out);
	}

	private static String definedName(LispVal form) {
		return form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name ? name.name() : "";
	}

	private static boolean usesJava(List<LispVal> program) {
		for (LispVal form : program) {
			if (mentions(form, LispNames.JAVA_OPERATORS_QUALIFIED)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentions(LispVal form, List<String> names) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (mentions(cons.car(), names)) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && names.contains(symbol.name());
	}

	private static boolean references(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (references(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol
				&& (isClojureFunction(symbol.name()) || ClojureArms.isFamilyName(symbol.name()));
	}

}
