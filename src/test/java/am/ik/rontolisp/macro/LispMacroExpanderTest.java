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
		// -- on the WASM GC backend that is the float digit printer and its ~3.8 KB of
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

}
