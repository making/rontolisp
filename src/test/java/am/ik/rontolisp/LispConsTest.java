package am.ik.rontolisp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LispConsTest {

	private static LispVal list(LispVal... items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			result = new LispCons(items[i], result);
		}
		return result;
	}

	// -- the identity rule every AST pass must honour (.kb/source-positions.md) --------

	@Test
	void rebuiltReturnsTheOriginalWhenNeitherHalfChanged() {
		LispCons cons = new LispCons(new LispSymbol("A"), new LispSymbol("B"));
		assertThat(LispCons.rebuilt(cons, cons.car(), cons.cdr())).isSameAs(cons);
	}

	@Test
	void rebuiltAllocatesWhenEitherHalfChanged() {
		LispCons cons = new LispCons(new LispSymbol("A"), new LispSymbol("B"));
		LispVal newCar = LispCons.rebuilt(cons, new LispSymbol("A"), cons.cdr());
		// An EQUAL but distinct car is still a change: identity is what carries a
		// form's recorded source position, so a pass that mints a fresh symbol has
		// changed the cons whether or not it changed the program.
		assertThat(newCar).isNotSameAs(cons);
		assertThat(LispCons.rebuilt(cons, cons.car(), LispNil.INSTANCE)).isNotSameAs(cons);
	}

	@Test
	void rebuiltListReturnsTheOriginalWhenEveryElementIsTheSameObject() {
		LispSymbol a = new LispSymbol("A");
		LispSymbol b = new LispSymbol("B");
		LispCons original = (LispCons) list(a, b);
		assertThat(LispCons.rebuiltList(original, List.of(a, b))).isSameAs(original);
	}

	@Test
	void rebuiltListAllocatesWhenAnElementOrTheLengthChanged() {
		LispSymbol a = new LispSymbol("A");
		LispSymbol b = new LispSymbol("B");
		LispCons original = (LispCons) list(a, b);
		assertThat(LispCons.rebuiltList(original, List.of(a, new LispSymbol("B")))).isNotSameAs(original);
		assertThat(LispCons.rebuiltList(original, List.of(a))).isNotSameAs(original);
		assertThat(LispCons.rebuiltList(original, List.of(a, b, new LispSymbol("C")))).isNotSameAs(original);
		assertThat(LispCons.rebuiltList(original, List.of(a, b, new LispSymbol("C"))).print()).isEqualTo("(A B C)");
	}

	@Test
	void rebuiltListNeverReturnsADottedOriginalItWouldHaveProperized() {
		// (a . b) walked into the elements [a] is NOT the same list: rebuiltList's
		// contract is a PROPER list, so the dotted tail must be dropped by allocating,
		// never by handing back the dotted original.
		LispCons dotted = new LispCons(new LispSymbol("A"), new LispSymbol("B"));
		LispVal rebuilt = LispCons.rebuiltList(dotted, List.of(dotted.car()));
		assertThat(rebuilt).isNotSameAs(dotted);
		assertThat(rebuilt.print()).isEqualTo("(A)");
	}

	@Test
	void aRebuildOfALocatedConsStaysLocated() {
		// The interpreter's positions ride on the cell itself, so a pass that rewrites a
		// located form (the package resolver qualifying its symbols) must keep it
		// located -- and one that changes nothing hands back the very same cell.
		LocatedCons located = new LocatedCons(new LispSymbol("F"), LispNil.INSTANCE, "app.lisp", 7);
		assertThat(LispCons.rebuilt(located, located.car(), located.cdr())).isSameAs(located);
		LispCons rewritten = LispCons.rebuilt(located, new LispSymbol("APP::F"), LispNil.INSTANCE);
		assertThat(rewritten).isInstanceOfSatisfying(LocatedCons.class, copy -> {
			assertThat(copy.file()).isEqualTo("app.lisp");
			assertThat(copy.line()).isEqualTo(7);
		});
		assertThat(LispCons.rebuiltList(located, List.of(new LispSymbol("APP::F")))).isInstanceOf(LocatedCons.class);
		// Nothing a program can observe differs from a plain cell.
		assertThat(located).isEqualTo(new LispCons(new LispSymbol("F"), LispNil.INSTANCE));
		assertThat(located.print()).isEqualTo("(F)");
	}

}
