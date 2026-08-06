package am.ik.rontolisp.compiler;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OptimizeLevel} parsing and the two questions the backends ask it. The
 * backward-compatibility pin is {@link #theBareFlagIsTheDefaultLevel()}:
 * {@code --optimize} with no value reaches here as the empty string and must keep meaning
 * what it has always meant, since it is in every doc page, CI job and example.
 */
class OptimizeLevelTest {

	@Test
	void theAbsentFlagIsNoOptimizationAtAll() {
		assertThat(OptimizeLevel.parse(null)).isEqualTo(OptimizeLevel.NONE);
		assertThat(OptimizeLevel.NONE.eliminatesDeadCode()).isFalse();
		assertThat(OptimizeLevel.NONE.prefersSizeOverSpeed()).isFalse();
	}

	@Test
	void theBareFlagIsTheDefaultLevel() {
		assertThat(OptimizeLevel.parse("")).isEqualTo(OptimizeLevel.DEFAULT);
		assertThat(OptimizeLevel.parse("default")).isEqualTo(OptimizeLevel.DEFAULT);
		assertThat(OptimizeLevel.DEFAULT.eliminatesDeadCode()).isTrue();
		assertThat(OptimizeLevel.DEFAULT.prefersSizeOverSpeed()).isFalse();
	}

	@Test
	void theSizeLevelAlsoTradesSpeedForSize() {
		assertThat(OptimizeLevel.parse("size")).isEqualTo(OptimizeLevel.SIZE);
		assertThat(OptimizeLevel.SIZE.eliminatesDeadCode()).isTrue();
		assertThat(OptimizeLevel.SIZE.prefersSizeOverSpeed()).isTrue();
	}

	@Test
	void anUnknownLevelNamesTheAcceptedOnes() {
		// Including the one the design deliberately did NOT ship: a level with no
		// content of its own would be a synonym of default, and a synonym teaches a
		// reader that levels are decoration.
		assertThatThrownBy(() -> OptimizeLevel.parse("high")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("unknown --optimize level 'high' (accepted: default, size)");
		assertThatThrownBy(() -> OptimizeLevel.parse("SIZE")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OptimizeLevel.parse("2")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void everyLevelIsDistinguishableAndOnlyNoneHasNoSpelling() {
		// A level that answers both questions the same way as another IS that other
		// level under a second name; the answers are the whole of what a level is.
		Set<String> answers = new HashSet<>();
		for (OptimizeLevel level : OptimizeLevel.values()) {
			assertThat(answers.add(level.eliminatesDeadCode() + "/" + level.prefersSizeOverSpeed()))
				.as("%s duplicates another level's behavior", level)
				.isTrue();
		}
		assertThat(Arrays.stream(OptimizeLevel.values()).filter(l -> l.spelling() == null).toList())
			.containsExactly(OptimizeLevel.NONE);
		assertThat(OptimizeLevel.spellings()).isEqualTo("default, size");
	}

}
