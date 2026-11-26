package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
	}

	@Test
	void shieldCirclesLeavesATreeToWalk() {
		// Sharing is no circle: the form is untouched and nothing is wrapped.
		LispVal shared = LispReader.readFromString("(#1=(a b) #1# . #1#)");
		assertThat(LispTrees.shieldCircles(shared)).isSameAs(shared);
		assertThat(shared.print()).isEqualTo("((A B) (A B) A B)");

		// On a circle itself, by a car and by a tail.
		assertThat(LispTrees.shieldCircles(LispReader.readFromString("#1=(a #1# b)")))
			.isInstanceOf(LispCircularLiteral.class);
		assertThat(LispTrees.shieldCircles(LispReader.readFromString("#1=(a b . #1#)")))
			.isInstanceOf(LispCircularLiteral.class);

		// A quoted datum and an array go whole, a list in code from the circle on, and
		// one label reached twice is one wrapper.
		LispCons form = (LispCons) LispTrees.shieldCircles(
				LispReader.readFromString("(f '(a #1=(b #1#)) #(c #2=(d #2#)) (e . #3=(g . #3#)) '#4=(h #4#) '#4#)"));
		assertThat(form.print()).isEqualTo(
				"(F '#<circular literal> #<circular literal> (E . #<circular literal>) '#<circular literal> '#<circular literal>)");
		List<LispVal> args = form.toList();
		LispCircularLiteral quoted = (LispCircularLiteral) ((LispCons) ((LispCons) args.get(1)).cdr()).car();
		LispCons datum = (LispCons) quoted.datum();
		assertThat(datum.car().print()).isEqualTo("A");
		LispCons labelled = (LispCons) ((LispCons) datum.cdr()).car();
		assertThat(((LispCons) labelled.cdr()).car()).isSameAs(labelled);
		assertThat(((LispCircularLiteral) args.get(2)).datum()).isInstanceOf(LispArray.class);
		assertThat(((LispCons) ((LispCons) args.get(4)).cdr()).car())
			.isSameAs(((LispCons) ((LispCons) args.get(5)).cdr()).car());

		// A program's form names what its wrappers hide; a form with no circle stays.
		assertThat(LispTrees.shieldProgramForm(LispReader.readFromString("(f '#1=(p::g :k #S(h) #(i) #1#))")).print())
			.isEqualTo("(PROGN '(I H P::G) (F '#<circular literal>))");
		assertThat(LispTrees.shieldProgramForm(shared)).isSameAs(shared);
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
		LispCircularLiteral wrapped = (LispCircularLiteral) LispTrees
			.rewriteDatumSymbols(new LispCircularLiteral(LispReader.readFromString("#1=(foo #1#)")), qualify);
		assertThat(((LispCons) wrapped.datum()).car().print()).isEqualTo("P::FOO");
		LispStructLiteral literalCycle = (LispStructLiteral) LispTrees
			.rewriteDatumSymbols(LispReader.readFromString("#1=#S(foo :x (foo #1#))"), qualify);
		assertThat(literalCycle.typeName()).isEqualTo("P::FOO");
		assertThat(((LispCons) ((LispCons) literalCycle.slotValues().get(0)).cdr()).car()).isSameAs(literalCycle);
		// A position survives, and an array the reader cannot write is a value, not a
		// datum to walk.
		LocatedCons located = new LocatedCons(new LispSymbol("FOO"), LispNil.INSTANCE, "f.lisp", 3);
		assertThat(LispTrees.rewriteDatumSymbols(located, qualify)).isInstanceOfSatisfying(LocatedCons.class,
				copy -> assertThat(copy.line()).isEqualTo(3));
		LispArray live = new LispArray(new int[] { 2 }, new LispVal[] { new LispSymbol("FOO"), LispNil.INSTANCE }, 1,
				true);
		assertThat(LispTrees.rewriteDatumSymbols(live, qualify)).isSameAs(live);
		// A node stands or takes its copy by what it reaches, so two data sharing it
		// agree
		// whichever comes first, and whether or not the other changes at all.
		LispCons two = (LispCons) LispReader.readFromString("((#1=(n) (foo #1#)) ((foo #1#) #1#) #2=(foo) (a #2#))");
		Map<LispVal, LispVal> copies = new IdentityHashMap<>();
		LispCons plain = (LispCons) two.car();
		LispVal rewritten = LispTrees.rewriteDatumSymbols(plain, qualify, copies);
		assertThat(((LispCons) rewritten).car()).isSameAs(plain.car());
		LispCons third = (LispCons) ((LispCons) ((LispCons) two.cdr()).cdr()).car();
		LispCons fourth = (LispCons) ((LispCons) ((LispCons) ((LispCons) two.cdr()).cdr()).cdr()).car();
		assertThat(((LispCons) LispTrees.rewriteDatumSymbols(fourth, qualify, copies)).cdr()).isInstanceOfSatisfying(
				LispCons.class,
				cell -> assertThat(cell.car()).isSameAs(LispTrees.rewriteDatumSymbols(third, qualify, copies)));
	}

	@Test
	void aHiddenNamesListNeverReadsAsAReadTimeMarker() {
		// (%READ-EVAL X) as a tail of the list would evaluate X, a name of the datum.
		LispCons marker = new LispCons(new LispSymbol(LispNames.READ_EVAL),
				new LispCons(new LispSymbol("BACK"), LispNil.INSTANCE));
		LispVal circle = LispReader.readFromString("#1=(x #1#)");
		((LispCons) circle).setCar(marker);
		LispCons shielded = (LispCons) LispTrees.shieldProgramForm(circle);
		LispCons names = (LispCons) ((LispCons) ((LispCons) shielded.cdr()).car()).cdr();
		assertThat(names.car().print()).isEqualTo("(BACK)");
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
