package am.ik.femtolisp.eval;

import java.io.PrintStream;

import am.ik.femtolisp.LispInteger;
import am.ik.femtolisp.LispVal;
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

}
