package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class LispMacroExpanderTest {

	/**
	 * The names appearing in the emitted {@code %subtypep-ancestor-table%}, in emission
	 * order.
	 */
	private static List<String> ancestorTableNames(String source) {
		List<LispVal> expanded = LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source),
				new HashMap<>(), new ClosRegistry());
		List<String> names = new ArrayList<>();
		for (LispVal form : expanded) {
			// Only the data forms -- the (defvar %SUBTYPEP-ANCESTOR-TABLE '(...)) and the
			// (setq %SUBTYPEP-ANCESTOR-TABLE (append '(...) ...)) chunks. The dispatch
			// defun also names the table, and carries counter-generated temporaries that
			// legitimately renumber.
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
					|| !(LispNames.DEFVAR.equals(op.name()) || LispNames.SETQ.equals(op.name()))
					|| !(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispSymbol var)
					|| !LispNames.SUBTYPEP_ANCESTOR_TABLE.equals(var.name())) {
				continue;
			}
			collectSymbols(rest.cdr(), names);
		}
		names.removeIf(name -> LispNames.QUOTE.equals(name) || LispNames.APPEND.equals(name)
				|| LispNames.SUBTYPEP_ANCESTOR_TABLE.equals(name));
		return names;
	}

	private static void collectSymbols(LispVal form, List<String> out) {
		switch (form) {
			case LispSymbol sym -> out.add(sym.name());
			case LispCons cons -> {
				collectSymbols(cons.car(), out);
				collectSymbols(cons.cdr(), out);
			}
			default -> {
			}
		}
	}

	@Test
	void aNonOperatorRestartNameDoesNotFlipRestartMode() {
		// A tagbody tag, a let binding, or quoted data spelling a restart-runtime name is
		// not a call: chipz's bzip2 decoder has a tagbody tag named CONTINUE, and the old
		// spine-walking scan put every program that loads chipz into restart mode.
		assertThat(LispMacroExpander
			.usesRestartSystem(LispReader.readAllFromString("(defun f (x) (tagbody continue (go continue)))")))
			.isFalse();
		// NOTE a binding pair or clause head spelling a restart name -- e.g.
		// (let ((continue 1)) ...) -- still over-approximates to true; that is the safe
		// direction and not asserted here.
		assertThat(LispMacroExpander.usesRestartSystem(LispReader.readAllFromString("(print '(abort continue))")))
			.isFalse();
		assertThat(LispMacroExpander.usesRestartSystem(LispReader.readAllFromString("(case x (:abort 1) (t 2))")))
			.isFalse();
	}

	@Test
	void anOperatorPositionRestartFormStillFlipsRestartMode() {
		assertThat(LispMacroExpander.usesRestartSystem(LispReader.readAllFromString("(continue)"))).isTrue();
		assertThat(LispMacroExpander.usesRestartSystem(LispReader.readAllFromString("(when t (abort c))"))).isTrue();
		assertThat(LispMacroExpander
			.usesRestartSystem(LispReader.readAllFromString("(restart-case (error \"x\") (retry () 1))"))).isTrue();
		assertThat(LispMacroExpander.usesRestartSystem(LispReader.readAllFromString("(mapcar #'continue restarts)")))
			.isTrue();
		assertThat(LispMacroExpander
			.usesRestartSystem(LispReader.readAllFromString("(handler-bind ((error #'ignore)) (f))"))).isTrue();
	}

	@Test
	void needsSignalClauseMatchRequiresBothASignalAndACatchingForm() {
		// The signal-point clause match (CLHS 9.1.4.1) is needed exactly when the
		// program both signals and establishes a handler-case/ignore-errors; either
		// half alone keeps the historical byte-identical emission.
		assertThat(LispMacroExpander
			.needsSignalClauseMatch(LispReader.readAllFromString("(handler-case (f) (error (e) 1))"))).isFalse();
		assertThat(LispMacroExpander.needsSignalClauseMatch(LispReader.readAllFromString("(signal 'x)"))).isFalse();
		assertThat(LispMacroExpander.needsSignalClauseMatch(
				LispReader.readAllFromString("(defun f () (signal 'x)) (handler-case (f) (error (e) 1))")))
			.isTrue();
		assertThat(
				LispMacroExpander.needsSignalClauseMatch(LispReader.readAllFromString("(ignore-errors (signal 'x))")))
			.isTrue();
		// A #'signal reference counts; quoted data does not (the operator-position
		// discipline).
		assertThat(LispMacroExpander.needsSignalClauseMatch(
				LispReader.readAllFromString("(handler-case (funcall #'signal \"s\") (error (e) 1))")))
			.isTrue();
		assertThat(LispMacroExpander
			.needsSignalClauseMatch(LispReader.readAllFromString("(handler-case (print '(signal x)) (error (e) 1))")))
			.isFalse();
	}

	@Test
	void applyRuntimeIsNotNeededForLiteralTargetsAndNeededForComputedOnes() {
		java.util.Set<String> wrappers = java.util.Set.of("+", "LIST");
		// Literal #'defun-name / 'defun-name targets compile to direct calls.
		assertThat(LispMacroExpander.needsApplyRuntime(
				LispReader.readAllFromString("(defun f (&rest xs) xs) (apply #'f (list 1 2))"), wrappers))
			.isFalse();
		assertThat(LispMacroExpander
			.needsApplyRuntime(LispReader.readAllFromString("(defun f (&rest xs) xs) (apply 'f (list 1 2))"), wrappers))
			.isFalse();
		// #'wrapper is injectable via the reference itself; 'wrapper is not.
		assertThat(
				LispMacroExpander.needsApplyRuntime(LispReader.readAllFromString("(apply #'+ (list 1 2))"), wrappers))
			.isFalse();
		assertThat(LispMacroExpander.needsApplyRuntime(LispReader.readAllFromString("(apply '+ (list 1 2))"), wrappers))
			.isTrue();
		// Computed designators, lambda designators and unknown names need _apply.
		assertThat(LispMacroExpander
			.needsApplyRuntime(LispReader.readAllFromString("(let ((f #'+)) (apply f (list 1 2)))"), wrappers))
			.isTrue();
		assertThat(LispMacroExpander
			.needsApplyRuntime(LispReader.readAllFromString("(apply (lambda (a b) (+ a b)) (list 1 2))"), wrappers))
			.isTrue();
		assertThat(LispMacroExpander.needsApplyRuntime(LispReader.readAllFromString("(apply #'nosuch (list 1))"),
				wrappers))
			.isTrue();
		// A flet/labels-bound name is a variable at the call site after the rewrite.
		assertThat(LispMacroExpander.needsApplyRuntime(
				LispReader.readAllFromString("(defun f (&rest xs) xs) (flet ((f (x) x)) (apply #'f (list 1)))"),
				wrappers))
			.isTrue();
		// multiple-value-call spreads through apply: literal known target stays direct.
		assertThat(LispMacroExpander
			.needsApplyRuntime(LispReader.readAllFromString("(multiple-value-call #'list (values 1 2))"), wrappers))
			.isFalse();
		assertThat(LispMacroExpander
			.needsApplyRuntime(LispReader.readAllFromString("(multiple-value-call f (values 1 2))"), wrappers))
			.isTrue();
		// Quoted data is not a call.
		assertThat(LispMacroExpander.needsApplyRuntime(LispReader.readAllFromString("(print '(apply f x))"), wrappers))
			.isFalse();
	}

	@Test
	void labelsDropsALocalNoSurvivingReferenceNames() {
		// After an upstream dead-branch prune deletes the only #'dead reference, the
		// expansion must not still construct its closure -- and dead's own reference to
		// deeper must not keep deeper either.
		LispCons form = (LispCons) LispReader
			.readAllFromString(
					"(labels ((keep (x) (helper x)) (helper (x) x) (dead (x) (deeper x)) (deeper (x) x)) (keep 1))")
			.get(0);
		String expanded = LispMacroExpander.expandLabels(form).print();
		assertThat(expanded).contains("_KEEP", "_HELPER").doesNotContain("_DEAD", "_DEEPER");
	}

	@Test
	void labelsKeepsALocalReferencedOnlyAsAValue() {
		LispCons form = (LispCons) LispReader.readAllFromString("(labels ((k (x) x)) (mapcar #'k (list 1)))").get(0);
		assertThat(LispMacroExpander.expandLabels(form).print()).contains("_K ");
	}

	@Test
	void aSelfRecursiveLabelsLocalNothingElseNamesIsDropped() {
		LispCons form = (LispCons) LispReader.readAllFromString("(labels ((lonely (x) (lonely x))) 42)").get(0);
		assertThat(LispMacroExpander.expandLabels(form).print()).doesNotContain("_LONELY");
	}

	@Test
	void fletDropsAnUnreferencedLocal() {
		LispCons form = (LispCons) LispReader.readAllFromString("(flet ((used (x) x) (unused (x) x)) (used 2))").get(0);
		String expanded = LispMacroExpander.expandFlet(form).print();
		assertThat(expanded).contains("_USED").doesNotContain("_UNUSED");
	}

	@Test
	void theRuntimeSubtypepTableIsEmittedInLatticeDeclarationOrder() {
		// A computed (subtypep a b) makes the compiler bake the whole type lattice as a
		// data table. Its order must be a function of the PROGRAM, never of the JVM run:
		// Map.of/Set.of randomize their iteration order once per process, so a lattice
		// built from one emits a different (still correct) module on every compile and
		// silently destroys byte-identity, this project's standard way of showing that a
		// change leaves unrelated programs alone. The order below is the declaration
		// order of LispMacroExpander.SUBTYPEP_PARENTS; asserting it fails loudly the day
		// the table goes back to a salted collection.
		List<String> names = ancestorTableNames("(defun st (a b) (subtypep a b)) (print (st 'fixnum 'integer))");
		assertThat(names).isNotEmpty();
		assertThat(names.stream().distinct().toList()).startsWith("FIXNUM", "INTEGER", "RATIONAL", "REAL", "NUMBER",
				"BIGNUM");
	}

	// NOTE: only the assertion above can catch a salted collection. ImmutableCollections'
	// SALT is constant for the lifetime of a process, so comparing two expansions inside
	// one JVM (below) cannot see it -- that test guards the other failure mode, a
	// compiler that accumulates state between compilations.
	@Test
	void theRuntimeSubtypepTableDoesNotDependOnWhatWasCompiledBefore() {
		String source = "(defclass sh () ()) (defclass ci (sh) ()) (defun st (a b) (subtypep a b)) (print (st 'ci 'sh))";
		assertThat(ancestorTableNames(source)).isEqualTo(ancestorTableNames(source));
	}

	@Test
	void theRuntimeSubtypepTableAnswersEveryPairAsSubtypepDoes() {
		// The table resolves each name of its universe once and asks the name arm about
		// every pair; subtypep resolves both names per call. Every kind of name the
		// universe holds is here: a class reached only by an AMBIGUOUS member (two
		// packages), a unique member, an alias, an :include chain, deftypes (one
		// circular) and the built-in lattice.
		String source = """
				(defclass pa::widget () ())
				(defclass pb::widget () ())
				(defclass pa::gadget (pa::widget) ())
				(defclass pa::solo () ())
				(setf (find-class 'pa::solo-alias) (find-class 'pa::solo))
				(defstruct pa::base a)
				(defstruct (pa::derived (:include pa::base)) b)
				(deftype pa::either () '(or pa::base pa::gadget))
				(deftype pa::small () '(integer 0 9))
				(deftype pa::circle () 'pa::circle)
				(defun st (a b) (subtypep a b))
				(print (st 'pa::gadget 'pa::widget))
				""";
		ClosRegistry registry = new ClosRegistry();
		List<LispVal> expanded = LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source),
				new HashMap<>(), registry);
		java.util.Map<String, List<String>> rows = new java.util.LinkedHashMap<>();
		for (LispVal entry : ancestorTableEntries(expanded)) {
			List<LispVal> parts = ((LispCons) entry).toList();
			List<String> ancestors = parts.subList(1, parts.size()).stream().map(v -> ((LispSymbol) v).name()).toList();
			for (LispVal sub : ((LispCons) parts.get(0)).toList()) {
				rows.put(((LispSymbol) sub).name(), ancestors);
			}
		}
		assertThat(rows.keySet()).contains("PA::WIDGET", "PB::WIDGET", "WIDGET", "GADGET", "PA::SOLO-ALIAS",
				"SOLO-ALIAS", "PA::DERIVED", "BASE", "PA::EITHER", "PA::CIRCLE", "FIXNUM");
		List<String> universe = List.copyOf(rows.keySet());
		for (String name : universe) {
			List<String> expected = universe.stream()
				.filter(candidate -> LispMacroExpander.subtypep(new LispSymbol(name), new LispSymbol(candidate),
						registry))
				.toList();
			assertThat(rows.get(name)).as(name).containsExactlyInAnyOrderElementsOf(expected);
		}
		assertThat(rows.get("GADGET")).contains("PA::WIDGET").doesNotContain("WIDGET", "PB::WIDGET");
		assertThat(rows.get("PA::DERIVED")).contains("PA::BASE", "PA::EITHER", "STRUCTURE-OBJECT");
		assertThat(rows.get("PA::CIRCLE")).containsExactly("PA::CIRCLE");
	}

	/**
	 * The entries of the emitted {@code %subtypep-ancestor-table%}, across its chunks.
	 */
	private static List<LispVal> ancestorTableEntries(List<LispVal> expanded) {
		List<LispVal> entries = new ArrayList<>();
		for (LispVal form : expanded) {
			if (!(form instanceof LispCons cons) || !(cons.cdr() instanceof LispCons rest)
					|| !(rest.car() instanceof LispSymbol var) || !LispNames.SUBTYPEP_ANCESTOR_TABLE.equals(var.name())
					|| !(rest.cdr() instanceof LispCons valueCell)) {
				continue;
			}
			LispVal value = valueCell.car();
			if (cons.car() instanceof LispSymbol op && LispNames.SETQ.equals(op.name())) {
				// (setq table (append 'chunk table))
				value = ((LispCons) ((LispCons) value).cdr()).car();
			}
			if (cons.car() instanceof LispSymbol op
					&& (LispNames.DEFVAR.equals(op.name()) || LispNames.SETQ.equals(op.name()))) {
				entries.addAll(((LispCons) ((LispCons) ((LispCons) value).cdr()).car()).toList());
			}
		}
		return entries;
	}

	private static String expandOne(String source) {
		LispCons form = (LispCons) LispReader.readAllFromString(source).get(0);
		return LispMacroExpander.expandFormat(form).print();
	}

	@Test
	void aFixedDecimalDirectiveIsOneCallAndNotAnInlinedScaleRoundSliceExpansion() {
		// ~F and ~$ used to expand INLINE into eight ordinary forms -- scale by 10^d,
		// `round` to a bignum-capable integer, `princ-to-string` it, then punch in a
		// decimal point with `subseq` and `%string-concat` -- and every generic
		// operation in that chain was emitted with its full numeric type ladder at
		// every site. On the WASM GC backend one ~,15F cost 7,616 bytes of caller body
		// and pulled in 22 runtime functions nothing else reached (.kb/format.md).
		// If any of those names comes back into the lowering, the cost comes back too.
		String fixed = expandOne("(format nil \"~,15F\" x)");
		assertThat(fixed).contains("%FIXED-DECIMAL");
		assertThat(fixed).doesNotContain("ROUND")
			.doesNotContain("PRINC-TO-STRING")
			.doesNotContain("SUBSEQ")
			.doesNotContain("%STRING-CONCAT");
		assertThat(expandOne("(format nil \"~,3$\" x)")).contains("%FIXED-DECIMAL").doesNotContain("ROUND");
	}

	@Test
	void aFixedDecimalPieceGoesOutThroughWriteStringNotPrinc() {
		// %fixed-decimal answers a string by construction, and `princ` of a value whose
		// type the compiler cannot see has to keep the whole generic printer reachable
		// -- on the WASM GC backend that is the float printer and several KB of
		// runtime, in a program that no longer prints a float anywhere.
		String out = expandOne("(format t \"pi = ~,15F~%\" x)");
		assertThat(out).contains("WRITE-STRING").contains("%FIXED-DECIMAL");
	}

	@Test
	void anExpanderBuiltStringPieceIsTheInternalConversionNotThePublicProducer() {
		// The public princ-to-string / prin1-to-string are flipped producers: on the
		// compile paths each call finishes with a mutable-result conversion. The
		// expander builds its own string PIECES with the same rendering -- every ~a /
		// ~s / ~d, map 'string's per-ELEMENT accumulator, a condition's default
		// message -- and spelling those with the public name had measured 17-80% on the
		// whole string-building family (.kb/string-write-runtime.md, "The fourth
		// round"). The pieces name the internal %princ-piece / %prin1-piece instead; if
		// a public name comes back into an expansion, that cost comes back with it.
		String pieces = expandOne("(format nil \"~a ~s ~d ~c\" a b c d)");
		assertThat(pieces).contains("%PRINC-PIECE").contains("%PRIN1-PIECE");
		assertThat(pieces).doesNotContain("(PRINC-TO-STRING ").doesNotContain("(PRIN1-TO-STRING ");
		String toStream = expandOne("(format t \"~a\" a)");
		assertThat(toStream).doesNotContain("PRINC-TO-STRING");
		LispCons map = (LispCons) LispReader.readAllFromString("(map 'string #'char-upcase s)").get(0);
		String accumulator = LispMacroExpander.expandMap(map).print();
		assertThat(accumulator).contains("%PRINC-PIECE").doesNotContain("PRINC-TO-STRING");
	}

	@Test
	void aStringWriteSiteIsOneCallAndNotAnInlinedSubseqConcatRebuild() {
		// A rank-1 array place may hold a string at run time, so (setf (aref v i) x)
		// carries a string arm -- and that arm used to inline the whole rebuild: two
		// subseqs (each an inline COPY LOOP on both compile paths), a `string` and two
		// %string-concats, about 8 KB of wasm PER SITE. An array-only program paid it
		// too: webgl-cube spent 203 of its 218 KB on 25 such sites. If any of those
		// names comes back into the site, the cost comes back with it.
		String site = LispMacroExpander
			.expandScharSetFunctional((LispCons) LispReader.readAllFromString("(%schar-set s i c)").get(0))
			.print();
		assertThat(site).contains("%SCHAR-SET-RUNTIME");
		assertThat(site).doesNotContain("SUBSEQ").doesNotContain("%STRING-CONCAT").doesNotContain("%ARRAYP");
	}

	@Test
	void theStringWriteRuntimeIsInjectedForAnArrayPlaceAndOmittedWithoutOne() {
		// The helper the site calls has to be THERE, and the scan that puts it there
		// runs on the pre-expansion program (expression expansion happens per form much
		// later and cannot add a top-level defun). Generous on purpose: any of the place
		// heads that can grow a string arm carries it.
		assertThat(injectsStringWriteRuntime("(defun f (v i) (setf (aref v i) #\\x))")).isTrue();
		assertThat(injectsStringWriteRuntime("(defun f (s i) (setf (char s i) #\\x))")).isTrue();
		assertThat(injectsStringWriteRuntime("(defun f (s i) (setf (elt s i) #\\x))")).isTrue();
		assertThat(injectsStringWriteRuntime("(defun f (s i) (setf (row-major-aref s i) #\\x))")).isTrue();
		assertThat(injectsStringWriteRuntime("(defun f (v) (car v))")).isFalse();
	}

	private static boolean injectsStringWriteRuntime(String source) {
		return LispMacroExpander
			.expandTopLevelDefinitions(LispReader.readAllFromString(source), new HashMap<>(), new ClosRegistry())
			.stream()
			.anyMatch(form -> form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name && LispNames.SCHAR_SET_RUNTIME.equals(name.name()));
	}

	@Test
	void aSubseqSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch() {
		// The array arm of subseq is a %array-alike plus a dotimes copy loop whose body
		// is
		// an aref and a %aset -- each of those a multi-arm representation dispatch of its
		// own, about 2.3 KB of wasm PER SITE, paid by string-only code too because
		// nothing
		// in (subseq s i j) says s is not a vector. If those names come back into the
		// site, the cost comes back with it.
		LispCons call = (LispCons) LispReader.readAllFromString("(subseq s i j)").get(0);
		String shared = requireNonNull(LispMacroExpander.expandSubseqCompat(call, true, true)).print();
		assertThat(shared).isEqualTo("(%SUBSEQ-RUNTIME S I J)");
		// Without the helper the site keeps the pre-existing inline lowering, so a gate
		// that under-predicts costs sharing and never correctness.
		String inline = requireNonNull(LispMacroExpander.expandSubseqCompat(call, true, false)).print();
		assertThat(inline).contains("%ARRAY-ALIKE").contains("%ASET").contains("%SUBSEQ-CORE");
	}

	@Test
	void theSharedSubseqDispatchAnswersTheSameThingAsTheInlinedOne() {
		// One body, two homes: the defun and the inline lowering are the same dispatch
		// over the same three names, so routing a site to the helper cannot change what
		// it answers. The defun's end is a PARAMETER, nil when the caller omitted it,
		// which is what lets one call-site shape serve (subseq s i) and (subseq s i j).
		String helper = LispMacroExpander.subseqRuntimeWrapper().print();
		// The parameter names are spelled in lower case on purpose: the reader upcases,
		// so a name in this shape cannot collide with anything the program can write.
		assertThat(helper).startsWith("(SETQ %SUBSEQ-RUNTIME (LAMBDA (|%ssr_seq| |%ssr_start| |%ssr_end|)");
		assertThat(helper).contains("STRINGP").contains("%ARRAYP").contains("%ARRAY-ALIKE").contains("%ASET");
		assertThat(helper).contains("(%SUBSEQ-CORE |%ssr_seq| |%ssr_start| |%ssr_end|)");
		// It must not call subseq itself, or the lowering would re-enter it forever.
		assertThat(helper).doesNotContain("(SUBSEQ ");
	}

	@Test
	void theGeneralArrayGateNamesTheOperatorsThatCanProduceOne() {
		// The one list of "this program can hold an array", shared by the JVM backend's
		// array-runtime gate and by the shared subseq helper's injection. A string-only
		// program must stay off it: the helper's copy arm names aref/%aset, and turning
		// the JVM array runtime on for a program with no array costs ~120 KB.
		assertThat(usesGeneralArray("(defun f (n) (make-array n))")).isTrue();
		assertThat(usesGeneralArray("(defun f (v i) (aref v i))")).isTrue();
		assertThat(usesGeneralArray("(defun f (n) (make-string n))")).isTrue();
		assertThat(usesGeneralArray("(defun f (x) (coerce x 'list))")).isTrue();
		assertThat(usesGeneralArray("(print #(1 2 3))")).isTrue();
		assertThat(usesGeneralArray("(defun f (s i j) (subseq s i j))")).isFalse();
		assertThat(usesGeneralArray("(defun f (l) (car l))")).isFalse();
	}

	private static boolean usesGeneralArray(String source) {
		return LispMacroExpander.programUsesGeneralArrayOp(LispReader.readAllFromString(source));
	}

	@Test
	void aCoerceSiteIsOneCallWhenTheProgramCarriesTheSharedConversions() {
		// Every generic sequence lowering (reverse / remove / position / count / sort /
		// ...) wraps its scan in the string/vector dispatch whose arms are literal
		// coerce forms, and each conversion inlines a whole map loop (the string
		// builder drags the value printer too) -- 8-10 KB of wasm PER SITE. If those
		// shapes come back into the site, the cost comes back with it.
		LispCons toList = (LispCons) LispReader.readAllFromString("(coerce x 'list)").get(0);
		assertThat(LispMacroExpander.expandCoerce(toList, true, true).print()).isEqualTo("(%SEQ-TO-LIST X)");
		// The string site is the shared call plus the two forms that give a
		// PROGRAM-written (coerce x 'string) its mutable identity: the argument passes
		// through by identity when it already is a string (CLHS), and only the BUILT
		// result carries the wrap. The map loop must still be gone.
		LispCons toString = (LispCons) LispReader.readAllFromString("(coerce x 'string)").get(0);
		String stringSite = LispMacroExpander.expandCoerce(toString, true, true).print();
		assertThat(stringSite).contains("(%SEQ-TO-STRING ").contains("(%STR-FRESH ").doesNotContain("(MAP ");
		LispCons toVector = (LispCons) LispReader.readAllFromString("(coerce x 'vector)").get(0);
		assertThat(LispMacroExpander.expandCoerce(toVector, true, true).print()).isEqualTo("(%SEQ-TO-VECTOR X)");
		// Without the trio the site keeps the pre-existing inline lowering, so a gate
		// that under-predicts costs sharing and never correctness.
		String inline = LispMacroExpander.expandCoerce(toList, true, false).print();
		assertThat(inline).contains("(MAP ").doesNotContain("%SEQ-TO-LIST");
		// A float result type is not a sequence conversion and never routes.
		LispCons toFloat = (LispCons) LispReader.readAllFromString("(coerce x 'double-float)").get(0);
		assertThat(LispMacroExpander.expandCoerce(toFloat, true, true).print()).doesNotContain("%SEQ-TO");
		// A computed type dispatches at runtime over the same three helpers.
		LispCons computed = (LispCons) LispReader.readAllFromString("(coerce x ty)").get(0);
		assertThat(LispMacroExpander.expandCoerce(computed, true, true).print()).contains("(%SEQ-TO-LIST ")
			.contains("(%SEQ-TO-STRING ")
			.contains("(%SEQ-TO-VECTOR ");
	}

	@Test
	void theSharedConversionsAnswerTheSameThingAsTheInlinedOnes() {
		// One body, two homes each: the trio defuns carry the same conversion the
		// inline lowering spells, so routing a site to them cannot change what it
		// answers. None may contain a literal coerce, or compiling the helper would
		// re-enter the routing forever.
		List<LispVal> trio = LispMacroExpander.seqConversionWrappers();
		assertThat(trio).hasSize(3);
		String toList = trio.get(0).print();
		// The parameter names are spelled in lower case on purpose: the reader upcases,
		// so a name in this shape cannot collide with anything the program can write.
		assertThat(toList).startsWith("(SETQ %SEQ-TO-LIST (LAMBDA (|%stl_x|)");
		assertThat(toList).contains("LISTP").contains("STRINGP").contains("(MAP ");
		String toString = trio.get(1).print();
		assertThat(toString).startsWith("(SETQ %SEQ-TO-STRING (LAMBDA (|%sts_x|)");
		assertThat(toString).contains("STRINGP").contains("(MAP ");
		String toVector = trio.get(2).print();
		assertThat(toVector).startsWith("(SETQ %SEQ-TO-VECTOR (LAMBDA (|%stv_x|)");
		assertThat(toVector).contains("MAKE-ARRAY").contains("%ASET");
		for (LispVal helper : trio) {
			assertThat(helper.print()).doesNotContain("(COERCE ");
		}
	}

	@Test
	void theSeqConversionGateNamesTheOperatorsThatCanReachAConversion() {
		// The injection gate for the trio: a program (or a generated wrapper body)
		// naming any generic sequence operator can hold a conversion site.
		// Over-predicting costs three unreachable defuns, which --optimize drops;
		// under-predicting costs the module its sharing and never its correctness.
		assertThat(usesSeqConversion("(defun f (x) (coerce x 'list))")).isTrue();
		assertThat(usesSeqConversion("(defun f (x) (reverse x))")).isTrue();
		assertThat(usesSeqConversion("(defun f (x) (position 1 x))")).isTrue();
		assertThat(usesSeqConversion("(defun f (x) (sort x #'<))")).isTrue();
		assertThat(usesSeqConversion("(defun f (x) (remove-duplicates x))")).isTrue();
		// nreverse is an in-place cons-chain splice with no representation dispatch,
		// and plain list operators reach no conversion at all.
		assertThat(usesSeqConversion("(defun f (l) (nreverse l))")).isFalse();
		assertThat(usesSeqConversion("(defun f (l) (car l))")).isFalse();
	}

	private static boolean usesSeqConversion(String source) {
		return LispMacroExpander.programUsesSeqConversion(LispReader.readAllFromString(source));
	}

	@Test
	void theSortRuntimeIsOneMergeSortEveryCompiledSortSiteCalls() {
		// The site is a call when the program carries the helper, and keeps its
		// backend's inline sort when it does not (.kb/sort.md). Only the plain
		// two-argument shape routes: a :key call is rewritten to stable-sort first, and
		// its inner sort is what reaches here.
		LispCons site = (LispCons) LispReader.readAllFromString("(sort l #'<)").get(0);
		LispVal routed = java.util.Objects.requireNonNull(LispMacroExpander.sortRuntimeCall(site, true));
		assertThat(routed.print()).isEqualTo("(%SORT-RUNTIME L #'<)");
		assertThat(LispMacroExpander.sortRuntimeCall(site, false)).isNull();
		// The helper: (setq %sort-runtime (lambda (list predicate) ...)), and its body
		// is a MERGE sort -- it splits the list, calls itself on both halves and relinks
		// with rplacd. A quadratic sort has none of that (.kb/sort.md).
		LispVal helper = LispMacroExpander.sortRuntimeWrapper();
		assertThat(((LispSymbol) ((LispCons) ((LispCons) helper).cdr()).car()).name()).isEqualTo("%SORT-RUNTIME");
		assertThat(helper.print()).contains("(%SORT-RUNTIME %SRT-LST %SRT-PRED)").contains("RPLACD");
		// The gate: a program that sorts, or whose stable-sort expansion will, and one
		// that does neither.
		assertThat(usesSort("(defun f (l) (sort l #'<))")).isTrue();
		assertThat(usesSort("(defun f (l) (stable-sort l #'< :key #'car))")).isTrue();
		assertThat(usesSort("(defun f (l) (reverse l))")).isFalse();
	}

	private static boolean usesSort(String source) {
		return LispMacroExpander.programUsesSort(LispReader.readAllFromString(source));
	}

	@Test
	void aDestructiveSequenceOperatorSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch() {
		// replace / fill / map-into each inline a whole runtime dispatch -- a
		// %row-major-aset copy loop, a list arm, an immutable-string rebuild made of
		// three subseqs and a concatenate -- 3.8 KB / 1.7 KB / 1.9 KB of wasm PER SITE.
		// chipz's update-window is four replaces and was 18 KB for thirty lines of Lisp.
		// If those shapes come back into the site, the cost comes back with it.
		LispCons replace = (LispCons) LispReader.readAllFromString("(replace a b :start1 i :end2 j)").get(0);
		assertThat(LispMacroExpander.expandReplace(replace, true, true).print())
			.isEqualTo("(%REPLACE-RUNTIME A B I NIL NIL J)");
		LispCons fill = (LispCons) LispReader.readAllFromString("(fill a v :start i)").get(0);
		assertThat(LispMacroExpander.expandFill(fill, true).print()).isEqualTo("(%FILL-RUNTIME A V I NIL)");
		// map-into routes to the helper of its own SOURCE-SEQUENCE COUNT: the loop body
		// is a funcall of exactly that many arguments, so one helper cannot serve two
		// counts without an apply (and with it the spread dispatcher).
		LispCons mapInto = (LispCons) LispReader.readAllFromString("(map-into r 'f x y)").get(0);
		assertThat(LispMacroExpander.expandMapInto(mapInto, true).print()).isEqualTo("(%MAP-INTO-RUNTIME-2 R #'F X Y)");
		// Without the helper each site keeps the pre-existing inline lowering, so a gate
		// that under-predicts costs sharing and never correctness.
		assertThat(LispMacroExpander.expandReplace(replace, true, false).print()).contains("%ROW-MAJOR-ASET")
			.contains("(SUBSEQ ")
			.doesNotContain("%REPLACE-RUNTIME");
		assertThat(LispMacroExpander.expandFill(fill, false).print()).contains("%ROW-MAJOR-ASET")
			.doesNotContain("%FILL-RUNTIME");
		assertThat(LispMacroExpander.expandMapInto(mapInto, false).print()).contains("FUNCALL")
			.doesNotContain("%MAP-INTO-RUNTIME");
		// A site whose DESTINATION the backend has proved to be an array calls the
		// array-arm-only helper instead, on the same call-site shape: it skips a %arrayp
		// test whose answer is already known, and leaves the wide dispatch (the list
		// rewrite, the immutable-string rebuild) reachable only from a site that needs
		// it.
		assertThat(LispMacroExpander.expandReplace(replace, true, true, true).print())
			.isEqualTo("(%REPLACE-RUNTIME-ARRAY A B I NIL NIL J)");
		assertThat(LispMacroExpander.expandFill(fill, true, true).print()).isEqualTo("(%FILL-RUNTIME-ARRAY A V I NIL)");
	}

	@Test
	void theSharedSequenceOpDispatchesAnswerTheSameThingAsTheInlinedOnes() {
		// One body, two homes each: the defun carries the same dispatch the inline
		// lowering spells, so routing a site to it cannot change what it answers. The
		// bounds are PARAMETERS, nil when the caller omitted the keyword, which is what
		// lets one call-site shape serve every keyword combination. None may call its
		// own operator, or compiling the helper would re-enter the routing forever.
		String replace = LispMacroExpander.replaceRuntimeWrapper().print();
		// The parameter names are spelled in lower case on purpose: the reader upcases,
		// so a name in this shape cannot collide with anything the program can write.
		assertThat(replace)
			.startsWith("(SETQ %REPLACE-RUNTIME (LAMBDA (|%rpr_1| |%rpr_2| |%rpr_s1| |%rpr_e1| |%rpr_s2| |%rpr_e2|)");
		// All three destination arms live here: the list cons-cell rewrite, the
		// immutable-string rebuild, and -- as a CALL, so the two helpers hold one copy
		// of it between them -- the destructive element store.
		assertThat(replace).contains("%ARRAYP")
			.contains("LISTP")
			.contains("RPLACA")
			.contains("(OR |%rpr_s1| 0)")
			.contains("CONCATENATE")
			.contains("(%REPLACE-RUNTIME-ARRAY |__rpl_1| |__rpl_2| |__rpl_s1| |__rpl_e1| |__rpl_s2| |__rpl_e2|)")
			.doesNotContain("%ROW-MAJOR-ASET");
		assertThat(replace).doesNotContain("(REPLACE ");
		// The array arm on its own: the same bounds, defaulted the same way (so the wide
		// helper may hand it either raw or already-defaulted ones), and no dispatch left.
		String replaceArray = LispMacroExpander.replaceArrayRuntimeWrapper().print();
		assertThat(replaceArray).startsWith(
				"(SETQ %REPLACE-RUNTIME-ARRAY (LAMBDA (|%rpa_1| |%rpa_2| |%rpa_s1| |%rpa_e1| |%rpa_s2| |%rpa_e2|)");
		assertThat(replaceArray).contains("%ROW-MAJOR-ASET")
			.contains("(OR |%rpa_s1| 0)")
			.doesNotContain("%ARRAYP")
			.doesNotContain("RPLACA")
			.doesNotContain("CONCATENATE")
			.doesNotContain("(REPLACE ");
		// A LIST source is walked with a cursor, as the list DESTINATION arm is: an elt
		// per element re-walks the list head, and elt is a whole representation dispatch
		// where car is one field read.
		assertThat(replaceArray).contains("NTHCDR").contains("(CAR |__rpl_sc|)").doesNotContain("(ELT ");
		String fill = LispMacroExpander.fillRuntimeWrapper().print();
		assertThat(fill).startsWith("(SETQ %FILL-RUNTIME (LAMBDA (|%flr_s| |%flr_v| |%flr_a| |%flr_b|)");
		assertThat(fill).contains("%ARRAYP")
			.contains("RPLACA")
			.contains("(OR |%flr_a| 0)")
			.contains("(%FILL-RUNTIME-ARRAY |__fll_s| |__fll_v| |__fll_a| |__fll_b|)")
			.doesNotContain("%ROW-MAJOR-ASET");
		assertThat(fill).doesNotContain("(FILL ");
		String fillArray = LispMacroExpander.fillArrayRuntimeWrapper().print();
		assertThat(fillArray).startsWith("(SETQ %FILL-RUNTIME-ARRAY (LAMBDA (|%fla_s| |%fla_v| |%fla_a| |%fla_b|)");
		assertThat(fillArray).contains("%ROW-MAJOR-ASET")
			.contains("(OR |%fla_a| 0)")
			.doesNotContain("%ARRAYP")
			.doesNotContain("RPLACA")
			.doesNotContain("CONCATENATE")
			.doesNotContain("(FILL ");
		String mapInto = LispMacroExpander.mapIntoRuntimeWrapper(2).print();
		assertThat(mapInto).startsWith("(SETQ %MAP-INTO-RUNTIME-2 (LAMBDA (|%mir_res| |%mir_fn| |%mir_s0| |%mir_s1|)");
		assertThat(mapInto).contains("FUNCALL").contains("RPLACA");
		assertThat(mapInto).doesNotContain("(MAP-INTO ");
	}

	@Test
	void theSequenceOpRuntimeGateInjectsOnlyTheHelpersThatWouldHaveACaller() {
		// The injection gate: one helper per operator the program (or a generated
		// wrapper body) names, gated apart because the three are independent lowerings
		// rather than three arms of one runtime dispatch. map-into answers one helper
		// per source-sequence count in use.
		// replace and fill each answer a PAIR: the wide dispatch and the array arm it
		// calls. Which of them a site can reach is decided per site, long after this
		// scan, so they travel together and the shaker drops whichever ends up without a
		// caller.
		assertThat(seqOpHelperNames("(defun f (a b) (replace a b))")).containsExactly("%REPLACE-RUNTIME",
				"%REPLACE-RUNTIME-ARRAY");
		assertThat(seqOpHelperNames("(defun f (a) (fill a 0))")).containsExactly("%FILL-RUNTIME",
				"%FILL-RUNTIME-ARRAY");
		assertThat(seqOpHelperNames("(defun f (r x y) (map-into r #'+ x y) (map-into r #'1+ x))"))
			.containsExactly("%MAP-INTO-RUNTIME-1", "%MAP-INTO-RUNTIME-2");
		assertThat(seqOpHelperNames("(defun f (l) (car l))")).isEmpty();
		// The replace and fill bodies call subseq, so they must count toward the
		// %subseq-runtime gate -- which is why a backend injects them BEFORE it and
		// scans them (.kb/sequence-op-runtimes.md).
		assertThat(LispMacroExpander.programUsesSubseq(
				LispMacroExpander.sequenceOpRuntimeWrappers(LispReader.readAllFromString("(replace a b)"))))
			.isTrue();
	}

	private static List<String> seqOpHelperNames(String source) {
		return LispMacroExpander.sequenceOpRuntimeWrappers(LispReader.readAllFromString(source))
			.stream()
			.map(form -> ((LispSymbol) ((LispCons) ((LispCons) form).cdr()).car()).name())
			.toList();
	}

	@Test
	void theDispatcherLastResortIsOneCallOfTheSharedNoApplicableMethodSignal() {
		// The last-resort error tail (condition construction plus the class-naming
		// render) re-inlined per dispatcher costs over a kilobyte EACH across a
		// library's synthesized slot accessors; a dispatcher instead calls the shared
		// signal defun, carrying only the message's per-generic TAIL (its 22-byte head
		// is one string per program, not one per generic), and expandTopLevelDefinitions
		// appends that defun exactly once. The tail keeps the " on " separator so the
		// literal cannot be read as a function-name designator by the dispatch gate.
		String source = "(defclass box () ((v :accessor box-v))) (defgeneric poke (x))"
				+ " (defmethod poke ((x box)) x)";
		List<LispVal> expanded = LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source),
				new HashMap<>(), new ClosRegistry());
		String printed = expanded.stream().map(LispVal::print).reduce("", (a, b) -> a + "\n" + b);
		assertThat(printed).contains("(%NO-APPLICABLE-METHOD \"POKE on \" X)")
			.doesNotContain("\"No applicable method: POKE on \"")
			.doesNotContain("\"POKE\"");
		assertThat(expanded.stream().filter(LispMacroExpanderTest::isNoApplicableMethodDefun)).hasSize(1);
		// A program without a generic in reach carries nothing.
		List<LispVal> plain = LispMacroExpander.expandTopLevelDefinitions(
				LispReader.readAllFromString("(defun f (x) x) (print (f 1))"), new HashMap<>(), new ClosRegistry());
		assertThat(plain.stream().filter(LispMacroExpanderTest::isNoApplicableMethodDefun)).isEmpty();
	}

	private static boolean isNoApplicableMethodDefun(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op && LispNames.DEFUN.equals(op.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
				&& LispNames.NO_APPLICABLE_METHOD_RUNTIME.equals(name.name());
	}

	private static LispMacroExpander.ConditionNarrowing narrowingOf(String source) {
		ClosRegistry registry = new ClosRegistry();
		List<LispVal> expanded = LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source),
				new HashMap<>(), registry);
		return LispMacroExpander.conditionNarrowing(expanded, registry, false);
	}

	@Test
	void conditionNarrowingCollectsLiteralTagsAndDeclinesTheRenderer() {
		// Literal datums only: the constructible set is exactly what is named, plus the
		// two families no site names but a landing pad can still build -- the
		// synthesized simple-* three and the raw-failure classes -- and
		// with no unrendered control in sight the runtime renderer is declined.
		LispMacroExpander.ConditionNarrowing narrowing = narrowingOf("""
				(define-condition my-error (error) ())
				(handler-case (error 'my-error) (error (e) (princ e)))
				(handler-case (error "plain ~a message" 1) (error (e) (princ e)))
				""");
		assertThat(narrowing.constructibleTags()).isNotNull()
			.contains("%class-MY-ERROR", "%class-SIMPLE-ERROR", "%class-TYPE-ERROR", "%class-DIVISION-BY-ZERO")
			.doesNotContain("%class-END-OF-FILE", "%class-UNBOUND-SLOT");
		assertThat(narrowing.declineRenderer()).isTrue();
	}

	@Test
	void theRendererFreeReportServesAControlWhoseOnlyDirectiveIsADoubledTilde() {
		// A rendered message reaches format-control as its text control (every ~
		// doubled), and %control-text -- the renderer-free arm -- undoubles it; so a
		// literal control whose only directive is ~~ is served without the renderer,
		// while any other directive still forces it.
		assertThat(narrowingOf("""
				(handler-case (error 'simple-error :format-control "a~~b") (error (e) (princ e)))
				""").declineRenderer()).isTrue();
		assertThat(narrowingOf("""
				(handler-case (error 'simple-error :format-control "a~%b") (error (e) (princ e)))
				""").declineRenderer()).isFalse();
		assertThat(narrowingOf("""
				(handler-case (error 'simple-error :format-control "a~") (error (e) (princ e)))
				""").declineRenderer()).isFalse();
		assertThat(LispMacroExpander.textControlForm(new LispString("a~b")).print()).isEqualTo("\"a~~b\"");
		assertThat(LispMacroExpander.textControlForm(new LispSymbol("M")).print()).isEqualTo("(%TEXT-CONTROL M)");
	}

	@Test
	void conditionNarrowingMarksProgramErrorConstructibleOnlyBehindALandingPad() {
		// The %program-error lowering constructs the class during BODY compilation,
		// after this scan, and only where a pad can observe it -- so a pad stands in
		// for the tag exactly as it does for the raw-failure classes.
		assertThat(narrowingOf("""
				(defun boom () (error 'simple-error :format-control "x"))
				(handler-case (boom) (error (e) (princ e)))
				""").constructibleTags()).contains("%class-PROGRAM-ERROR");
		assertThat(narrowingOf("""
				(defun boom () (error 'simple-error :format-control "x"))
				(boom)
				""").constructibleTags()).isNotNull().doesNotContain("%class-PROGRAM-ERROR");
	}

	@Test
	void restartModeNarrowsTheConditionReportRuntime() {
		// A handler-bind puts the program into restart mode. Every construction restart
		// mode adds is visible to the scan or always in the set: the signal hook's
		// instances are the synthesized simple-* three, restart-mode cerror keeps its
		// error datum, and the restart runtime's defuns are injected before the scan.
		// So the report partition keeps only what the program can construct, and the
		// runtime format renderer stays out.
		List<LispVal> expanded = LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString("""
				(defun main ()
				  (handler-bind ((error (lambda (c) (format t "saw ~a~%" c))))
				    (car 5)))
				(print (ignore-errors (main)))
				"""), new HashMap<>(), new ClosRegistry());
		String report = expanded.stream()
			.filter(form -> isDefunNamed(form, LispNames.CONDITION_REPORT_STR_INTERNAL))
			.map(LispVal::print)
			.findFirst()
			.orElseThrow();
		assertThat(report).contains("%class-TYPE-ERROR", "%class-SIMPLE-ERROR")
			.doesNotContain("%class-END-OF-FILE", "%class-UNBOUND-SLOT", "%class-FILE-ERROR");
		assertThat(expanded.stream().map(LispVal::print)).noneMatch(printed -> printed.contains(FormatRenderer.RENDER));
	}

	private static boolean isDefunNamed(LispVal form, String name) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op && LispNames.DEFUN.equals(op.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol defined
				&& name.equals(defined.name());
	}

	@Test
	void establishesLandingPadReadsOperatorPositionOnly() {
		assertThat(LispMacroExpander
			.establishesLandingPad(LispReader.readAllFromString("(defun f () (ignore-errors (g)))"))).isTrue();
		assertThat(LispMacroExpander
			.establishesLandingPad(LispReader.readAllFromString("(defun f () (handler-bind ((error #'h)) (g)))")))
			.isTrue();
		assertThat(LispMacroExpander
			.establishesLandingPad(LispReader.readAllFromString("(defun f () '(handler-case ignore-errors))")))
			.isFalse();
	}

	@Test
	void keywordTailProblemHonoursAllowOtherKeys() {
		// CLHS 3.4.1.4.1.1: the LEFTMOST :allow-other-keys pair decides, a true value
		// suppresses the check entirely, and the key itself is always accepted.
		assertThat(problemOf("(remove 'a lst :bad t)"))
			.isEqualTo("REMOVE expects keyword arguments :TEST/:TEST-NOT/:KEY, got: :BAD");
		assertThat(problemOf("(remove 'a lst :key)")).isEqualTo("REMOVE expects a value after :KEY");
		assertThat(problemOf("(remove 'a lst 'bad t)"))
			.isEqualTo("REMOVE expects keyword arguments :TEST/:TEST-NOT/:KEY, got: 'BAD");
		assertThat(problemOf("(remove 'a lst :bad t :allow-other-keys t)")).isNull();
		assertThat(problemOf("(remove 'a lst :allow-other-keys t 'bad t)")).isNull();
		assertThat(problemOf("(remove 'a lst :bad1 t :allow-other-keys t :bad2 t :allow-other-keys nil :bad3 t)"))
			.isNull();
		assertThat(problemOf("(remove 'a lst :allow-other-keys nil)")).isNull();
		assertThat(problemOf("(remove 'a lst :allow-other-keys nil :bad t)"))
			.isEqualTo("REMOVE expects keyword arguments :TEST/:TEST-NOT/:KEY, got: :BAD");
		// An odd tail is malformed whatever :allow-other-keys says.
		assertThat(problemOf("(remove 'a lst :allow-other-keys t :bad)"))
			.isEqualTo("REMOVE expects a value after :BAD");
	}

	@Test
	void aBoundedSequenceScanEmitsOnlyTheScaffoldingItsKeywordsAskFor() {
		// CLHS 17.2.1's bounding keywords are lowered PIECEWISE: a call that spells none
		// of them must expand to exactly the loop it always did -- no element index, no
		// count budget, no reversed walk, no length -- so every existing call site keeps
		// its size. The pieces then arrive one keyword at a time.
		String plain = removeExpansionOf("(remove 'a lst)");
		assertThat(plain).doesNotContain("|__remove_i|")
			.doesNotContain("|__remove_left|")
			.doesNotContain("(REVERSE ")
			.doesNotContain("(LENGTH ");
		String counted = removeExpansionOf("(remove 'a lst :count 2)");
		assertThat(counted).contains("|__remove_left|")
			.doesNotContain("|__remove_i|")
			.doesNotContain("(REVERSE ")
			.doesNotContain("(LENGTH ");
		// :start/:end are an INDEX bound on the walk, never a (subseq ...) handed to it:
		// the excluded elements must not reach the :test or :key designator, and a
		// subsequence would have to be built before the first element was looked at.
		String bounded = removeExpansionOf("(remove 'a lst :start 1 :end 3)");
		assertThat(bounded).contains("|__remove_i|")
			.doesNotContain("(SUBSEQ ")
			.doesNotContain("|__remove_left|")
			.doesNotContain("(REVERSE ")
			.doesNotContain("(LENGTH ");
		// :from-end is served by reversing the walked list and running the SAME forward
		// loop: the bounds are mapped into the reversed walk's own coordinates, and the
		// closing nreverse becomes conditional instead of a second loop appearing.
		String reversed = removeExpansionOf("(remove 'a lst :from-end t :count 1 :start 1)");
		assertThat(reversed).contains("(REVERSE |__remove_seq|)")
			.contains("(LENGTH |__remove_seq|)")
			.contains("|__remove_left|")
			.contains("|__remove_i|");
	}

	@Test
	void aComputedSequenceDesignatorBindsOnceBeforeTheScan() {
		// A LITERAL designator keeps being inlined into the loop body: evaluating it is
		// not observable, and the compilers' function-designator normalization is what
		// turns the #'name there into a direct call.
		String literal = removeExpansionOf("(remove 'a lst :test #'eq :key #'car)");
		assertThat(literal).contains("(FUNCALL #'EQ |__remove_item| (FUNCALL #'CAR (CAR |__remove_cur|)))")
			.doesNotContain("|__remove_k");
		// A COMPUTED one binds once, before the scan, in the order the call spells the
		// keywords -- the loop body sees only the variable (CLHS 3.1.2.1.2.3). The
		// sequence binds ahead of them, because the call spells it first.
		String computed = removeExpansionOf("(remove 'a lst :key (mk) :test (mt))");
		assertThat(computed).contains("(LET ((|__remove_a2| LST)) (LET ((|__remove_k3| (OR (MK) #'IDENTITY)))")
			.contains("(LET ((|__remove_k5| (OR (MT) #'EQL)))")
			.contains("(FUNCALL |__remove_k5| |__remove_item| (FUNCALL |__remove_k3| (CAR |__remove_cur|)))");
		// ... and it appears exactly once, which is the whole point: it used to be
		// evaluated per element.
		assertThat(computed.indexOf("(MK)")).isEqualTo(computed.lastIndexOf("(MK)"));
		assertThat(computed.indexOf("(MT)")).isEqualTo(computed.lastIndexOf("(MT)"));
		// A computed :test-not binds as the COMPLEMENTED :test, so the match form is the
		// same funcall either way, and a nil value is the absent designator.
		assertThat(removeExpansionOf("(remove 'a lst :test-not (mt))")).contains(
				"(LET ((|__remove_k3| (LET ((|__testnot_fn| (MT))) (IF |__testnot_fn| (LAMBDA (|__testnot_a| |__testnot_b|) (NOT (FUNCALL |__testnot_fn| |__testnot_a| |__testnot_b|))) #'EQL))))")
			.contains("(FUNCALL |__remove_k3| |__remove_item| (CAR |__remove_cur|))");
	}

	@Test
	void complementAnswersALambdaThatCoversEveryDesignatorArity() {
		// The function form is evaluated once, into a let the lambda closes over.
		String expansion = LispMacroExpander
			.expandComplement((LispCons) LispReader.readAllFromString("(complement (mk))").get(0))
			.print();
		assertThat(expansion).startsWith("(LET ((|__complement_fn| (MK)))");
		assertThat(expansion.indexOf("(MK)")).isEqualTo(expansion.lastIndexOf("(MK)"));
		// &optional with supplied-p flags plus an &rest arm: a complemented :test-not
		// runs once per element of the sequence being scanned, and a rest list would
		// cons there -- so only the fourth-argument-and-beyond arm applies.
		assertThat(expansion).contains(
				"(LAMBDA (&OPTIONAL (|__complement_a0| NIL |__complement_p0|) (|__complement_a1| NIL |__complement_p1|) (|__complement_a2| NIL |__complement_p2|) &REST |__complement_more|)");
		// One funcall arm per arity, the widest tested first -- an equality designator
		// (two arguments) is the common one and used to be an arity error.
		assertThat(expansion)
			.contains("(IF |__complement_p2| (FUNCALL |__complement_fn| |__complement_a0| |__complement_a1| "
					+ "|__complement_a2|)")
			.contains("(IF |__complement_p1| (FUNCALL |__complement_fn| |__complement_a0| |__complement_a1|)")
			.contains("(IF |__complement_p0| (FUNCALL |__complement_fn| |__complement_a0|)")
			.contains("(FUNCALL |__complement_fn|)");
		// The &rest arm applies past the fixed set -- the only APPLY in the expansion.
		assertThat(expansion).contains("(IF |__complement_more| (APPLY |__complement_fn| |__complement_a0| "
				+ "|__complement_a1| |__complement_a2| |__complement_more|)");
		assertThat(expansion.indexOf("APPLY")).isEqualTo(expansion.lastIndexOf("APPLY"));
		// A site whose arity is statically two spells the two, rather than paying the
		// dispatch per element: the first-class remove/position wrappers and KeywordTail.
		assertThat(LispMacroExpander.twoArgumentComplement(LispReader.readAllFromString("(mk)").get(0)).print())
			.isEqualTo("(LET ((|__complement2_fn| (MK))) (LAMBDA (|__complement2_a| |__complement2_b|) "
					+ "(NOT (FUNCALL |__complement2_fn| |__complement2_a| |__complement2_b|))))");
	}

	@Test
	void aBoundedRemoveDuplicatesLooksForTheDuplicateInsideTheWindow() {
		// The piecewise rule, here too: a call that spells no bound and a LITERAL
		// direction keeps the member-over-the-tail loop it always expanded to -- no
		// element index, no inner bounded scan.
		String plain = dedupExpansionOf("(remove-duplicates lst)");
		assertThat(plain).contains("(MEMBER (CAR |__rd_cur|) (CDR |__rd_cur|))")
			.doesNotContain("|__rd_i|")
			.doesNotContain("(POSITION ");
		assertThat(dedupExpansionOf("(remove-duplicates lst :from-end t)"))
			.contains("(MEMBER (CAR |__rd_cur|) |__rd_acc|)")
			.doesNotContain("|__rd_i|");
		// :start/:end bound which elements are CONSIDERED: one outside the window is
		// kept verbatim (the guard's else arm accumulates instead of skipping) and the
		// duplicate is looked for by INDEX inside the window, never in a (subseq ...).
		String bounded = dedupExpansionOf("(remove-duplicates lst :start 1 :end 3)");
		assertThat(bounded)
			.contains("(IF (AND (>= |__rd_i| |__rd_lo|) (IF |__rd_hi| (< |__rd_i| |__rd_hi|) T)) "
					+ "(IF (POSITION (CAR |__rd_cur|) |__seq_lst| :START (+ |__rd_i| 1) :END |__rd_hi|) NIL "
					+ "(SETQ |__rd_acc| (CONS (CAR |__rd_cur|) |__rd_acc|))) "
					+ "(SETQ |__rd_acc| (CONS (CAR |__rd_cur|) |__rd_acc|)))")
			.doesNotContain("(SUBSEQ ");
		// :from-end moves that window to the other side of the element, keeping the
		// FIRST occurrence ...
		assertThat(dedupExpansionOf("(remove-duplicates lst :start 1 :from-end t)"))
			.contains("(POSITION (CAR |__rd_cur|) |__seq_lst| :START |__rd_lo| :END |__rd_i|)");
		// ... so a COMPUTED direction is a branch over those two index bounds rather
		// than over two loops, which is why it no longer has to be a literal.
		assertThat(dedupExpansionOf("(remove-duplicates lst :from-end (f))")).contains(
				"(POSITION (CAR |__rd_cur|) |__seq_lst| :START (IF |__rd_dir| 0 (+ |__rd_i| 1)) :END (IF |__rd_dir| |__rd_i| NIL))");
		// The :test-not pair forwards to the inner scan as it does to the inner member,
		// and the :key is applied to the candidate before it (position's own :key covers
		// the sequence side only).
		assertThat(dedupExpansionOf("(remove-duplicates lst :end 3 :test-not #'eq :key #'car)")).contains(
				"(POSITION (FUNCALL #'CAR (CAR |__rd_cur|)) |__seq_lst| :START (+ |__rd_i| 1) :END |__rd_hi| :TEST-NOT #'EQ :KEY #'CAR)");
	}

	@Test
	void aCountedButlastEmitsTheLengthPassAndTheUncountedOneDoesNot() {
		// butlast's optional count cannot bound the walk from the far end without a
		// second cursor running n cells ahead, and n is allowed to be enormous (ANSI
		// passes most-positive-fixnum + 1), so the counted spelling counts the conses
		// first and subtracts. The UNCOUNTED spelling keeps its own one-pass loop --
		// the piecewise rule of .kb/sequence-bounding-keywords.md, here too: a call that
		// spells no count pays for none.
		String plain = butlastExpansionOf("(butlast lst)");
		assertThat(plain).doesNotContain("|__butlast_len|").doesNotContain("|__butlast_keep|");
		String counted = butlastExpansionOf("(butlast lst 2)");
		assertThat(counted).contains("|__butlast_len|").contains("|__butlast_keep|").contains("|__butlast_n|");
	}

	private static String butlastExpansionOf(String call) {
		return LispMacroExpander.expandButlast((LispCons) LispReader.readAllFromString(call).get(0)).print();
	}

	private static String removeExpansionOf(String call) {
		return LispMacroExpander.expandRemove((LispCons) LispReader.readAllFromString(call).get(0)).print();
	}

	private static String dedupExpansionOf(String call) {
		return LispMacroExpander.expandRemoveDuplicates((LispCons) LispReader.readAllFromString(call).get(0)).print();
	}

	private static @org.jspecify.annotations.Nullable String problemOf(String call) {
		LispCons cons = (LispCons) LispReader.readAllFromString(call).get(0);
		return LispMacroExpander.keywordTailProblem("REMOVE", cons.toList(), 3,
				List.of(LispNames.TEST_KEYWORD, LispNames.TEST_NOT_KEYWORD, LispNames.KEY_KEYWORD));
	}

	@Test
	void anExplicitFormatControlInitargForcesTheRenderer() {
		// An explicit :format-control initarg can carry directives into the slot, so
		// the identity fast path is not enough and the renderer stays.
		LispMacroExpander.ConditionNarrowing narrowing = narrowingOf("""
				(handler-case (error 'simple-error :format-control "x ~a" :format-arguments (list 1))
				  (error (e) (princ e)))
				""");
		assertThat(narrowing.constructibleTags()).isNotNull();
		assertThat(narrowing.declineRenderer()).isFalse();
	}

	@Test
	void aComputedDatumMakesTheConditionSetUnknowable() {
		// (error datum) with a computed datum can name any condition class.
		LispMacroExpander.ConditionNarrowing narrowing = narrowingOf("""
				(defun boom (which) (error which))
				(handler-case (boom 'end-of-file) (error (e) (princ e)))
				""");
		assertThat(narrowing.constructibleTags()).isNull();
		assertThat(narrowing.declineRenderer()).isFalse();
	}

	@Test
	void aDirectiveFreeLiteralFormatControlStillDeclinesTheRenderer() {
		LispMacroExpander.ConditionNarrowing narrowing = narrowingOf("""
				(handler-case (error 'simple-error :format-control "no directives here")
				  (error (e) (princ e)))
				""");
		assertThat(narrowing.declineRenderer()).isTrue();
	}

	/** Whether the compile path routes condition reports for this program. */
	private static boolean routesReports(String source) {
		ClosRegistry registry = new ClosRegistry();
		LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source), new HashMap<>(), registry);
		return registry.routesConditionReports();
	}

	/**
	 * The same answer under {@code lazyConditionMessages} (the wasm-GC backends, whose
	 * signal path renders no message): the gate narrows to "can program code HOLD a
	 * condition".
	 */
	private static boolean routesReportsLazy(String source) {
		ClosRegistry registry = new ClosRegistry();
		LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source), new HashMap<>(), registry,
				null, false, true);
		return registry.routesConditionReports();
	}

	@Test
	void aThrowOnlyConstructionDoesNotRouteReportsWhereMessagesAreLazy() {
		// A typed signal builds an instance only to THROW it: on a backend that never
		// renders the signal message (an uncaught condition is a bare trap), nothing
		// about the site is observable through the report machinery -- while the
		// message-rendering backends keep routing for the eager signal text.
		String typedSignal = "(define-condition zc (error) ()) (defun f () (error 'zc)) (print (f))";
		assertThat(routesReportsLazy(typedSignal)).isFalse();
		assertThat(routesReports(typedSignal)).isTrue();
		// The keyword constructor define-condition splices returns its instance, but
		// with no reference to it the instance cannot reach program hands.
		assertThat(routesReportsLazy("(define-condition zc (error) ()) (print 1)")).isFalse();
		// signal unwinds or answers nil; the instance is never held.
		assertThat(routesReportsLazy("(define-condition zc (error) ()) (print (signal 'zc))")).isFalse();
	}

	@Test
	void anEntryReportRendersEveryBuildableConditionButRoutesNoPrinterUnlessOneIsHeld() {
		// The entry report reads every condition that escapes, so the renderer is in as
		// soon as one can be built; a printing operator can only be handed one program
		// code holds.
		ClosRegistry thrown = entryReportRegistry("(define-condition zc (error) ()) (defun f () (error 'zc)) (f)");
		assertThat(thrown.routesConditionReports()).isTrue();
		assertThat(thrown.printsConditionReports()).isFalse();
		ClosRegistry held = entryReportRegistry("(define-condition zc (error) ()) (print (make-condition 'zc))");
		assertThat(held.routesConditionReports()).isTrue();
		assertThat(held.printsConditionReports()).isTrue();
	}

	@Test
	void aDeclinedRendererKeepsNoFunctionControlArmInAnyMode() {
		// Every control is a directive-free literal, so none is a function: the arm that
		// funcalls one -- a runtime designator -- is gone in every signal-messages mode,
		// not only under ENTRY_REPORT.
		String source = "(define-condition zc (error) ()) (defun f () (error 'zc)) (f)";
		assertThat(formatConditionDefun(source, SignalMessages.ENTRY_REPORT)).doesNotContain("FUNCALL");
		assertThat(formatConditionDefun(source, SignalMessages.RENDERED)).doesNotContain("FUNCALL");
	}

	@Test
	void aForcedRendererKeepsTheFunctionControlArm() {
		// An explicit :format-control initarg forces the renderer, so the control CAN be
		// a
		// function at run time and the arm must stay, in every mode.
		String source = "(defun f (x) (error 'simple-error :format-control x)) (f (read))";
		assertThat(formatConditionDefun(source, SignalMessages.ENTRY_REPORT)).contains("FUNCALL");
		assertThat(formatConditionDefun(source, SignalMessages.RENDERED)).contains("FUNCALL");
	}

	private static ClosRegistry entryReportRegistry(String source) {
		ClosRegistry registry = new ClosRegistry();
		LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source), new HashMap<>(), registry,
				null, false, SignalMessages.ENTRY_REPORT, null);
		return registry;
	}

	private static String formatConditionDefun(String source, SignalMessages signalMessages) {
		return LispMacroExpander
			.expandTopLevelDefinitions(LispReader.readAllFromString(source), new HashMap<>(), new ClosRegistry(), null,
					false, signalMessages, null)
			.stream()
			.filter(form -> form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name && LispNames.FORMAT_CONDITION_INTERNAL.equals(name.name()))
			.findFirst()
			.map(LispVal::toString)
			.orElseThrow();
	}

	@Test
	void aHeldConditionStillRoutesReportsWhereMessagesAreLazy() {
		// A handler-case clause that names its condition hands it to program code.
		assertThat(routesReportsLazy("""
				(define-condition zc (error) ())
				(print (handler-case (error 'zc) (error (e) (princ-to-string e))))
				""")).isTrue();
		// make-condition returns the instance.
		assertThat(routesReportsLazy("(define-condition zc (error) ()) (print (make-condition 'zc))")).isTrue();
		// make-instance of a condition class reaches the spliced constructor.
		assertThat(routesReportsLazy("(define-condition zc (error) ()) (print (make-instance 'zc))")).isTrue();
		// A typed warn PRINTS a message that renders through the report machinery.
		assertThat(routesReportsLazy("(define-condition zw (warning) ()) (warn 'zw)")).isTrue();
	}

	@Test
	void aHandlerCaseThatNeverNamesItsConditionDoesNotRouteReports() {
		// The handler prologue synthesizes a simple-error for ANY caught trap, so an
		// instance really is built -- but with no clause naming it, nothing in the
		// program can hand one to a printing operator, and the report renderer plus the
		// string machinery it anchors are three quarters of a bare handler-case
		// artifact.
		assertThat(routesReports("(print (handler-case (+ 1 2) (error () 0)))")).isFalse();
		// Bound but never mentioned is the same thing.
		assertThat(routesReports("(print (handler-case (+ 1 2) (error (e) 0)))")).isFalse();
		// :no-error binds the protected form's VALUES, never a condition.
		assertThat(routesReports("(print (handler-case (+ 1 2) (error () 0) (:no-error (v) v)))")).isFalse();
		// Mentioned: it can reach a print, so the routing stays.
		assertThat(routesReports("(print (handler-case (+ 1 2) (error (e) (princ e))))")).isTrue();
		// A clause head is a TYPE SPECIFIER, not a call -- reading (error (e) ...) as a
		// signal site with initargs is what made every handler-case route.
		assertThat(routesReports("(print (handler-case (+ 1 2) (error (e) (list e))))")).isTrue();
		// A real condition source in the same program still routes.
		assertThat(routesReports("""
				(define-condition my-error (error) ())
				(print (handler-case (error 'my-error) (error () 0)))
				""")).isTrue();
	}

	@Test
	void aHandlerBindHandlerThatCanSeeItsConditionRoutesReports() {
		// Every handler-bind handler is CALLED with the instance, so a lambda whose body
		// mentions its parameter holds it -- on the message-rendering backends and where
		// messages are lazy alike.
		String named = "(print (handler-bind ((error (lambda (c) (format t \"~a\" c)))) (car 5)))";
		assertThat(routesReports(named)).isTrue();
		assertThat(routesReportsLazy(named)).isTrue();
		assertThat(routesReports("(print (handler-bind ((error #'(lambda (c) (princ c)))) (car 5)))")).isTrue();
		// A named function (or any computed handler) can do anything with it.
		assertThat(routesReports("(defun h (c) (princ c)) (print (handler-bind ((error #'h)) (car 5)))")).isTrue();
		assertThat(routesReports("(print (handler-bind ((error 'h)) (car 5)))")).isTrue();
		assertThat(routesReports("(print (handler-bind ((error (lambda (&rest r) (princ r)))) (car 5)))")).isTrue();
		// A handler that never mentions its parameter cannot hand it to a printer.
		assertThat(routesReports("(print (handler-bind ((error (lambda (c) (print 1)))) (car 5)))")).isFalse();
		assertThat(routesReportsLazy("(print (handler-bind ((error (lambda (c) (print 1)))) (car 5)))")).isFalse();
	}

	@Test
	void ignoreErrorsRoutesReportsOnlyWhereASecondValueCanBeRead() {
		// (ignore-errors f) hands the condition back as its SECONDARY value, which
		// travels through the %mv-spill global that only a consumer ever reads.
		assertThat(routesReports("(print (ignore-errors (+ 1 2)))")).isFalse();
		assertThat(routesReports("(multiple-value-bind (v c) (ignore-errors (+ 1 2)) (princ c))")).isTrue();
		assertThat(routesReports("(print (nth-value 1 (ignore-errors (+ 1 2))))")).isTrue();
	}

	@Test
	void printsUnderAPackageIsThePackageGateOfThePrinter() {
		// A top-level in-package resolves to (setq *package* :P): one that leaves
		// cl-user, or any other mention of the variable, means the printer must consult
		// the current package (CLHS 22.1.3.3.1); assigning cl-user alone does not.
		assertThat(LispMacroExpander.printsUnderAPackage(LispReader.readAllFromString("(setq *package* :APP)")))
			.isTrue();
		assertThat(LispMacroExpander.printsUnderAPackage(LispReader.readAllFromString("(print *package*)"))).isTrue();
		assertThat(LispMacroExpander.printsUnderAPackage(LispReader.readAllFromString("(setq *package* :CL-USER)")))
			.isFalse();
		assertThat(LispMacroExpander.printsUnderAPackage(LispReader.readAllFromString("(print 'x)"))).isFalse();
		// The renderer's own defuns read the variables they honor; they are not the
		// program naming one.
		assertThat(LispMacroExpander
			.mentionsPrintControlVariable(LispReader.readAllFromString("(defun %print-cased (x) *print-case*)")))
			.isFalse();
		assertThat(LispMacroExpander
			.mentionsPrintControlVariable(LispReader.readAllFromString("(let ((*print-case* :downcase)) 1)"))).isTrue();
		// The route flips for a package program only once the renderer is spliced.
		assertThat(LispMacroExpander.usesPrintControls(LispReader.readAllFromString("(setq *package* :APP)")))
			.isFalse();
		assertThat(LispMacroExpander
			.usesPrintControls(LispReader.readAllFromString("(defun %print-cased (x e) x) (setq *package* :APP)")))
			.isTrue();
	}

}
