package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
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
		assertThat(lowered("(map #(* % %) '(1 2))")).contains("MAPCAR").contains("NTH");
		assertThatThrownBy(() -> Clojure.read("%", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("outside the anon form");
	}

	@Test
	void coreCallsLowerToTheirCommonLispNames() {
		assertThat(lowered("(map + '(1 2))")).contains("MAPCAR").contains("#'+").contains("COND");
		assertThat(lowered("(filter odd? '(1 2 3))")).contains("REMOVE-IF-NOT").contains("COND");
		assertThat(lowered("(reduce + 0 '(1 2))")).contains(":INITIAL-VALUE").contains("COND");
		assertThat(lowered("(apply max '(3 9 4))")).contains("APPLY").contains("COND");
		assertThat(lowered("(concat '(1 2) [3 4])")).contains("APPEND").contains("COERCE");
		assertThat(lowered("(concat)")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("(= 1 1)")).contains("LABELS").contains("(EQUAL");
		assertThat(lowered("(= 1 1)")).contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(cond (= 1 2) :one :else :fallback)")).contains(":C%KEYWORD").contains("fallback");
	}

	@Test
	void seqsCoerceCollectionsToStrictLists() {
		assertThat(lowered("(seq [1 2])")).contains("COND").contains("COERCE");
		assertThat(lowered("(first [1 2])")).contains("(CAR").contains("COERCE");
		assertThat(lowered("(rest [1 2])")).contains("(CDR").contains("COERCE");
		assertThat(lowered("(next [1 2])")).contains("(CDR").contains("COERCE");
		assertThat(lowered("(cons 0 [1 2])")).contains("(CONS").contains("COERCE");
		assertThat(lowered("(first '(1 2))")).contains("(CAR").contains("CONSP");
		assertThat(lowered("(seq {:a 1})")).contains("MAPHASH").contains("VECTOR");
	}

	@Test
	void takeDropAndFiniteRangeAreStrict() {
		assertThat(lowered("(take 2 [1 2])")).contains("LABELS").contains("REVERSE");
		assertThat(lowered("(drop 2 [1 2])")).contains("NTHCDR");
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
	void lazySeqsAreRefusedByName() {
		assertThatThrownBy(() -> Clojure.read("(lazy-seq [1])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("lazy sequences are not supported: lazy-seq");
		assertThatThrownBy(() -> Clojure.read("(cycle [1])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("lazy sequences are not supported: cycle");
		assertThatThrownBy(() -> Clojure.read("(repeat 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("lazy sequences are not supported: repeat");
		assertThatThrownBy(() -> Clojure.read("(iterate inc 0)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("lazy sequences are not supported: iterate");
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
		assertThat(lowered("(str :a)")).contains("(CONCATENATE 'STRING \":\"");
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
		assertThat(lowered("(map :a '({:a 1}))")).contains("MAPCAR").contains("LAMBDA").contains("GETHASH");
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
		assertThat(lowered("(map false? '(1))")).contains("MAPCAR")
			.contains("(LAMBDA (|c%pred|) (EQ |c%pred| RONTOLISP::%CLOJURE-FALSE))");
	}

	@Test
	void printAndStrSpellTrueFalseNil() {
		assertThat(lowered("(str nil)")).isEqualTo(FALSE_BINDING
				+ "(CONCATENATE 'STRING (LET ((|__clojure_0| NIL)) (IF (EQ |__clojure_0| RONTOLISP::%CLOJURE-FALSE) \"false\" (IF (EQ |__clojure_0| T) \"true\" (IF (NULL |__clojure_0|) \"\" (IF (AND (CONSP |__clojure_0|) (EQ (CAR |__clojure_0|) :C%KEYWORD) (STRINGP (CADR |__clojure_0|))) (CONCATENATE 'STRING \":\" (CADR |__clojure_0|)) (PRINC-TO-STRING |__clojure_0|)))))))");
		assertThat(lowered("(println true)")).contains("(PRINC (CONCATENATE 'STRING")
			.contains("\"true\"")
			.contains("PRINC-TO-STRING");
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
		assertThat(lowered("(println \"x\" \"y\")")).contains("\" \"");
		assertThat(lowered("(print \"a\" \"b\")")).contains("\" \"");
		assertThat(lowered("(println \"x\")")).doesNotContain("\" \"");
		assertThat(lowered("(str \"a\" \"b\")")).doesNotContain("\" \"");
		assertThat(lowered("(pr \"a\" 1)")).contains("PRIN1-TO-STRING").contains("\" \"");
		assertThat(lowered("(prn :a)")).contains("PRIN1-TO-STRING").contains("(PRINC");
	}

	@Test
	void builtinsNameFunctionValues() {
		assertThat(lowered("(map inc '(1 2))")).contains("MAPCAR").contains("(LAMBDA").contains("(+");
		assertThat(lowered("(map dec [1 2])")).contains("MAPCAR").contains("(-");
		assertThat(lowered("(map str [1 2])")).contains("MAPCAR").contains("CONCATENATE");
		assertThat(lowered("(map count [[1]])")).contains("MAPCAR").contains("LENGTH");
		assertThat(lowered("(filter first [[1] []])")).contains("REMOVE-IF-NOT").contains("(CAR");
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
		assertThat(lowered("(list* 1 [2 3])")).contains("(CONS").contains("COERCE");
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
	void iterationAndMultimethodsAreRefusedByName() {
		assertThatThrownBy(() -> Clojure.read("(doseq [x [1]] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("iteration forms are not supported yet: doseq");
		assertThatThrownBy(() -> Clojure.read("(dotimes [i 2] i)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("iteration forms are not supported yet: dotimes");
		assertThatThrownBy(() -> Clojure.read("(for [x [1]] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("iteration forms are not supported yet: for");
		assertThatThrownBy(() -> Clojure.read("(defmulti area :shape)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("multimethods are not supported yet: defmulti");
		assertThatThrownBy(() -> Clojure.read("(defmethod area :circle [m] 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("multimethods are not supported yet: defmethod");
	}

}
