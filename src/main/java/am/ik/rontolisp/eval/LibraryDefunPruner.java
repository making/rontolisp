package am.ik.rontolisp.eval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispPackageException;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import org.jspecify.annotations.Nullable;

/**
 * The compile-path library tree-shaker: an AST-level pre-pass that drops spliced
 * definitions ({@code defun}/{@code defparameter}/{@code defvar}/{@code defconstant})
 * unreachable from the user program. Two sources are in scope -- the bundled Lisp
 * libraries ({@link LinalgLibrary}, {@link VecLibrary}, {@link JsonLibrary},
 * {@link UrlLibrary}, {@link LispPreludeLibrary}) and any third-party ASDF system
 * {@code LoadInliner} spliced, which it marks with {@code %begin-system} brackets. The
 * splices are <em>whole</em> (linalg.lisp alone is ~100 defuns of which a typical program
 * calls a handful; cl-ppcre is ~300 definitions), and the {@code --optimize} bytecode
 * shakers can only trim after the complete class/module has been serialized once -- and
 * cannot reach a dead Lisp defun at all, since every one of them is reachable from the
 * funcall dispatcher. That is how the ci-spec corpus class approached the JVM 65535
 * constant-pool ceiling ({@code .kb/library-defun-pruning.md}). Pruning before Pass 1
 * keeps the pool (and the un-optimized artifact) small for every program.
 *
 * <p>
 * Reachability is deliberately over-approximate ("carve-out" semantics):
 * <ul>
 * <li>a reference is ANY occurrence of a definition's name anywhere in a kept form --
 * operator position, argument position, inside {@code quote}d data, inside
 * {@code (function ...)} -- plus any string literal that contains the name as a substring
 * (so {@code (intern "linalg:norm")} / {@code read-from-string} idioms keep their
 * target);</li>
 * <li>a third-party definition is additionally referenced by an uninterned {@code '#:foo}
 * designator with its member name, and by a string literal whose WHOLE content is its
 * canonical or member name ({@code (find-symbol "FOO" :pkg)}, folded at codegen time --
 * after this pass). The substring rule above is deliberately NOT extended to them:
 * measured over the vendored trees its only hits there are docstring coincidences;</li>
 * <li>the fixpoint starts from every non-library top-level form (the user program is
 * never pruned) and follows references transitively through kept library
 * definitions;</li>
 * <li>the one reference the later macro expansion synthesizes out of thin air,
 * {@code (setf (vec:aref ...))} -> {@code vec:aset}, is a hardcoded edge; usocket is
 * excluded from pruning entirely because its {@code with-*} built-in macros synthesize
 * {@code usocket:socket-close}/{@code usocket::%usock-guard} calls that are not textually
 * present, and so is every other system {@link BuiltinSystems} stands in for;</li>
 * <li>the pass bails (prunes nothing) when a runtime {@code load}/{@code require}
 * survives the {@code LoadInliner} -- loaded code can call anything by name. The CLI
 * additionally skips the pass under {@code --dynamic} (late binding can resolve any name
 * at runtime) and {@code --no-prune}.</li>
 * </ul>
 *
 * The documented limitation of the carve-out: a program that forges a library function's
 * qualified name at runtime from computed strings and invokes it through the eval family
 * ({@code eval}/{@code apply}/computed {@code fboundp}) gets the ordinary "undefined
 * function" error for a pruned function; compile with {@code --dynamic} (or
 * {@code --no-prune}) to keep every library definition.
 *
 * <p>
 * Analysis runs on a {@link PackageResolver#resolveProgram(List) resolved} copy of the
 * program (index-aligned 1:1) so user-source spellings ({@code in-package}, nicknames,
 * bare exported names) -- and the definition names themselves -- read as the canonical
 * ones; forms are removed from the pre-resolution list, so surviving forms stay
 * byte-identical (the resolver fixed-point invariants of the library sources are
 * untouched). The one structural change is that a top-level {@code progn}/{@code
 * eval-when} a macro produced is spliced first, which every backend does anyway as the
 * first step of {@code compile()}. The interpreter path is unaffected: it lazy-loads
 * libraries whole at runtime, where nothing is pruned.
 */
public final class LibraryDefunPruner {

