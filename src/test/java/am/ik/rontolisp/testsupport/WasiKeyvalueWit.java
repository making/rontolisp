package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * WebAssembly/wasi-keyvalue's {@code wit/} at {@code v0.2.0-draft}, the version
 * wasmtime's {@code -S keyvalue=y} host answers, as it is published: five files, only
 * {@code world.wit} declaring the package, and {@code atomic.wit} / {@code batch.wit} /
 * {@code watch.wit} taking their types from {@code store.wit}.
 */
public final class WasiKeyvalueWit {

	/** The files, in name order. */
	public static final List<String> FILES = List.of("atomic.wit", "batch.wit", "store.wit", "watch.wit", "world.wit");

	private WasiKeyvalueWit() {
	}

	/**
	 * Writes the package as published.
	 * @param dir the directory to write the files into (created)
	 * @return {@code dir}
	 */
	public static Path directory(Path dir) {
		try {
			Files.createDirectories(dir);
			for (String file : FILES) {
				Files.writeString(dir.resolve(file), text(file));
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return dir;
	}

	/**
	 * The package concatenated into one file by hand, the way a program had to bind it
	 * before a directory could be read: {@code world.wit}, which declares the package,
	 * then the others.
	 * @return the WIT text
	 */
	public static String concatenated() {
		StringBuilder out = new StringBuilder(text("world.wit"));
		for (String file : FILES) {
			if (!file.equals("world.wit")) {
				out.append('\n').append(text(file));
			}
		}
		return out.toString();
	}

	private static String text(String file) {
		try (InputStream in = WasiKeyvalueWit.class.getResourceAsStream("wasi-keyvalue/" + file)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource wasi-keyvalue/" + file);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
