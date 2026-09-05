package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every permit of the sealed {@link LispFloatArray} is REACHABLE, and answers its own
 * element type, through every door that opens on a width by NAME.
 * <p>
 * {@code .kb/vec.md}'s exhaustive-switch net catches a permit that no ALLOCATION site
 * handles -- but the name -&gt; width direction cannot be a compile error, because a new
 * width's name is new source text: the resolver {@code make-array} used before this test
 * existed compared the designator against private string constants and answered
 * {@code null} for anything it did not know, so an un-wired width silently built a BOXED
 * general array and {@code array-element-type} answered {@code t}. The same shape sat in
 * {@code vec.lisp}'s {@code %make} (default double), in the {@code typep} upgrade, and in
 * the {@code %print-object-str} vector arm's exclusion list. What the compiler cannot
 * give, this test gives: it enumerates {@link Class#getPermittedSubclasses()} -- exactly
 * and automatically -- and asks each permit of every door. A permit added without wiring
 * turns one of these red rather than passing silently.
 * <p>
 * The int-vector audit next door: {@link LispIntVector} is NOT sealed -- one final class
 * with the width as a field (8/16/32) -- and its designator {@code (unsigned-byte N)} is
 * parsed from the SPECIFIER, not resolved from permit names, so there is no permits
 * clause to enumerate here. Its two hand-written width lists are the
 * {@code LispIntVector} constructor's check (LOUD: an unsupported width throws) and
 * {@code LispNames.unsignedByteWidth}'s parse (silent: an unsupported width answers 0 and
 * the general array). The last two tests pin both directions as they stand.
 */
class PackedFloatReachabilityTest {

	/**
	 * One table row per permit of {@link LispFloatArray}, which is what the door tests
	 * below iterate over -- NOT {@code LispFloatArray.WIDTHS} itself. The difference is
	 * the whole point: a permit with no row must turn EVERY door red, not silently shrink
	 * the loop each door feeds, or the test reproduces the boxed-array fallback inside
	 * itself -- the very failure mode it was written to catch.
	 */
	private static List<LispFloatArray> prototypesForAllPermits() {
		List<LispFloatArray> protos = new java.util.ArrayList<>();
		for (Class<?> permit : LispFloatArray.class.getPermittedSubclasses()) {
			LispFloatArray row = null;
			for (LispFloatArray proto : LispFloatArray.WIDTHS) {
				if (proto.getClass() == permit) {
					row = proto;
				}
			}
			// A permit missing from the table fails HERE, and every door test below
			// with it, before any door can answer for a width it does not know.
			assertThat(row).as("permit %s has a row in LispFloatArray.WIDTHS", permit.getSimpleName()).isNotNull();
			protos.add(row);
		}
		return protos;
	}

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	/**
	 * The table {@code LispFloatArray.prototypeFor} walks is exactly the permits, one row
	 * each: this is the one hand-written point the reflective net guards, so a permit
	 * added without its table row -- and therefore unreachable from every door below --
	 * fails HERE first.
	 */
	@Test
	void theWidthsTableHoldsExactlyOnePrototypePerPermit() {
		Set<Class<?>> permits = Set.of(LispFloatArray.class.getPermittedSubclasses());
		Set<Class<?>> rows = java.util.Arrays.stream(LispFloatArray.WIDTHS)
			.map(LispFloatArray::getClass)
			.collect(Collectors.toSet());
		assertThat(rows).as("one row per permit of LispFloatArray, no more, no less")
			.containsExactlyInAnyOrderElementsOf(permits);
		for (LispFloatArray proto : LispFloatArray.WIDTHS) {
			assertThat(proto.totalSize()).as("the %s row is a zero-length prototype", proto.getClass().getSimpleName())
				.isZero();
		}
	}

	@Test
	void everyPermitIsReachableThroughMakeArrayAndAnswersItsOwnType() {
		for (LispFloatArray proto : prototypesForAllPermits()) {
			String name = proto.elementType();
			LispVal v = eval("(make-array '(2) :element-type '" + name + ")");
			assertThat(v).as("(make-array '(2) :element-type '%s) builds the width's own class", name)
				.isInstanceOf(proto.getClass());
			LispVal et = eval("(let ((a (make-array '(2) :element-type '" + name + "))) (array-element-type a))");
			assertThat(et).as("array-element-type answers the name that built it (%s)", name)
				.isInstanceOf(LispSymbol.class);
			assertThat(((LispSymbol) et).name()).as("and answers it as a bare symbol").isEqualTo(name);
		}
	}

