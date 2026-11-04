package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LispTreesTest {

	@Test
	void rebuildSpineKeepsEveryCellNothingChangedBelow() {
		LispVal form = LispReader.readFromString("(a (b c) d . e)");
		assertThat(LispTrees.rebuildSpine(form, node -> null, car -> car)).isSameAs(form);
		LispCons first = (LispCons) form;
		LispCons second = (LispCons) first.cdr();
		LispCons third = (LispCons) second.cdr();
		// Only D changes: the cells before it are rebuilt, the dotted tail is shared.
		LispCons rebuilt = (LispCons) LispTrees.rebuildSpine(form, node -> null,
				car -> car instanceof LispSymbol sym && "D".equals(sym.name()) ? new LispSymbol("X") : car);
		assertThat(rebuilt.print()).isEqualTo("(A (B C) X . E)");
		assertThat(rebuilt).isNotSameAs(first);
		assertThat(rebuilt.car()).isSameAs(first.car());
		assertThat(((LispCons) rebuilt.cdr()).car()).isSameAs(second.car());
		assertThat(((LispCons) ((LispCons) rebuilt.cdr()).cdr()).cdr()).isSameAs(third.cdr());
	}

	@Test
	void rebuildSpineVisitsInTheRecursiveWalksOrder() {
		List<String> seen = new ArrayList<>();
		LispVal form = LispReader.readFromString("(a b (quote c) d)");
		LispVal result = LispTrees.rebuildSpine(form, node -> {
			seen.add("stop " + node.print());
			// A tail cell spelled like a special form is handled as one, as the
			// recursive walk handled it.
			return node instanceof LispCons cell && cell.car() instanceof LispSymbol sym && "B".equals(sym.name())
					? new LispSymbol("CUT") : null;
		}, car -> {
			seen.add("car " + car.print());
			return car;
		});
		assertThat(result.print()).isEqualTo("(A . CUT)");
		assertThat(seen).containsExactly("stop (A B 'C D)", "car A", "stop (B 'C D)");
	}

	@Test
	void aSpineClosedIntoItselfIsReportedNotWalkedForEver() {
		LispVal circular = LispReader.readFromString("#1=(a b c . #1#)");
		assertThatThrownBy(() -> LispTrees.rebuildSpine(circular, node -> null, car -> car))
			.isInstanceOf(LispTrees.CircularListException.class);
		assertThat(LispTrees.circularSpine(circular)).isNotNull();
		assertThat(LispTrees.circularSpine(LispReader.readFromString("(x #(1 #1=(a . #1#)))"))).isNotNull();
	}

	@Test
	void sharedStructureAndCarCyclesAreNotCircularSpines() {
		assertThat(LispTrees.circularSpine(LispReader.readFromString("(#1=(a b) #1# . #1#)"))).isNull();
		assertThat(LispTrees.circularSpine(LispReader.readFromString("#1=(a #1# b)"))).isNull();
	}

	@Test
	void rewriteDatumSymbolsKeepsTheShapeOfTheDatum() {
		Function<LispSymbol, LispVal> qualify = sym -> "FOO".equals(sym.name()) ? new LispSymbol("P::FOO") : sym;
		LispVal untouched = LispReader.readFromString("(a #(b) #S(c :d e) #1=(f #1#))");
		assertThat(LispTrees.rewriteDatumSymbols(untouched, qualify)).isSameAs(untouched);

		// A label's sharing and its circle are the datum's: a rewrite that copies keeps
		// both, a tail cycle included.
		LispCons shared = (LispCons) LispTrees.rewriteDatumSymbols(LispReader.readFromString("(#1=(foo) #1#)"),
				qualify);
		assertThat(shared.car().print()).isEqualTo("(P::FOO)");
		assertThat(((LispCons) shared.cdr()).car()).isSameAs(shared.car());
		LispCons carCycle = (LispCons) LispTrees.rewriteDatumSymbols(LispReader.readFromString("#1=(foo #1#)"),
				qualify);
		assertThat(carCycle.car().print()).isEqualTo("P::FOO");
		assertThat(((LispCons) carCycle.cdr()).car()).isSameAs(carCycle);
		LispCons tailCycle = (LispCons) LispTrees.rewriteDatumSymbols(LispReader.readFromString("#1=(foo a . #1#)"),
				qualify);
		assertThat(((LispCons) tailCycle.cdr()).cdr()).isSameAs(tailCycle);
		LispArray arrayCycle = (LispArray) LispTrees.rewriteDatumSymbols(LispReader.readFromString("#1=#(foo #1#)"),
				qualify);
		assertThat(arrayCycle.data()[0].print()).isEqualTo("P::FOO");
		assertThat(arrayCycle.data()[1]).isSameAs(arrayCycle);
		// A position survives, and an array the reader cannot write is a value, not a
		// datum to walk.
		LocatedCons located = new LocatedCons(new LispSymbol("FOO"), LispNil.INSTANCE, "f.lisp", 3);
		assertThat(LispTrees.rewriteDatumSymbols(located, qualify)).isInstanceOfSatisfying(LocatedCons.class,
				copy -> assertThat(copy.line()).isEqualTo(3));
		LispArray live = new LispArray(new int[] { 2 }, new LispVal[] { new LispSymbol("FOO"), LispNil.INSTANCE }, 1,
				true);
		assertThat(LispTrees.rewriteDatumSymbols(live, qualify)).isSameAs(live);
	}

	@Test
	void equalOnTwoCircularListsReportsTheCycle() {
		LispVal a = LispReader.readFromString("#1=(1 2 . #1#)");
		LispVal b = LispReader.readFromString("#1=(1 2 . #1#)");
		assertThat(LispEquality.equal(a, a)).isTrue();
		assertThatThrownBy(() -> LispEquality.equal(a, b)).isInstanceOf(LispTrees.CircularListException.class);
		assertThat(LispEquality.equal(a, LispReader.readFromString("#1=(1 3 . #1#)"))).isFalse();
	}

}
