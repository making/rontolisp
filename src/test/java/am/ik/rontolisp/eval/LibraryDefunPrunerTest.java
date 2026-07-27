package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.LoadInliner;
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
				&& (op.name().equals("DEFUN") || op.name().equals("DEFPARAMETER") || op.name().equals("DEFVAR")
						|| op.name().equals("DEFCONSTANT"))
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		return null;
	}

	// An ASDF system spliced from an in-memory "disk", then run through the CLI's
	// compile-path chain and pruned -- the third-party half of the pass. LoadInliner
	// brackets what it splices with the provenance markers the pruner reads.
	private static List<LispVal> spliceSystem(String source, Map<String, String> files) {
		List<LispVal> loaded = LoadInliner.inline(LispReader.readAllFromString(source), path -> {
			String src = files.get(path);
			if (src == null) {
				throw new java.io.FileNotFoundException(path);
			}
			return src;
		});
		return UsocketLibrary.process(GrayStreamsLibrary.process(LispPreludeLibrary.process(loaded)));
	}

	// The surviving names by MEMBER. A form UserMacroExpander rebuilt comes back
	// canonically qualified (DEMO:USED) while one it left alone keeps the library's raw
	// spelling (USED); which of the two a defun gets is not what these tests are about.
	private static List<String> memberNames(List<LispVal> program) {
		return definedNames(program).stream().map(LispSymbol::memberName).toList();
	}

	private static List<String> systemDefinedNames(String source, Map<String, String> files) {
		return memberNames(LibraryDefunPruner.prune(UserMacroExpander.expand(spliceSystem(source, files))));
	}

	// A one-file system named "demo" exporting USED; the caller supplies its body.
	private static Map<String, String> demoSystem(String body) {
		return Map.of("demo.asd", "(defsystem :demo :components ((:file \"demo\")))", "demo.lisp",
				"(defpackage :demo (:use :cl) (:export :used))\n(in-package :demo)\n" + body);
	}

	@Test
	void keepsOnlyTheTransitiveClosureOfTheCalledLinalgFunction() {
		List<String> names = definedNames(spliceAndPrune("(print (linalg:to-list (linalg:zeros '(2 2))))"));
		assertThat(names).contains("LINALG:ZEROS", "LINALG:TO-LIST")
			// %la-make is the constructor funnel zeros goes through
			.contains("LINALG::%LA-MAKE")
			// unrelated members of the library are dropped
			.doesNotContain("LINALG:DET", "LINALG:INV", "LINALG:RANDN", "LINALG:MATMUL");
	}

	@Test
	void dropsTheRngSeedsWhenNoRngFunctionIsReachable() {
		List<String> names = definedNames(spliceAndPrune("(print (linalg:to-list (linalg:zeros '(2))))"));
		assertThat(names).doesNotContain("LINALG::%LA-RNG-S1", "LINALG::%LA-RNG-S2", "LINALG::%LA-RNG-S3",
				"LINALG::%LA-RNG-NEXT");
	}

	@Test
	void keepsTheRngSeedsThroughTheRngClosure() {
		List<String> names = definedNames(
				spliceAndPrune("(linalg:seed 42) (print (linalg:to-list (linalg:randn '(2))))"));
		assertThat(names).contains("LINALG:SEED", "LINALG:RANDN", "LINALG::%LA-RNG-NEXT", "LINALG::%LA-RNG-S1",
				"LINALG::%LA-RNG-S2", "LINALG::%LA-RNG-S3");
	}

	@Test
	void setfOfVecArefKeepsTheSynthesizedVecAset() {
		// (setf (vec:aref v i) x) expands to (vec:aset v i x) AFTER the pruner runs,
		// so vec:aset is a hardcoded edge of vec:aref.
		List<String> names = definedNames(
				spliceAndPrune("(let ((v (vec:zeros 3))) (setf (vec:aref v 0) 1.0) (print (vec:aref v 0)))"));
		assertThat(names).contains("VEC:AREF", "VEC:ASET");
	}

	@Test
	void functionQuoteAndQuotedDesignatorAndStringLiteralAllCountAsReferences() {
		assertThat(definedNames(spliceAndPrune("(print (funcall #'linalg:ndim (linalg:zeros '(2))))")))
			.contains("LINALG:NDIM");
		assertThat(definedNames(spliceAndPrune("(print (funcall 'linalg:ndim (linalg:zeros '(2))))")))
			.contains("LINALG:NDIM");
		// A string literal containing the qualified name keeps the target: the
		// carve-out for (intern "...")/read-from-string idioms.
		assertThat(definedNames(spliceAndPrune("(print (linalg:shape (linalg:zeros '(2)))) (print \"linalg:ndim\")")))
			.contains("LINALG:NDIM");
	}

	@Test
	void bareNamesInsideInPackageResolveAgainstTheLibrary() {
		// The analysis runs on a PackageResolver-resolved copy, so a bare exported name
		// under (in-package linalg) references the qualified library defun.
		List<String> names = definedNames(spliceAndPrune("""
				(in-package :linalg)
				(cl:print (to-list (zeros (cl:quote (2)))))
				"""));
		assertThat(names).contains("LINALG:ZEROS", "LINALG:TO-LIST").doesNotContain("LINALG:DET");
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
		assertThat(names).contains("USOCKET:SOCKET-CLOSE", "USOCKET::%USOCK-RESIGNAL", "USOCKET:GET-PEER-ADDRESS");
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
		assertThat(names).contains("RONTOLISP:FETCH", "%HTTP-BODY-VALUE", "%HTTP-WRITE-BODY", "%FETCH-SEND");
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
	void aThirdPartySystemsUnreachableDefinitionsAreDropped() {
		List<String> names = systemDefinedNames("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defun helper () 1)
				(defun used () (helper))
				(defun unused () 2)
				(defvar *dead-var* 3)
				(defconstant +dead-constant+ 4)
				"""));
		assertThat(names).contains("USED", "HELPER").doesNotContain("UNUSED", "*DEAD-VAR*", "+DEAD-CONSTANT+");
	}

	@Test
	void aThirdPartyVariableWithAnImpureInitformStaysEvenWhenNobodyReadsIt() {
		// Dropping a definition drops its initform with it. A defun can only ever fail
		// loudly ("undefined function"), but a (defvar *x* (register ...)) nobody READS
		// would lose the registration silently -- so an initform that is not provably a
		// pure value computation keeps its definition, and everything it names.
		List<String> names = systemDefinedNames("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defvar *registry* (make-hash-table))
				(defun register (k) (setf (gethash k *registry*) t))
				(defvar *registered* (register :thing))
				(defparameter *pure-but-dead* (list 1 2 3))
				(defun used () 1)
				"""));
		assertThat(names).contains("*REGISTERED*", "REGISTER", "*REGISTRY*").doesNotContain("*PURE-BUT-DEAD*");
	}

	@Test
	void thirdPartyProvenanceSurvivesUserMacroExpansion() {
		// A library defun that calls the library's OWN macro is rebuilt by
		// UserMacroExpander (fresh cons cells, and the defmacro itself is dropped), which
		// is why provenance rides in the form list as a marker rather than on the form
		// objects or their indexes. Both defuns below take that path.
		List<String> names = systemDefinedNames("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defmacro twice (x) (list '* 2 x))
				(defun used () (twice 21))
				(defun unused () (twice 1))
				"""));
		assertThat(names).contains("USED").doesNotContain("UNUSED");
	}

	@Test
	void definitionsHiddenInAMacroProducedPrognAreStillPruned() {
		// UserMacroExpander flattens top-level progn/eval-when BEFORE expanding macros,
		// so
		// a progn a macro produced is never re-flattened -- the pruner does it itself, or
		// every definition inside one stays a root.
		List<String> names = systemDefinedNames("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defmacro defpair (a b) (list 'progn (list 'defun a '() 1) (list 'defun b '() 2)))
				(defpair alive orphan)
				(defun used () (alive))
				"""));
		assertThat(names).contains("ALIVE", "USED").doesNotContain("ORPHAN");
	}

	@Test
	void theUsersOwnDefinitionsAreNeverPrunedEvenWhenUnreachable() {
		// The entry program is the root set: only what LoadInliner spliced is prunable.
		List<String> names = systemDefinedNames("""
				(asdf:load-system :demo)
				(defun unreferenced-user-function () 1)
				(print (demo:used))
				""", demoSystem("(defun used () 1)\n(defun unused () 2)"));
		assertThat(names).contains("UNREFERENCED-USER-FUNCTION").doesNotContain("UNUSED");
	}

	@Test
	void anExportedButUnreferencedThirdPartyFunctionIsStillPruned() {
		// Load-bearing tripwire: the scan runs on the PackageResolver-resolved copy,
		// where
		// the whole defpackage has already become (quote DEMO). If a future change let
		// the
		// :export clause survive into that copy, every exported name would anchor its
		// definition and the pass would stop pruning a library's public API -- measured
		// at
		// 62% of the reachable win on the cl-postgres tree.
		List<String> names = definedNames(
				LibraryDefunPruner.prune(UserMacroExpander.expand(spliceSystem("(asdf:load-system :demo) (print 1)",
						Map.of("demo.asd", "(defsystem :demo :components ((:file \"demo\")))", "demo.lisp",
								"(defpackage :demo (:use :cl) (:export :exported-but-dead))\n(in-package :demo)\n"
										+ "(defun exported-but-dead () 1)")))));
		assertThat(names).doesNotContain("EXPORTED-BUT-DEAD");
	}

	@Test
	void anUninternedDesignatorAndAWholeStringLiteralKeepTheirThirdPartyTarget() {
		// (string '#:foo) / (find-symbol "FOO" :pkg) name a symbol without naming its
		// package, and find-symbol is folded at codegen time -- after this pass -- so the
		// designator is the only trace of the call it becomes.
		List<String> names = systemDefinedNames("""
				(asdf:load-system :demo)
				(print (funcall (intern (concatenate 'string "DEMO:" (string '#:by-designator)))))
				(print (find-symbol "BY-STRING" :demo))
				""", demoSystem("""
				(defun by-designator () 1)
				(defun by-string () 2)
				(defun neither () 3)
				"""));
		assertThat(names).contains("BY-DESIGNATOR", "BY-STRING").doesNotContain("NEITHER");
	}

	@Test
	void aDeclaimIsNotAReferenceSource() {
		// declaim/proclaim expand to nil on every backend, so nothing they name can be
		// called through them -- but an (inline F) specifier would otherwise anchor F.
		List<String> names = systemDefinedNames("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(declaim (inline dead-but-declaimed))
				(defun dead-but-declaimed () 1)
				(defun used () 2)
				"""));
		assertThat(names).contains("USED").doesNotContain("DEAD-BUT-DECLAIMED");
	}

	@Test
	void closAndStructureDefinitionsStayRootsAndKeepWhatTheyName() {
		// defclass/defmethod/defstruct/define-condition expand into defuns and
		// dispatchers
		// INSIDE the backends, after this pass, so they stay roots -- and a defun only
		// they mention stays alive with them.
		List<String> names = systemDefinedNames("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct point x y)
				(defclass shape () ((size :initarg :size)))
				(define-condition demo-error (error) ())
				(defgeneric area (s))
				(defmethod area ((s shape)) (scale 2))
				(defun scale (n) n)
				(defun used () 1)
				"""));
		assertThat(names).contains("SCALE", "USED");
	}

	@Test
	void aBuiltinSystemSplicedAsADependencyIsNeverPruned() {
		// usocket's with-* built-in macros synthesize socket-close/%usock-guard calls
		// that
		// are not textually present, so the shim stays whole -- and its own provenance
		// bracket is what stops it inheriting the depending system's prunability.
		List<String> names = systemDefinedNames("(asdf:load-system :netapp) (print (netapp:connect))",
				Map.of("netapp.asd", "(defsystem :netapp :depends-on (:usocket) :components ((:file \"netapp\")))",
						"netapp.lisp", """
								(defpackage :netapp (:use :cl) (:export :connect))
								(in-package :netapp)
								(defun connect () (usocket:socket-connect "127.0.0.1" 1234))
								"""));
		assertThat(names).contains("SOCKET-CLOSE", "%USOCK-RESIGNAL", "GET-PEER-ADDRESS");
	}

	@Test
	void theVendoredClPpcreLosesItsStaticallyDeadDefunsAndKeepsTheCalledOnes() {
		// Pins the vendored src/test/resources/cl-ppcre snapshot, not upstream. Both dead
		// names occur exactly once in that tree (their own defun) and neither is
		// exported, so no program can reach them.
		List<String> names = memberNames(LibraryDefunPruner.prune(UserMacroExpander
			.expand(UsocketLibrary.process(GrayStreamsLibrary.process(LispPreludeLibrary.process(LoadInliner.inline(
					LispReader.readAllFromString("(asdf:load-system :cl-ppcre) (print (cl-ppcre:scan \"a\" \"a\"))"),
					SourceLoader.fileSystem(), null, List.of("src/test/resources/cl-ppcre"))))))));
		// scan itself is a defgeneric, so the defuns on its path are what this asserts.
		assertThat(names).contains("CREATE-SCANNER-AUX", "PARSE-STRING")
			.doesNotContain("CHARMAP-CONTENTS", "STRING-LIST-TO-SIMPLE-STRING");
	}

	@Test
	void stripSystemMarkersDropsTheBracketsWithoutPruningAnything() {
		// What the CLI runs under --dynamic/--no-prune: those escape hatches must emit
		// the artifact they emitted before the markers existed.
		List<LispVal> spliced = spliceSystem("(asdf:load-system :demo) (print 1)",
				demoSystem("(defun used () 1)\n(defun unused () 2)"));
		List<LispVal> stripped = LibraryDefunPruner.stripSystemMarkers(spliced);
		assertThat(stripped.stream().map(LispVal::print))
			.noneMatch(form -> form.contains("%BEGIN-SYSTEM") || form.contains("%END-SYSTEM"));
		assertThat(definedNames(stripped)).contains("USED", "UNUSED");
		assertThat(stripped.size()).isEqualTo(spliced.size() - 2);
	}

	@Test
	void jsonParseClosureKeepsTheParserHelpers() {
		List<String> names = definedNames(spliceAndPrune("(print (rontolisp:json-parse \"[1,2]\"))"));
		assertThat(names).contains("RONTOLISP::%JSON-PARSE", "RONTOLISP::%JSON-VALUE", "RONTOLISP::%JSON-ARRAY")
			// the stringify side is unreachable from parse alone (shared low-level
			// helpers like %json-concat stay, but the %json-out* family goes)
			.doesNotContain("RONTOLISP::%JSON-OUT", "RONTOLISP::%JSON-STRINGIFY");
	}

}
