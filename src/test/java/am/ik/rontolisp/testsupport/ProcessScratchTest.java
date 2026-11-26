package am.ik.rontolisp.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessScratchTest {

	@TempDir
	Path base;

	@Test
	void theRootIsGoneOnceItsJvmExits() throws Exception {
		Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				System.getProperty("java.class.path"), Child.class.getName(), this.base.toString())
			.inheritIO()
			.start();
		assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
		assertThat(child.exitValue()).isZero();
		assertThat(this.base.resolve("p" + child.pid())).doesNotExist();
	}

	@Test
	void aDeadProcessesDirectoriesAreSweptAndALiveOnesKept() throws Exception {
		Process finished = new ProcessBuilder("true").start();
		finished.waitFor();
		long dead = finished.pid();
		Files.createDirectories(this.base.resolve("p" + dead + "/w1/sub"));
		Files.writeString(this.base.resolve("p" + dead + "/w1/sub/f.txt"), "x");
		// The per-thread names older builds left beside the root.
		Files.createDirectories(this.base.resolve("p" + dead + "-w42"));
		long live = ProcessHandle.current().pid();
		Files.createDirectories(this.base.resolve("p" + live + "-w1"));
		Files.createDirectories(this.base.resolve("unrelated"));

		ProcessScratch.sweep(this.base);

		assertThat(this.base.resolve("p" + dead)).doesNotExist();
		assertThat(this.base.resolve("p" + dead + "-w42")).doesNotExist();
		assertThat(this.base.resolve("p" + live + "-w1")).isDirectory();
		assertThat(this.base.resolve("unrelated")).isDirectory();
	}

	/** Creates a root under the base it is given, fills it, and exits normally. */
	static final class Child {

		public static void main(String[] args) throws Exception {
			Path root = ProcessScratch.create(Path.of(args[0]));
			Files.createDirectories(root.resolve("w1/sub"));
			Files.writeString(root.resolve("w1/sub/f.txt"), "x");
		}

	}

}