	@Nullable private static volatile Set<String> prunableNames;

	private LibraryDefunPruner() {
	}

	/**
	 * Drops spliced library definitions unreachable from the user program. Returns the
	 * program unchanged when nothing can be pruned (no library definitions present, or a
	 * runtime {@code load}/{@code require} survives).
	 * @param program the top-level forms, after load inlining, user-macro expansion and
	 * every library splice pre-pass
	 * @return the program with unreachable library definitions removed
	 */
	public static List<LispVal> prune(List<LispVal> program) {
		// A macro-produced top-level (progn (defun ...) ...) hides its definitions from
		// this pass -- definitionName returns null for a progn, so the whole block is a
		// root. Every backend flattens progn/locally/eval-when as the first step of
		// compile(), so flattening here only makes the pruner see the same top level the
		// backends do. A program with nothing to splice comes back unchanged.
		List<LispVal> forms = LispMacroExpander.flattenTopLevel(program);
		Provenance provenance = Provenance.scan(forms);
		Set<String> bundled = prunableNames();
		if (!provenance.hasSystems() && !hasBundledDefinition(forms, bundled)) {
			// Nothing this pass could remove (the common case: a program that splices no
			// library at all). Return the caller's list, not the flattened copy.
			return program;
		}
		// A runtime load/require (a non-literal one the LoadInliner could not inline)
		// can call any function by name, so reachability is unknowable: keep everything
		// except the markers, which are this pass's own bookkeeping.
		if (usesAnySymbol(forms, Set.of(LispNames.LOAD, LispNames.REQUIRE))) {
			return provenance.withoutMarkers(forms);
		}
		// Analyze a resolved copy (index-aligned 1:1 with the original) so user-source
		// spellings match the definitions' canonical names. A resolution error is not
		// this pass's to report: the compiler runs the identical resolution first thing.
		List<LispVal> resolved;
		try {
			resolved = new PackageResolver().resolveProgram(forms);
		}
		catch (LispPackageException ex) {
			return provenance.withoutMarkers(forms);
		}
		// Definition names come from the RESOLVED copy: a bundled library form is a
		// resolver fixed point and reads the same either way, but a third-party
		// (defun scan ...) under (in-package :cl-ppcre) is only CL-PPCRE:SCAN there --
		// which is also the spelling every reference to it resolves to.
		Map<String, List<Integer>> defsByName = new HashMap<>();
		Map<Integer, String> nameByIndex = new HashMap<>();
		Set<String> thirdParty = new HashSet<>();
		for (int i = 0; i < resolved.size(); i++) {
			String name = definitionName(resolved.get(i));
			if (name == null) {
				continue;
			}
			boolean fromSystem = provenance.isPrunableSystem(i);
			if (!fromSystem && !bundled.contains(name)) {
				continue;
			}
			if (fromSystem) {
				if (!hasPrunableInitform(resolved.get(i))) {
					continue;
				}
				thirdParty.add(name);
			}
			defsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
			nameByIndex.put(i, name);
		}
		if (defsByName.isEmpty()) {
			return provenance.withoutMarkers(forms);
		}
		Prunable prunable = Prunable.of(defsByName.keySet(), bundled, thirdParty);
		// Fixpoint from the roots (every top-level form that is not a prunable
		// definition): a definition is kept iff its name is referenced from a kept form,
		// transitively.
		Set<String> live = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		Set<String> roots = new LinkedHashSet<>();
		for (int i = 0; i < resolved.size(); i++) {
			if (!nameByIndex.containsKey(i) && !provenance.isMarker(i) && !isDeclamation(resolved.get(i))) {
				collectReferences(resolved.get(i), prunable, roots);
			}
		}
		for (String name : roots) {
			if (live.add(name)) {
				queue.add(name);
			}
		}
		while (!queue.isEmpty()) {
			String name = queue.remove();
			List<Integer> indexes = defsByName.get(name);
			if (indexes == null) {
				continue;
			}
			for (int index : indexes) {
				Set<String> refs = new LinkedHashSet<>();
				collectReferences(resolved.get(index), prunable, refs);
				for (String ref : refs) {
					if (live.add(ref)) {
						queue.add(ref);
					}
				}
			}
		}
		List<LispVal> out = new ArrayList<>(forms.size());
		for (int i = 0; i < forms.size(); i++) {
			String name = nameByIndex.get(i);
			if (provenance.isMarker(i) || (name != null && !live.contains(name))) {
				continue;
			}
			out.add(forms.get(i));
		}
		return out.size() == forms.size() ? forms : out;
	}

