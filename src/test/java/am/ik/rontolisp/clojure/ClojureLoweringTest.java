package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureMacroTime;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The lowering table of {@code .kb/clojure-frontend.md}, row by row, as the Common Lisp
 * the front end emits. What the emitted forms DO on each backend is
 * {@code ClojureSpecE2eTest}'s business.
 */
class ClojureLoweringTest {

	private static String lowered(String source) {
		List<LispVal> forms = Clojure.read(source, null);
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	private static final String FALSE_BINDING = "(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)\n";

	@Test
	void everyIdentifierManglesBehindThePrefix() {
		assertThat(lowered("(def x 1) x")).isEqualTo(FALSE_BINDING + "(SETQ |c%x| 1)\n|c%x|");
		assertThat(ClojureLowering.mangle("a:b")).isEqualTo("c%a%cb");
		assertThat(ClojureLowering.mangle("a%b")).isEqualTo("c%a%%b");
	}

	@Test
	void defnIsADefunCalledDirectly() {
		assertThat(lowered("(defn f [x] x) (f 1)")).isEqualTo(FALSE_BINDING + "(DEFUN |c%f| (|c%x|) |c%x|)\n(|c%f| 1)");
		assertThat(lowered("(defn f [x] x) f")).isEqualTo(FALSE_BINDING + "(DEFUN |c%f| (|c%x|) |c%x|)\n#'|c%f|");
	}

	@Test
	void headPositionCallsToVariablesReachTheValueCell() {
		// a parameter may hold a collection, so its call goes through the prelude
		// dispatcher (which funcalls real functions); a let/def binding of a real
		// function stays a direct funcall, like a declared name stays direct
		assertThat(lowered("(defn call-it [f x] (f x))"))
			.contains("(DEFUN |c%call-it| (|c%f| |c%x|) (RONTOLISP::%CLOJURE-CALL |c%f| (LIST |c%x|)))");
		assertThat(lowered("(let [g inc] (g 1))")).contains("(FUNCALL |c%g| 1)");
		assertThat(lowered("(let [s #{:h}] (s :h))")).contains("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("(def v (fn [x] x)) (v 1)")).contains("(FUNCALL |c%v| 1)");
		assertThat(lowered("(declare u) (u 1)")).contains("(|c%u| 1)").doesNotContain("FUNCALL");
	}

	@Test
	void letIsSequentialAndLoopIsALabelsSelfCall() {
		assertThat(lowered("(let [x 1 y x] y)")).isEqualTo(FALSE_BINDING + "(LET* ((|c%x| 1) (|c%y| |c%x|)) |c%y|)");
		assertThat(lowered("(loop [a 0] (recur 1))")).contains("LABELS");
		assertThatThrownBy(() -> Clojure.read("(recur 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("recur outside loop");
	}

	@Test
	void fnAndAnonFnAreLambdas() {
		assertThat(lowered("((fn [a b] (+ a b)) 1 2)"))
			.isEqualTo(FALSE_BINDING + "(FUNCALL (LAMBDA (|c%a| |c%b|) (+ |c%a| |c%b|)) 1 2)");
		assertThat(lowered("(map #(* % %) '(1 2))")).contains("%CLOJURE-MAP").contains("NTH");
		assertThatThrownBy(() -> Clojure.read("%", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("outside the anon form");
	}

	@Test
	void coreCallsLowerToTheirCommonLispNames() {
		assertThat(lowered("(map + '(1 2))")).contains("%CLOJURE-MAP").contains("#'+");
		assertThat(lowered("(map + '(1 2) '(3 4))")).contains("%CLOJURE-MAP");
		assertThat(lowered("(filter odd? '(1 2 3))")).contains("%CLOJURE-FILTER");
		assertThat(lowered("(reduce + 0 '(1 2))")).contains(":INITIAL-VALUE").contains("%CLOJURE-SEQ");
		assertThat(lowered("(apply max '(3 9 4))")).contains("APPLY").contains("%CLOJURE-SEQ");
		assertThat(lowered("(concat '(1 2) [3 4])")).contains("%CLOJURE-CONCAT");
		assertThat(lowered("(concat)")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("(= 1 1)")).contains("LABELS").contains("(EQUAL");
		assertThat(lowered("(= 1 1)")).contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(cond (= 1 2) :one :else :fallback)")).contains(":C%KEYWORD").contains("fallback");
	}

	@Test
	void seqsCoerceCollectionsThroughOneSharedView() {
		assertThat(lowered("(seq [1 2])")).contains("%CLOJURE-SEQ");
		assertThat(lowered("(first [1 2])")).contains("(CAR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(rest [1 2])")).contains("(CDR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(next [1 2])")).contains("(CDR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(cons 0 [1 2])")).contains("%CLOJURE-CONS");
		assertThat(lowered("(first '(1 2))")).contains("(CAR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(seq {:a 1})")).contains("%CLOJURE-SEQ");
	}

	@Test
	void takeDropAndFiniteRangeAreLazyAware() {
		assertThat(lowered("(take 2 [1 2])")).contains("%CLOJURE-TAKE");
		assertThat(lowered("(drop 2 [1 2])")).contains("%CLOJURE-DROP");
		assertThat(lowered("(range 3)")).contains("LABELS").contains("REVERSE");
		assertThat(lowered("(range 1 5 2)")).contains("LABELS");
		assertThatThrownBy(() -> Clojure.read("(range)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("infinite range is not supported: range needs an end");
		assertThatThrownBy(() -> Clojure.read("(take 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("take takes a count and a collection");
		assertThatThrownBy(() -> Clojure.read("(drop 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("drop takes a count and a collection");
	}

	@Test
	void lazySeqsLowerToMemoizedThunks() {
		assertThat(lowered("(lazy-seq (cons 1 nil))")).contains("%CLOJURE-MAKE-LAZY").contains("LAMBDA");
		assertThat(lowered("(lazy-cat [0 1] [2])")).contains("%CLOJURE-CONCAT").contains("%CLOJURE-MAKE-LAZY");
		assertThat(lowered("(lazy-cat)")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("(repeat 3)")).contains("%CLOJURE-REPEAT");
		assertThat(lowered("(repeat 2 3)")).contains("%CLOJURE-REPEAT-N");
		assertThat(lowered("(cycle [1 2])")).contains("%CLOJURE-CYCLE");
		assertThat(lowered("(iterate inc 0)")).contains("%CLOJURE-ITERATE");
		assertThat(lowered("(repeatedly inc)")).contains("%CLOJURE-REPEATEDLY");
		assertThat(lowered("(repeatedly 2 inc)")).contains("%CLOJURE-REPEATEDLY-N");
		assertThatThrownBy(() -> Clojure.read("(repeat)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("repeat takes a value");
		assertThatThrownBy(() -> Clojure.read("(cycle)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cycle takes one collection");
		assertThatThrownBy(() -> Clojure.read("(iterate inc)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("iterate takes a function and a value");
		assertThatThrownBy(() -> Clojure.read("(map inc)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("map takes a function and collections");
	}

	@Test
	void vectorsQuotesAndKeywords() {
		assertThat(lowered("[1 :a]")).isEqualTo(FALSE_BINDING + "(VECTOR 1 (LIST :C%KEYWORD \"a\"))");
		assertThat(lowered("'a")).isEqualTo(FALSE_BINDING + "'|c%a|");
		assertThat(lowered(":a")).isEqualTo(FALSE_BINDING + "(LIST :C%KEYWORD \"a\")");
		assertThat(lowered(":A")).isEqualTo(FALSE_BINDING + "(LIST :C%KEYWORD \"A\")");
		assertThat(lowered("(str \"a\" 1)")).contains("CONCATENATE");
	}

	@Test
	void keywordsKeepTheirCaseAndPrintWithColon() {
		assertThat(lowered("(= :a :A)")).contains("(EQUAL").contains(":C%KEYWORD");
		assertThat(lowered("(str :a)")).contains("RONTOLISP::%CLOJURE-STR-OF");
		assertThat(lowered("':a")).isEqualTo(FALSE_BINDING + "'(:C%KEYWORD \"a\")");
		assertThat(lowered(":a/b")).isEqualTo(FALSE_BINDING + "(LIST :C%KEYWORD \"a/b\")");
		assertThatThrownBy(() -> Clojure.read("::foo", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("auto-resolved keywords are not supported yet: ::foo");
		assertThatThrownBy(() -> Clojure.read(":", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a keyword needs a name");
	}

	@Test
	void keywordsInCallPositionAreMapLookups() {
		String prelude = "(def m {:a 1}) ";
		assertThat(lowered(prelude + "(:a m)")).contains("GETHASH").contains("COND");
		assertThat(lowered(prelude + "(:a m 9)")).contains("GETHASH").contains("9");
		assertThat(lowered("(map :a '({:a 1}))")).contains("%CLOJURE-MAP").contains("LAMBDA").contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(:a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining(":a takes a collection and an optional default");
		assertThatThrownBy(() -> Clojure.read("(::foo m)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("auto-resolved keywords are not supported yet: ::foo");
	}

	@Test
	void mapsAndSetsBuildTables() {
		assertThat(lowered("{:a 1}")).contains("PLIST-HASH-TABLE").contains(":C%KEYWORD");
		assertThat(lowered("{}")).contains("PLIST-HASH-TABLE");
		assertThat(lowered("#{1 2}")).contains(":C%SET").contains("GETHASH");
		assertThat(lowered("#{ }")).contains(":C%SET");
		assertThatThrownBy(() -> Clojure.read("(nope 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name");
	}

	@Test
	void falseIsDistinctFromNil() {
		assertThat(lowered("false")).isEqualTo(FALSE_BINDING + "RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("true")).isEqualTo(FALSE_BINDING + "T");
		assertThat(lowered("nil")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("'false")).isEqualTo(FALSE_BINDING + "'|false|");
	}

	@Test
	void conditionalsTreatFalseAndNilAsFalsey() {
		assertThat(lowered("(def x false) (if x 1 2)")).isEqualTo(FALSE_BINDING
				+ "(SETQ |c%x| RONTOLISP::%CLOJURE-FALSE)\n"
				+ "(LET ((|__clojure_0| |c%x|)) (IF (OR (NULL |__clojure_0|) (EQ |__clojure_0| RONTOLISP::%CLOJURE-FALSE)) 2 1))");
		assertThat(lowered("(def x 1) (when x x)"))
			.contains("(LET ((|__clojure_0| |c%x|)) (IF (OR (NULL |__clojure_0|)");
		assertThat(lowered("(and 1 2)")).isEqualTo(FALSE_BINDING
				+ "(LET ((|__clojure_0| 1)) (IF (OR (NULL |__clojure_0|) (EQ |__clojure_0| RONTOLISP::%CLOJURE-FALSE)) |__clojure_0| 2))");
		assertThat(lowered("(or nil 3)")).isEqualTo(FALSE_BINDING
				+ "(LET ((|__clojure_0| NIL)) (IF (OR (NULL |__clojure_0|) (EQ |__clojure_0| RONTOLISP::%CLOJURE-FALSE)) 3 |__clojure_0|))");
		assertThat(lowered("(and)")).isEqualTo(FALSE_BINDING + "T");
		assertThat(lowered("(or)")).isEqualTo(FALSE_BINDING + "NIL");
	}

	@Test
	void booleanBuiltinsAnswerTrueOrFalse() {
		assertThat(lowered("(nil? nil)")).isEqualTo(FALSE_BINDING + "(IF (NULL NIL) T RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(false? nil)"))
			.isEqualTo(FALSE_BINDING + "(IF (EQ NIL RONTOLISP::%CLOJURE-FALSE) T RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(true? nil)")).isEqualTo(FALSE_BINDING + "(IF (EQ NIL T) T RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(boolean? nil)")).contains("(LET ((|__clojure_0| NIL)) (IF (OR (EQ |__clojure_0| T)");
		assertThat(lowered("(not nil)")).contains("(LET ((|__clojure_0| NIL)) (IF (OR (NULL |__clojure_0|)");
		assertThat(lowered("(map false? '(1))")).contains("%CLOJURE-MAP")
			.contains("(LAMBDA (|c%pred|) (IF (EQ |c%pred| RONTOLISP::%CLOJURE-FALSE) T RONTOLISP::%CLOJURE-FALSE))");
		assertThat(lowered("(map odd? '(1 2))")).contains("%CLOJURE-MAP").contains("ODDP");
	}

	@Test
	void printAndStrSpellTrueFalseNil() {
		assertThat(lowered("(str nil)"))
			.isEqualTo(FALSE_BINDING + "(CONCATENATE 'STRING (RONTOLISP::%CLOJURE-STR-OF NIL \"\" NIL))");
		assertThat(lowered("(println true)"))
			.isEqualTo(FALSE_BINDING + "(PROGN (RONTOLISP::%CLOJURE-WRITE-DATUM T \"nil\" NIL) (TERPRI) NIL)");
		assertThat(lowered("(print true)"))
			.isEqualTo(FALSE_BINDING + "(PROGN (RONTOLISP::%CLOJURE-WRITE-DATUM T \"nil\" NIL) NIL)");
		assertThat(lowered("(pr-str nil)"))
			.isEqualTo(FALSE_BINDING + "(CONCATENATE 'STRING (RONTOLISP::%CLOJURE-STR-OF NIL \"nil\" T))");
	}

	@Test
	void mapVerbsLowerToTableOperations() {
		String prelude = "(def m {:a 1}) (def v [1]) (def s #{1}) (def c '(1)) ";
		assertThat(lowered(prelude + "(assoc m :a 1)")).contains("PLIST-HASH-TABLE").contains("APPEND");
		assertThat(lowered(prelude + "(dissoc m :a)")).contains("REMHASH");
		assertThat(lowered(prelude + "(get m :a)")).contains("GETHASH");
		assertThat(lowered(prelude + "(get m :a 9)")).contains("GETHASH");
		assertThat(lowered(prelude + "(contains? m :a)")).contains("GETHASH").contains("COND");
		assertThat(lowered(prelude + "(keys m)")).contains("MAPHASH");
		assertThat(lowered(prelude + "(vals m)")).contains("MAPHASH");
		assertThat(lowered(prelude + "(merge m m)")).contains("APPEND").contains("PLIST-HASH-TABLE");
		assertThat(lowered("(merge)")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered(prelude + "(conj v 1)")).contains("COND").contains("COERCE");
		assertThat(lowered(prelude + "(disj s 1)")).contains("REMHASH").contains(":C%SET");
		assertThat(lowered(prelude + "(set c)")).contains("DOLIST").contains(":C%SET");
		assertThat(lowered(prelude + "(hash-map :a 1)")).contains("PLIST-HASH-TABLE");
		assertThat(lowered(prelude + "(array-map :a 1)")).contains("PLIST-HASH-TABLE");
		assertThat(lowered(prelude + "(count m)")).contains("HASH-TABLE-COUNT").contains("LENGTH");
		assertThat(lowered(prelude + "(empty? m)")).contains("HASH-TABLE-COUNT").contains("NULL");
	}

	@Test
	void mapVerbsRefuseBadShapesByName() {
		assertThatThrownBy(() -> Clojure.read("(assoc m :a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("assoc takes a map and key/value pairs");
		assertThatThrownBy(() -> Clojure.read("(get m)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("get takes a map");
		assertThatThrownBy(() -> Clojure.read("(hash-map :a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("hash-map takes key/value pairs");
		assertThatThrownBy(() -> Clojure.read("(conj)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("conj takes a collection and items");
		assertThatThrownBy(() -> Clojure.read("(count a b)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("count takes one collection");
	}

	@Test
	void transientsAreRefusedByName() {
		assertThatThrownBy(() -> Clojure.read("(assoc! m :a 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("transients are not supported yet: assoc!");
		assertThatThrownBy(() -> Clojure.read("(transient {})", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("transients are not supported yet: transient");
		assertThatThrownBy(() -> Clojure.read("(conj! t 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("transients are not supported yet: conj!");
	}

	@Test
	void quotedMapsAndSetsBuildTables() {
		assertThat(lowered("'{:a 1}")).contains("PLIST-HASH-TABLE").contains(":C%KEYWORD");
		assertThat(lowered("'#{1 2}")).contains(":C%SET").contains("GETHASH");
		assertThat(lowered("'{:a 1}")).contains("(LIST :C%KEYWORD \"a\")");
		assertThat(lowered("'#{:a}")).contains(":C%SET").contains("(LIST :C%KEYWORD \"a\")");
	}

	@Test
	void nsDefinesNothing() {
		assertThat(lowered("(ns foo) (def x 1) x")).isEqualTo(FALSE_BINDING + "(SETQ |c%x| 1)\n|c%x|");
		assertThat(lowered("(ns foo (:require [clojure.string :as s])) (def x 1) x"))
			.isEqualTo(FALSE_BINDING + "(SETQ |c%x| 1)\n|c%x|");
	}

	@Test
	void nthIndexesTheSeqViewWithAnOptionalDefault() {
		assertThat(lowered("(nth '(1 2 3) 1)")).contains("(NTH").contains("LENGTH");
		assertThat(lowered("(nth [10 20] 5 :nf)")).contains("(NTH").contains(":C%KEYWORD");
		assertThat(lowered("nth")).contains("(LAMBDA").contains("(NTH");
		assertThatThrownBy(() -> Clojure.read("(nth '(1 2 3))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("nth takes a collection, an index and an optional default");
	}

	@Test
	void quotTruncatesTowardZero() {
		assertThat(lowered("(quot 7 2)")).isEqualTo(FALSE_BINDING + "(TRUNCATE 7 2)");
		assertThat(lowered("quot")).contains("(LAMBDA").contains("TRUNCATE");
	}

	@Test
	void errorsCarryTheReadersPosition() {
		LispReadException unknown = catchThrowableOfType(() -> Clojure.read("(nope 1)", "prog.clj"),
				LispReadException.class);
		assertThat(unknown.getMessage()).isEqualTo("prog.clj:1:1: unknown name: nope");
		assertThat(unknown.location()).isNotNull();
		assertThat(unknown.location().line()).isEqualTo(1);
		assertThat(unknown.location().column()).isEqualTo(1);
		LispReadException secondLine = catchThrowableOfType(() -> Clojure.read("(def x 1)\n  (take 1)", "prog.clj"),
				LispReadException.class);
		assertThat(secondLine.getMessage()).isEqualTo("prog.clj:2:3: take takes a count and a collection");
		LispReadException nested = catchThrowableOfType(() -> Clojure.read("(let [x 1] (nope x))", "prog.clj"),
				LispReadException.class);
		assertThat(nested.getMessage()).isEqualTo("prog.clj:1:12: unknown name: nope");
		// without a file the message stays bare but the position is still recorded
		LispReadException bare = catchThrowableOfType(() -> Clojure.read("(nope 1)", null), LispReadException.class);
		assertThat(bare.getMessage()).isEqualTo("unknown name: nope");
		assertThat(bare.location()).isNotNull();
		assertThat(bare.location().line()).isEqualTo(1);
		assertThat(bare.location().column()).isEqualTo(1);
	}

	@Test
	void printPartsAreSpaceSeparatedAndPrIsReadable() {
		assertThat(lowered("(println \"x\" \"y\")")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM")
			.contains("(WRITE-CHAR #\\Space)")
			.contains("(TERPRI)");
		assertThat(lowered("(print \"a\" \"b\")")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM")
			.contains("(WRITE-CHAR #\\Space)")
			.doesNotContain("(TERPRI)");
		assertThat(lowered("(println \"x\")")).doesNotContain("(WRITE-CHAR");
		assertThat(lowered("(str \"a\" \"b\")")).doesNotContain("(WRITE-CHAR").contains("RONTOLISP::%CLOJURE-STR-OF");
		assertThat(lowered("(pr \"a\" 1)")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM")
			.contains("(WRITE-CHAR #\\Space)")
			.contains("\"nil\" T");
		assertThat(lowered("(prn :a)")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM").contains("(TERPRI)");
		assertThat(lowered("(pr-str \"a\" 1)")).contains("(CONCATENATE 'STRING")
			.contains("\" \"")
			.contains("\"nil\" T");
		assertThat(lowered("(map pr-str [1 2])")).contains("%CLOJURE-MAP").contains("CONCATENATE");
	}

	@Test
	void builtinsNameFunctionValues() {
		assertThat(lowered("(map inc '(1 2))")).contains("%CLOJURE-MAP").contains("(LAMBDA").contains("(+");
		assertThat(lowered("(map dec [1 2])")).contains("%CLOJURE-MAP").contains("(-");
		assertThat(lowered("(map str [1 2])")).contains("%CLOJURE-MAP").contains("CONCATENATE");
		assertThat(lowered("(map count [[1]])")).contains("%CLOJURE-MAP").contains("LENGTH");
		assertThat(lowered("(filter first [[1] []])")).contains("%CLOJURE-FILTER").contains("(CAR");
		assertThat(lowered("inc")).contains("(LAMBDA").contains("(+");
		assertThat(lowered("(apply + 1 '(2 3))")).contains("APPLY").contains("#'+");
	}

	@Test
	void destructuringBindsSequentialPatterns() {
		assertThat(lowered("(let [[a b] [1 2]] a)")).contains("LET*").contains("(NTH").contains("|c%a|");
		assertThat(lowered("(let [[a & r] [1 2 3]] r)")).contains("NTHCDR");
		assertThat(lowered("(let [[a :as v] [1 2]] v)")).contains("LET*");
		assertThat(lowered("(let [{:keys [a b] :as m :or {a 9}} {:a 1}] a)")).contains("GETHASH")
			.contains(":C%KEYWORD");
		assertThat(lowered("(let [{s :s} {:s 1}] s)")).contains("GETHASH");
		assertThat(lowered("(let [{:strs [s]} {:s 1}] s)")).contains("GETHASH");
		assertThat(lowered("(loop [[a b] [1 2]] a)")).contains("LABELS").contains("(NTH");
		assertThat(lowered("((fn [[a b]] a) [1 2])")).contains("(NTH");
		assertThat(lowered("((fn [{:keys [a]}] a) {:a 1})")).contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(let [[a &] [1]] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a vector pattern & needs a single rest pattern after it");
		assertThatThrownBy(() -> Clojure.read("(let [{:keys a} {:a 1}] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a map pattern :keys takes a vector of plain names");
	}

	@Test
	void threadingIsADatumRewriteAroundOneTemporary() {
		assertThat(lowered("(-> 5 inc)")).isEqualTo(FALSE_BINDING + "(+ 5 1)");
		assertThat(lowered("(->> 5 (conj [1]))")).contains("COERCE").contains("(VECTOR 1)");
		assertThat(lowered("(-> {:a 1} :a)")).contains("GETHASH");
		assertThat(lowered("(as-> 5 x (inc x))")).contains("LET*");
		assertThat(lowered("(doto 5 (inc))")).contains("(LET");
		assertThat(lowered("(cond-> 5 true inc)")).contains("(IF");
		assertThat(lowered("(some-> nil (inc))")).contains("NULL");
		assertThat(lowered("(list* 1 [2 3])")).contains("%CLOJURE-CONS").contains("(VECTOR 2 3)");
		assertThatThrownBy(() -> Clojure.read("(->)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("-> takes a value and forms");
		assertThatThrownBy(() -> Clojure.read("(cond-> 5 true)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cond-> takes a value and test/form pairs");
	}

	@Test
	void multiArityDefnDispatchesByCount() {
		assertThat(lowered("(defn mar-f ([x] x) ([x y] (+ x y)))")).contains("(DEFUN")
			.contains("LENGTH")
			.contains("wrong number of arguments passed to: mar-f");
		assertThat(lowered("(defn mar-g ([x & xs] xs))")).contains("NTHCDR");
		assertThatThrownBy(() -> Clojure.read("(defn bad ([x] x) ([y] y))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("two clauses for arity 1");
		assertThatThrownBy(() -> Clojure.read("(defn bad ([x & a] x) ([y & b] y))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("at most one variadic clause");
		assertThatThrownBy(() -> Clojure.read("(let [x 1] (defn bad ([y] y) ([z w] z)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a multi-arity defn is only allowed at the top level");
	}

	@Test
	void namedAndMultiArityFn() {
		assertThat(lowered("((fn myfn [x] x) 1)")).contains("LABELS").contains("#'|c%myfn|");
		assertThat(lowered("((fn ([x] x) ([x y] y)) 1)")).contains("LENGTH").contains("(NTH");
		assertThatThrownBy(() -> Clojure.read("(fn)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("fn needs a parameter vector and a body");
	}

	@Test
	void declareRegistersForwardNames() {
		assertThat(lowered("(declare dcl-f) (defn dcl-g [] (dcl-f 1))")).contains("NIL").contains("|c%dcl-f|");
	}

	@Test
	void doseqDotimesForLowerToCoreLoops() {
		assertThat(lowered("(doseq [x [1]] x)")).contains("DOLIST").contains("|c%x|").contains("(PROGN");
		assertThat(lowered("(doseq [x [1 2] y [3 4]] x)")).contains("DOLIST");
		assertThat(lowered("(dotimes [i 2] i)")).contains("DOTIMES").contains("TRUNCATE");
		assertThat(lowered("(for [x [1]] x)")).contains("DOLIST").contains("REVERSE").contains("SETQ");
		assertThat(lowered("(for [x [1] :when x] x)")).contains("DOLIST");
		assertThat(lowered("(for [x [1] :while x] x)")).contains("BLOCK").contains("RETURN-FROM");
		assertThat(lowered("(doseq [x [1] :while x] x)")).contains("BLOCK").contains("RETURN-FROM");
		assertThat(lowered("(for [x [1] :let [y 2]] y)")).contains("LET*").contains("|c%y|");
		assertThat(lowered("(for [[a b] [[1 2]]] a)")).contains("DOLIST").contains("LET*");
		assertThat(lowered("(dorun [1])")).contains("PROGN");
		assertThat(lowered("(doall [1])")).isEqualTo(FALSE_BINDING + "(VECTOR 1)");
		assertThatThrownBy(() -> Clojure.read("(for [x [1] :foo 1] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid 'for' keyword :foo");
		assertThatThrownBy(() -> Clojure.read("(doseq [x [1] :unless true] x)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid 'doseq' keyword :unless");
		assertThatThrownBy(() -> Clojure.read("(for [x [1]] 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("for takes a binding vector and a body");
		assertThatThrownBy(() -> Clojure.read("(for [] 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("for takes at least one binding pair");
		assertThatThrownBy(() -> Clojure.read("(dotimes [i] i)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("dotimes takes exactly one name and count");
		assertThatThrownBy(() -> Clojure.read("(dotimes [[a] 2] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("dotimes needs a plain name");
		assertThatThrownBy(() -> Clojure.read("(dorun)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("dorun takes a collection and an optional count");
		assertThatThrownBy(() -> Clojure.read("(doseq [x [1] :when] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("doseq :when takes a form after it");
	}

	@Test
	void defmultiIsATablePlusADispatcherDefun() {
		assertThat(lowered("(defmulti area :shape)")).contains("DEFUN |c%area|")
			.contains("GETHASH")
			.contains("|c%area%methods|");
		assertThat(lowered("(defmulti area :shape) (defmethod area :circle [m] 1)")).contains("SETF")
			.contains("LAMBDA");
		assertThatThrownBy(() -> Clojure.read("(defmethod area :circle [m] 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such multimethod: area");
		assertThatThrownBy(() -> Clojure.read("(prefer-method area :x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("prefer-method takes a multimethod and two dispatch values");
		assertThatThrownBy(() -> Clojure.read("(prefer-method missing :x :y)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such multimethod: missing");
		assertThat(lowered("(defmulti area :shape) (prefer-method area :x :y)")).contains("C%H-PREFERRED")
			.contains("|c%area%prefers|");
		assertThat(lowered("(derive :a :b)")).contains("C%H-DERIVE").contains("C%H-GLOBAL");
		assertThat(lowered("(isa? :a :b)")).contains("C%H-ISA?");
		assertThat(lowered("(parents :a)")).contains("C%H-PARENTS");
		assertThat(lowered("(make-hierarchy)")).contains("C%H-EMPTY");
		assertThat(lowered("(def h (make-hierarchy)) (defmulti area :shape :hierarchy h)")).contains("C%H-DISPATCH");
		assertThatThrownBy(() -> Clojure.read("(isa? :a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("isa? takes a child and a parent");
		assertThat(lowered("(ex-info \"m\" {:a 1})")).contains("MAKE-CONDITION").contains("C%E-EX-INFO");
		assertThat(lowered("(def e (ex-info \"m\" {:a 1})) (ex-data e)")).contains("C%E-DATA");
		assertThat(lowered("(def e (ex-info \"m\" {:a 1})) (ex-message e)")).contains("C%E-MESSAGE");
		assertThatThrownBy(() -> Clojure.read("(ex-info \"m\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("ex-info takes a message and a data map");
	}

	@Test
	void protocolsRecordsAndTypesLowerToTables() {
		// a protocol is a method-table global plus one dispatcher defun per method,
		// over the shared tag reader (the multimethod shape without the hierarchy
		// search); the protocol name answers its table
		assertThat(lowered("(defprotocol P (foo [x]) (bar [x y]))")).contains("C%PROTOCOL-TAG")
			.contains("|c%P%methods|")
			.contains("DEFUN |c%foo|")
			.contains("DEFUN |c%bar|")
			.contains("(SETQ |c%P| |c%P%methods|)");
		// a record is a (:C%RECORD tag fields table) wrapper with positional and map
		// constructors as mangled defuns, so constructor calls stay direct
		assertThat(lowered("(defprotocol P (foo [x])) (defrecord R [a] P (foo [_] a))")).contains(":C%RECORD")
			.contains("DEFUN |c%->R|")
			.contains("DEFUN |c%map->R|")
			.contains("GETHASH");
		assertThat(lowered("(defrecord R [a]) (->R 1)")).contains("(|c%->R| 1)");
		assertThat(lowered("(->R 1) (defrecord R [a])")).contains("(|c%->R| 1)");
		assertThat(lowered("(defrecord R [a]) (R. 1)")).contains("(|c%->R| 1)");
		assertThat(lowered("(defrecord R [a]) (map->R {:a 1})")).contains("|c%map->R|");
		assertThat(lowered("(T. 1)")).contains("JAVA:NEW");
		// a deftype shares the shape with an opaque tag and no map constructor
		assertThat(lowered("(deftype T [a])")).contains(":C%TYPE").contains("DEFUN |c%->T|");
		assertThat(lowered("(deftype T [a])")).doesNotContain("map->");
		assertThatThrownBy(() -> Clojure.read("(map->T {:a 1}) (deftype T [a])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: map->T");
		// reify answers one fresh tag per evaluation with a row per method
		assertThat(lowered("(defprotocol P (foo [x])) (reify P (foo [_] 1))")).contains(":C%REIFY")
			.contains("GENSYM")
			.contains("|c%P%methods|");
		// extend-protocol/extend-type/extend are defmethod rows; satisfies? is table
		// membership; instance? of a type is tag equality
		assertThat(lowered("(defprotocol P (foo [x])) (extend-protocol P String (foo [s] s))"))
			.contains("|c%P%methods|");
		assertThat(lowered("(defprotocol P (foo [x])) (extend-type String P (foo [s] s))")).contains("|c%P%methods|");
		assertThat(lowered("(defprotocol P (foo [x])) (extend String P {:foo (fn [s] s)})")).contains("|c%P%methods|");
		assertThat(lowered("(defprotocol P (foo [x])) (satisfies? P 1)")).contains("C%PROTOCOL-TAG");
		assertThat(lowered("(defrecord R [a]) (instance? R 1)")).contains("C%PROTOCOL-TAG");
		assertThat(lowered("(defrecord R [a]) (.-a (->R 1))")).contains("GETHASH");
		// the stays-refused set: interfaces and code generation, multi-arity methods,
		// metadata extension, non-core extend targets, unknown protocols and methods
		// outside their protocols
		assertThatThrownBy(() -> Clojure.read("(gen-class)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("protocols are not supported yet: gen-class");
		assertThatThrownBy(() -> Clojure.read("(gen-interface)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("protocols are not supported yet: gen-interface");
		assertThatThrownBy(() -> Clojure.read("(definterface I (m [x]))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("protocols are not supported yet: definterface");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m ([x] 1) ([x y] 2)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("multi-arity protocol methods are not supported yet: m");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q :extend-via-metadata true (m [x]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("extend-via-metadata is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x])) (extend-protocol Q Instant (m [x] 1))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("extend-protocol needs a core type, not Instant");
		assertThatThrownBy(() -> Clojure.read("(extend-protocol Missing String (m [x] 1))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such protocol: Missing");
		assertThatThrownBy(() -> Clojure.read("(satisfies? Missing 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such protocol: Missing");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x])) (defrecord R [a] Q (nope [x] 1))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can't define method not in interfaces: nope");
		assertThatThrownBy(() -> Clojure.read("(defrecord R [a] :load-ns true)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defrecord option");
	}

	@Test
	void tryIsAHandlerCaseInsideAnUnwindProtect() {
		assertThat(lowered("(try 1 (catch Exception e 2) (finally 3))")).contains("HANDLER-CASE")
			.contains("UNWIND-PROTECT")
			.contains("(ERROR (|c%e|)");
		assertThat(lowered("(throw \"boom\")")).contains("C%E-THROW");
	}

	@Test
	void atomsAreTaggedCells() {
		assertThat(lowered("(atom 1)")).contains(":C%ATOM").contains("(VECTOR 1)");
		assertThat(lowered("(def a (atom 1)) @a")).contains("AREF");
		assertThat(lowered("(def a (atom 1)) (swap! a inc)")).contains("APPLY");
		assertThat(lowered("(def a (atom 1)) (compare-and-set! a 1 2)")).contains("EQL");
		assertThatThrownBy(() -> Clojure.read("(deref a 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("deref takes one argument");
	}

	@Test
	void nsRequireWiresAliasesAndRefers() {
		assertThat(lowered("(ns t (:require [clojure.string :as s])) (s/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(ns t (:require [clojure.string :as s :refer [join]])) (join \",\" [\"a\"])"))
			.contains("CONCATENATE");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [no.such.lib :as n]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown namespace: no.such.lib");
		assertThatThrownBy(() -> Clojure.read("(s/join \",\" [\"a\"])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: s/join");
	}

	@Test
	void interopLowersToTheJavaSurface() {
		assertThat(lowered("(.toUpperCase \"hi\")")).contains("JAVA:CALL").contains("toUpperCase");
		assertThat(lowered("(. \"hi\" toUpperCase)")).contains("JAVA:CALL");
		assertThat(lowered("(Math/max 3 7)")).contains("JAVA:STATIC").contains("java.lang.Math");
		assertThat(lowered("(String. \"hi\")")).contains("JAVA:NEW").contains("java.lang.String");
		assertThat(lowered("(Integer/MAX_VALUE)")).contains("JAVA:FIELD");
		assertThat(lowered("(new String \"hi\")")).contains("JAVA:NEW");
		assertThat(lowered("((memfn toUpperCase) \"hi\")")).contains("LAMBDA").contains("toUpperCase");
		assertThatThrownBy(() -> Clojure.read("(memfn toUpperCase 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("memfn needs a plain name, not 1");
		assertThat(lowered("(proxy [java.util.function.Supplier] [] (get [] 42))")).contains("JAVA:PROXY")
			.contains("java.util.function.Supplier");
		assertThatThrownBy(() -> Clojure.read("(proxy [A B] [] (get [] 1))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy takes a single interface");
		assertThatThrownBy(() -> Clojure.read("(set! x 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("set! is not supported yet");
	}

	@Test
	void charactersAndRadixLowerAsThemselves() {
		assertThat(lowered("\\a")).contains("#\\a");
		assertThat(lowered("0xFF")).isEqualTo(FALSE_BINDING + "255");
		assertThat(lowered("1M")).isEqualTo(FALSE_BINDING + "1");
		assertThat(lowered("0.1M")).isEqualTo(FALSE_BINDING + "1/10");
	}

	private static String loweredWithMacros(String source) {
		List<LispVal> forms = Clojure.read(source, null, ClojureMacroTime.create());
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	@Test
	void defmacroEmitsATableEntryAndRegistersTheExpander() {
		String out = loweredWithMacros("(defmacro mu-unless [c t] (list 'if c nil t))");
		assertThat(out).contains("PROGN")
			.contains("|c%mu-unless%macro|")
			.contains("LAMBDA")
			.contains("wrong number of arguments passed to macro: mu-unless");
	}

	@Test
	void macroCallsExpandAtLowerTime() {
		String out = loweredWithMacros("(defmacro mu-unless [c t] (list 'if c nil t)) (mu-unless false 42)");
		assertThat(out).contains("|c%mu-unless%macro|");
		// the call lowered to the if over the false value, answering 42 for false
		assertThat(out).contains("(LET ((|__clojure_").contains("RONTOLISP::%CLOJURE-FALSE").contains(" 42 NIL))");
	}

	@Test
	void macroCallsAboveTheirDefinitionNameTheMissingExpander() {
		assertThatThrownBy(
				() -> Clojure.read("(mu-early 1) (defmacro mu-early [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("macro `mu-early` used before its definition");
		assertThatThrownBy(() -> Clojure.read("(mu-missing 1)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: mu-missing");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-noeval [x] x) (mu-noeval 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot expand without a macro evaluator");
	}

	@Test
	void defmacroRefusesCoreFormsAndEnvironments() {
		assertThatThrownBy(() -> Clojure.read("(defmacro if [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot name a macro");
		assertThatThrownBy(() -> Clojure.read("(defmacro gensym [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot name a macro");
		assertThatThrownBy(() -> Clojure.read("(defmacro s/m [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot name a macro");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-env [&form x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("&form is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-env2 [x &env] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("&env is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(defmacro)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defmacro needs a name");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-short)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defmacro needs a parameter vector and a body");
	}

	@Test
	void macrosHaveNoValue() {
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-val [x] x) (list mu-val)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("is a macro, not a function");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-val2 [x] x) mu-val2", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("is a macro, not a function");
		assertThatThrownBy(
				() -> Clojure.read("(defmacro mu-val3 [x] x) (map mu-val3 '(1))", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("is a macro, not a function");
	}

	@Test
	void unquoteOutsideSyntaxQuoteIsAnError() {
		assertThatThrownBy(() -> Clojure.read("(list ~x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unquote outside syntax-quote");
		assertThatThrownBy(() -> Clojure.read("~@x", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unquote-splicing outside syntax-quote");
		assertThatThrownBy(() -> Clojure.read("`~@x", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unquote-splicing outside a sequence");
		assertThat(lowered("(def spl-v [1]) `{:a ~@spl-v}")).contains("APPEND");
	}

	@Test
	void syntaxQuoteQualifiesSplicesAndGensyms() {
		String out = loweredWithMacros("(defmacro mu-sq [x] `(a ~x ~@'(1 2) s#))");
		assertThat(out).contains("(GENSYM \"s\")").contains("APPEND").contains("'|c%a|");
		String out2 = loweredWithMacros("(defmacro mu-doc \"docs\" [x] x) (mu-doc 1)");
		assertThat(out2).contains("|c%mu-doc%macro|");
	}

	@Test
	void gensymFreshnessAcrossTwoExpansions() {
		String out = loweredWithMacros(
				"(defmacro mu-bg [e] `(let [x# ~e] x#)) (def mu-a (mu-bg 1)) (def mu-b (mu-bg 2))");
		java.util.regex.Matcher found = java.util.regex.Pattern.compile("#:\\S+").matcher(out);
		java.util.Set<String> gensyms = new java.util.HashSet<>();
		while (found.find()) {
			gensyms.add(found.group());
		}
		assertThat(gensyms).hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	void macroexpandLowersToTheRuntimeExpander() {
		assertThat(lowered("(macroexpand-1 '(mu-x 1))")).contains("C%MACROEXPAND-1").contains("'");
		assertThat(lowered("(macroexpand '(mu-x 1))")).contains("(C%MACROEXPAND '");
		assertThat(lowered("macroexpand-1")).contains("LAMBDA").contains("C%MACROEXPAND-1");
		assertThat(lowered("macroexpand")).contains("LAMBDA").contains("C%MACROEXPAND");
		assertThatThrownBy(() -> Clojure.read("(macroexpand-1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("macroexpand-1 takes one form");
		assertThatThrownBy(() -> Clojure.read("(macroexpand 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("macroexpand takes one form");
	}

	@Test
	void multiArityMacrosDispatchByCount() {
		String out = loweredWithMacros(
				"(defmacro mu-ch ([x f] (list '. x f)) ([x f & m] (concat (list 'mu-ch (list '. x f)) m))) (mu-ch \"hi\" toUpperCase length)");
		assertThat(out).contains("wrong number of arguments passed to macro: mu-ch");
		assertThat(out).contains("STRING-UPCASE");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-dup ([x] x) ([y] y))", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("two clauses for arity 1");
		assertThatThrownBy(
				() -> Clojure.read("(defmacro mu-ar ([x] x) ([x y] y)) (mu-ar 1 2 3)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("in macro `mu-ar`")
			.hasMessageContaining("wrong number of arguments passed to macro: mu-ar");
	}

	@Test
	void gensymLowersToThePrimitive() {
		assertThat(lowered("(gensym)")).contains("(GENSYM)");
		assertThat(lowered("(gensym \"p\")")).contains("(GENSYM \"p\")");
		assertThat(lowered("gensym")).contains("#'GENSYM");
		assertThatThrownBy(() -> Clojure.read("(gensym 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("gensym takes an optional prefix");
	}

	@Test
	void varStaysRefusedWhileMetadataDrops() {
		assertThatThrownBy(() -> Clojure.read("#'x", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("var is not supported yet");
		// metadata parses and drops: the object lowers as itself
		assertThat(lowered("(def v 1) ^:k v")).contains("|c%v|").doesNotContain("WITH-META");
		assertThatThrownBy(() -> Clojure.read("(with-meta 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("with-meta takes an object and metadata");
	}

	@Test
	void conditionalBindingFormsLowerOverLet() {
		assertThat(lowered("(when-let [x 1] x)")).contains("LET*").contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(when-let [[a b] [1 2]] (+ a b))")).contains("LET*").contains("%CLOJURE-SEQ");
		assertThat(lowered("(if-let [x 1] x :e)")).contains("LET*").contains(":C%KEYWORD");
		assertThat(lowered("(when-not false 1)")).contains("IF");
		assertThat(lowered("(if-not nil 1 2)")).contains("IF");
		assertThat(lowered("(when-first [x [1]] x)")).contains("LET*").contains("CAR");
		assertThatThrownBy(() -> Clojure.read("(when-let [x] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("when-let takes a single binding pair");
		assertThatThrownBy(() -> Clojure.read("(if-let [x 1] a b c)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("if-let takes a binding vector");
	}

	@Test
	void collectionsAnswerCallsAndValues() {
		assertThat(lowered("(#{:h} :h)")).contains("GETHASH");
		assertThat(lowered("({:a 1} :a :d)")).contains("GETHASH");
		assertThat(lowered("([1 2] 0)")).contains("NTH");
		assertThat(lowered("(filter #{:h} [:h])")).contains("%CLOJURE-FILTER").contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(#{:h})", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a collection as a function takes a key");
	}

	@Test
	void seqVerbsLowerOverTheSeqView() {
		assertThat(lowered("(keep inc [1])")).contains("REMOVE-IF").contains("MAPCAR");
		assertThat(lowered("(keep-indexed odd? [1])")).contains("LABELS");
		assertThat(lowered("(map-indexed vector [1])")).contains("LABELS");
		assertThat(lowered("(every? odd? [1])")).contains("LABELS").contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(some odd? [1])")).contains("LABELS");
		assertThat(lowered("(distinct [1])")).contains("HASH-TABLE");
		assertThat(lowered("(partition 2 [1])")).contains("LABELS");
		assertThat(lowered("(take-while odd? [1])")).contains("LABELS");
		assertThat(lowered("(interleave [1] [2])")).contains("APPEND");
		assertThat(lowered("(zipmap [1] [2])")).contains("GETHASH");
		assertThat(lowered("(sort [2 1])")).contains("SORT").contains("COPY-LIST");
		assertThat(lowered("(group-by odd? [1])")).contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(partition 2 1 [1] [2])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("partition takes a size");
	}

	@Test
	void mapVerbsBuildFreshTables() {
		assertThat(lowered("(update {:a 1} :a inc)")).contains("RONTOLISP::%CLOJURE-CALL").contains("HASH-TABLE");
		assertThat(lowered("(update-in {:a 1} [:a] inc)")).contains("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("(assoc-in {} [:a] 1)")).contains("HASH-TABLE");
		assertThat(lowered("(get-in {:a 1} [:a])")).contains("GETHASH");
		assertThat(lowered("(select-keys {:a 1} [:a])")).contains("GETHASH");
		assertThat(lowered("(merge-with + {:a 1} {:a 2})")).contains("MAPHASH");
		assertThat(lowered("(into [] [1])")).contains("REDUCE");
		assertThat(lowered("(frequencies [1])")).contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(update-in {:a 1} :a inc)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("update-in takes a vector of keys");
		assertThatThrownBy(() -> Clojure.read("(into [] [1] (map inc))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("transducers are not supported yet: into");
	}

	@Test
	void higherOrderFormsComposeAndCache() {
		assertThat(lowered("((comp inc inc) 5)")).contains("LAMBDA").contains("FUNCALL");
		assertThat(lowered("((partial + 1) 2)")).contains("LAMBDA").contains("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("((complement odd?) 1)")).contains("LAMBDA");
		assertThat(lowered("((constantly 1) 2)")).contains("LAMBDA");
		assertThat(lowered("(memoize inc)")).contains("HASH-TABLE");
		assertThat(lowered("(trampoline inc 1)")).contains("LABELS").contains("FUNCTIONP");
	}

	@Test
	void predicatesAndCastsReadAndAnswer() {
		assertThat(lowered("(coll? [1])")).contains("CONSP").contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(symbol? 'a)")).contains("SYMBOLP");
		assertThat(lowered("(instance? String \"a\")")).contains("STRINGP");
		assertThat(lowered("(class 1)")).contains(":C%KEYWORD");
		assertThat(lowered("(int 1.5)")).contains("TRUNCATE");
		assertThatThrownBy(() -> Clojure.read("(instance? Point 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("instance? needs a core class, not Point");
	}

	@Test
	void ioEntryPointsAndFormat() {
		assertThat(lowered("(spit \"f\" \"x\")")).contains("WITH-OPEN-FILE").contains("WRITE-STRING");
		assertThat(lowered("(slurp \"f\")")).contains("READ-CHAR");
		assertThat(lowered("(line-seq \"f\")")).contains("READ-LINE").contains("STREAMP");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) (jio/reader \"f\")")).contains("(OPEN \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) (line-seq (jio/reader \"f\"))"))
			.contains("READ-LINE")
			.contains("STREAMP");
		assertThat(lowered("(format \"%s=%d\" :a 1)")).contains("FORMAT").contains("~A");
		assertThatThrownBy(() -> Clojure.read("(file-seq \".\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("file-seq is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(reader \"f\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: reader");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.java.io :as jio])) (jio/writer \"f\")", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.java.io/writer");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.java.io :as jio])) (jio/file \".\")", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.java.io/file");
		assertThatThrownBy(() -> Clojure.read("(format \"%e\" 1.5)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("format directive %e is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(format x 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("format takes a literal format string");
	}

	@Test
	void javaIoReaderWiresLikeClojureString() {
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) (jio/reader \"f\")")).contains("(OPEN \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) (clojure.java.io/reader \"f\")"))
			.contains("(OPEN \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio :refer [reader]])) (reader \"f\")"))
			.contains("(OPEN \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :refer :all])) (reader \"f\")")).contains("(OPEN \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) jio/reader")).contains("LAMBDA")
			.contains("OPEN");
		assertThat(lowered("(ns t (:require [clojure.java.io :refer [reader]])) reader")).contains("LAMBDA")
			.contains("OPEN");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.java.io :refer [writer]]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.java.io/writer");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.java.io :as jio])) (jio/reader)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("reader takes one path");
		assertThatThrownBy(
				() -> Clojure.read("(ns t (:require [clojure.java.io :as jio])) (jio/reader \"a\" \"b\")", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("reader takes one path");
	}

	@Test
	void regexAndForeignNamespacesStayRefused() {
		assertThatThrownBy(() -> Clojure.read("#\"x\"", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("regex literals are not supported yet");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.spec.alpha :as s]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown namespace: clojure.spec.alpha");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.xml :as x]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown namespace: clojure.xml");
	}

	@Test
	void metadataNamesDefinitionsWithoutAffectingThem() {
		assertThat(lowered("(defn- f [x] x)")).contains("(DEFUN |c%f| (|c%x|) |c%x|)");
		assertThat(lowered("(def ^:private x 1)")).contains("(SETQ |c%x| 1)");
		assertThat(lowered("(def ^:dynamic *d* 1)")).contains("(DEFPARAMETER |c%*d*| 1)");
		assertThat(lowered("(defn ^:private f [x] x)")).contains("(DEFUN |c%f| (|c%x|) |c%x|)");
		assertThat(lowered("(defn f {:private true} [x] x)")).contains("(DEFUN |c%f| (|c%x|) |c%x|)");
		assertThat(lowered("(def x \"a docstring\" 1)")).contains("(SETQ |c%x| 1)");
		assertThat(lowered("(def x \"a docstring\")")).contains("(SETQ |c%x| NIL)");
		assertThat(lowered("(def x {:a 1})")).contains("HASH-TABLE");
		assertThat(lowered("(defn f [^String x] x)")).contains("(DEFUN |c%f| (|c%x|) |c%x|)");
		assertThat(lowered("(let [^String x 1] x)")).contains("(LET* ((|c%x| 1)) |c%x|)");
		assertThatThrownBy(() -> Clojure.read("(defmacro ref [x] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot name a macro");
	}

	@Test
	void defonceDefstructAndStructLower() {
		assertThat(lowered("(defonce d 1)")).contains("BOUNDP").contains("(SETQ |c%d| 1)");
		assertThat(lowered("(defonce d)")).contains("(SETQ |c%d| NIL)");
		assertThat(lowered("(defstruct s :a :b)")).contains("(SETQ |c%s| (VECTOR");
		assertThat(lowered("(def s [:a]) (struct s 1)")).contains("GETHASH").contains("DOTIMES");
		assertThat(lowered("(def s [:a]) (struct-map s :a 1)")).contains("GETHASH").contains("DOTIMES");
		assertThatThrownBy(() -> Clojure.read("(defstruct s \"a\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("defstruct takes keyword keys");
	}

	@Test
	void stmVerbsLowerOverTheAtomCell() {
		assertThat(lowered("(ref 0)")).contains(":C%ATOM");
		assertThat(lowered("(ref 0 :validator odd?)")).contains("C%STM-PUT");
		assertThat(lowered("(dosync 1)")).contains("C%STM-DEPTH");
		assertThat(lowered("(def r (ref 0)) (alter r inc)")).contains("C%STM-CHECK").contains("No transaction");
		assertThat(lowered("(def r (ref 0)) (commute r inc)")).contains("C%STM-CHECK");
		assertThat(lowered("(def r (ref 0)) (ref-set r 1)")).contains("C%STM-CHECK");
		assertThat(lowered("(def r (ref 0)) (ensure r)")).contains("C%STM-DEPTH");
		assertThat(lowered("(agent 0)")).contains(":C%ATOM");
		assertThat(lowered("(def a (agent 0)) (send a inc)")).contains("C%AGENT").contains("C%STM-CHECK");
		assertThat(lowered("(def a (agent 0)) (send-off a inc)")).contains("C%AGENT");
		assertThat(lowered("(def a (agent 0)) (await a)")).contains("PROGN").contains("NIL");
		assertThat(lowered("(shutdown-agents)")).contains("NIL");
		assertThat(lowered("alter")).contains("LAMBDA");
		assertThatThrownBy(() -> Clojure.read("(ref 0 :history 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("ref option :history is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(dosync (alter r inc))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: r");
	}

	@Test
	void bindingNeedsDynamicVars() {
		assertThat(lowered("(def ^:dynamic *d* 1) (binding [*d* 2] *d*)")).contains("LET*").contains("|c%*d*|");
		assertThat(lowered("(binding [*out* 1] 1)")).contains("*STANDARD-OUTPUT*");
		assertThatThrownBy(() -> Clojure.read("(def x 1) (binding [x 2] x)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("binding x needs a ^:dynamic var");
		assertThatThrownBy(() -> Clojure.read("(binding [x 2] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("binding x needs a ^:dynamic var");
	}

	@Test
	void withOpenWithOutStrAndTimeLower() {
		assertThat(lowered("(def s \"x\") (with-open [a s] a)")).contains("UNWIND-PROTECT")
			.contains("JAVA:CALL")
			.contains("\"close\"")
			.contains("CLOSE");
		assertThat(lowered("(with-open [] 1)")).contains("1").doesNotContain("UNWIND-PROTECT");
		assertThat(lowered("(with-out-str 1)")).contains("MAKE-STRING-OUTPUT-STREAM")
			.contains("GET-OUTPUT-STREAM-STRING");
		assertThat(lowered("(time 1)")).contains("GET-INTERNAL-REAL-TIME").contains("Elapsed time: ");
		assertThat(lowered("(. *out* write \"x\")")).contains("STREAM");
	}

	@Test
	void futuresPromisesAndProxySuperStayRefused() {
		assertThatThrownBy(() -> Clojure.read("(future 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("future is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(delay 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("delay is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(force x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("force is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(promise)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("promise is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(deliver p 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("deliver is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(proxy-super x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy-super is not supported yet");
	}

}
