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
import java.util.Set;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural + behavioral tests for the JVM dead-code eliminator. These verify the
 * shaker's invariants on real compiled classes: the output is smaller, unreachable
 * methods and unreferenced fields are gone, the class still loads (the JVM verifier is
 * the well-formedness check) and behaves identically, dynamically-reached methods and the
 * reflective {@code _apply} root survive, and the pass is idempotent. The whole
 * cross-backend feature corpus is exercised by {@code JvmClassShakerCorpusTest}.
 */
class JvmClassShakerTest {

	@TempDir
	Path tempDir;

	private static byte[] compile(String source, OptimizeLevel optimize) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new JvmLispCompiler("Test", false, optimize).compile(program);
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
			List<String> fieldNames = Arrays.stream(loader.loadClass("Test").getDeclaredFields())
				.map(java.lang.reflect.Field::getName)
				.toList();
			assertThat(fieldNames).containsExactly("_col");
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
	void keepsTheReflectiveApplyRootForJavaInterop() throws Exception {
		// The java: bridge looks up _apply reflectively (no bytecode edge); the shaker is
		// invoked with _apply as an extra root, so a proxy callback still works.
		String source = """
				(setq s (java:proxy "java.util.function.Supplier" (lambda (method) 42)))
				(print (java:call s "get"))
				""";
		assertThat(run(compile(source, OptimizeLevel.DEFAULT))).isEqualTo("42");
	}

	@Test
	void nonAsciiConstantsSurviveCompaction() throws Exception {
		// Compaction copies CONSTANT_Utf8 entries verbatim (byte-length modified UTF-8).
		assertThat(run(compile("(princ \"日本語\")", OptimizeLevel.DEFAULT))).isEqualTo("日本語");
		assertThat(run(compile("(print '日本語)", OptimizeLevel.DEFAULT))).isEqualTo("日本語");
	}

	@Test
	void isIdempotent() {
		byte[] once = JvmClassShaker.shake(compile("(print (+ 1 2))", OptimizeLevel.NONE), Set.of("main"));
		byte[] twice = JvmClassShaker.shake(once, Set.of("main"));
		assertThat(twice).isEqualTo(once);
	}

	@Test
	void aWellFormedClassHasNoUnresolvedSelfMethods() {
		// The compiler runs this check on every build (an unresolved own-class call is
		// how a mispredicted runtime-helper gate shows up), so anything it emits must
		// come back clean.
		assertThat(JvmClassShaker.unresolvedSelfMethods(compile("""
				(defun add1 (x) (+ x 1))
				(let ((s "abc")) (setf (elt s 0) #\\z) (print s))
				(print (funcall #'funcall #'add1 41))
				(print (mapcar #'class-of (list 1)))
				""", OptimizeLevel.NONE))).isEmpty();
	}

	@Test
	void unresolvedSelfMethodsNamesTheCallAndItsCallers() {
		// A class whose main() invokestatics an own _helper that is never declared --
		// exactly the shape a mispredicted runtime-helper gate produces, and one the
		// JVM accepts until the branch actually runs.
		ConstantPool cp = new ConstantPool();
		ConstantPool.ClassConstant thisClass = cp.addClass(cp.addUtf8("Dangling"));
		ConstantPool.ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		ConstantPool.MethodrefConstant helper = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8("_helper"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		ConstantPool.Utf8Constant mainName = cp.addUtf8("main");
		ConstantPool.Utf8Constant mainDesc = cp.addUtf8("([Ljava/lang/String;)V");
		ConstantPool.Utf8Constant codeAttr = cp.addUtf8("Code");
		ByteArrayOutputStream classOut = new ByteArrayOutputStream();
		new ByteCodeWriter(classOut).write(0xCA, 0xFE, 0xBA, 0xBE)
			.writeVersion(0, 50)
			.writeConstantPool(cp)
			.writeClass(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, thisClass, objectClass)
			.writeInterfaces(i -> {
			})
			.writeFields(f -> {
			})
			.writeMethods(methods -> methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, mainName, mainDesc,
					method -> method.writeAttributes(attrs -> attrs.add(codeAttr,
							attr -> attr.writeU2(1)
								.writeU2(1)
								.writeCode(Opcode.ACONST_NULL, Opcode.INVOKESTATIC, helper.indexAsU2(), Opcode.POP,
										Opcode.RETURN)
								.writeU2(0)
								.writeU2(0)))))
			.writeAttributes(a -> {
			});

		assertThat(JvmClassShaker.unresolvedSelfMethods(classOut.toByteArray())).singleElement().satisfies(missing -> {
			assertThat(missing.name()).isEqualTo("_helper");
			assertThat(missing.descriptor()).isEqualTo("(Ljava/lang/Object;)Ljava/lang/Object;");
			assertThat(missing.callers()).containsExactly("main");
		});
	}

}
