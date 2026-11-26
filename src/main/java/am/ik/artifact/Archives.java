package am.ik.artifact;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jspecify.annotations.Nullable;

/**
 * Archive extraction with no external dependency: gzip-compressed tar (a Quicklisp
 * release, a forge's source archive) and zip (a jar). Both write directories and regular
 * files only, and confine every entry to the destination ({@link #safeResolve}) -- an
 * entry naming {@code ../x} or an absolute path fails the whole extraction. A truncated
 * archive fails too rather than yielding a short tree; the caller extracts into a staging
 * directory ({@link AtomicInstall}), so a failure leaves nothing behind.
 */
public final class Archives {

	private static final int TAR_BLOCK = 512;

	/** The zip end-of-central-directory signature, {@code PK\5\6} little-endian. */
	private static final int ZIP_EOCD_SIGNATURE = 0x06054b50;

	/** The fixed part of the end-of-central-directory record. */
	private static final int ZIP_EOCD_LENGTH = 22;

	private Archives() {
	}

	/**
	 * Extracts a gzip-compressed tar archive into {@code destDir}. Handles the USTAR
	 * {@code name}/{@code prefix} split, GNU long names ({@code L}) and PAX {@code path}
	 * records ({@code x}); creates directories and regular files and skips every other
	 * entry type (links, devices, global PAX headers).
	 * @param tarGz the archive bytes
	 * @param destDir the destination directory
	 * @throws IOException on a malformed or truncated archive or an entry escaping
	 * {@code destDir}
	 */
	public static void extractTarGz(byte[] tarGz, Path destDir) throws IOException {
		Path base = destDir.toAbsolutePath().normalize();
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(tarGz))) {
			String longName = null;
			while (true) {
				byte[] header = in.readNBytes(TAR_BLOCK);
				if (header.length < TAR_BLOCK || isZeroBlock(header)) {
					break;
				}
				long size = parseOctal(header, 124, 12);
				char type = (char) (header[156] & 0xff);
				if (type == 'L' || type == 'x') {
					byte[] data = readEntry(in, size);
					String text = new String(data, 0, (int) size, StandardCharsets.UTF_8);
					String name = type == 'L' ? trimNul(text) : paxPath(text);
					if (name != null) {
						longName = name;
					}
					continue;
				}
				String name = longName != null ? longName : combineName(header);
				longName = null;
				if (type == '5') {
					Files.createDirectories(safeResolve(base, name));
					skipFully(in, size);
				}
				else if (type == '0' || type == '\0') {
					byte[] data = readEntry(in, size);
					writeFile(safeResolve(base, name), data, (int) size);
				}
				else {
					skipFully(in, size);
				}
			}
		}
	}

	/**
	 * Extracts a zip archive (a jar) into {@code destDir}: directory entries and regular
	 * files. The archive must end in an end-of-central-directory record -- a zip stream
	 * cut at an entry boundary otherwise reads as a complete, shorter archive.
	 * @param zip the archive bytes
	 * @param destDir the destination directory
	 * @throws IOException on a malformed or truncated archive or an entry escaping
	 * {@code destDir}
	 */
	public static void extractZip(byte[] zip, Path destDir) throws IOException {
		if (!hasEndOfCentralDirectory(zip)) {
			throw new IOException("not a complete zip archive (no end-of-central-directory record)");
		}
		Path base = destDir.toAbsolutePath().normalize();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				Path target = safeResolve(base, entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				}
				else {
					byte[] data = in.readAllBytes();
					writeFile(target, data, data.length);
				}
			}
		}
	}

	/**
	 * Resolves an archive entry name against {@code base}, refusing any name that
	 * normalizes to a path outside it ({@code ../x}, an absolute path).
	 * @param base the absolute, normalized destination directory
	 * @param name the entry name
	 * @return the resolved path, inside {@code base}
	 * @throws IOException if the name escapes {@code base} or is not a path
	 */
	public static Path safeResolve(Path base, String name) throws IOException {
		Path resolved;
		try {
			resolved = base.resolve(name).normalize();
		}
		catch (InvalidPathException ex) {
			throw new IOException("unsafe path in archive: " + name, ex);
		}
		if (!resolved.startsWith(base)) {
			throw new IOException("unsafe path in archive: " + name);
		}
		return resolved;
	}

	private static void writeFile(Path target, byte[] data, int length) throws IOException {
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(target, length == data.length ? data : Arrays.copyOf(data, length));
	}

	/**
	 * Reads an entry's data blocks (padded to the block size), failing when cut short.
	 */
	private static byte[] readEntry(InputStream in, long size) throws IOException {
		if (size > Integer.MAX_VALUE - TAR_BLOCK) {
			throw new IOException("tar entry too large: " + size + " bytes");
		}
		int padded = (int) ((size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK);
		byte[] data = in.readNBytes(padded);
		if (data.length < padded) {
			throw new IOException("truncated tar archive");
		}
		return data;
	}

	private static void skipFully(InputStream in, long size) throws IOException {
		long padded = (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK;
		in.skipNBytes(padded);
	}

	/**
	 * The {@code path} value of a PAX extended header ({@code "LEN key=value\n"}
	 * records), or {@code null} if it has none.
	 */
	private static @Nullable String paxPath(String records) {
		int at = 0;
		while (at < records.length()) {
			int space = records.indexOf(' ', at);
			if (space < 0) {
				return null;
			}
			int length;
			try {
				length = Integer.parseInt(records.substring(at, space));
			}
			catch (NumberFormatException ex) {
				return null;
			}
			int end = at + length;
			if (length <= 0 || end > records.length()) {
				return null;
			}
			String record = records.substring(space + 1, end - 1);
			if (record.startsWith("path=")) {
				return record.substring("path=".length());
			}
			at = end;
		}
		return null;
	}

	private static boolean hasEndOfCentralDirectory(byte[] zip) {
		// The record sits at the end, followed by a comment of at most 65535 bytes.
		int earliest = Math.max(0, zip.length - ZIP_EOCD_LENGTH - 0xffff);
		for (int at = zip.length - ZIP_EOCD_LENGTH; at >= earliest; at--) {
			int signature = (zip[at] & 0xff) | (zip[at + 1] & 0xff) << 8 | (zip[at + 2] & 0xff) << 16
					| (zip[at + 3] & 0xff) << 24;
			if (signature == ZIP_EOCD_SIGNATURE) {
				int commentLength = (zip[at + 20] & 0xff) | (zip[at + 21] & 0xff) << 8;
				if (at + ZIP_EOCD_LENGTH + commentLength == zip.length) {
					return true;
				}
			}
		}
		return false;
	}

	private static String combineName(byte[] header) {
		String name = parseString(header, 0, 100);
		String prefix = parseString(header, 345, 155);
		return prefix.isEmpty() ? name : prefix + "/" + name;
	}

	private static boolean isZeroBlock(byte[] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static String parseString(byte[] block, int offset, int length) {
		int end = offset;
		int limit = offset + length;
		while (end < limit && block[end] != 0) {
			end++;
		}
		return new String(block, offset, end - offset, StandardCharsets.UTF_8);
	}

	private static String trimNul(String s) {
		int end = s.indexOf('\0');
		return (end < 0 ? s : s.substring(0, end)).trim();
	}

	private static long parseOctal(byte[] block, int offset, int length) {
		long value = 0;
		int i = offset;
		int limit = offset + length;
		// Skip leading spaces and NULs.
		while (i < limit && (block[i] == ' ' || block[i] == 0)) {
			i++;
		}
		while (i < limit && block[i] >= '0' && block[i] <= '7') {
			value = (value << 3) + (block[i] - '0');
			i++;
		}
		return value;
	}

}
