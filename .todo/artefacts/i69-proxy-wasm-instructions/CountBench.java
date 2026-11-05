package am.ik.sluice.client.filter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import am.ik.sluice.client.config.FilterConfig;
import am.ik.sluice.tunnel.TunnelBudget;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;
import run.endive.wasm.types.OpCode;

/** The instructions of one exchange: how many, of what, and where. */
public class CountBench {

	public static void main(String[] args) throws Exception {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.ERROR);
		int size = Integer.parseInt(args[1]);
		Map<Integer, String> names = new HashMap<>();
		if (args.length > 2 && Files.exists(Path.of(args[2]))) {
			for (String line : Files.readAllLines(Path.of(args[2]))) {
				if (line.startsWith("[func-size] ") && !line.startsWith("[func-size] total")) {
					String[] parts = line.substring(12).split("\t");
					names.put(Integer.parseInt(parts[1]), parts[2]);
				}
			}
		}
		int detail = args.length > 3 ? Integer.parseInt(args[3]) : 40;
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		UpstreamFilter filter = Filters.builder()
			.definitions(Map.of("plugin",
					FilterConfig.builder()
						.plugin(args[0])
						.vms(1)
						.maxMemory(DataSize.ofMegabytes(512))
						.callTimeout(Duration.ofSeconds(60))
						.maxBody(DataSize.ofMegabytes(16))
						.maxMeters(16)
						.build()))
			.meters(registry)
			.budget(TunnelBudget.unlimited())
			.build()
			.forRoute("route.test", List.of(FilterConfig.builder().name("plugin").build()));
		String response = "HTTP/1.1 200 OK\r\nX-Name: Value\r\nContent-Length: " + size + "\r\n\r\n" + "x".repeat(size);
		for (int i = 0; i < 3; i++) {
			FilterTests.relayed(filter, "GET", "/", response, 64 * 1024);
		}
		long[] before = OpCounter.COUNTS.clone();
		OpCounter counter = OpCounter.current;
		long[] fnBefore = counter.byFunction.clone();
		long[] entBefore = counter.entries.clone();
		long[] hostBefore = counter.hostCalls.clone();
		Map<String, long[]> exBefore = new TreeMap<>();
		OpCounter.BY_EXPORT.forEach((k, v) -> exBefore.put(k, v.clone()));
		int n = 5;
		OpCounter.ROOTS.clear();
		for (int i = 0; i < n; i++) {
			FilterTests.relayed(filter, "GET", "/", response, 64 * 1024);
		}
		OpCode[] codes = OpCode.values();
		long total = 0;
		long gc = 0;
		long calls = 0;
		long memory = 0;
		List<String> rows = new ArrayList<>();
		for (int i = 0; i < codes.length; i++) {
			long c = (OpCounter.COUNTS[i] - before[i]) / n;
			if (c == 0) {
				continue;
			}
			total += c;
			String name = codes[i].name();
			if (name.startsWith("STRUCT_") || name.startsWith("ARRAY_") || name.startsWith("REF_")
					|| name.startsWith("BR_ON") || name.startsWith("I31") || name.startsWith("CAST_")
					|| name.startsWith("ANY_") || name.startsWith("EXTERN_")) {
				gc += c;
			}
			if (name.contains("CALL") || name.equals("RETURN")) {
				calls += c;
			}
			if (name.contains("LOAD") || name.contains("STORE") || name.startsWith("MEMORY_")) {
				memory += c;
			}
			rows.add(String.format("%012d %s", c, name));
		}
		rows.sort(java.util.Comparator.reverseOrder());
		StringBuilder top = new StringBuilder();
		for (int i = 0; i < Math.min(7, rows.size()); i++) {
			String[] parts = rows.get(i).split(" ");
			top.append(parts[1]).append('=').append(Long.parseLong(parts[0])).append(' ');
		}
		System.out.printf("total=%d gc-ops=%d (%d%%) calls=%d memory=%d | %s failures=%s%n", total, gc,
				total == 0 ? 0 : gc * 100 / total, calls, memory, top, FilterTests.counters(registry));
		System.out.println("-- per export: instructions/exchange, calls/exchange");
		OpCounter.BY_EXPORT.forEach((k, v) -> {
			long[] b = exBefore.getOrDefault(k, new long[2]);
			long d = v[0] - b[0];
			if (d != 0 || v[1] != b[1]) {
				System.out.printf("  %-34s %10.1f %6.1f%n", k, (double) d / n, (double) (v[1] - b[1]) / n);
			}
		});
		System.out.println("-- per function: instructions/exchange, entries/exchange, instr/entry");
		int total2 = counter.byFunction.length;
		List<long[]> fnRows = new ArrayList<>();
		for (int f = 0; f < total2; f++) {
			long d = counter.byFunction[f] - fnBefore[f];
			if (d != 0) {
				fnRows.add(new long[] { d, f, counter.entries[f] - entBefore[f] });
			}
		}
		fnRows.sort((a, b) -> Long.compare(b[0], a[0]));
		for (int i = 0; i < Math.min(detail, fnRows.size()); i++) {
			long[] r = fnRows.get(i);
			double perEntry = r[2] == 0 ? 0 : (double) r[0] / r[2];
			System.out.printf("  %10.1f %5.1f%% %7.1f %8.1f  #%-5d %s%n", (double) r[0] / n, 100.0 * r[0] / n / total,
					(double) r[2] / n, perEntry, r[1], names.getOrDefault((int) r[1], "?"));
		}
		System.out.printf("  (%d functions ran; %d shown)%n", fnRows.size(), Math.min(detail, fnRows.size()));
		System.out.println("-- host calls/exchange");
		for (int i = 0; i < counter.hostCalls.length; i++) {
			long d = counter.hostCalls[i] - hostBefore[i];
			if (d != 0) {
				System.out.printf("  %7.1f #%d %s%n", (double) d / n, i, counter.importName(i));
			}
		}
		if (args.length > 4) {
			long floor = Long.parseLong(args[4]);
			System.out.println("-- call tree: inclusive, self, entries, per exchange");
			OpCounter.ROOTS.forEach((k, v) -> v.print(System.out, 0, n, floor, f -> names.getOrDefault(f, "#" + f)));
		}
		System.exit(0);
	}

}
