package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormMemoTest {

	private static LispCons form(int value) {
		return new LispCons(new LispInteger(value), LispNil.INSTANCE);
	}

	@Test
	void aFormIsItsOwnCons() {
		FormMemo<String> memo = new FormMemo<>();
		LispCons form = form(1);
		memo.put(form, "first");
		assertThat(memo.get(form)).isEqualTo("first");
		assertThat(memo.get(form(1))).as("an equal form is another form").isNull();
		memo.put(form, "second");
		assertThat(memo.get(form)).isEqualTo("second");
		assertThat(memo.size()).isEqualTo(1);
		memo.clear();
		assertThat(memo.get(form)).isNull();
		assertThat(memo.isEmpty()).isTrue();
	}

	@Test
	void everyFormASystemKeepsStaysRemembered() {
		FormMemo<Integer> memo = new FormMemo<>();
		List<LispVal> forms = new ArrayList<>();
		for (int i = 0; i < 100_000; i++) {
			LispCons form = form(i);
			forms.add(form);
			memo.put(form, i);
		}
		for (int i = 0; i < forms.size(); i++) {
			assertThat(memo.get(forms.get(i))).isEqualTo(i);
		}
	}

	@Test
	void aFormLeavesWithItsGarbage() throws InterruptedException {
		FormMemo<String> memo = new FormMemo<>();
		LispCons kept = form(0);
		memo.put(kept, "kept");
		memo.put(form(1), "garbage");
		for (int attempt = 0; attempt < 200 && memo.size() > 1; attempt++) {
			System.gc();
			Thread.sleep(10);
			// an insertion is what notices
			memo.put(kept, "kept");
		}
		assertThat(memo.size()).isEqualTo(1);
		assertThat(memo.get(kept)).isEqualTo("kept");
	}

}
