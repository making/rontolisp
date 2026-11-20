package am.ik.rontolisp.codegen.jvm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

class JvmRawGlobalsTest {

	// A program of many globals each assigned once: the Java corpus, whose every static
	// is a global. One walk of the program counts them all in milliseconds; a walk per
	// global is 20,000 walks of 40,000 forms -- A REGRESSION IS A HANG, which the
	// timeout cuts (it took the corpus's JVM compile past four minutes).
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void theAssignmentsOfManyGlobalsAreCountedInOneWalk() {
		int count = 20_000;
		StringBuilder source = new StringBuilder();
		SequencedSet<String> globals = new LinkedHashSet<>();
		for (int i = 0; i < count; i++) {
			source.append("(defvar g")
				.append(i)
				.append(" 0) (setq g")
				.append(i)
				.append(" (+ g")
				.append(i)
				.append(" 1))\n");
			globals.add("G" + i);
		}
		// a name some let binds keeps its plain field
		List<LispVal> program = LispReader.readAllFromString(source.toString());
		Set<String> eligible = JvmRawGlobals.collect(program, globals, Set.of("G1"), true);
		assertThat(eligible).hasSize(count - 1).startsWith("G0", "G2").doesNotContain("G1");
		assertThat(JvmRawGlobals.collect(program, globals, Set.of(), false)).isEmpty();
	}

}
