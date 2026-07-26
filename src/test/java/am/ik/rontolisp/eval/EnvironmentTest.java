package am.ik.rontolisp.eval;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentTest {

	@Test
	void defineAndLookup() {
		Environment env = Environment.createGlobal(System.out);
		env.define("x", new LispInteger(42));
		LispVal result = env.lookup("x");
		assertThat(result).isEqualTo(new LispInteger(42));
	}

	@Test
	void lookupFromParent() {
		Environment parent = Environment.createGlobal(System.out);
		parent.define("x", new LispInteger(10));
		Environment child = new Environment(parent);
		LispVal result = child.lookup("x");
		assertThat(result).isEqualTo(new LispInteger(10));
	}

	@Test
	void shadowParentBinding() {
		Environment parent = Environment.createGlobal(System.out);
		parent.define("x", new LispInteger(10));
		Environment child = new Environment(parent);
		child.define("x", new LispInteger(20));
		assertThat(child.lookup("x")).isEqualTo(new LispInteger(20));
		assertThat(parent.lookup("x")).isEqualTo(new LispInteger(10));
	}

	@Test
	void lookupUndefinedThrows() {
		Environment env = Environment.createGlobal(System.out);
		assertThatThrownBy(() -> env.lookup("undefined")).isInstanceOf(LispEvalException.class);
	}

	@Test
	void setUpdatesExistingBinding() {
		Environment env = Environment.createGlobal(System.out);
		env.define("x", new LispInteger(10));
		env.set("x", new LispInteger(20));
		assertThat(env.lookup("x")).isEqualTo(new LispInteger(20));
	}

	// --- defineLazy: the compile path's pending macro-time globals ---

	@Test
	void lazyValueIsProducedOnFirstLookupAndOnlyOnce() {
		Environment env = Environment.createGlobal(System.out);
		AtomicInteger runs = new AtomicInteger();
		env.defineLazy("x", () -> {
			runs.incrementAndGet();
			return new LispInteger(42);
		});
		assertThat(runs).hasValue(0);
		assertThat(env.lookup("x")).isEqualTo(new LispInteger(42));
		assertThat(env.lookup("x")).isEqualTo(new LispInteger(42));
		assertThat(env.lookupOrNull("x")).isEqualTo(new LispInteger(42));
		assertThat(runs).hasValue(1);
	}

	@Test
	void existenceTestsDoNotProduceTheLazyValue() {
		Environment env = Environment.createGlobal(System.out);
		AtomicInteger runs = new AtomicInteger();
		env.defineLazy("x", () -> {
			runs.incrementAndGet();
			return new LispInteger(42);
		});
		assertThat(env.isBound("x")).isTrue();
		assertThat(new Environment(env).hasBinding("x")).isTrue();
		assertThat(runs).hasValue(0);
	}

	@Test
	void defineAndSetDiscardAPendingValue() {
		Environment defined = Environment.createGlobal(System.out);
		AtomicInteger definedRuns = new AtomicInteger();
		defined.defineLazy("x", () -> {
			definedRuns.incrementAndGet();
			return new LispInteger(42);
		});
		defined.define("x", new LispInteger(7));
		assertThat(defined.lookup("x")).isEqualTo(new LispInteger(7));
		assertThat(definedRuns).hasValue(0);

		Environment assigned = Environment.createGlobal(System.out);
		AtomicInteger assignedRuns = new AtomicInteger();
		assigned.defineLazy("x", () -> {
			assignedRuns.incrementAndGet();
			return new LispInteger(42);
		});
		// setq of a global reaches set(), not define()
		new Environment(assigned).set("x", new LispInteger(9));
		assertThat(assigned.lookup("x")).isEqualTo(new LispInteger(9));
		assertThat(assignedRuns).hasValue(0);
	}

	@Test
	void aLazyValueThatDeclinesLeavesTheNameUnbound() {
		Environment env = Environment.createGlobal(System.out);
		env.defineLazy("x", () -> null);
		assertThatThrownBy(() -> env.lookup("x")).isInstanceOf(LispEvalException.class);
		// The pending entry is consumed by the attempt: a second read does not retry.
		assertThat(env.lookupOrNull("x")).isNull();
		assertThat(env.isBound("x")).isFalse();
	}

	@Test
	void aSelfReferentialLazyValueSeesItselfUnboundRatherThanRecursing() {
		Environment env = Environment.createGlobal(System.out);
		env.defineLazy("x", () -> env.lookupOrNull("x"));
		assertThatThrownBy(() -> env.lookup("x")).isInstanceOf(LispEvalException.class);
	}

}