	/**
	 * Drops the {@code %begin-system}/{@code %end-system} provenance markers without
	 * pruning anything -- what the CLI runs instead of {@link #prune} under
	 * {@code --dynamic}/{@code --no-prune}, so those escape hatches emit exactly the
	 * artifact they emitted before the markers existed.
	 * @param program the top-level forms, after the splice chain
	 * @return the program with the markers removed (the same list when it has none)
	 */
	public static List<LispVal> stripSystemMarkers(List<LispVal> program) {
		return Provenance.scan(program).withoutMarkers(program);
	}

	/**
	 * Whether any top-level form defines a bundled library name. Bundled library forms
	 * are resolver fixed points, so this pre-check needs no resolution -- it only decides
	 * whether the pass has anything to do at all.
	 */
	private static boolean hasBundledDefinition(List<LispVal> forms, Set<String> bundled) {
		for (LispVal form : forms) {
			String name = definitionName(form);
			if (name != null && bundled.contains(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a third-party definition may be dropped on the strength of its own body. A
	 * {@code defun} always may -- removing it can only ever produce the loud "undefined
	 * function" error the carve-out documents. A {@code defvar}/{@code defparameter}/
	 * {@code defconstant} may only when its initform cannot do anything but compute a
	 * value: dropping the definition drops the initform with it, and a third-party
	 * {@code (defvar *registered* (register-all-types))} whose VALUE nobody reads would
	 * lose the registration silently -- the one way this pass could produce wrong output
	 * instead of a loud error.
	 *
	 * <p>
	 * The judgment is syntactic and denies by default: a literal, a variable read, a
	 * {@code quote}, or a call to one of a small set of allocating/arithmetic operators
	 * with pure arguments. Measured over the vendored trees this keeps a handful of
	 * definitions that are in fact pure ({@code md5::*t*} builds its table with a
	 * {@code loop}) and costs none of the large wins -- the 61 dead {@code defconstant}s
	 * of the cl-postgres stack are literal integers. The bundled libraries keep the
	 * unconditional rule they were audited under.
	 */
	private static boolean hasPrunableInitform(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !(cons.cdr() instanceof LispCons rest)) {
			return false;
		}
		if (LispNames.DEFUN.equals(op.name())) {
			return true;
		}
		// (defvar *x*) with no initform defines nothing to run.
		return !(rest.cdr() instanceof LispCons valueCell) || isPureValue(valueCell.car());
	}

	private static boolean isPureValue(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			// A literal, or a symbol read (including nil/t and a keyword).
			return true;
		}
		if (!(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return false;
		}
		if (LispNames.QUOTE.equals(op.name()) || LispNames.FUNCTION.equals(op.name())) {
			return true;
		}
		if (!PURE_INITFORM_OPERATORS.contains(member(op.name()))) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (!isPureValue(parts.get(i))) {
				return false;
			}
		}
		return true;
	}

	// Operators an initform may call and still be droppable: allocation, arithmetic,
	// comparison and non-destructive readers. Deliberately EXCLUDES anything that could
	// touch state a later form depends on -- setf/push/pushnew, I/O, funcall/apply, and
	// any call to a library-defined function (absent from this set by construction).
	private static final Set<String> PURE_INITFORM_OPERATORS = Set.of("LIST", "LIST*", "CONS", "VECTOR", "MAKE-ARRAY",
			"MAKE-HASH-TABLE", "MAKE-STRING", "MAKE-LIST", "APPEND", "REVERSE", "COPY-LIST", "COPY-SEQ", "COPY-TREE",
			"CONCATENATE", "SUBSEQ", "LENGTH", "IF", "AND", "OR", "NOT", "+", "-", "*", "/", "1+", "1-", "EXPT", "MOD",
			"REM", "FLOOR", "CEILING", "ROUND", "TRUNCATE", "ABS", "MIN", "MAX", "SQRT", "ISQRT", "ASH", "LOGAND",
			"LOGIOR", "LOGXOR", "LOGNOT", "BYTE", "=", "/=", "<", ">", "<=", ">=", "EQ", "EQL", "EQUAL", "EQUALP",
			"CODE-CHAR", "CHAR-CODE", "CHAR", "COERCE", "STRING", "SYMBOL-NAME", "CAR", "CDR", "FIRST", "REST", "NTH",
			"ELT", "AREF", "GETHASH", "NULL", "ZEROP", "PLUSP", "MINUSP");

	/**
	 * Whether the top-level form is a {@code declaim}/{@code proclaim}. Both expand to
	 * {@code nil} on every backend, so nothing they name can be called through them --
	 * yet an {@code (inline F)} / {@code (ftype (function ...) F)} specifier would
	 * otherwise anchor F as a root. The form itself STAYS in the program (a top-level
	 * {@code (declaim (special ...))} is read by {@code SpecialVarCollector}); only its
	 * symbol occurrences stop counting as references.
	 */
	private static boolean isDeclamation(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)) {
			return false;
		}
		String member = member(op.name());
		return LispNames.DECLAIM.equals(member) || LispNames.PROCLAIM.equals(member);
	}

