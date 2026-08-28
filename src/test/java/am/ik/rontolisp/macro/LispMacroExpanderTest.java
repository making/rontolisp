package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
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
		assertThat(helper).startsWith("(SETQ %SUBSEQ-RUNTIME (LAMBDA (%ssr_seq %ssr_start %ssr_end)");
		assertThat(helper).contains("STRINGP").contains("%ARRAYP").contains("%ARRAY-ALIKE").contains("%ASET");
		assertThat(helper).contains("(%SUBSEQ-CORE %ssr_seq %ssr_start %ssr_end)");
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
		LispCons toString = (LispCons) LispReader.readAllFromString("(coerce x 'string)").get(0);
		assertThat(LispMacroExpander.expandCoerce(toString, true, true).print()).isEqualTo("(%SEQ-TO-STRING X)");
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
		assertThat(toList).startsWith("(SETQ %SEQ-TO-LIST (LAMBDA (%stl_x)");
		assertThat(toList).contains("LISTP").contains("STRINGP").contains("(MAP ");
		String toString = trio.get(1).print();
		assertThat(toString).startsWith("(SETQ %SEQ-TO-STRING (LAMBDA (%sts_x)");
		assertThat(toString).contains("STRINGP").contains("(MAP ");
		String toVector = trio.get(2).print();
		assertThat(toVector).startsWith("(SETQ %SEQ-TO-VECTOR (LAMBDA (%stv_x)");
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
		assertThat(routed.print()).isEqualTo("(%SORT-RUNTIME L (FUNCTION <))");
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
		assertThat(LispMacroExpander.expandMapInto(mapInto, true).print())
			.isEqualTo("(%MAP-INTO-RUNTIME-2 R (FUNCTION F) X Y)");
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
			.startsWith("(SETQ %REPLACE-RUNTIME (LAMBDA (%rpr_1 %rpr_2 %rpr_s1 %rpr_e1 %rpr_s2 %rpr_e2)");
		// All three destination arms live here: the list cons-cell rewrite, the
		// immutable-string rebuild, and -- as a CALL, so the two helpers hold one copy
		// of it between them -- the destructive element store.
		assertThat(replace).contains("%ARRAYP")
			.contains("LISTP")
			.contains("RPLACA")
			.contains("(OR %rpr_s1 0)")
			.contains("CONCATENATE")
			.contains("(%REPLACE-RUNTIME-ARRAY __rpl_1 __rpl_2 __rpl_s1 __rpl_e1 __rpl_s2 __rpl_e2)")
			.doesNotContain("%ROW-MAJOR-ASET");
		assertThat(replace).doesNotContain("(REPLACE ");
		// The array arm on its own: the same bounds, defaulted the same way (so the wide
		// helper may hand it either raw or already-defaulted ones), and no dispatch left.
		String replaceArray = LispMacroExpander.replaceArrayRuntimeWrapper().print();
		assertThat(replaceArray)
			.startsWith("(SETQ %REPLACE-RUNTIME-ARRAY (LAMBDA (%rpa_1 %rpa_2 %rpa_s1 %rpa_e1 %rpa_s2 %rpa_e2)");
		assertThat(replaceArray).contains("%ROW-MAJOR-ASET")
			.contains("(OR %rpa_s1 0)")
			.doesNotContain("%ARRAYP")
			.doesNotContain("RPLACA")
			.doesNotContain("CONCATENATE")
			.doesNotContain("(REPLACE ");
		// A LIST source is walked with a cursor, as the list DESTINATION arm is: an elt
		// per element re-walks the list head, and elt is a whole representation dispatch
		// where car is one field read.
		assertThat(replaceArray).contains("NTHCDR").contains("(CAR __rpl_sc)").doesNotContain("(ELT ");
		String fill = LispMacroExpander.fillRuntimeWrapper().print();
		assertThat(fill).startsWith("(SETQ %FILL-RUNTIME (LAMBDA (%flr_s %flr_v %flr_a %flr_b)");
		assertThat(fill).contains("%ARRAYP")
			.contains("RPLACA")
			.contains("(OR %flr_a 0)")
			.contains("(%FILL-RUNTIME-ARRAY __fll_s __fll_v __fll_a __fll_b)")
			.doesNotContain("%ROW-MAJOR-ASET");
		assertThat(fill).doesNotContain("(FILL ");
		String fillArray = LispMacroExpander.fillArrayRuntimeWrapper().print();
		assertThat(fillArray).startsWith("(SETQ %FILL-RUNTIME-ARRAY (LAMBDA (%fla_s %fla_v %fla_a %fla_b)");
		assertThat(fillArray).contains("%ROW-MAJOR-ASET")
			.contains("(OR %fla_a 0)")
			.doesNotContain("%ARRAYP")
			.doesNotContain("RPLACA")
			.doesNotContain("CONCATENATE")
			.doesNotContain("(FILL ");
		String mapInto = LispMacroExpander.mapIntoRuntimeWrapper(2).print();
		assertThat(mapInto).startsWith("(SETQ %MAP-INTO-RUNTIME-2 (LAMBDA (%mir_res %mir_fn %mir_s0 %mir_s1)");
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
		return LispMacroExpander.conditionNarrowing(expanded, registry, false, false);
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
	void ignoreErrorsRoutesReportsOnlyWhereASecondValueCanBeRead() {
		// (ignore-errors f) hands the condition back as its SECONDARY value, which
		// travels through the %mv-spill global that only a consumer ever reads.
		assertThat(routesReports("(print (ignore-errors (+ 1 2)))")).isFalse();
		assertThat(routesReports("(multiple-value-bind (v c) (ignore-errors (+ 1 2)) (princ c))")).isTrue();
		assertThat(routesReports("(print (nth-value 1 (ignore-errors (+ 1 2))))")).isTrue();
	}

}
