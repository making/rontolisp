package am.ik.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tar.gz and zip extraction: the tree each writes, the path-escape refusal both share,
 * and a truncated archive refused rather than extracted short.
 */
class ArchivesTest {

	@Test
	void extractsATarGz(@TempDir Path dest) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("lib-1.0/lib.asd", "(defsystem \"lib\")");
		files.put("lib-1.0/src/lib.lisp", "(defun f () 1)");

		Archives.extractTarGz(tarGz(files), dest);

		assertThat(tree(dest)).containsExactly("lib-1.0/lib.asd", "lib-1.0/src/lib.lisp");
		assertThat(Files.readString(dest.resolve("lib-1.0/src/lib.lisp"))).isEqualTo("(defun f () 1)");
	}

	@Test
	void aPaxPathRecordNamesTheNextEntry(@TempDir Path dest) throws IOException {
		// git archive and modern tar write a name past 100 bytes as a PAX "path"
		// record; the header's own name field then holds a truncated placeholder.
		String longName = "repo-abc/" + "deep/".repeat(30) + "file.clj";
		String record = "path=" + longName + "\n";
		String pax = (record.length() + 4) + " " + record;
		ByteArrayOutputStream tar = new ByteArrayOutputStream();
		writeEntry(tar, "PaxHeader/placeholder", 'x', pax.getBytes(StandardCharsets.UTF_8));
		writeEntry(tar, "placeholder", '0', "(ns deep)".getBytes(StandardCharsets.UTF_8));
		tar.write(new byte[1024]);

		Archives.extractTarGz(gzip(tar.toByteArray()), dest);

		assertThat(tree(dest)).containsExactly(longName);
	}

	@Test
	void aTarEntryEscapingTheDestinationIsRefused(@TempDir Path dest) {
		assertThatThrownBy(() -> Archives.extractTarGz(tarGz(Map.of("../escape.txt", "x")), dest.resolve("out")))
			.isInstanceOf(IOException.class)
			.hasMessage("unsafe path in archive: ../escape.txt");
		assertThat(dest.resolve("escape.txt")).doesNotExist();
	}

	@Test
	void aTruncatedTarGzIsRefused(@TempDir Path dest) {
		byte[] whole = tarGz(Map.of("lib/big.txt", "0123456789abcdef".repeat(4096)));

		assertThatThrownBy(() -> Archives.extractTarGz(Arrays.copyOf(whole, whole.length / 2), dest))
			.isInstanceOf(IOException.class);
	}

	@Test
	void extractsAZip(@TempDir Path dest) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("META-INF/", "");
		files.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
		files.put("clojure/data/json.clj", "(ns clojure.data.json)");

		Archives.extractZip(zip(files), dest);

		assertThat(tree(dest)).containsExactly("META-INF/MANIFEST.MF", "clojure/data/json.clj");
		assertThat(Files.readString(dest.resolve("clojure/data/json.clj"))).isEqualTo("(ns clojure.data.json)");
	}

	@Test
	void aZipEntryEscapingTheDestinationIsRefused(@TempDir Path dest) {
		assertThatThrownBy(() -> Archives.extractZip(zip(Map.of("../../escape.txt", "x")), dest.resolve("out")))
			.isInstanceOf(IOException.class)
			.hasMessage("unsafe path in archive: ../../escape.txt");
	}

	@Test
	void aZipCutAtAnEntryBoundaryIsRefused(@TempDir Path dest) throws IOException {
		// Cut right after the first entry: a stream reader alone would see a complete,
		// shorter archive.
		Map<String, String> first = Map.of("a.txt", "first");
		Map<String, String> both = new LinkedHashMap<>(first);
		both.put("b.txt", "second");
		byte[] whole = zip(both);
		int firstEntryEnd = indexOf(whole, new byte[] { 'P', 'K', 3, 4 }, 4);

		assertThatThrownBy(() -> Archives.extractZip(Arrays.copyOf(whole, firstEntryEnd), dest))
			.isInstanceOf(IOException.class)
			.hasMessage("not a complete zip archive (no end-of-central-directory record)");
		assertThat(tree(dest)).isEmpty();
	}

	private static int indexOf(byte[] haystack, byte[] needle, int from) {
		for (int at = from; at <= haystack.length - needle.length; at++) {
			if (Arrays.equals(haystack, at, at + needle.length, needle, 0, needle.length)) {
				return at;
			}
		}
		throw new IllegalStateException("not found");
	}

	private static List<String> tree(Path dir) throws IOException {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
		}
	}

	static byte[] tarGz(Map<String, String> files) {
		ByteArrayOutputStream tar = new ByteArrayOutputStream();
		for (Map.Entry<String, String> entry : files.entrySet()) {
			writeEntry(tar, entry.getKey(), '0', entry.getValue().getBytes(StandardCharsets.UTF_8));
		}
		tar.writeBytes(new byte[1024]);
		return gzip(tar.toByteArray());
	}

	private static void writeEntry(ByteArrayOutputStream tar, String name, char type, byte[] content) {
		byte[] header = new byte[512];
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		System.arraycopy(nameBytes, 0, header, 0, Math.min(100, nameBytes.length));
		byte[] size = String.format("%011o", content.length).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(size, 0, header, 124, size.length);
		header[156] = (byte) type;
		tar.writeBytes(header);
		tar.writeBytes(content);
		tar.writeBytes(new byte[(512 - content.length % 512) % 512]);
	}

	private static byte[] gzip(byte[] bytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
			gz.write(bytes);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return out.toByteArray();
	}

	private static byte[] zip(Map<String, String> files) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, String> entry : files.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return out.toByteArray();
	}

}
