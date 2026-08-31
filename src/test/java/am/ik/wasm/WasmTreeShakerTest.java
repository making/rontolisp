package am.ik.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural (no-Docker) tests for the WASM dead-code eliminator. These verify the
 * tree-shaker's invariants directly on real compiled modules: the output is smaller,
 * stays well-formed (function and code sections stay aligned, every function reference is
 * in range), the roots survive, and the pass is idempotent. End-to-end execution under
 * wasmtime is covered by {@code WasmLispCompilerIntegrationTest}.
 */
class WasmTreeShakerTest {

	private static byte[] compile(String source, boolean noWasi, OptimizeLevel optimize) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, noWasi, optimize).compile(program);
	}

	private static byte[] compileComponent(String source, OptimizeLevel optimize) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, true, false, optimize).compile(program);
	}

	@Test
	void dropsUnreachableFunctionsAndShrinksOutput() {
		String source = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""";
		byte[] plain = compile(source, true, OptimizeLevel.NONE);
		byte[] optimized = compile(source, true, OptimizeLevel.DEFAULT);

		assertThat(optimized.length).isLessThan(plain.length / 5);
		Module before = Module.parse(plain);
		Module after = Module.parse(optimized);
		assertThat(after.definedFunctionCount()).isLessThan(before.definedFunctionCount());
		// no-wasi reactor: the top-level init entry is exported as `_initialize`, not
		// `_start`.
		assertThat(after.exportedFunctionNames()).contains("fact", "_initialize");
		after.assertWellFormed();
	}

	@Test
	void dropsUnusedWasiImports() {
		// A program that only prints uses fd_write; the other seven WASI imports are
		// dead.
		byte[] optimized = compile("(print (+ 1 2))", false, OptimizeLevel.DEFAULT);
		Module m = Module.parse(optimized);
		m.assertWellFormed();
		assertThat(m.functionImportNames()).containsExactly("fd_write");
	}

	@Test
	void dropsTheNameSectionRenumberingHasInvalidated() {
		// A `name` section maps FUNCTION AND TYPE indices to names, and the pass has just
		// renumbered both -- keeping it would describe the module's old shape. The
		// rontolisp backend emits none, so the module that shows this is the hand-written
		// WASI adapter, where the name section is most of the bytes.
		byte[] adapter;
		try (java.io.InputStream in = WasmTreeShakerTest.class
			.getResourceAsStream("/am/ik/rontolisp/codegen/wasm/component/adapter.wasm")) {
			adapter = java.util.Objects.requireNonNull(in, "adapter.wasm").readAllBytes();
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		assertThat(customSectionNames(adapter)).contains("name");

		byte[] shaken = WasmTreeShaker.shake(
				WasmExports.retain(adapter, new java.util.LinkedHashMap<>(java.util.Map.of("fd_write", "fd_write"))));

		assertThat(customSectionNames(shaken)).isEmpty();
	}

	private static List<String> customSectionNames(byte[] module) {
		List<String> names = new ArrayList<>();
		int[] p = { 8 };
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xff;
			int size = leb(module, p);
			int end = p[0] + size;
			if (id == 0) {
				int len = leb(module, p);
				names.add(new String(module, p[0], len, java.nio.charset.StandardCharsets.UTF_8));
			}
			p[0] = end;
		}
		return names;
	}

	private static int leb(byte[] buf, int[] p) {
		int value = 0;
		int shift = 0;
		while (true) {
			int b = buf[p[0]++] & 0xff;
			value |= (b & 0x7f) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
			shift += 7;
		}
	}

	@Test
	void keepsTransitivelyReachableRuntime() {
		// Ratio arithmetic reaches the rational runtime helpers; they must survive.
		byte[] optimized = compile("(print (+ 1/3 1/6))", false, OptimizeLevel.DEFAULT);
		Module m = Module.parse(optimized);
		m.assertWellFormed();
		assertThat(m.exportedFunctionNames()).contains("_start");
	}

	@Test
	void shakesEhModeModules() {
		// EH mode: the shaker must walk try_table/throw/throw_ref
		// immediates correctly and keep the tag section verbatim, so P1 EH +
		// --optimize compose.
		String source = """
				(defun protected-div (a b)
				  (handler-case (/ a b) (error (e) -1)))
				(print (unwind-protect (protected-div 10 2) (print :cleaned)))
				""";
		byte[] plain = compile(source, false, OptimizeLevel.NONE);
		byte[] optimized = compile(source, false, OptimizeLevel.DEFAULT);
		assertThat(optimized.length).isLessThan(plain.length);
		Module m = Module.parse(optimized);
		m.assertWellFormed();
		assertThat(m.exportedFunctionNames()).contains("_start");
		// Idempotence over the EH opcodes too.
		assertThat(WasmTreeShaker.shake(optimized)).isEqualTo(optimized);
	}

	@Test
	void internDoesNotHoldTheFuncallDispatchGateOpen() {
		// A symbol BUILDER no longer bails the funcall-dispatch gate: whatever an intern
		// can produce that resolves is a spelling the module holds, and the
		// dispatchableFuncIds probes read every such spelling (symbol, framed string
		// literal, keyword, alias, bare member). A name forged out of computed pieces is
		// the LibraryDefunPruner carve-out -- the ordinary undefined-function error --
		// so the computed-intern module shakes exactly like the keyword-intern one.
		// Only the data evaluators (eval/read/read-from-string/load) still keep every
		// function dispatchable, because their names arrive from OUTSIDE the module.
		// The funcall keeps the dispatch machinery emitted at all -- without one there
		// are no ladders and every module would be tiny whatever the gate decides. Its
		// designator is COMPUTED on purpose: a designator the compiler can read is a
		// direct call and emits no ladder either, and so is one it can read through a
		// let temp (WasmDesignatorCall, LetBoundDesignators).
		String funcall = "(defun f () 1) (print (funcall (car (list #'f)))) ";
		String keyword = funcall + "(print (eq (intern (string-upcase \"post\") :keyword) :post))";
		String forging = funcall + "(print (intern (string-upcase \"post\")))";
		String reading = funcall + "(print (eval (read)))";
		byte[] gated = compile(keyword, false, OptimizeLevel.DEFAULT);
		byte[] computed = compile(forging, false, OptimizeLevel.DEFAULT);
		byte[] bailed = compile(reading, false, OptimizeLevel.DEFAULT);
		Module.parse(gated).assertWellFormed();
		Module.parse(computed).assertWellFormed();
		// The computed intern shakes like the keyword one (within the few hundred bytes
		// its own string handling costs)...
		assertThat((double) computed.length).isLessThan(gated.length * 1.5);
		// ...while a data evaluator still keeps every wrapper dispatchable.
		assertThat(gated.length).isLessThan(bailed.length / 2);
	}

	@Test
	void widenedProbesApplyOnlyWithASymbolBuilderPresent() {
		// The framed-string and keyword probes exist for the symbol BUILDERS: without
		// one, no runtime path can turn a string constant into a designator, so a
		// defun whose member name merely collides with an unrelated string literal
		// keeps no registry row and shakes out. Pinned as a paired difference: the
		// colliding literal and a non-colliding one must yield the same function
		// count (an unconditional framed probe would keep RUNTASK alive in the first).
		// The funcall's designator is COMPUTED so a ladder exists to keep RUNTASK at all
		// (a designator the compiler can read -- written out or through a let temp -- is
		// a direct call, WasmDesignatorCall / LetBoundDesignators).
		String base = "(defun runtask () 2) (defun f () 1) (print (funcall (car (list #'f)))) ";
		String collide = base + "(print \"RUNTASK\")";
		String noCollide = base + "(print \"RUNTASX\")";
		byte[] colliding = compile(collide, false, OptimizeLevel.DEFAULT);
		byte[] plain = compile(noCollide, false, OptimizeLevel.DEFAULT);
		Module.parse(colliding).assertWellFormed();
		assertThat(Module.parse(colliding).definedFunctionCount())
			.isEqualTo(Module.parse(plain).definedFunctionCount());
	}

	@Test
	void theCompilersOwnInternShapesDoNotWidenTheProbes() {
		// Two intern spellings ride into every Worker/serve program from the
		// compiler's own emissions, and neither can produce a FUNCTION designator, so
		// neither may hold the widened probes open (RuntimeNameProducers): the
		// keyword-package shape (http-server.lisp interns the request method) only
		// makes keywords, and the injected %slot-name-key defun (the slot-name fold
		// every slot-value dispatcher calls) only re-interns a name the program
		// already holds as a symbol. Same paired-difference pin as above, with each
		// shape present.
		String keyword = " (print (eq (intern (string-upcase \"post\") :keyword) :post))";
		String slots = " (defstruct pt (x 0)) (print (slot-value (make-pt :x 7) 'x))";
		for (String shape : new String[] { keyword, slots }) {
			String base = "(defun runtask () 2) (defun f () 1) (print (funcall (car (list #'f))))" + shape;
			byte[] colliding = compile(base + " (print \"RUNTASK\")", false, OptimizeLevel.DEFAULT);
			byte[] plain = compile(base + " (print \"RUNTASX\")", false, OptimizeLevel.DEFAULT);
			Module.parse(colliding).assertWellFormed();
			assertThat(Module.parse(colliding).definedFunctionCount()).describedAs(shape)
				.isEqualTo(Module.parse(plain).definedFunctionCount());
		}
	}

	@Test
	void aCompilerInternedTableNameDoesNotArmTheDispatchGate() {
		// The gate's name probes read Ctx.spelledLiterals -- the spellings Pass 2
		// emitted as VALUES -- not the whole string table. An instance layout's slot
		// names are interned for the layout directory (a private compiler table), so a
		// defun whose name merely matches a slot keeps no registry row and shakes.
		// Paired difference as above: a slot named RUNTASK and one named RUNTASX must
		// yield the same function count.
		String base = "(defun runtask () 2) (defun f () 1) (print (funcall (car (list #'f)))) ";
		String collide = base + "(defclass box () ((runtask :initarg :size))) "
				+ "(print (null (make-instance 'box :size 5)))";
		String noCollide = base + "(defclass box () ((runtasx :initarg :size))) "
				+ "(print (null (make-instance 'box :size 5)))";
		byte[] colliding = compile(collide, false, OptimizeLevel.DEFAULT);
		byte[] plain = compile(noCollide, false, OptimizeLevel.DEFAULT);
		Module.parse(colliding).assertWellFormed();
		assertThat(Module.parse(colliding).definedFunctionCount())
			.isEqualTo(Module.parse(plain).definedFunctionCount());
	}

	@Test
	void aGeneratedReaderBodySlotNameDoesNotArmTheDispatchGate() {
		// In a program with conditions live, a generated :reader/:accessor body quotes
		// its slot name for the unbound-slot signal. That quote is synthesized, not
		// spelled by the user, so it rides in %unspelled-quote and must not arm the
		// gate -- before it did, every slot name of every class held a same-named
		// defun's row, ladder case and body alive (one per chipz header accessor on
		// the zlib size-report row). The read still answers, and the handler still
		// catches the unbound read, with the rows absent.
		String base = "(defun runtask () 2) (defun f () 1) (print (funcall (car (list #'f)))) ";
		String tail = " (let ((b (make-instance 'box))) " + "(print (handler-case (box-task b) (error (e) -1))))";
		String collide = base + "(defclass box () ((runtask :accessor box-task)))" + tail;
		String noCollide = base + "(defclass box () ((runtasx :accessor box-task)))" + tail;
		byte[] colliding = compile(collide, false, OptimizeLevel.DEFAULT);
		byte[] plain = compile(noCollide, false, OptimizeLevel.DEFAULT);
		Module.parse(colliding).assertWellFormed();
		assertThat(Module.parse(colliding).definedFunctionCount())
			.isEqualTo(Module.parse(plain).definedFunctionCount());
	}

	@Test
	void aLiteralDesignatorSiteBuysNoLadderCase() {
		// (mapcar #'dbl ...) is the direct call its head-position spelling would have
		// been, so DBL never becomes a function VALUE and the arity-1 ladder is left the
		// unreachable stub -- which is what stops it keeping HALVE, a function nothing
		// calls that is dispatchable only because the program spells its name. Compute
		// the same designator and the ladder comes back with both.
		String defs = "(defun dbl (x) (* x 2)) (defun halve (x) (/ x 2)) ";
		String tail = " (print 'halve)";
		byte[] literal = compile(defs + "(print (mapcar #'dbl '(1 2)))" + tail, false, OptimizeLevel.DEFAULT);
		byte[] computed = compile(defs + "(print (mapcar (car (list #'dbl)) '(1 2)))" + tail, false,
				OptimizeLevel.DEFAULT);
		Module.parse(literal).assertWellFormed();
		Module.parse(computed).assertWellFormed();
		assertThat(Module.parse(literal).definedFunctionCount())
			.isLessThan(Module.parse(computed).definedFunctionCount());
	}

	@Test
	void anUnselectableGenericBranchAndItsMethodShakeOut() {
		// The dispatcher lists only the branches some call site's argument shapes may
		// select (compiler/GenericDispatchNarrowing): with (sizeof 21) as the only
		// call, the string method -- and the helper only it calls -- fall out of the
		// dispatcher and shake. A string call site brings them back, and so does
		// taking #'sizeof as a VALUE (the narrower's escape).
		String defs = "(defgeneric sizeof (x)) (defmethod sizeof ((x integer)) (keepme x)) "
				+ "(defmethod sizeof ((x string)) (dropme x)) (defun keepme (x) (* x 2)) "
				+ "(defun dropme (x) 999) (print (sizeof 21))";
		byte[] narrowed = compile(defs, false, OptimizeLevel.DEFAULT);
		byte[] stringSite = compile(defs + " (print (sizeof \"abc\"))", false, OptimizeLevel.DEFAULT);
		byte[] escaped = compile(defs + " (print (funcall (car (list #'sizeof)) \"abc\"))", false,
				OptimizeLevel.DEFAULT);
		Module.parse(narrowed).assertWellFormed();
		Module.parse(stringSite).assertWellFormed();
		Module.parse(escaped).assertWellFormed();
		assertThat(Module.parse(narrowed).definedFunctionCount())
			.isLessThan(Module.parse(stringSite).definedFunctionCount());
		assertThat(Module.parse(narrowed).definedFunctionCount())
			.isLessThan(Module.parse(escaped).definedFunctionCount());
	}

	@Test
	void aDesignatorBoundToATempIsTheSameDirectCall() {
		// A designator the compiler can read through a let temp is the case above, not
		// the computed one: the binding is propagated into the funcall sites and dropped
		// (LetBoundDesignators), so the module is the written-out literal's own bytes --
		// no ladder case, and HALVE shakes. Every expander that names a designator to
		// avoid re-evaluating it (map/maplist/every/...) binds one, so this is what
		// stops a coerced string from pinning the arity-1 ladder.
		String defs = "(defun dbl (x) (* x 2)) (defun halve (x) (/ x 2)) ";
		String tail = " (print 'halve)";
		byte[] literal = compile(defs + "(print (mapcar #'dbl '(1 2)))" + tail, false, OptimizeLevel.DEFAULT);
		byte[] bound = compile(defs + "(let ((f #'dbl)) (print (mapcar f '(1 2))))" + tail, false,
				OptimizeLevel.DEFAULT);
		assertThat(bound).isEqualTo(literal);
		// One use as a plain VALUE and the binding stays: the value has to resolve, so
		// the ladder is back and keeps HALVE with it.
		byte[] valued = compile(
				defs + "(let ((f #'dbl)) (print (mapcar f '(1 2))) (print (funcall (car (list f)) 3)))" + tail, false,
				OptimizeLevel.DEFAULT);
		Module.parse(valued).assertWellFormed();
		assertThat(Module.parse(literal).definedFunctionCount())
			.isLessThan(Module.parse(valued).definedFunctionCount());
	}

	@Test
	void orphanedCaseFoldTableSegmentsAreDropped() {
		// The ~16 KB Unicode case-fold tables ride in their own data segments owned by
		// _char_upcase/_char_downcase. A program that never case-folds loses both
		// segments with the helpers; one that folds keeps them and still works (behavior
		// pinned by WasmLispCompilerIntegrationTest).
		// The case fold has to be over a value the COMPILER cannot know, or the
		// pure-builtin literal fold evaluates it and the helpers are unreachable after
		// all (.kb/pure-builtin-fold.md) -- which is the third case asserted below.
		byte[] hello = compile("(print \"Hello World!\")", false, OptimizeLevel.DEFAULT);
		byte[] folding = compile("(defun fold-char (c) (char-upcase (char-downcase c))) (print (fold-char #\\A))",
				false, OptimizeLevel.DEFAULT);
		byte[] literalFolded = compile("(print (char-upcase (char-downcase #\\A)))", false, OptimizeLevel.DEFAULT);
		Module.parse(hello).assertWellFormed();
		Module.parse(folding).assertWellFormed();
		Module.parse(literalFolded).assertWellFormed();
		assertThat(dataSectionSize(hello)).isLessThan(512);
		assertThat(dataSectionSize(folding)).isGreaterThan(16000);
		assertThat(dataSectionSize(literalFolded)).isLessThan(512);
		// 1024 also pins the literal-print specialization (WasmLiteralPrint): without
		// it the generic printer family alone puts the module near 6 KB.
		assertThat(hello.length).isLessThan(1024);
	}

	@Test
	void everySpellingOfHelloWorldReachesTheSameFloor() {
		// The literal fold belongs to the print FAMILY, not to `print`: whichever way a
		// program spells "write this constant", none of them may reference the generic
		// printer (WasmLiteralPrint). Before it was shared, the princ / format spellings
		// carried the whole runtime printer -- 4.8 KB against print's 0.65 KB -- and a
		// format argument hid its literal behind a `let` temp on top of that.
		List<String> spellings = List.of("(print \"Hello World!\")", "(princ \"Hello World!\") (terpri)",
				"(format t \"Hello World!~%\")", "(write-line \"Hello World!\")", "(write-string \"Hello World!\")",
				"(format t \"Hello, ~a!~%\" \"World\")", "(format t \"~a~%\" 42)",
				"(format t \"~s~%\" \"Hello World!\")");
		for (String spelling : spellings) {
			byte[] module = compile(spelling, false, OptimizeLevel.DEFAULT);
			Module.parse(module).assertWellFormed();
			assertThat(module.length).describedAs(spelling).isLessThan(1024);
		}
	}

	@Test
	void aFoldedComputationCompilesToTheLiteralItReducesTo() {
		// The pure-builtin fold's payoff is that it feeds the literal folds above: a
		// computed argument that the compiler can work out is a literal by the time
		// WasmLiteralPrint sees it, so the whole generic printer shakes out. Byte
		// IDENTITY with the literal spelling is the strongest form of that claim --
		// nothing of the deleted call survives anywhere in the module
		// (.kb/pure-builtin-fold.md).
		assertThat(compile("(princ (* 6 7))", false, OptimizeLevel.DEFAULT))
			.isEqualTo(compile("(princ 42)", false, OptimizeLevel.DEFAULT));
		assertThat(compile("(princ (length \"Hello World!\"))", false, OptimizeLevel.DEFAULT))
			.isEqualTo(compile("(princ 12)", false, OptimizeLevel.DEFAULT));
		// concatenate 'string used to have a row here; it left the fold table when its
		// compiled result became a mutable character vector with identity (a fold to
		// one shared literal would forge aliasing -- .kb/pure-builtin-fold.md).
		// symbol-name keeps a string-producing row in its place: its runtime answer is
		// still an immutable value, so the fold forges nothing.
		assertThat(compile("(princ (symbol-name :hello))", false, OptimizeLevel.DEFAULT))
			.isEqualTo(compile("(princ \"HELLO\")", false, OptimizeLevel.DEFAULT));
		assertThat(compile("(format t \"~a~%\" (* 6 7))", false, OptimizeLevel.DEFAULT))
			.isEqualTo(compile("(format t \"~a~%\" 42)", false, OptimizeLevel.DEFAULT));
	}

	@Test
	void everySpellingOfHelloWorldReachesTheSameComponentFloor() {
		// The same eight spellings wrapped as WASI 0.3 components. The wrapper follows
		// the core rather than standing under it, and for a program whose whole I/O is
		// "write this constant to fd 1" that means TWO imported interfaces and no
		// allocator: wasi:cli/stderr goes because the reserved fd 2 is unreachable from a
		// source that names neither *error-output* nor warn, and the shared cabi_realloc
		// goes because nothing here lifts host-owned bytes -- which takes the whole
		// bump-allocator body of the shared memory module with it.
		List<String> spellings = List.of("(print \"Hello World!\")", "(princ \"Hello World!\") (terpri)",
				"(format t \"Hello World!~%\")", "(write-line \"Hello World!\")", "(write-string \"Hello World!\")",
				"(format t \"Hello, ~a!~%\" \"World\")", "(format t \"~a~%\" 42)",
				"(format t \"~s~%\" \"Hello World!\")");
		for (String spelling : spellings) {
			byte[] component = compileComponent(spelling, OptimizeLevel.DEFAULT);
			assertThat(component.length).describedAs(spelling).isLessThan(2048);
			assertThat(names(component, "wasi:cli/stdout")).describedAs(spelling).isTrue();
			assertThat(names(component, "wasi:cli/stderr")).describedAs(spelling).isFalse();
			assertThat(names(component, "cabi_realloc")).describedAs(spelling).isFalse();
		}
	}

	@Test
	void aProgramThatCanWriteFdTwoKeepsTheStderrSurface() {
		// The narrowing above is a claim about the SOURCE -- a file descriptor is a
		// value in the core module, not an edge, so the shaker cannot see it. Both
		// spellings that materialize the reserved handle 2 must therefore hold the
		// interface open, or --optimize would compile a warning into a trap.
		for (String spelling : List.of("(warn \"careful\")", "(format *error-output* \"careful~%\")")) {
			byte[] component = compileComponent(spelling, OptimizeLevel.DEFAULT);
			assertThat(names(component, "wasi:cli/stderr")).describedAs(spelling).isTrue();
		}
	}

	// Whether the component names something anywhere in its bytes: interface ids and
	// core-module import/export fields are the only places these strings occur, and the
	// emitted component carries no name sections to give a false positive.
	private static boolean names(byte[] component, String text) {
		return new String(component, java.nio.charset.StandardCharsets.ISO_8859_1).contains(text);
	}

	@Test
	void dropsTypesTheSurvivorsNoLongerName() {
		// The type section is a verbatim-copied fixed table (~60 entries: every runtime
		// struct, array and helper signature). A program reaching almost none of the
		// runtime must keep almost none of them, and a rec group goes as a unit.
		byte[] plain = compile("(print \"Hello World!\")", false, OptimizeLevel.NONE);
		byte[] optimized = compile("(print \"Hello World!\")", false, OptimizeLevel.DEFAULT);
		Module before = Module.parse(plain);
		Module after = Module.parse(optimized);
		after.assertWellFormed();
		assertThat(before.typeEntryCount()).isGreaterThan(50);
		assertThat(after.typeEntryCount()).isLessThan(15);
	}

	@Test
	void keepsTheTypesAnEhModeModuleStillNames() {
		// The tag section names a function type by index and is copied verbatim, so its
		// type has to be a renumbering root -- otherwise the tag would dangle.
		byte[] optimized = compile("""
				(print (handler-case (error "boom") (error (e) :caught)))
				""", false, OptimizeLevel.DEFAULT);
		Module m = Module.parse(optimized);
		m.assertWellFormed();
		assertThat(WasmTreeShaker.shake(optimized)).isEqualTo(optimized);
	}

	@Test
	void dropsStringsOnlyDeadBodiesInterned() {
		// The builtin wrappers Pass 2a compiles intern their literals -- FIND-PACKAGE's
		// package-alias alist alone is ~680 bytes -- and the shaker then deletes the
		// wrappers, leaving the bytes behind. A hello program keeps only what a live
		// body still addresses; a program that actually reaches find-package keeps the
		// table.
		// A COMPUTED designator is what reaches the alist (PackageResolver folds a
		// literal one before the compiler sees it), so that program keeps the table.
		byte[] hello = compile("(print \"Hello World!\")", false, OptimizeLevel.DEFAULT);
		byte[] packages = compile("""
				(defun pkg (n) (find-package (string-upcase n)))
				(print (pkg "cl-user"))
				""", false, OptimizeLevel.DEFAULT);
		Module.parse(hello).assertWellFormed();
		Module.parse(packages).assertWellFormed();
		assertThat(dataSectionSize(packages) - dataSectionSize(hello)).isGreaterThan(600);
		// The rendered literal itself must still be there: 12 bytes of "Hello World!"
		// plus its quotes, the seed cells, and little else.
		assertThat(dataSectionSize(hello)).isBetween(32, 128);
	}

	@Test
	void dropsThePrinterPrologueNoLiveBodyReads() {
		// The StringTable constructor's fixed entries (NIL, the list punctuation, the
		// float specials, the "#\" prefix and the eight character names) are read only by
		// the RUNTIME printer bodies, each of which bakes the offset as its own
		// i32.const -- so they are ordinary droppable ranges. A literal-fold hello never
		// reaches the generic printer and must keep none of them; a program that prints a
		// COMPUTED cons does reach it and keeps the punctuation.
		byte[] hello = compile("(princ \"Hello World!\") (terpri)", false, OptimizeLevel.DEFAULT);
		byte[] generic = compile("""
				(defun pair (a b) (cons a b))
				(print (pair 1 2))
				""", false, OptimizeLevel.DEFAULT);
		Module.parse(hello).assertWellFormed();
		Module.parse(generic).assertWellFormed();
		byte[] helloData = dataSectionPayload(hello);
		for (String dead : new String[] { "#<function>", "#<FUTURE>", "Infinity", "Rubout", "Backspace", "#A(" }) {
			assertThat(contains(helloData, dead)).as("hello module keeps the dead prologue entry %s", dead).isFalse();
		}
		// "\n" is the one prologue entry a literal write reaches directly (terpri), so it
		// stays -- and it is all that is left besides the printed literal.
		assertThat(contains(helloData, "\n")).isTrue();
		byte[] genericData = dataSectionPayload(generic);
		for (String live : new String[] { "NIL", " . " }) {
			assertThat(contains(genericData, live)).as("generic printer lost %s", live).isTrue();
		}
	}

	@Test
	void aBareOnePastTheEndPointerDoesNotKeepARange() {
		// The keep predicate is the HALF-OPEN interval. A dead builtin-wrapper literal
		// that happens to sit immediately before the one the program prints ends exactly
		// where the live range starts; under a closed interval the live start pointer
		// pinned the dead neighbour too. Same shape inside the prologue, where " . "
		// abuts "\n". Nothing a live body cannot address may remain.
		byte[] hello = compile("(princ \"Hello World!\") (terpri)", false, OptimizeLevel.DEFAULT);
		byte[] data = dataSectionPayload(hello);
		// What is left: the three 4-byte seed cells, "\n", and the framed literal.
		assertThat(dataSectionSize(hello)).isLessThan(80);
		assertThat(contains(data, "keyword")).isFalse();
		assertThat(contains(data, " . ")).isFalse();
		assertThat(contains(data, "\"Hello World!\"")).isTrue();
	}

	private static boolean contains(byte[] haystack, String needle) {
		byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		outer: for (int i = 0; i + n.length <= haystack.length; i++) {
			for (int j = 0; j < n.length; j++) {
				if (haystack[i + j] != n[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	// The data section's payload bytes (empty when the section is absent).
	private static byte[] dataSectionPayload(byte[] module) {
		int[] p = { 8 };
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xff;
			int size = WasmSections.readU(module, p);
			if (id == 11) {
				return java.util.Arrays.copyOfRange(module, p[0], p[0] + size);
			}
			p[0] += size;
		}
		return new byte[0];
	}

	@Test
	void anInterningProgramOffersPerEntryRangesRowsFallingWithTheirBytes() {
		// _intern scans a blob citing EVERY interned entry by offset, and that citation
		// lives in DATA where the i32.const scan cannot see it -- which used to
		// disqualify every candidate of an interning program wholesale. The citation is
		// resolved structurally now: each candidate's (offset, length) row is its own
		// droppable range PROBED on the string's interval, so row and bytes fall
		// together, and _intern skips the zeroed hole a cut row reads as. An interning
		// program therefore keeps only what live bodies still address: printing the
		// runtime-made symbol keeps the whole generic printer and with it the prologue,
		// while the dead wrapper literals (the sequence keywords, the find-package
		// alias alist) go, rows included -- the module was over 24 KB of data before.
		// The name is computed behind a defun so nothing folds: a folded
		// string-upcase would drag the ~16 KB Unicode case-fold tables into a test
		// that is about the intern blob's rows, and a folded subseq would replace the
		// runtime-made name with a (%str-fresh ...) constant.
		byte[] interning = compile("(defun sh (x) x) (print (intern (subseq (sh \"FOOX\") 0 3)))", false,
				OptimizeLevel.DEFAULT);
		Module.parse(interning).assertWellFormed();
		// The generic printer keeps its own data: the printer prologue strings plus
		// the ~755-byte Schubfach float table.
		assertThat(dataSectionSize(interning)).isLessThan(2048);
		byte[] data = dataSectionPayload(interning);
		for (String dead : new String[] { ":FROM-END", "\"ASDF\"", "keyword" }) {
			assertThat(contains(data, dead)).as("dead wrapper literal %s survived the interning program", dead)
				.isFalse();
		}
		for (String live : new String[] { "\"FOOX\"", "Rubout", "#<FUTURE>" }) {
			assertThat(contains(data, live)).as("the interning program lost %s", live).isTrue();
		}
		assertThat(WasmTreeShaker.shake(interning)).isEqualTo(interning);
	}

	// Total payload size of the data section (0 when absent).
	private static int dataSectionSize(byte[] module) {
		int[] p = { 8 };
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xff;
			int size = WasmSections.readU(module, p);
			if (id == 11) {
				return size;
			}
			p[0] += size;
		}
		return 0;
	}

	@Test
	void decodesTheOneByteAbstractReferenceBlockType() {
		// THE decoder trap of the shortest-encoding pass: `block (result eqref)` is the
		// single byte 0x6D, and a blocktype's third alternative is an s33 TYPE INDEX. A
		// predicate that does not list the abstract-reference shorthands reads that byte
		// as index -19 and tries to renumber it. Every value-producing `if` in a
		// compiled body is one, so the module below is full of them.
		byte[] optimized = compile("""
				(defun pick (n) (if (> n 0) "positive" "not"))
				(print (pick 1))
				(print (pick -1))
				""", false, OptimizeLevel.DEFAULT);
		Module m = Module.parse(optimized);
		m.assertWellFormed();
		// 0x04 = `if`, 0x6D = eqref: the short form really is in the bytes being decoded.
		assertThat(indexOf(optimized, new byte[] { 0x04, 0x6D })).isGreaterThan(0);
		// A misread blocktype renumbers a type index that is not one, so the second pass
		// would not agree with the first.
		assertThat(WasmTreeShaker.shake(optimized)).isEqualTo(optimized);
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int k = 0; k < needle.length; k++) {
				if (haystack[i + k] != needle[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	@Test
	void aSectionTheShakeEmptiedIsDroppedRatherThanWrittenBackEmpty() {
		// A module exporting only its memory: the one function is unreachable and goes,
		// and so does the type only it named. `00` (zero entries) says exactly what no
		// section at all says, so the type, function and code sections must be gone --
		// not present and empty. This is the shape the component's shared-memory module
		// shakes down to for a print-only program, where it carried three of them.
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm")
			.writeLittleEndian4(1)
			.writeTypeSection(types -> types.addFunc(new Type[0], new Type[0]))
			.writeFunction(funcs -> funcs.addFunction(0))
			.writeMemory(memories -> memories.addMemory(1))
			.writeExport(exports -> exports.addExport("memory", ExternalKind.MEMORY, 0))
			.writeCode(code -> code.addFunction(new byte[] { 0x00, (byte) 0x0B }));
		byte[] shaken = WasmTreeShaker.shake(out.toByteArray());

		assertThat(sectionIds(shaken)).containsExactly(5, 7); // memory, export -- no more
		assertThat(WasmTreeShaker.shake(shaken)).isEqualTo(shaken);
	}

	private static List<Integer> sectionIds(byte[] module) {
		List<Integer> ids = new ArrayList<>();
		int[] p = { 8 };
		while (p[0] < module.length) {
			ids.add(module[p[0]++] & 0xff);
			// Read the size first: `p[0] += readU(module, p)` would discard readU's own
			// advance of p[0].
			int size = WasmSections.readU(module, p);
			p[0] += size;
		}
		return ids;
	}

	@Test
	void isIdempotent() {
		byte[] once = WasmTreeShaker.shake(compile("(print 42)", false, OptimizeLevel.NONE));
		byte[] twice = WasmTreeShaker.shake(once);
		assertThat(twice).isEqualTo(once);
	}

	@Test
	void returnsEquivalentModuleWhenNothingToDrop() {
		// Shaking an already-minimal module should not corrupt it.
		byte[] optimized = compile("(print 1)", false, OptimizeLevel.DEFAULT);
		assertThat(WasmTreeShaker.shake(optimized)).isEqualTo(optimized);
	}

	/**
	 * A minimal read-only view of a core WASM module sufficient to assert the
	 * tree-shaker's structural invariants. Mirrors the binary framing the shaker itself
	 * relies on.
	 */
	private record Module(int functionImportCount, List<String> functionImportNames, int definedFunctionCount,
			int codeEntryCount, List<String> exportedFunctionNames, List<Integer> exportedFunctionIndices,
			int typeEntryCount, int definedTypeCount, List<Integer> functionTypeIndices) {

		void assertWellFormed() {
			// Function section and code section must stay aligned.
			assertThat(codeEntryCount).isEqualTo(definedFunctionCount);
			// Every exported function index must be in range.
			int total = functionImportCount + definedFunctionCount;
			assertThat(exportedFunctionIndices).allSatisfy(i -> assertThat(i).isBetween(0, total - 1));
			// Every function signature must still name a type the module defines.
			assertThat(functionTypeIndices).hasSize(total)
				.allSatisfy(i -> assertThat(i).isBetween(0, definedTypeCount - 1));
		}

		static Module parse(byte[] module) {
			int[] p = { 8 };
			int functionImportCount = 0;
			List<String> functionImportNames = new ArrayList<>();
			int definedFunctionCount = 0;
			int codeEntryCount = 0;
			int typeEntryCount = 0;
			int definedTypeCount = 0;
			List<String> exportedFunctionNames = new ArrayList<>();
			List<Integer> exportedFunctionIndices = new ArrayList<>();
			List<Integer> functionTypeIndices = new ArrayList<>();
			while (p[0] < module.length) {
				int id = module[p[0]++] & 0xff;
				int size = readU(module, p);
				int end = p[0] + size;
				switch (id) {
					case 1 -> { // type
						typeEntryCount = readU(module, p);
						for (int i = 0; i < typeEntryCount; i++) {
							definedTypeCount += skipRecType(module, p);
						}
					}
					case 2 -> { // import
						int count = readU(module, p);
						for (int i = 0; i < count; i++) {
							skipName(module, p); // module
							String name = readName(module, p); // field
							int kind = module[p[0]++] & 0xff;
							switch (kind) {
								case 0x00 -> { // func
									functionTypeIndices.add(readU(module, p)); // typeidx
									functionImportCount++;
									functionImportNames.add(name);
								}
								case 0x01 -> { // table
									skipValType(module, p);
									skipLimits(module, p);
								}
								case 0x02 -> skipLimits(module, p);
								case 0x03 -> { // global
									skipValType(module, p);
									p[0]++;
								}
								default -> throw new IllegalStateException("kind " + kind);
							}
						}
					}
					case 3 -> { // function
						definedFunctionCount = readU(module, p);
						for (int i = 0; i < definedFunctionCount; i++) {
							functionTypeIndices.add(readU(module, p));
						}
					}
					case 7 -> { // export
						int count = readU(module, p);
						for (int i = 0; i < count; i++) {
							String name = readName(module, p);
							int kind = module[p[0]++] & 0xff;
							int index = readU(module, p);
							if (kind == 0x00) {
								exportedFunctionNames.add(name);
								exportedFunctionIndices.add(index);
							}
						}
					}
					case 10 -> codeEntryCount = readU(module, p); // code
					default -> {
					}
				}
				p[0] = end;
			}
			return new Module(functionImportCount, functionImportNames, definedFunctionCount, codeEntryCount,
					exportedFunctionNames, exportedFunctionIndices, typeEntryCount, definedTypeCount,
					functionTypeIndices);
		}

		private static int readU(byte[] buf, int[] p) {
			int result = 0;
			int shift = 0;
			while (true) {
				int b = buf[p[0]++] & 0xff;
				result |= (b & 0x7f) << shift;
				if ((b & 0x80) == 0) {
					return result;
				}
				shift += 7;
			}
		}

		private static String readName(byte[] buf, int[] p) {
			int len = readU(buf, p);
			String s = new String(buf, p[0], len, java.nio.charset.StandardCharsets.UTF_8);
			p[0] += len;
			return s;
		}

		private static void skipName(byte[] buf, int[] p) {
			// Read the length first: `p[0] += readU(buf, p)` would discard readU's own
			// advance of p[0] (compound assignment captures the old p[0] before the
			// call).
			int len = readU(buf, p);
			p[0] += len;
		}

		private static void skipValType(byte[] buf, int[] p) {
			int b = buf[p[0]++] & 0xff;
			if (b == 0x63 || b == 0x64) {
				readU(buf, p);
			}
		}

		// rectype := 0x4E vec(subtype) | subtype. Returns the number of type indices the
		// entry defines, so a rec group counts as its member count.
		private static int skipRecType(byte[] buf, int[] p) {
			if ((buf[p[0]] & 0xff) == 0x4E) {
				p[0]++;
				int members = readU(buf, p);
				for (int i = 0; i < members; i++) {
					skipSubType(buf, p);
				}
				return members;
			}
			skipSubType(buf, p);
			return 1;
		}

		private static void skipSubType(byte[] buf, int[] p) {
			int b = buf[p[0]] & 0xff;
			if (b == 0x50 || b == 0x4F) { // sub / sub final
				p[0]++;
				int supertypes = readU(buf, p);
				for (int i = 0; i < supertypes; i++) {
					readU(buf, p);
				}
			}
			int tag = buf[p[0]++] & 0xff;
			switch (tag) {
				case 0x60 -> { // func
					int params = readU(buf, p);
					for (int i = 0; i < params; i++) {
						skipValType(buf, p);
					}
					int results = readU(buf, p);
					for (int i = 0; i < results; i++) {
						skipValType(buf, p);
					}
				}
				case 0x5E -> skipFieldType(buf, p); // array
				case 0x5F -> { // struct
					int fields = readU(buf, p);
					for (int i = 0; i < fields; i++) {
						skipFieldType(buf, p);
					}
				}
				default -> throw new IllegalStateException("comptype tag " + tag);
			}
		}

		private static void skipFieldType(byte[] buf, int[] p) {
			int b = buf[p[0]] & 0xff;
			if (b == 0x78 || b == 0x77) { // i8 / i16 packed storage
				p[0]++;
			}
			else {
				skipValType(buf, p);
			}
			p[0]++; // mutability
		}

		private static void skipLimits(byte[] buf, int[] p) {
			int flag = buf[p[0]++] & 0xff;
			readU(buf, p);
			if ((flag & 0x01) != 0) {
				readU(buf, p);
			}
		}
	}

}
