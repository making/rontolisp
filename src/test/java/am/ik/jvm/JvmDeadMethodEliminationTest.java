package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural + behavioral tests for the JVM dead-code eliminator, the shake
 * {@link JvmClassSplitter} applies as it writes a class. These verify its invariants on
 * real compiled classes: the output is smaller, unreachable methods and unreferenced
 * fields are gone, the class still loads (the JVM verifier is the well-formedness check)
 * and behaves identically, and dynamically-reached methods and the reflective
 * {@code _apply} root survive. The whole cross-backend feature corpus is exercised by
 * {@code JvmDeadMethodEliminationCorpusTest}.
 */
class JvmDeadMethodEliminationTest {

	@TempDir
	Path tempDir;

	private static byte[] compile(String source, OptimizeLevel optimize) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return JvmLispCompiler.builder().className("Test").optimize(optimize).build().compile(program);
	}

	// Loads the class (which makes the JVM verifier check the shaken bytecode), runs its
	// main, and returns the captured stdout.
	private String run(byte[] classBytes) throws Exception {
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			catch (InvocationTargetException ex) {
				if (ex.getCause() instanceof RuntimeException re) {
					throw re;
				}
				throw ex;
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	private List<String> declaredMethodNames(byte[] classBytes) throws Exception {
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			return Arrays.stream(loader.loadClass("Test").getDeclaredMethods()).map(Method::getName).toList();
		}
	}

	@Test
	void dropsUnreachableMethodsAndShrinksOutput() throws Exception {
		String source = "(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1))))) (print (fact 5))";
		byte[] plain = compile(source, OptimizeLevel.NONE);
		byte[] optimized = compile(source, OptimizeLevel.DEFAULT);

		assertThat(optimized.length).isLessThan(plain.length / 2);
		assertThat(declaredMethodNames(optimized).size()).isLessThan(declaredMethodNames(plain).size());
		assertThat(run(optimized)).isEqualTo("120");
	}

	@Test
	void dropsAnUncalledDefunAndItsHelpers() throws Exception {
		byte[] optimized = compile("(defun used (x) (+ x 1)) (defun unused (x) (car x)) (print (used 41))",
				OptimizeLevel.DEFAULT);
		List<String> names = declaredMethodNames(optimized);
		assertThat(names).contains("main", "USED").doesNotContain("UNUSED");
		assertThat(run(optimized)).isEqualTo("42");
	}

	@Test
	void dropsUnreferencedFields() throws Exception {
		byte[] optimized = compile("(print 1)", OptimizeLevel.DEFAULT);
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, optimized);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			// (print 1) tracks the stdout column (_col) but touches no stream/stdin
			// state, so those fields are dropped with the I/O helpers that used them.
			// The renderers' cycle-guard pair survives: _consToString (reachable from
			// _lispToString, which print uses) reads and writes both
			// (.kb/pretty-printer.md, "A cyclic value prints finitely"). So do the
			// sized-stack launcher's two instance fields, which main and _main$run use
			// (.kb/interpreter-stack.md).
			List<String> fieldNames = Arrays.stream(loader.loadClass("Test").getDeclaredFields())
				.map(java.lang.reflect.Field::getName)
				.toList();
			assertThat(fieldNames).containsExactly("_renderPath", "_renderDepth", "_col", "_main$args", "_main$thrown");
		}
	}

	@Test
	void keepsTransitivelyReachableRuntime() throws Exception {
		// Ratio arithmetic reaches the rational runtime helpers; they must survive.
		assertThat(run(compile("(print (+ 1/3 1/6))", OptimizeLevel.DEFAULT))).isEqualTo("1/2");
	}

	@Test
	void keepsDynamicallyReachedFunctionsThroughDispatch() throws Exception {
		// funcall/#'/eval resolve targets at runtime through the dispatch methods, whose
		// bodies contain real invokestatic calls -- reachability must keep every target.
		String source = """
				(defun add1 (x) (+ x 1))
				(print (funcall #'add1 41))
				(print (reduce #'+ (list 1 2 3)))
				(print (eval '(add1 4)))
				""";
		assertThat(run(compile(source, OptimizeLevel.DEFAULT))).isEqualTo("42\n6\n5");
	}

	@Test
	void internDoesNotHoldTheDispatchGateOpen() throws Exception {
		// A symbol BUILDER no longer bails the dispatch gate: whatever an intern can
		// produce that resolves is a spelling the class holds, and the
		// dispatchableFuncIds probes read every such spelling (symbol, framed string
		// literal, keyword, alias, bare member). A name forged out of computed pieces
		// is the LibraryDefunPruner carve-out (the ordinary undefined-function error),
		// so the computed intern and the quoted intern shape both leave UNUSED
		// shakeable. Only the data evaluators (eval/read/read-from-string/load) still
		// keep every function dispatchable -- their names arrive from OUTSIDE.
		// The funcall keeps the dispatch machinery emitted at all -- without one there
		// are no dispatch methods and UNUSED is dropped whatever the gate decides. Its
		// designator is COMPUTED on purpose: a designator the compiler can read is a
		// direct call and emits no dispatcher either, and so is one it can read through
		// a let temp (JvmDesignatorCall, LetBoundDesignators).
		String prefix = "(defun unused (x) (car x)) (defun f () 1) (print (funcall (car (list #'f)))) ";
		byte[] gated = compile(prefix + "(print (eq (intern (string-upcase \"post\") :keyword) :post))",
				OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(gated)).contains("F").doesNotContain("UNUSED");
		assertThat(run(gated)).isEqualTo("1\nT");
		byte[] forging = compile(prefix + "(print (intern (string-upcase \"post\")))", OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(forging)).doesNotContain("UNUSED");
		byte[] quoted = compile(prefix + "(print (cadr '(intern \"POST\" :keyword)))", OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(quoted)).doesNotContain("UNUSED");
		// A SPELLED name still resolves through the framed-string probe: the module
		// holds "F" as a string literal, so the row survives and the funcall lands.
		byte[] spelled = compile("(defun unused (x) (car x)) (defun f () 1) (print (funcall (intern \"F\")))",
				OptimizeLevel.DEFAULT);
		assertThat(run(spelled)).isEqualTo("1");
		// A data evaluator still keeps every function dispatchable.
		byte[] bailed = compile(prefix + "(eval (car '(f)))", OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(bailed)).contains("UNUSED");
	}

	@Test
	void aFramedSpellingWithoutABuilderDoesNotHoldARow() throws Exception {
		// The framed-string and keyword probes exist for the symbol BUILDERS
		// (intern/find-symbol/make-symbol/uiop:symbol-call): only a builder can turn a
		// string constant into a designator at run time. Without one, a defun whose
		// member name merely collides with an unrelated string literal stays call-only
		// and shakes; the same program plus an intern of something unrelated widens the
		// probes again, and the row keeps the defun alive.
		// The funcall's designator is COMPUTED so a dispatcher exists to keep RUNTASK at
		// all (a designator the compiler can read -- written out or through a let temp --
		// is a direct call, JvmDesignatorCall / LetBoundDesignators).
		String collide = "(defun runtask () 2) (defun f () 1) (print (funcall (car (list #'f)))) "
				+ "(print \"RUNTASK\") ";
		byte[] builderless = compile(collide, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(builderless)).contains("F").doesNotContain("RUNTASK");
		assertThat(run(builderless)).isEqualTo("1\n\"RUNTASK\"");
		byte[] withBuilder = compile(collide + "(print (intern (string-upcase \"zz\")))", OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(withBuilder)).contains("RUNTASK");
		// The compiler's own keyword-package intern shape does not widen the probes:
		// it can only produce a keyword, which can never name a defun.
		byte[] keywordShape = compile(collide + "(print (eq (intern (string-upcase \"zz\") :keyword) :zz))",
				OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(keywordShape)).doesNotContain("RUNTASK");
	}

	@Test
	void aCompilerInternedTableNameDoesNotArmTheDispatchGate() throws Exception {
		// The gate's name probes read Ctx.spelledLiterals -- the spellings Pass 2
		// emitted as VALUES -- not the whole constant pool. An instance layout's slot
		// names reach the pool for the layout tables (a private compiler structure), so
		// a defun whose name merely matches a slot keeps no registry row and shakes.
		String collide = "(defun runtask () 2) (defun f () 1) (print (funcall (car (list #'f)))) "
				+ "(defclass box () ((runtask :initarg :size))) (print (null (make-instance 'box :size 5)))";
		byte[] shaken = compile(collide, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(shaken)).contains("F").doesNotContain("RUNTASK");
		assertThat(run(shaken)).isEqualTo("1\nNIL");
	}

	@Test
	void aGeneratedReaderBodySlotNameDoesNotArmTheDispatchGate() throws Exception {
		// In a program with conditions live, a generated :reader/:accessor body quotes
		// its slot name for the unbound-slot signal. That quote is synthesized, not
		// spelled by the user, so it rides in %unspelled-quote and must not arm the
		// gate -- before it did, every slot name of every class held a same-named
		// defun's row and dispatch case alive. The unbound read still signals and the
		// handler still catches it, with the row absent.
		String collide = "(defun runtask () 2) (defun f () 1) (print (funcall (car (list #'f)))) "
				+ "(defclass box () ((runtask :accessor box-task))) "
				+ "(let ((b (make-instance 'box))) (print (handler-case (box-task b) (error (e) -1))))";
		byte[] shaken = compile(collide, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(shaken)).doesNotContain("RUNTASK");
		assertThat(run(shaken)).isEqualTo("1\n-1");
	}

	@Test
	void anUnselectableGenericBranchAndItsMethodShakeOut() throws Exception {
		// The dispatcher lists only the branches some call site's argument shapes may
		// select (compiler/GenericDispatchNarrowing): with (sizeof 21) as the only
		// call, the string method -- and DROPME, which only it calls -- fall out of
		// the dispatcher and shake. A string call site brings them back; so does
		// taking #'sizeof as a VALUE (the narrower's escape, mirroring the
		// funcall-dispatch gate); and without --optimize nothing narrows at all.
		// keepme's body is deliberately NOT a single closed integer tree: a one-liner
		// (* x 2) is fusion-inlinable (JvmIntFusionCompiler), and substituting it into
		// the caller would leave KEEPME uncalled -- correctly shaken, but no longer the
		// witness this test needs for "the SELECTED branch's callee survives".
		String defs = "(defgeneric sizeof (x)) (defmethod sizeof ((x integer)) (keepme x)) "
				+ "(defmethod sizeof ((x string)) (dropme x)) (defun keepme (x) x (* x 2)) "
				+ "(defun dropme (x) 999) (print (sizeof 21))";
		byte[] narrowed = compile(defs, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(narrowed)).contains("KEEPME").doesNotContain("DROPME");
		assertThat(run(narrowed)).isEqualTo("42");
		byte[] stringSite = compile(defs + " (print (sizeof \"abc\"))", OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(stringSite)).contains("KEEPME", "DROPME");
		assertThat(run(stringSite)).isEqualTo("42\n999");
		byte[] escaped = compile(defs + " (print (funcall (car (list #'sizeof)) \"abc\"))", OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(escaped)).contains("KEEPME", "DROPME");
		byte[] unoptimized = compile(defs, OptimizeLevel.NONE);
		assertThat(declaredMethodNames(unoptimized)).contains("KEEPME", "DROPME");
	}

	@Test
	void aLiteralDesignatorSiteBuysNoDispatchCase() throws Exception {
		// (mapcar #'dbl ...) is the direct invokestatic its head-position spelling would
		// have been, so DBL never becomes a function VALUE and the arity-1 dispatcher is
		// not emitted at all -- which is what stops the ladder from keeping HALVE, a
		// function nothing calls that is dispatchable only because the program spells its
		// name. Compute the same designator and both come back.
		String defs = "(defun dbl (x) (* x 2)) (defun halve (x) (/ x 2)) ";
		String tail = " (print 'halve)";
		byte[] literal = compile(defs + "(print (mapcar #'dbl '(1 2)))" + tail, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(literal)).contains("DBL").doesNotContain("HALVE", "_invoke_1");
		byte[] computed = compile(defs + "(print (mapcar (car (list #'dbl)) '(1 2)))" + tail, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(computed)).contains("DBL", "HALVE", "_invoke_1");
		// Same answer either way -- this only moves where the call is decided.
		assertThat(run(literal)).isEqualTo(run(computed));
	}

	@Test
	void aDesignatorBoundToATempIsTheSameDirectCall() throws Exception {
		// A designator the compiler can read through a let temp is the case above, not
		// the computed one: the binding is propagated into the funcall sites and dropped
		// (LetBoundDesignators), so the class holds exactly the written-out literal's
		// methods -- no dispatcher, and HALVE shakes. Every expander that names a
		// designator to avoid re-evaluating it (map/maplist/every/...) binds one, so this
		// is what stops a coerced string from pinning the arity-1 dispatcher.
		String defs = "(defun dbl (x) (* x 2)) (defun halve (x) (/ x 2)) ";
		String tail = " (print 'halve)";
		byte[] literal = compile(defs + "(print (mapcar #'dbl '(1 2)))" + tail, OptimizeLevel.DEFAULT);
		byte[] bound = compile(defs + "(let ((f #'dbl)) (print (mapcar f '(1 2))))" + tail, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(bound)).isEqualTo(declaredMethodNames(literal));
		assertThat(run(bound)).isEqualTo(run(literal));
		// One use as a plain VALUE and the binding stays: the value has to resolve, so
		// the dispatcher is back and keeps HALVE with it.
		byte[] valued = compile(
				defs + "(let ((f #'dbl)) (print (mapcar f '(1 2))) (print (funcall (car (list f)) 3)))" + tail,
				OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(valued)).contains("DBL", "HALVE", "_invoke_1");
	}

	@Test
	void aClosureWhoseEveryCreatorIsShakenTakesItsDispatcherCaseAlong() throws Exception {
		// MAKER makes a closure and nothing calls MAKER, so no closure of it can exist:
		// the arity-1 dispatcher a live computed funcall keeps carries no case for it,
		// and ONLY-CLOSURE, which only the closure calls, shakes with it. Call MAKER and
		// both come back; without --optimize nothing is shaken at all. ONLY-CLOSURE's
		// body is not a single integer tree, which would be inlined
		// (JvmIntFusionCompiler).
		String defs = "(defun only-closure (x) x (* x 3)) (defun maker () (lambda (y) (only-closure y))) "
				+ "(defun f (x) (+ x 1)) (print (funcall (car (list #'f)) 1)) ";
		byte[] shaken = compile(defs, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(shaken)).contains("F", "_invoke_1")
			.doesNotContain("MAKER", "ONLY-CLOSURE")
			.noneMatch(name -> name.startsWith("_lambda_"));
		assertThat(run(shaken)).isEqualTo("2");
		byte[] made = compile(defs + "(print (funcall (maker) 2))", OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(made)).contains("MAKER", "ONLY-CLOSURE")
			.anyMatch(name -> name.startsWith("_lambda_"));
		assertThat(run(made)).isEqualTo("2\n6");
		assertThat(declaredMethodNames(compile(defs, OptimizeLevel.NONE))).contains("MAKER", "ONLY-CLOSURE")
			.anyMatch(name -> name.startsWith("_lambda_"));
	}

	@Test
	void theRegistryKeepsTheCaseOfEveryNameItAnswers() throws Exception {
		// A name the run-time registry (_lookup) resolves is a value the registry makes:
		// H, reached only through its quoted name, keeps its dispatcher cases -- the
		// per-arity one funcall takes and the spread one apply takes -- and so does G,
		// whose name the program interns from a string it spells.
		String source = """
				(defun h (x) x (* x 10))
				(defun g (x) x (* x 100))
				(print (funcall (car (list 'h)) 3))
				(print (apply (car (list 'h)) '(4)))
				(print (funcall (intern (car (list "G"))) 5))
				""";
		byte[] optimized = compile(source, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(optimized)).contains("H", "G");
		assertThat(run(optimized)).isEqualTo("30\n40\n500");
	}

	@Test
	void aFunctionValueWhoseNameNothingSpellsHasNoRegistryRow() throws Exception {
		// G is a value only inside NOBODY, which nothing calls, and nothing spells its
		// name -- a gensym's "G" prefix is no spelling, only the symbol it builds is: the
		// registry a live symbol funcall keeps has no row for it, so G shakes with its
		// last maker. A name forged at run time does not resolve to it -- the
		// undefined-function error a never-valued function gives -- at every level, the
		// unshaken one included.
		String source = """
				(defun h (x) x (* x 10))
				(defun g (x) x (* x 100))
				(defun nobody () #'g)
				(print (funcall (car (list 'h)) 3))
				(print (symbolp (gensym)))
				(print (handler-case (funcall (intern (string-upcase (car (list "g")))) 5)
				         (undefined-function () 'unresolved)))
				""";
		byte[] optimized = compile(source, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(optimized)).contains("H").doesNotContain("G", "NOBODY");
		assertThat(run(optimized)).isEqualTo("30\nT\nUNRESOLVED");
		assertThat(run(compile(source, OptimizeLevel.NONE))).isEqualTo("30\nT\nUNRESOLVED");
		// Call NOBODY and the value is made: G comes back, its case with it.
		byte[] made = compile(source + "(print (funcall (nobody) 6))", OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(made)).contains("G", "NOBODY");
		assertThat(run(made)).isEqualTo("30\nT\nUNRESOLVED\n600");
	}

	@Test
	void aClosureMadeOnlyBehindATrampolineBounceKeepsItsCase() throws Exception {
		// Every call through a value here is a tail call, so only the value tails' _vtc1
		// and the trampoline reach the arity-1 dispatcher, and only behind them is the
		// closure MAKE-ADDER makes made: the cases are decided once the trampoline is
		// written, every caller of the dispatcher counted, so the adder's case stays and
		// the funcall that lands on it answers.
		String source = """
				(defun make-adder (n) (lambda (x) (+ x n)))
				(defun run-it (f) (funcall f 2))
				(print (run-it (lambda (k) (funcall (make-adder k) 3))))
				""";
		byte[] optimized = compile(source, OptimizeLevel.DEFAULT);
		assertThat(declaredMethodNames(optimized)).contains("_tramp", "MAKE-ADDER");
		assertThat(run(optimized)).isEqualTo("5");
	}

	@Test
	void valuesThroughEveryDispatcherShapeAnswerAsUnoptimized() throws Exception {
		// The dispatcher shapes a value reaches -- a computed funcall, an apply through
		// the spread dispatcher, reduce :from-end's argument-swapping closures, a #'name
		// of a built-in's wrapper -- answer the same with the shake as without it.
		String source = """
				(defun twice (x) (* 2 x))
				(print (funcall (car (list #'twice)) 21))
				(print (apply (car (list #'list)) 1 '(2 3)))
				(print (reduce #'- '(1 2 3 4) :from-end t))
				(print (reduce (car (list #'-)) '(1 2 3 4) :from-end t))
				(print (mapcar (car (list #'car)) '((1) (2))))
				""";
		String expected = "42\n(1 2 3)\n-2\n-2\n(1 2)";
		assertThat(run(compile(source, OptimizeLevel.DEFAULT))).isEqualTo(expected);
		assertThat(run(compile(source, OptimizeLevel.NONE))).isEqualTo(expected);
	}

	@Test
	void keepsTheReflectiveApplyRootForJavaInterop() throws Exception {
		// The java: bridge looks up _apply reflectively (no bytecode edge); the shaker is
		// invoked with _apply as an extra root, so a proxy callback still works. The
		// interface is named at run time, so the proxy is the bridge's.
		String source = """
				(defvar *iface* "java.util.function.Supplier")
				(setq s (java:proxy *iface* (lambda (method) 42)))
				(print (java:call s "get"))
				""";
		JvmLispCompiler compiler = JvmLispCompiler.builder().className("Test").optimize(OptimizeLevel.DEFAULT).build();
		byte[] classBytes = compiler.compile(LispReader.readAllFromString(source));
		// The bridge travels beside the class as its own file.
		for (var file : compiler.runtimeClassFiles().entrySet()) {
			Files.write(this.tempDir.resolve(file.getKey()), file.getValue());
		}
		assertThat(run(classBytes)).isEqualTo("42");
	}

	@Test
	void keepsTheCallbacksOfAGeneratedInterfaceImplementation() throws Exception {
		// A java:reify's generated class calls its program-side callback from its own
		// class (no edge in this one); the callbacks are extra roots.
		String source = """
				(print (java:call (java:reify "java.util.function.Supplier" "get" (lambda () 42)) "get"))
				""";
		JvmLispCompiler compiler = JvmLispCompiler.builder().className("Test").optimize(OptimizeLevel.DEFAULT).build();
		byte[] classBytes = compiler.compile(LispReader.readAllFromString(source));
		assertThat(compiler.runtimeClassFiles()).containsKey("Test$Reify0.class");
		for (var file : compiler.runtimeClassFiles().entrySet()) {
			Files.write(this.tempDir.resolve(file.getKey()), file.getValue());
		}
		assertThat(declaredMethodNames(classBytes)).anyMatch(name -> name.startsWith("_jimpl$"));
		assertThat(run(classBytes)).isEqualTo("42");
	}

	@Test
	void nonAsciiConstantsSurviveCompaction() throws Exception {
		// Compaction copies CONSTANT_Utf8 entries verbatim (byte-length modified UTF-8).
		assertThat(run(compile("(princ \"日本語\")", OptimizeLevel.DEFAULT))).isEqualTo("日本語");
		assertThat(run(compile("(print '日本語)", OptimizeLevel.DEFAULT))).isEqualTo("日本語");
	}

}
