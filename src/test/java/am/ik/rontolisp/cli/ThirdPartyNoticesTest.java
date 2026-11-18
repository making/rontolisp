package am.ik.rontolisp.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What keeps {@code THIRD-PARTY-NOTICES.txt} true. A file holding third-party code or
 * data says so in its header -- {@code Notice: THIRD-PARTY-NOTICES.txt}, a comma and the
 * entry's name in double quotes -- and the entry must list the file, and every file an
 * entry lists must carry that header, so a copy that announces itself cannot land without
 * its notice, nor a notice outlive its copy. A vendored library kept whole carries its
 * own licence text instead. And {@code rontolisp notices} prints, from what the jar
 * carries, what a compiled program's distribution owes and what the tool owes.
 */
class ThirdPartyNoticesTest {

	private static final Path NOTICES = Path.of("THIRD-PARTY-NOTICES.txt");

	private static final Pattern MARKER = Pattern.compile("THIRD-PARTY-NOTICES\\.txt, \"([^\"]+)\"");

	/**
	 * What is not scanned for headers: the notes, which cite the file in prose, and what
	 * is not this repository's text.
	 */
	private static final Set<String> NOT_SCANNED = Set.of(".git", ".kb", ".todo", ".claude", ".idea", "target",
			"node_modules", "dist", ".wrangler", ".gradle", ".venv", "ansi-test");

	/**
	 * A directory under {@code src/test/resources} whose {@code .asd} declares a licence
	 * is a vendored library unless it is one of these fixtures of ours.
	 */
	private static final Set<String> OWN_FIXTURES = Set.of("package-inferred-demo");

	/** What a licence text says, when a library keeps it in its sources. */
	private static final Pattern LICENCE_TEXT = Pattern.compile("Permission is hereby granted|Redistribution and use"
			+ "|Permission to use, copy|public domain|GNU (Lesser|Library) General Public|Boost Software License");

	@Test
	void everyListedFileCarriesItsHeaderAndEveryHeaderIsListed() throws IOException {
		ThirdPartyNotices notices = ThirdPartyNotices.parse(Files.readString(NOTICES));
		assertThat(notices.entries()).isNotEmpty();
		Map<String, List<String>> headers = headers();
		List<String> problems = new ArrayList<>();
		for (ThirdPartyNotices.Entry entry : notices.entries()) {
			for (String field : List.of("Origin", "License", "Ships", "Files", "Use")) {
				String value = entry.field(field);
				if (value == null || value.isBlank()) {
					problems.add(entry.name() + ": no " + field);
				}
			}
			if (!List.of("output", "tool", "repository").contains(entry.field("Ships"))) {
				problems.add(entry.name() + ": Ships is " + entry.field("Ships"));
			}
			if (entry.text().isBlank()) {
				problems.add(entry.name() + ": no licence text");
			}
			for (String file : entry.files()) {
				Path path = Path.of(file);
				if (!Files.isRegularFile(path)) {
					problems.add(entry.name() + ": lists " + file + ", which does not exist");
				}
				else if (text(path) != null && !headers.getOrDefault(file, List.of()).contains(entry.name())) {
					problems.add(entry.name() + ": lists " + file + ", whose header does not cite it");
				}
			}
		}
		headers.forEach((file, names) -> {
			for (String name : names) {
				if (notices.entries()
					.stream()
					.noneMatch(entry -> entry.name().equals(name) && entry.files().contains(file))) {
					problems.add(file + " cites \"" + name + "\", which no entry of " + NOTICES + " lists it under");
				}
			}
		});
		assertThat(problems).isEmpty();
	}

	@Test
	void everyVendoredLibraryCarriesItsLicence() throws IOException {
		List<String> missing = new ArrayList<>();
		try (Stream<Path> dirs = Files.list(Path.of("src", "test", "resources"))) {
			for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
				if (OWN_FIXTURES.contains(dir.getFileName().toString()) || !declaresALicence(dir)) {
					continue;
				}
				try (Stream<Path> files = Files.walk(dir)) {
					if (files.filter(Files::isRegularFile).noneMatch(ThirdPartyNoticesTest::holdsALicence)) {
						missing.add(dir.toString());
					}
				}
			}
		}
		assertThat(missing).as("vendored libraries without their licence text").isEmpty();
	}

	@Test
	void noticesPrintWhatAProgramAndTheToolOwe() {
		ByteArrayOutputStream program = new ByteArrayOutputStream();
		assertThat(new NoticesCommand(new PrintStream(program, true, StandardCharsets.UTF_8)).run(new String[0]))
			.isZero();
		String text = program.toString(StandardCharsets.UTF_8);
		assertThat(text).contains("Apache License", "Copyright 2001-2026 The Apache Software Foundation", "\nfdlibm\n",
				"\nring\n", "\nUnicode\n");
		assertThat(text).doesNotContain("\nJLine\n", "\nllama2.c\n");

		ByteArrayOutputStream tool = new ByteArrayOutputStream();
		assertThat(
				new NoticesCommand(new PrintStream(tool, true, StandardCharsets.UTF_8)).run(new String[] { "--tool" }))
			.isZero();
		assertThat(tool.toString(StandardCharsets.UTF_8)).contains("\nJLine\n", "\nfdlibm\n")
			.doesNotContain("\nllama2.c\n");

		ByteArrayOutputStream refused = new ByteArrayOutputStream();
		assertThat(new NoticesCommand(new PrintStream(refused, true, StandardCharsets.UTF_8))
			.run(new String[] { "--native", "--native-target", "plan9-mips" })).isEqualTo(2);
	}

	/** Each scanned file's header citations, by repository path. */
	private static Map<String, List<String>> headers() throws IOException {
		Map<String, List<String>> headers = new TreeMap<>();
		Files.walkFileTree(Path.of("."), new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return NOT_SCANNED.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE
						: FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				String text = attrs.size() < 4_000_000 ? text(file) : null;
				if (text != null && !file.normalize().equals(NOTICES)) {
					Matcher marker = MARKER.matcher(text);
					while (marker.find()) {
						headers
							.computeIfAbsent(Path.of(".").relativize(file).normalize().toString().replace('\\', '/'),
									k -> new ArrayList<>())
							.add(marker.group(1));
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return headers;
	}

	private static boolean declaresALicence(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(file -> file.getFileName().toString().endsWith(".asd")).anyMatch(file -> {
				String text = text(file);
				return text != null && (text.contains(":license") || text.contains(":licence"));
			});
		}
		catch (IOException ex) {
			return false;
		}
	}

	private static boolean holdsALicence(Path file) {
		String name = file.getFileName().toString().toUpperCase(Locale.ROOT);
		if (name.startsWith("LICENSE") || name.startsWith("LICENCE") || name.startsWith("COPYING")
				|| name.startsWith("COPYRIGHT")) {
			return true;
		}
		String text = text(file);
		return text != null && LICENCE_TEXT.matcher(text).find();
	}

	/** A file's text, or {@code null} for one that is not UTF-8 text. */
	private static @Nullable String text(Path file) {
		try {
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
		}
		catch (CharacterCodingException ex) {
			return null;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
