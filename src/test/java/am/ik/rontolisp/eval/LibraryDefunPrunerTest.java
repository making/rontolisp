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
		return UsocketLibrary.process(VecLibrary.process(LispPreludeLibrary.process(UrlLibrary.process(LinalgLibrary
			.process(TorchLibrary.process(JsonLibrary.process(LispReader.readAllFromString(source))))))));
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
	void keepsOnlyTheTransitiveClosureOfTheCalledTorchFunction() {
		List<String> names = definedNames(spliceAndPrune("(print (torch:data (torch:tensor '(1.0 2.0))))"));
		assertThat(names).contains("TORCH:TENSOR", "TORCH:DATA")
			// the record constructor and the data coercion funnel tensor goes through
			.contains("TORCH::%T-NEW", "TORCH::%T-AS-DATA")
			// the linalg defuns the kept torch bodies reference stay too
			.contains("LINALG:FROM-LIST")
			// unrelated members of both libraries are dropped
			.doesNotContain("TORCH:BACKWARD", "TORCH:MATMUL", "TORCH:SOFTMAX", "LINALG:DET", "LINALG:MATMUL");
	}

	@Test
	void torchNoGradKeepsTheSynthesizedGradEnabledVariable() {
		// (torch:no-grad ...) expands to a let over torch::*grad-enabled* AFTER the
		// pruner runs (LispMacroExpander.expandTorchNoGrad), so the variable is a
		// hardcoded edge of the macro name -- the vec:aref -> vec:aset pattern.
		List<String> names = definedNames(spliceAndPrune("(torch:no-grad (print (torch:data (torch:tensor 1.0))))"));
		assertThat(names).contains("TORCH::*GRAD-ENABLED*");
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

	// The surviving top-level operator+name pairs, so CLOS definitions are visible to
	// the assertions (definedNames sees only the defun family).
	private static List<String> survivingHeads(List<LispVal> program) {
		List<String> heads = new ArrayList<>();
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& cons.cdr() instanceof LispCons rest) {
				String name = switch (rest.car()) {
					case LispSymbol sym -> sym.name();
					// (defmethod (setf place) ...) renders as "(SETF PLACE)"; an
					// option-headed (defstruct (name (:opt ...)) ...) as its name.
					case LispCons designator when designator.car() instanceof LispSymbol head ->
						"SETF".equals(LispSymbol.memberName(head.name()))
								&& designator.cdr() instanceof LispCons placeCell
								&& placeCell.car() instanceof LispSymbol place
										? "(SETF " + LispSymbol.memberName(place.name()) + ")" : head.name();
					default -> null;
				};
				if (name != null) {
					heads.add(LispSymbol.memberName(op.name()) + " " + LispSymbol.memberName(name));
				}
			}
		}
		return heads;
	}

	private static List<String> systemSurvivingHeads(String source, Map<String, String> files) {
		return survivingHeads(LibraryDefunPruner.prune(UserMacroExpander.expand(spliceSystem(source, files))));
	}

	@Test
	void unreferencedClosDefinitionsArePrunedAndTheirDefunClosureWithThem() {
		// The CLOS definition kinds are candidates like defuns now: a class, condition,
		// struct, generic and method nothing references leave, and a defun only their
		// bodies mentioned leaves with them.
		List<String> heads = systemSurvivingHeads("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct point x y)
				(defclass shape () ((size :initarg :size)))
				(define-condition demo-error (error) ())
				(defgeneric area (s))
				(defmethod area ((s shape)) (scale 2))
				(defun scale (n) n)
				(defun used () 1)
				"""));
		assertThat(heads).contains("DEFUN USED")
			.doesNotContain("DEFSTRUCT POINT", "DEFCLASS SHAPE", "DEFINE-CONDITION DEMO-ERROR", "DEFGENERIC AREA",
					"DEFMETHOD AREA", "DEFUN SCALE");
	}

	@Test
	void aReferencedGenericKeepsItsMethodsAndTheirSpecializerClasses() {
		List<String> heads = systemSurvivingHeads("(asdf:load-system :demo) (print (demo::area (demo:used)))",
				demoSystem("""
						(defclass shape () ((size :initarg :size)))
						(defgeneric area (s))
						(defmethod area ((s shape)) (scale 2))
						(defun scale (n) n)
						(defun used () (make-instance 'shape :size 1))
						"""));
		assertThat(heads).contains("DEFGENERIC AREA", "DEFMETHOD AREA", "DEFCLASS SHAPE", "DEFUN SCALE", "DEFUN USED");
	}

	@Test
	void aMethodOnALiveGenericSpecializingADeadClassIsPruned() {
		// Per-method reachability: AREA is called, but no instance of GHOST can exist
		// (nothing references the class), so its method leaves while SHAPE's stays. A
		// method on a built-in type specializer has no class to gate on and stays with
		// the generic.
		List<String> heads = systemSurvivingHeads(
				"(asdf:load-system :demo) (print (demo::area (make-instance 'demo::shape)))", demoSystem("""
						(defclass shape () ())
						(defclass ghost () ())
						(defgeneric area (s))
						(defmethod area ((s shape)) 1)
						(defmethod area ((s ghost)) (ghost-helper))
						(defmethod area ((s string)) 3)
						(defun ghost-helper () 2)
						"""));
		assertThat(heads).contains("DEFCLASS SHAPE", "DEFMETHOD AREA", "DEFMETHOD AREA")
			.doesNotContain("DEFCLASS GHOST", "DEFUN GHOST-HELPER");
		assertThat(heads.stream().filter("DEFMETHOD AREA"::equals)).hasSize(2);
	}

	@Test
	void aLiveSubclassKeepsTheMethodsOnItsSuperclass() {
		// Applicability flows through inheritance textually: instantiating the subclass
		// references the superclass in its defclass form, so a method specializing on
		// the superclass stays reachable.
		List<String> heads = systemSurvivingHeads(
				"(asdf:load-system :demo) (print (demo::area (make-instance 'demo::square)))", demoSystem("""
						(defclass shape () ())
						(defclass square (shape) ())
						(defgeneric area (s))
						(defmethod area ((s shape)) 1)
						"""));
		assertThat(heads).contains("DEFCLASS SHAPE", "DEFCLASS SQUARE", "DEFMETHOD AREA");
	}

	@Test
	void aProtocolMethodIsGatedByItsSpecializerAlone() {
		// initialize-instance/print-object calls are synthesized by the expansions with
		// no textual reference, so methods on CL protocol names have no generic gate:
		// the live class keeps its :after method, the dead class loses it -- and the
		// defun only the dead one called leaves with it.
		List<String> heads = systemSurvivingHeads("(asdf:load-system :demo) (print (make-instance 'demo::live-class))",
				demoSystem("""
						(defclass live-class () ())
						(defclass dead-class () ())
						(defmethod initialize-instance :after ((obj live-class) &key) (live-hook obj))
						(defmethod initialize-instance :after ((obj dead-class) &key) (dead-hook obj))
						(defmethod print-object ((obj dead-class) stream) stream)
						(defun live-hook (o) o)
						(defun dead-hook (o) o)
						"""));
		assertThat(heads).contains("DEFCLASS LIVE-CLASS", "DEFMETHOD INITIALIZE-INSTANCE", "DEFUN LIVE-HOOK")
			.doesNotContain("DEFCLASS DEAD-CLASS", "DEFMETHOD PRINT-OBJECT", "DEFUN DEAD-HOOK");
		assertThat(heads.stream().filter("DEFMETHOD INITIALIZE-INSTANCE"::equals)).hasSize(1);
	}

	@Test
	void anAccessorReferenceKeepsTheDefiningClassButProvesNoInstance() {
		// (size x) needs the class kept so the reader generic exists -- but an accessor
		// reference alone is not an instantiation, so a protocol method on the class
		// still leaves when nothing can create one.
		List<String> heads = systemSurvivingHeads("(asdf:load-system :demo) (print (demo::size demo::*thing*))",
				demoSystem("""
						(defclass shape () ((size :initarg :size :accessor size)))
						(defmethod initialize-instance :after ((obj shape) &key) obj)
						(defparameter *thing* nil)
						"""));
		assertThat(heads).contains("DEFCLASS SHAPE").doesNotContain("DEFMETHOD INITIALIZE-INSTANCE");
	}

	@Test
	void structGeneratedNamesEachKeepTheDefstruct() {
		Map<String, String> files = demoSystem("""
				(defstruct point x y)
				(defstruct (rect (:constructor build-rect) (:conc-name rc-) (:predicate rectp)) w h)
				(defstruct orphan z)
				(defun used () 1)
				""");
		assertThat(systemSurvivingHeads("(asdf:load-system :demo) (print (demo::make-point :x 1))", files))
			.contains("DEFSTRUCT POINT")
			.doesNotContain("DEFSTRUCT ORPHAN", "DEFSTRUCT RECT");
		assertThat(systemSurvivingHeads("(asdf:load-system :demo) (print (demo::point-x demo::pt))", files))
			.contains("DEFSTRUCT POINT");
		assertThat(systemSurvivingHeads("(asdf:load-system :demo) (print (demo::copy-point demo::pt))", files))
			.contains("DEFSTRUCT POINT");
		assertThat(systemSurvivingHeads("(asdf:load-system :demo) (print (demo::rc-w demo::r))", files))
			.contains("DEFSTRUCT RECT")
			.doesNotContain("DEFSTRUCT POINT");
		assertThat(systemSurvivingHeads("(asdf:load-system :demo) (print (demo::rectp demo::r))", files))
			.contains("DEFSTRUCT RECT");
	}

	@Test
	void anIncludingStructsInheritedAccessorKeepsIt() {
		// child-x reads an inherited slot: the accessor name is generated over the
		// :include parent's slot list, so the child must be keyed under it -- and the
		// kept child's form references the parent, which :include needs registered.
		List<String> heads = systemSurvivingHeads("(asdf:load-system :demo) (print (demo::child-x demo::c))",
				demoSystem("""
						(defstruct parent x)
						(defstruct (child (:include parent)) y)
						(defun used () 1)
						"""));
		assertThat(heads).contains("DEFSTRUCT CHILD", "DEFSTRUCT PARENT");
	}

	@Test
	void aConditionIsKeptByItsSignallingReferenceOrItsReader() {
		Map<String, String> files = demoSystem("""
				(define-condition demo-error (error) ((code :initarg :code :reader demo-error-code)))
				(define-condition dead-error (error) ())
				(defun used () 1)
				""");
		assertThat(systemSurvivingHeads("(asdf:load-system :demo) (error 'demo::demo-error :code 1)", files))
			.contains("DEFINE-CONDITION DEMO-ERROR")
			.doesNotContain("DEFINE-CONDITION DEAD-ERROR");
		assertThat(systemSurvivingHeads(
				"(asdf:load-system :demo) (handler-case (demo:used) (error (e) (demo::demo-error-code e)))", files))
			.contains("DEFINE-CONDITION DEMO-ERROR");
	}

	@Test
	void aMetaclassDefclassStaysARoot() {
		// A (:metaclass ...) definition runs the ensure-class driver at load time --
		// user :around code with arbitrary side effects -- so it is never a candidate.
		List<String> heads = systemSurvivingHeads("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defclass meta-thing () ((x :initarg :x)) (:metaclass standard-class))
				(defun used () 1)
				"""));
		assertThat(heads).contains("DEFCLASS META-THING");
	}

	@Test
	void aSetfMethodIsKeyedUnderItsPlaceName() {
		// The place reference (setf (width ...)) is what keeps the writer generic: the
		// defgeneric survives (so the place stays registrable and dispatchable), and
		// with the class instantiated the methods survive too.
		List<String> heads = systemSurvivingHeads("""
				(asdf:load-system :demo)
				(defun poke (r) (setf (demo::width r) 3))
				(print (poke (make-instance 'demo::rect)))
				""", demoSystem("""
				(defclass rect () ())
				(defgeneric width (r))
				(defgeneric (setf width) (v r))
				(defmethod width ((r rect)) 1)
				(defmethod (setf width) (v (r rect)) v)
				(defclass dead () ())
				"""));
		assertThat(heads)
			.contains("DEFMETHOD (SETF WIDTH)", "DEFGENERIC (SETF WIDTH)", "DEFMETHOD WIDTH", "DEFCLASS RECT")
			.doesNotContain("DEFCLASS DEAD");
	}

	@Test
	void aMethodOnlySetfGenericKeepsItsMethodOnThePlaceReferenceAlone() {
		// No (defgeneric (setf title)) anywhere: pruning the only setf method would
		// leave a kept (setf (title ...)) place with no writer registration to expand
		// through, so a method-only setf generic has no specializer gate.
		List<String> heads = systemSurvivingHeads("""
				(asdf:load-system :demo)
				(defun poke (r) (setf (demo::title r) 3))
				(print (poke nil))
				""", demoSystem("""
				(defclass rect () ())
				(defmethod (setf title) (v (r rect)) v)
				"""));
		assertThat(heads).contains("DEFMETHOD (SETF TITLE)");
	}

	@Test
	void aMethodOnlyLocalGenericKeepsItsMethodsOnceTheNameIsLive() {
		// No defgeneric anywhere: pruning the last method of a live name would leave a
		// kept call site compiling against no definition at all, so a method-only
		// generic has no specializer gate -- and the kept method's own specializer
		// keeps its class registered.
		List<String> heads = systemSurvivingHeads("""
				(asdf:load-system :demo)
				(defun poke (x) (demo::render x))
				(print (poke nil))
				""", demoSystem("""
				(defclass never-made () ())
				(defmethod render ((x never-made)) x)
				"""));
		assertThat(heads).contains("DEFMETHOD RENDER", "DEFCLASS NEVER-MADE");
	}

	@Test
	void aForgedNameTemplateKeepsEveryDefinitionItCanProduce() {
		// sxql's find-constructor: (symbol-function (intern (concatenate 'string
		// "MAKE-" (symbol-name x) suffix))) resolves struct constructors the program
		// never spells. The literal pieces form a template; every member it can
		// produce counts as referenced -- while a definition outside the template
		// still prunes.
		List<String> heads = systemSurvivingHeads("(asdf:load-system :demo) (print (demo:used :=))", demoSystem("""
				(defstruct (=-op (:constructor make-=-op)) left right)
				(defstruct unrelated-thing a)
				(defun used (name)
				  (funcall (symbol-function
				            (intern (concatenate 'string "MAKE-" (symbol-name name) "-OP")))))
				"""));
		assertThat(heads).contains("DEFSTRUCT =-OP").doesNotContain("DEFSTRUCT UNRELATED-THING");
		// The format spelling of the same forge, with the pieces in a control string.
		List<String> formatHeads = systemSurvivingHeads("(asdf:load-system :demo) (print (demo:used :=))",
				demoSystem("""
						(defstruct (=-op (:constructor make-=-op)) left right)
						(defstruct unrelated-thing a)
						(defun used (name)
						  (funcall (symbol-function (intern (format nil "MAKE-~a-OP" name)))))
						"""));
		assertThat(formatHeads).contains("DEFSTRUCT =-OP").doesNotContain("DEFSTRUCT UNRELATED-THING");
	}

	@Test
	void subclassEnumerationRootsEveryClass() {
		// cl-dbi's find-driver reaches a driver class through
		// (c2mop:class-direct-subclasses (find-class 'dbi-driver)) and a forged
		// string -- no name reference at all -- so a program that enumerates
		// subclasses keeps every third-party class; defuns still prune.
		List<String> heads = systemSurvivingHeads("""
				(asdf:load-system :demo)
				(print (demo:used "x"))
				""", demoSystem("""
				(defclass driver-base () ())
				(defclass secret-driver (driver-base) ())
				(defun used (name)
				  (find name (%class-direct-subclasses (find-class 'driver-base))
				        :key (lambda (c) c) :test (lambda (a b) t)))
				(defun unused () 1)
				"""));
		assertThat(heads).contains("DEFCLASS SECRET-DRIVER", "DEFCLASS DRIVER-BASE").doesNotContain("DEFUN UNUSED");
	}

	@Test
	void defgenericInlineMethodsFallAndStayWithTheGeneric() {
		Map<String, String> files = demoSystem("""
				(defgeneric describe-it (x) (:method ((x string)) x))
				(defgeneric dead-generic (x) (:method ((x string)) x))
				(defun used () 1)
				""");
		assertThat(systemSurvivingHeads("(asdf:load-system :demo) (print (demo::describe-it \"a\"))", files))
			.contains("DEFGENERIC DESCRIBE-IT")
			.doesNotContain("DEFGENERIC DEAD-GENERIC");
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

	// The printed body of the surviving definition whose name has the given member.
	private static String survivingPrintOf(List<LispVal> program, String member) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name && member.equals(LispSymbol.memberName(name.name()))) {
				return form.print();
			}
		}
		return "";
	}

	private static List<LispVal> systemPruned(String source, Map<String, String> files) {
		return LibraryDefunPruner.prune(UserMacroExpander.expand(spliceSystem(source, files)));
	}

	@Test
	void aCaseArmNoCallerConstantCanReachIsFoldedAndItsTreeWithIt() {
		// Stage A (ConstantCaseArmPruner): every call site passes 'fast, so the
		// (:slow slow) arm can never run -- it is deleted from make-thing and the slow
		// constructor tree leaves with it. The default (t ...) arm always stays.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used 'demo::fast))", demoSystem("""
				(defun make-thing (format)
				  (case format
				    ((:fast fast) (make-fast))
				    ((:slow slow) (make-slow))
				    (t (error "bad format"))))
				(defun make-fast () 1)
				(defun make-slow () 2)
				(defun used (format) (make-thing format))
				"""));
		assertThat(memberNames(program)).contains("MAKE-FAST").doesNotContain("MAKE-SLOW");
		assertThat(survivingPrintOf(program, "MAKE-THING")).contains(":FAST").contains("ERROR").doesNotContain(":SLOW");
	}

	@Test
	void aCaseArmSurvivesWhenTheDispatchArgumentEscapes() {
		// #'make-thing outside funcall/apply head position: calls through the escaped
		// value are invisible, so every arm stays reachable.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used 'demo::fast))"
				+ " (print (mapcar #'demo::make-thing (list :fast)))", demoSystem("""
						(defun make-thing (format)
						  (case format
						    ((:fast fast) (make-fast))
						    ((:slow slow) (make-slow))
						    (t (error "bad format"))))
						(defun make-fast () 1)
						(defun make-slow () 2)
						(defun used (format) (make-thing format))
						"""));
		assertThat(memberNames(program)).contains("MAKE-FAST", "MAKE-SLOW");
		assertThat(survivingPrintOf(program, "MAKE-THING")).contains(":SLOW");
	}

	@Test
	void theCallerConstantFlowsThroughAGenericAndApplyIntoTheCaseFold() {
		// The chipz shape end to end: user constant -> the defgeneric's default method
		// -> %decomp -> make-thing, with the recursive (apply #'decomp output state ...)
		// contributing only a NON-KEY struct instance -- so the :slow arm folds, its
		// constructor dies, and the slow struct definition leaves with it.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct fast-state)
				(defstruct slow-state)
				(defgeneric decomp (output state input &key &allow-other-keys)
				  (:method (output format input &rest keys)
				    (%decomp output format input keys)))
				(defmethod decomp ((output null) (state fast-state) (input vector) &key)
				  :inflated)
				(defun %decomp (output format input keys)
				  (let ((state (make-thing format)))
				    (apply #'decomp output state input keys)))
				(defun make-thing (format)
				  (case format
				    ((:fast fast) (make-fast-state))
				    ((:slow slow) (make-slow-state))
				    (t (error "bad format"))))
				(defun used () (decomp nil 'fast (vector 1)))
				"""));
		assertThat(survivingHeads(program)).contains("DEFSTRUCT FAST-STATE").doesNotContain("DEFSTRUCT SLOW-STATE");
		assertThat(survivingPrintOf(program, "MAKE-THING")).doesNotContain(":SLOW");
	}

	@Test
	void aCaseArmNoStoredSlotValueCanReachIsFolded() {
		// Slot-carried constants: the only writer of THING's MODE slot is the BOA
		// constructor, called with 'fast everywhere -- so an ecase over the slot READ
		// can only see FAST, whatever instance flows in, and the slow arm folds.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct (thing (:constructor %make-thing (mode))) (mode 'fast))
				(defun run (thing)
				  (ecase (thing-mode thing)
				    (fast (do-fast))
				    (slow (do-slow))))
				(defun do-fast () 1)
				(defun do-slow () 2)
				(defun used () (run (%make-thing 'fast)))
				"""));
		assertThat(memberNames(program)).contains("DO-FAST").doesNotContain("DO-SLOW");
		assertThat(survivingPrintOf(program, "RUN")).doesNotContain("SLOW");
	}

	@Test
	void aDefaultedSlotContributesItsInitformValue() {
		// (make-thing) leaves MODE defaulted, so the initform's 'slow is a stored value:
		// the slow arm stays, and the fast arm (which nothing stores) folds.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct thing (mode 'slow))
				(defun run (thing)
				  (ecase (thing-mode thing)
				    (fast (do-fast))
				    (slow (do-slow))))
				(defun do-fast () 1)
				(defun do-slow () 2)
				(defun used () (run (make-thing)))
				"""));
		assertThat(memberNames(program)).contains("DO-SLOW").doesNotContain("DO-FAST");
	}

	@Test
	void aSetfOfTheSlotJoinsTheWrittenValue() {
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct (thing (:constructor %make-thing (mode))) (mode 'fast))
				(defun run (thing which)
				  (setf (thing-mode thing) which)
				  (ecase (thing-mode thing)
				    (fast (do-fast))
				    (slow (do-slow))))
				(defun do-fast () 1)
				(defun do-slow () 2)
				(defun used () (run (%make-thing 'fast) 'slow))
				"""));
		assertThat(memberNames(program)).contains("DO-FAST", "DO-SLOW");
	}

	@Test
	void aWriteThroughAChildConstructorReachesTheParentAccessorsRead() {
		// :include aliases storage: the child's BOA constructor writes the slot the
		// parent's accessor reads, so the join must be shared along the chain.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct (base (:conc-name b-)) (mode 'fast))
				(defstruct (child (:include base) (:constructor %make-child (mode))))
				(defun run (x)
				  (ecase (b-mode x)
				    (fast (do-fast))
				    (slow (do-slow))))
				(defun do-fast () 1)
				(defun do-slow () 2)
				(defun used () (run (%make-child 'slow)))
				"""));
		assertThat(memberNames(program)).contains("DO-SLOW").doesNotContain("DO-FAST");
	}

	@Test
	void aReadModifyWriteOnTheSlotPlaceKeepsEveryArm() {
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct (thing (:constructor %make-thing (mode))) (mode 'fast))
				(defun run (thing)
				  (push 1 (thing-mode thing))
				  (ecase (thing-mode thing)
				    (fast (do-fast))
				    (slow (do-slow))))
				(defun do-fast () 1)
				(defun do-slow () 2)
				(defun used () (run (%make-thing 'fast)))
				"""));
		assertThat(memberNames(program)).contains("DO-FAST", "DO-SLOW");
	}

	@Test
	void anEscapedAccessorNameKeepsEveryArm() {
		// 'thing-mode in quoted data: an eval-family forge could write through the
		// accessor, so the slot goes wide and both arms stay.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used)) (print '(demo::thing-mode))",
				demoSystem("""
						(defstruct (thing (:constructor %make-thing (mode))) (mode 'fast))
						(defun run (thing)
						  (ecase (thing-mode thing)
						    (fast (do-fast))
						    (slow (do-slow))))
						(defun do-fast () 1)
						(defun do-slow () 2)
						(defun used () (run (%make-thing 'fast)))
						"""));
		assertThat(memberNames(program)).contains("DO-FAST", "DO-SLOW");
	}

	@Test
	void aRuntimeReadStandsTheSlotTrackingDown() {
		// (read) can construct a #S literal with slot values this walk never saw.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used)) (print (read))",
				demoSystem("""
						(defstruct (thing (:constructor %make-thing (mode))) (mode 'fast))
						(defun run (thing)
						  (ecase (thing-mode thing)
						    (fast (do-fast))
						    (slow (do-slow))))
						(defun do-fast () 1)
						(defun do-slow () 2)
						(defun used () (run (%make-thing 'fast)))
						"""));
		assertThat(memberNames(program)).contains("DO-FAST", "DO-SLOW");
	}

	@Test
	void aTypecaseArmOnAnUninstantiatedStructIsDeletedAndItsBodyPrunedWithIt() {
		// Stage B: no instantiator of SLOW-STATE is live, so the (slow-state #'do-slow)
		// arm contributes no references -- do-slow and the struct leave, and the arm is
		// deleted from the surviving defun (its #'do-slow would no longer compile).
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct fast-state)
				(defstruct slow-state)
				(defun pick (state)
				  (typecase state
				    (fast-state #'do-fast)
				    (slow-state #'do-slow)))
				(defun do-fast () 1)
				(defun do-slow () 2)
				(defun used () (funcall (pick (make-fast-state))))
				"""));
		assertThat(memberNames(program)).contains("DO-FAST").doesNotContain("DO-SLOW");
		assertThat(survivingHeads(program)).doesNotContain("DEFSTRUCT SLOW-STATE");
		assertThat(survivingPrintOf(program, "PICK")).contains("FAST-STATE").doesNotContain("SLOW-STATE");
	}

	@Test
	void aReadModifyWritePlaceKeepsTheCaseSubjectUnknown() {
		// (setf (ldb ...) result) assigns RESULT even though the place is a cons --
		// cl-postgres's generated read-uint4 builds its network integer exactly this
		// way, and folding its callers' ecase arms broke AUTHENTICATE. Every symbol
		// inside a non-symbol place is poisoned, so both arms stay.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defun read-code ()
				  (let ((result 0))
				    (setf (ldb (byte 8 0) result) 3)
				    result))
				(defun dispatch (code)
				  (ecase code
				    (3 (do-three))
				    (10 (do-ten))))
				(defun do-three () 3)
				(defun do-ten () 10)
				(defun used () (dispatch (read-code)))
				"""));
		assertThat(memberNames(program)).contains("DO-THREE", "DO-TEN");
		assertThat(survivingPrintOf(program, "DISPATCH")).contains("DO-TEN");
	}

	@Test
	void aTypecaseArmOpensWhenAnInstantiatorOfItsHeadGoesLive() {
		// The gate is monotone: the moment a live form references a constructor, the
		// arm's references join the fixpoint and everything stays.
		List<LispVal> program = systemPruned("(asdf:load-system :demo) (print (demo:used))", demoSystem("""
				(defstruct fast-state)
				(defstruct slow-state)
				(defun pick (state)
				  (typecase state
				    (fast-state #'do-fast)
				    (slow-state #'do-slow)))
				(defun do-fast () 1)
				(defun do-slow () 2)
				(defun used () (list (funcall (pick (make-fast-state))) (make-slow-state)))
				"""));
		assertThat(memberNames(program)).contains("DO-FAST", "DO-SLOW");
		assertThat(survivingHeads(program)).contains("DEFSTRUCT SLOW-STATE");
		assertThat(survivingPrintOf(program, "PICK")).contains("SLOW-STATE");
	}

}