	/**
	 * Which ASDF system each top-level form was spliced from, read from the
	 * {@code %begin-system}/{@code %end-system} brackets {@code LoadInliner} emits.
	 * Brackets nest with {@code :depends-on} and the innermost one wins, which is what
	 * keeps a built-in system spliced as a dependency (usocket) out of a third-party
	 * system's provenance.
	 *
	 * @param systemAt the innermost enclosing system name per index, {@code null} outside
	 * every bracket
	 * @param marker whether the form at that index is a bracket marker (dropped from the
	 * output)
	 * @param balanced whether every bracket was closed -- when it is not, provenance is
	 * discarded rather than guessed
	 */
	private record Provenance(@Nullable String[] systemAt, boolean[] marker, boolean balanced) {

		static Provenance scan(List<LispVal> forms) {
			String[] systemAt = new String[forms.size()];
			boolean[] marker = new boolean[forms.size()];
			Deque<String> open = new ArrayDeque<>();
			boolean balanced = true;
			for (int i = 0; i < forms.size(); i++) {
				String begun = beginSystemName(forms.get(i));
				if (begun != null) {
					marker[i] = true;
					open.addLast(begun);
					continue;
				}
				if (isOperator(forms.get(i), LispNames.END_SYSTEM)) {
					marker[i] = true;
					if (open.isEmpty()) {
						balanced = false;
					}
					else {
						open.removeLast();
					}
					continue;
				}
				systemAt[i] = open.peekLast();
			}
			return new Provenance(systemAt, marker, balanced && open.isEmpty());
		}

		boolean isMarker(int index) {
			return this.marker[index];
		}