	@Test
	void everyPermitTypesAsItsOwnArray() {
		for (LispFloatArray proto : prototypesForAllPermits()) {
			String name = proto.elementType();
			assertThat(
					eval("(let ((a (make-array '(2) :element-type '" + name + "))) (typep a '(array " + name + ")))"))
				.as("(typep a '(array %s))", name)
				.isSameAs(LispTrue.INSTANCE);
			assertThat(eval("(let ((a (make-array '(2) :element-type '" + name + "))) (typep a '(simple-array " + name
					+ " (2))))"))
				.as("(typep a '(simple-array %s (2)))", name)
				.isSameAs(LispTrue.INSTANCE);
		}
	}

	@Test
	void everyPermitIsReachableThroughTheVecConstructors() {
		for (LispFloatArray proto : prototypesForAllPermits()) {
			String name = proto.elementType();
			for (String ctor : new String[] { "vec:zeros", "vec:ones", "vec:arange" }) {
				LispVal v = eval("(" + ctor + " 4 :element-type '" + name + ")");
				assertThat(v).as("%s 4 :element-type '%s) builds the width's own class", ctor, name)
					.isInstanceOf(proto.getClass());
				assertThat(((LispSymbol) eval(
						"(let ((a (" + ctor + " 4 :element-type '" + name + "))) (array-element-type a))"))
					.name()).as("%s answers %s back", ctor, name).isEqualTo(name);
			}
			// The width-preserving door: the element-wise kernels rebuild through
			// vec::%make-like, whose cond has one arm per width and a double default --
			// the door no compiler reaches at all, so it is pinned only here.
			LispVal sum = eval(
					"(vec:add (vec:ones 4 :element-type '" + name + ") (vec:ones 4 :element-type '" + name + "))");
			assertThat(sum).as("vec:add preserves the %s width", name).isInstanceOf(proto.getClass());
		}
	}

	@Test
	void thePrintObjectWalkerExcludesEveryPermit() {
		String walker = LispMacroExpander.printObjectStrDefuns(new ClosRegistry(), false, true)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		for (LispFloatArray proto : prototypesForAllPermits()) {
			// The as() text carries no format specifiers: AssertJ would expand the
			// %pos-* names in it, and "%p" is not one.
			assertThat(walker).as("pos-walk excludes the '" + proto.elementType() + "' width from the general walk")
				// The printed AST is upcased (the reader upcases, print shows the
				// canonical name), so the conjunct is matched as it prints.
				.contains("(NOT (EQUAL (ARRAY-ELEMENT-TYPE %POS-X) '" + proto.elementType() + "))");
		}
	}

	// --- the packed INTEGER audit: report, in tests as well as prose ----------------

	@Test
	void everyPackedIntegerWidthIsReachableAndAnswersItsSpecifier() {
		for (int width : new int[] { 8, 16, 32 }) {
			LispVal v = eval("(make-array 3 :element-type '(unsigned-byte " + width + "))");
			assertThat(v).as("(make-array 3 :element-type '(unsigned-byte %d))", width)
				.isInstanceOf(LispIntVector.class);
			assertThat(((LispIntVector) v).width()).isEqualTo(width);
			assertThat(eval("(let ((a (make-array 3 :element-type '(unsigned-byte " + width
					+ ")))) (equal (array-element-type a) '(unsigned-byte " + width + ")))"))
				.as("(unsigned-byte %d)", width)
				.isSameAs(LispTrue.INSTANCE);
		}
	}

	/**
	 * The audit's finding, pinned: the resolver's direction is the SILENT one -- an
	 * {@code (unsigned-byte N)} the resolver does not list degrades to the general array
	 * with no error and remembers nothing -- while the allocation's direction is loud:
	 * the constructor refuses a width outside 8/16/32. Adding an integer width therefore
	 * means editing BOTH {@code LispNames.unsignedByteWidth} and the
	 * {@code LispIntVector} constructor, and the second makes at least its half of the
	 * edit a hard error -- which is what the float umbrella's switches give at compile
	 * time.
	 */
	@Test
	void thePackedIntegerWidthListsAreTwoAndTheirFailureModesDiffer() {
		assertThat(eval("(let ((a (make-array 3 :element-type '(unsigned-byte 64)))) (array-element-type a))"))
			.as("an unlisted width degrades to the general array, remembering nothing")
			.isSameAs(LispTrue.INSTANCE);
		assertThatThrownBy(() -> new LispIntVector(64, new long[0])).isInstanceOf(IllegalArgumentException.class);
	}

}
