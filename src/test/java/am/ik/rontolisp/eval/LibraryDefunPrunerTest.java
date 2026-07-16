package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryDefunPrunerTest {

	// Mirror the CLI compile-path splice chain (RontoLispCli.compileToFile), then prune.
	private static List<LispVal> spliceAndPrune(String source) {
		return LibraryDefunPruner.prune(splice(source));
	}

	private static List<LispVal> splice(String source) {
		return UsocketLibrary.process(VecLibrary.process(LispPreludeLibrary.process(
				UrlLibrary.process(LinalgLibrary.process(JsonLibrary.process(LispReader.readAllFromString(source)))))));
	}

	private static List<String> definedNames(List<LispVal> program) {
		List<String> names = new ArrayList<>();
		for (LispVal form : program) {
			String name = definitionName(form);
			if (name != null) {
				names.add(name);
			}
		}
		return names;
	}

	@Nullable private static String definitionName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& (op.name().equals("defun") || op.name().equals("defparameter") || op.name().equals("defvar"))
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		return null;
	}

	@Test
	void keepsOnlyTheTransitiveClosureOfTheCalledLinalgFunction() {
		List<String> names = definedNames(spliceAndPrune("(print (linalg:to-list (linalg:zeros '(2 2))))"));
		assertThat(names).contains("linalg:zeros", "linalg:to-list")
			// %la-make is the constructor funnel zeros goes through
			.contains("linalg::%la-make")
			// unrelated members of the library are dropped
			.doesNotContain("linalg:det", "linalg:inv", "linalg:randn", "linalg:matmul");
	}

	@Test
	void dropsTheRngSeedsWhenNoRngFunctionIsReachable() {
		List<String> names = definedNames(spliceAndPrune("(print (linalg:to-list (linalg:zeros '(2))))"));
		assertThat(names).doesNotContain("linalg::%la-rng-s1", "linalg::%la-rng-s2", "linalg::%la-rng-s3",
				"linalg::%la-rng-next");
	}

	@Test
	void keepsTheRngSeedsThroughTheRngClosure() {
		List<String> names = definedNames(
				spliceAndPrune("(linalg:seed 42) (print (linalg:to-list (linalg:randn '(2))))"));
		assertThat(names).contains("linalg:seed", "linalg:randn", "linalg::%la-rng-next", "linalg::%la-rng-s1",
				"linalg::%la-rng-s2", "linalg::%la-rng-s3");
	}

	@Test
	void setfOfVecArefKeepsTheSynthesizedVecAset() {
		// (setf (vec:aref v i) x) expands to (vec:aset v i x) AFTER the pruner runs,
		// so vec:aset is a hardcoded edge of vec:aref.
		List<String> names = definedNames(
				spliceAndPrune("(let ((v (vec:zeros 3))) (setf (vec:aref v 0) 1.0) (print (vec:aref v 0)))"));
		assertThat(names).contains("vec:aref", "vec:aset");
	}

	@Test
	void functionQuoteAndQuotedDesignatorAndStringLiteralAllCountAsReferences() {
		assertThat(definedNames(spliceAndPrune("(print (funcall #'linalg:ndim (linalg:zeros '(2))))")))
			.contains("linalg:ndim");
		assertThat(definedNames(spliceAndPrune("(print (funcall 'linalg:ndim (linalg:zeros '(2))))")))
			.contains("linalg:ndim");
		// A string literal containing the qualified name keeps the target: the
		// carve-out for (intern "...")/read-from-string idioms.
		assertThat(definedNames(spliceAndPrune("(print (linalg:shape (linalg:zeros '(2)))) (print \"linalg:ndim\")")))
			.contains("linalg:ndim");
	}

	@Test
	void bareNamesInsideInPackageResolveAgainstTheLibrary() {
		// The analysis runs on a PackageResolver-resolved copy, so a bare exported name
		// under (in-package linalg) references the qualified library defun.
		List<String> names = definedNames(spliceAndPrune("""
				(in-package :linalg)
				(cl:print (to-list (zeros (cl:quote (2)))))
				"""));
		assertThat(names).contains("linalg:zeros", "linalg:to-list").doesNotContain("linalg:det");
	}

	@Test
	void aResidualRuntimeLoadKeepsEverything() {
		String source = "(print (linalg:shape (linalg:zeros '(2)))) (load (concatenate 'string \"x\" \".lisp\"))";
		List<LispVal> spliced = splice(source);
		assertThat(LibraryDefunPruner.prune(spliced)).isSameAs(spliced);
	}

	@Test
	void usocketDefinitionsAreNeverPruned() {
		// usocket's with-* macros synthesize socket calls the AST does not contain, so
		// the whole package is excluded from pruning.
		List<String> names = definedNames(
				spliceAndPrune("(print (usocket:socket-stream (usocket:socket-connect \"127.0.0.1\" 1234)))"));
		assertThat(names).contains("usocket:socket-close", "usocket::%usock-resignal", "usocket:get-peer-address");
	}

	@Test
	void httpLispDefinitionsAreNeverPruned() {
		// http.lisp (the --component fetch/serve glue over wit-imported wasi:http) is
		// not a prunable library: its defuns -- including the stream/future body
		// helpers -- must survive the default-on pruner, or a compiled fetch would
		// error at runtime. HttpLibrary does its own reachability-based member filter.
		List<LispVal> spliced = HttpLibrary.process(
				LispReader.readAllFromString("(print (rontolisp:await (rontolisp:fetch \"http://example.com\")))"),
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, false);
		List<String> names = definedNames(LibraryDefunPruner.prune(spliced));
		assertThat(names).contains("rontolisp:fetch", "%http-read-all", "%http-write-body", "%fetch-send");
	}

	@Test
	void aProgramWithoutLibrariesIsReturnedUnchanged() {
		List<LispVal> program = LispReader.readAllFromString("(defun f (x) (+ x 1)) (print (f 2))");
		assertThat(LibraryDefunPruner.prune(program)).isSameAs(program);
	}

	@Test
	void survivingFormsKeepTheirOrderAndPrinting() {
		String source = "(print (linalg:to-list (linalg:zeros '(2))))";
		List<LispVal> spliced = splice(source);
		List<LispVal> pruned = LibraryDefunPruner.prune(spliced);
		assertThat(pruned.size()).isLessThan(spliced.size());
		// pruned is a subsequence of spliced: same objects, same relative order
		int j = 0;
		for (LispVal form : spliced) {
			if (j < pruned.size() && pruned.get(j) == form) {
				j++;
			}
		}
		assertThat(j).isEqualTo(pruned.size());
		// the user form survives verbatim at the end
		assertThat(pruned.get(pruned.size() - 1).print()).isEqualTo(spliced.get(spliced.size() - 1).print());
	}

	@Test
	void jsonParseClosureKeepsTheParserHelpers() {
		List<String> names = definedNames(spliceAndPrune("(print (rontolisp:json-parse \"[1,2]\"))"));
		assertThat(names).contains("rontolisp::%json-parse", "rontolisp::%json-value", "rontolisp::%json-array")
			// the stringify side is unreachable from parse alone (shared low-level
			// helpers like %json-concat stay, but the %json-out* family goes)
			.doesNotContain("rontolisp::%json-out", "rontolisp::%json-stringify");
	}

}