		boolean hasSystems() {
			for (boolean m : this.marker) {
				if (m) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Whether the form at the index was spliced from a system whose definitions may
		 * be pruned. A built-in system is excluded: {@code usocket}'s {@code with-*}
		 * built-in macros synthesize
		 * {@code usocket:socket-close}/{@code usocket::%usock-guard} calls that are not
		 * textually present in the pre-expansion AST, and the same reasoning covers every
		 * other shim {@code BuiltinSystems} stands in for.
		 */
		boolean isPrunableSystem(int index) {
			String system = this.systemAt[index];
			return this.balanced && system != null && !BuiltinSystems.isBuiltin(system);
		}

		List<LispVal> withoutMarkers(List<LispVal> forms) {
			if (!hasSystems()) {
				return forms;
			}
			List<LispVal> out = new ArrayList<>(forms.size());
			for (int i = 0; i < forms.size(); i++) {
				if (!this.marker[i]) {
					out.add(forms.get(i));
				}
			}
			return out;
		}

		@Nullable private static String beginSystemName(LispVal form) {
			if (!isOperator(form, LispNames.BEGIN_SYSTEM) || !(form instanceof LispCons cons)
					|| !(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispString name)) {
				return null;
			}
			return name.value();
		}

		private static boolean isOperator(LispVal form, String operator) {
			return form instanceof LispCons cons && cons.car() instanceof LispSymbol op && operator.equals(op.name());
		}
	}

	/**
	 * The name sets the reachability scan matches against, split by how a reference to
	 * them may be spelled.
	 *
	 * @param exact every prunable definition name, matched against a symbol occurrence
	 * verbatim
	 * @param substringScanned the bundled-library names only -- the original carve-out,
	 * where a string literal CONTAINING the name counts. It is deliberately not extended
	 * to third-party names: measured over the vendored trees its only hits there are
	 * docstring coincidences ("...use md5sum-string instead..." would keep
	 * {@code md5:md5sum-string} and, transitively, sixteen more definitions), and keeping
	 * it at ~230 names is also what keeps the scan linear
	 * @param thirdPartyByMember member name -> the third-party definitions with that
	 * member, for the two spellings that name a symbol without naming its package: an
	 * uninterned {@code '#:foo} designator and a whole string literal
	 * ({@code (find-symbol
	 * "FOO" :pkg)}, which is folded at codegen time, after this pass)
	 */
	private record Prunable(Set<String> exact, Set<String> substringScanned,
			Map<String, List<String>> thirdPartyByMember) {

		static Prunable of(Set<String> all, Set<String> bundled, Set<String> thirdParty) {
			Set<String> scanned = new HashSet<>(bundled);
			scanned.retainAll(all);
			Map<String, List<String>> byMember = new HashMap<>();
			for (String name : thirdParty) {
				byMember.computeIfAbsent(LispSymbol.memberName(name), k -> new ArrayList<>()).add(name);
			}
			return new Prunable(Set.copyOf(all), scanned, byMember);
		}
	}

	/**
	 * The union of every definition name in the prunable libraries (linalg, vec, json +
	 * its {@code #'} wrappers, url, prelude). usocket is deliberately absent: its
	 * {@code with-*} built-in macros synthesize calls not textually present in the
	 * pre-expansion AST.
	 */
	private static Set<String> prunableNames() {
		Set<String> cached = prunableNames;
		if (cached == null) {
			synchronized (LibraryDefunPruner.class) {
				cached = prunableNames;
				if (cached == null) {
					Set<String> names = new HashSet<>();
					collectDefinitionNames(LinalgLibrary.forms(), names);
					collectDefinitionNames(VecLibrary.forms(), names);
					collectDefinitionNames(JsonLibrary.forms(), names);
					collectDefinitionNames(JsonLibrary.wrapperForms(), names);
					collectDefinitionNames(UrlLibrary.forms(), names);
					for (String name : LispPreludeLibrary.names()) {
						collectDefinitionNames(LispPreludeLibrary.formsFor(name), names);
					}
					prunableNames = Set.copyOf(names);
					cached = prunableNames;
				}
			}
		}
		return cached;
	}

	private static void collectDefinitionNames(List<LispVal> forms, Set<String> out) {
		for (LispVal form : forms) {
			String name = definitionName(form);
			if (name != null) {
				out.add(name);
			}
		}
	}

	/**
	 * Returns the defined name of a top-level {@code defun}/{@code defparameter}/
	 * {@code defvar}/{@code defconstant} form, or {@code null} for anything else (which
	 * is then a root). A {@code (defun (setf N) ...)} writer is keyed under N, the same
	 * name every {@code (setf (N ...))} place and {@code #'(setf N)} reference contains
	 * textually.
	 *
	 * <p>
	 * The definition kinds a third-party tree also uses heavily but that are NOT listed
	 * here stay roots deliberately:
	 * {@code defclass}/{@code defgeneric}/{@code defmethod}/
	 * {@code define-condition}/{@code defstruct}/{@code deftype} are expanded into
	 * defuns, dispatchers and type tables by
	 * {@code LispMacroExpander.expandTopLevelDefinitions} INSIDE the backends, after this
	 * pass -- and {@code (make-instance 'c)} synthesizes a call to whatever generic is
	 * registered as {@code initialize-instance} with no textual occurrence at the call
	 * site, so pruning them would mean duplicating the CLOS method-selection rules here.
	 * {@code defmacro}, {@code define-compiler-macro}, {@code define-modify-macro},
	 * {@code defsetf} and {@code define-setf-expander} never reach this pass at all:
	 * {@code UserMacroExpander} registers and drops them.
	 */
	@Nullable private static String definitionName(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !(cons.cdr() instanceof LispCons rest)) {
			return null;
		}
		String operator = op.name();
		// defconstant rides with defparameter/defvar: the backends compile all three
		// through the same case (a global variable definition), and so do
		// GlobalVarCollector and SpecialVarCollector.
		if (!LispNames.DEFUN.equals(operator) && !LispNames.DEFPARAMETER.equals(operator)
				&& !LispNames.DEFVAR.equals(operator) && !LispNames.DEFCONSTANT.equals(operator)) {
			return null;
		}
		return switch (rest.car()) {
			case LispSymbol name -> name.name();
			// (defun (setf N) ...) -> keyed under N (kept iff N is referenced).
			case LispCons designator when LispNames.DEFUN.equals(operator)
					&& designator.car() instanceof LispSymbol setf && LispNames.SETF.equals(setf.name())
					&& designator.cdr() instanceof LispCons placeCell && placeCell.car() instanceof LispSymbol place ->
				place.name();
			default -> null;
		};
	}

