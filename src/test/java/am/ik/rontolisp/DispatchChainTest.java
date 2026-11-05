package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.reader.LispReader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchChainTest {

	private static final String DISPATCHER = """
			(defun g (x y &rest more)
			  (if (and (consp x) (symbolp y)) cons-symbol
			    (if (and (keywordp x) (find-package x)) package-arm
			      (if (or (%obj-is x 'a 'b) (integerp x)) instance-or-integer
			        (if (and (eql y (the t cell)) (not (consp x))) cell-arm last-arm)))))
			""";

	private static DispatchChain chain(String defun) {
		LispVal memoized = DispatchChain.memoizing(LispReader.readFromString(defun));
		return (DispatchChain) ((LispCons) memoized).toList().get(3);
	}

	private static LispSymbol symbol(String name) {
		return new LispSymbol(name);
	}

	/**
	 * What a call with these answers to the shape tests has left: the residual tests and
	 * the form it ends in, and how many tests were evaluated to get there.
	 */
	private static String selected(DispatchChain chain, Map<String, Boolean> answers, List<String> asked,
			Object... shapes) {
		DispatchChain.Selection selection = chain.select(shapes, form -> {
			String test = form.print();
			asked.add(test);
			return answers.getOrDefault(test, false);
		});
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < selection.tests().length; i++) {
			text.append(selection.tests()[i].print()).append(" => ").append(selection.arms()[i].print()).append("; ");
		}
		return text.append(selection.last().print()).toString();
	}

	@Test
	void theFormsSeeAnOrdinaryIfOverTheParametersTheTestsTellApart() {
		LispCons plain = (LispCons) ((LispCons) LispReader.readFromString(DISPATCHER)).toList().get(3);
		DispatchChain chain = chain(DISPATCHER);
		assertThat(chain).isEqualTo(plain);
		assertThat(chain.print()).isEqualTo(plain.print());
		assertThat(List.of(chain.keys())).containsExactly(symbol("X"), symbol("Y"));
		// No test the shapes decide, no memo: an eql specializer's chain stays as it is.
		String eqlOnly = "(defun g (x) (if (eql x 1) a b))";
		assertThat(DispatchChain.memoizing(LispReader.readFromString(eqlOnly)).print())
			.isEqualTo(LispReader.readFromString(eqlOnly).print());
		assertThat(((LispCons) DispatchChain.memoizing(LispReader.readFromString(eqlOnly))).toList().get(3))
			.isNotInstanceOf(DispatchChain.class);
		// A body that is no chain stays; a chain under a dynamic arm's lets is one.
		assertThat(((LispCons) DispatchChain.memoizing(LispReader.readFromString("(defun g (x) (let ((y x)) y))")))
			.toList()
			.get(3)).isNotInstanceOf(DispatchChain.class);
		LispCons arm = (LispCons) ((LispCons) DispatchChain.memoizing(LispReader.readFromString(
				"(defun g (x) (let ((rt (methods 'g))) (let ((found (f x rt))) (if found (d x) (if (consp x) a b)))))")))
			.toList()
			.get(3);
		assertThat(((LispCons) arm.toList().get(2)).toList().get(2)).isInstanceOf(DispatchChain.class);
	}

	@Test
	void aSelectionKeepsOnlyWhatTheShapesLeaveOpen() {
		// A cons and a symbol: the first test holds, the rest cannot be reached.
		List<String> asked = new ArrayList<>();
		assertThat(selected(chain(DISPATCHER), Map.of("(CONSP X)", true, "(SYMBOLP Y)", true), asked, LispCons.class,
				LispSymbol.class))
			.isEqualTo("CONS-SYMBOL");
		assertThat(asked).containsExactly("(CONSP X)", "(SYMBOLP Y)");
		// A keyword: package still depends on find-package, and an instance test holds
		// for no keyword; the eql test stays, its (not (consp x)) folded away.
		assertThat(selected(chain(DISPATCHER), Map.of("(KEYWORDP X)", true), new ArrayList<>(),
				DispatchChain.shapeOf(symbol(":K")), LispSymbol.class))
			.isEqualTo("(FIND-PACKAGE X) => PACKAGE-ARM; (EQL Y (THE T CELL)) => CELL-ARM; LAST-ARM");
		// An integer: decided, ahead of the eql test.
		assertThat(selected(chain(DISPATCHER), Map.of("(INTEGERP X)", true), new ArrayList<>(), LispInteger.class,
				LispSymbol.class))
			.isEqualTo("INSTANCE-OR-INTEGER");
	}

	@Test
	void aCallWithTheSameShapesFindsTheFirstOnesSelection() {
		DispatchChain chain = chain(DISPATCHER);
		Object x = DispatchChain.shapeOf(new LispString("s"));
		Object y = DispatchChain.shapeOf(symbol(":K"));
		DispatchChain.Selection selection = chain.select(new Object[] { x, y }, form -> false);
		DispatchChain.Shapes second = (DispatchChain.Shapes) chain.root().get(x);
		assertThat(second).isNotNull();
		assertThat(second.get(y)).isSameAs(selection);
		assertThat(second.get(LispSymbol.class)).isNull();
		assertThat(chain.root().get(LispCons.class)).isNull();
		// Enough shapes to grow the table, each found again.
		List<Object> shapes = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			Object shape = new Object();
			shapes.add(shape);
			chain.select(new Object[] { shape, y }, form -> false);
		}
		for (Object shape : shapes) {
			assertThat(chain.root().get(shape)).isNotNull();
		}
	}

	@Test
	void aShapeTellsApartWhatATestCan() {
		LispLayout layout = LispLayout.ofStruct("S", List.of(), List.of());
		LispInstance instance = LispInstance.ofNilSlots(layout);
		assertThat(DispatchChain.shapeOf(instance)).isSameAs(layout);
		assertThat(DispatchChain.shapeOf(symbol("A"))).isSameAs(DispatchChain.shapeOf(symbol("B")));
		assertThat(DispatchChain.shapeOf(symbol(":A"))).isSameAs(DispatchChain.shapeOf(symbol(":B")))
			.isNotSameAs(DispatchChain.shapeOf(symbol("A")));
		assertThat(DispatchChain.shapeOf(new LispString("s"))).isEqualTo(LispString.class);
		assertThat(DispatchChain.shapeOf(LispNil.INSTANCE)).isEqualTo(LispNil.class);
	}

}