	/**
	 * Collects every reference to a prunable name inside {@code form}: any symbol
	 * occurrence (any position, including quoted data) plus any string literal containing
	 * a prunable name as a substring. The one synthesized edge -- the later
	 * {@code (setf (vec:aref ...))} expansion calls {@code vec:aset} -- is added
	 * alongside {@code vec:aref}.
	 */
	private static void collectReferences(LispVal form, Prunable prunable, Set<String> out) {
		switch (form) {
			case LispSymbol sym -> {
				String name = sym.name();
				if (prunable.exact().contains(name)) {
					out.add(name);
				}
				if (name.startsWith("#:")) {
					// An uninterned symbol is never a variable or a call; it is a string
					// designator on its way to intern/find-symbol/format, and it names no
					// package (cl-postgres writes (intern (string
					// '#:make-ssl-client-stream)
					// :cl+ssl)). Match it by member name against every third-party
					// definition spelled that way. A KEYWORD deliberately does NOT widen
					// like this: in third-party CL a keyword is overwhelmingly data
					// (plist
					// keys, loop keywords, case labels), and allowing it was measured to
					// rescue exactly one definition across the whole vendored corpus
					// while
					// colliding with seven unrelated keywords.
					addAll(prunable.thirdPartyByMember().get(name.substring(2)), out);
				}
				if (LispNames.VEC_QUALIFIED_AREF.equals(name)) {
					out.add(LispNames.VEC_QUALIFIED_ASET);
				}
			}
			case LispString str -> {
				// Match case-insensitively: the reader upcases, so a lowercase source
				// string like "linalg:ndim" fed to read-from-string names LINALG:NDIM.
				String value = str.value().toUpperCase(java.util.Locale.ROOT);
				for (String name : prunable.substringScanned()) {
					if (value.contains(name)) {
						out.add(name);
					}
				}
				// A WHOLE string literal naming a definition, qualified or not: the
				// (find-symbol "NAME" :pkg) / (intern "PKG:NAME") idioms. find-symbol is
				// folded at codegen time -- after this pass -- so the literal is the only
				// trace of the call it becomes.
				if (prunable.exact().contains(value)) {
					out.add(value);
				}
				addAll(prunable.thirdPartyByMember().get(value), out);
			}
			case LispCons cons -> {
				collectReferences(cons.car(), prunable, out);
				collectReferences(cons.cdr(), prunable, out);
			}
			default -> {
			}
		}
	}

	private static void addAll(@Nullable List<String> names, Set<String> out) {
		if (names != null) {
			out.addAll(names);
		}
	}

	/**
	 * Returns whether any of the given symbol names occurs anywhere in the program,
	 * compared by member name so pre-resolution spellings like {@code cl:load} match.
	 */
	private static boolean usesAnySymbol(List<LispVal> program, Set<String> names) {
		for (LispVal form : program) {
			if (usesAnySymbol(form, names)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesAnySymbol(LispVal form, Set<String> names) {
		return switch (form) {
			case LispSymbol sym -> names.contains(member(sym.name()));
			case LispCons cons -> usesAnySymbol(cons.car(), names) || usesAnySymbol(cons.cdr(), names);
			default -> false;
		};
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
