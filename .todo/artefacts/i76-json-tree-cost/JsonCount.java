package am.ik.sluice.client.filter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.sluice.client.config.FilterConfig;
import am.ik.sluice.tunnel.TunnelBudget;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

/**
 * A measurement only: instructions and charged bytes of one JSON exchange through a
 * plugin, limits lifted. Args: module, KiB, names file, functions listed, tree floor.
 */
@SuppressWarnings("NullAway")
public class JsonCount {

	public static void main(String[] args) throws Exception {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.ERROR);
		int size = Integer.parseInt(args[1]) * 1024;
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
						.maxMemory(DataSize.ofMegabytes(Long.getLong("mem", 1024)))
						.callTimeout(Duration.ofSeconds(Long.getLong("timeout", 600)))
						.maxBody(DataSize.ofMegabytes(16))
						.maxMeters(16)
						.build()))
			.meters(registry)
			.budget(TunnelBudget.unlimited())
			.build()
			.forRoute("route.test", List.of(FilterConfig.builder().name("plugin").build()));
		StringBuilder json = new StringBuilder("{\"items\":[");
		int items = 0;
		while (json.length() < size - 64) {
			if (items > 0) {
				json.append(',');
			}
			json.append("{\"id\":").append(items).append(",\"name\":\"item-").append(items).append("\"}");
			items++;
		}
		json.append("]}");
		String body = json.toString();
		String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
				+ "\r\n\r\n" + body;
		int warm = Integer.getInteger("warm", 1);
		for (int i = 0; i < warm; i++) {
			check(FilterTests.relayed(filter, "GET", "/", response, 64 * 1024), items);
		}
		OpCounter counter = OpCounter.current;
		long totalBefore = OpCounter.total;
		long chargedBefore = PluginHeap.CHARGED;
		long[] typeBefore = PluginHeap.BY_TYPE.clone();
		long[] typeCountBefore = PluginHeap.COUNT_BY_TYPE.clone();
		long[] fnBefore = counter == null ? new long[0] : counter.byFunction.clone();
		long[][] opsBefore = new long[counter == null ? 0 : counter.fnOps.length][];
		for (int f = 0; f < opsBefore.length; f++) {
			opsBefore[f] = counter.fnOps[f] == null ? null : counter.fnOps[f].clone();
		}
		long[] entBefore = counter == null ? new long[0] : counter.entries.clone();
		OpCounter.ROOTS.clear();
		PluginHeap.PEAK = 0;
		int n = Integer.getInteger("runs", 1);
		long best = Long.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			long t = System.nanoTime();
			String relayed = FilterTests.relayed(filter, "GET", "/", response, 64 * 1024);
			best = Math.min(best, System.nanoTime() - t);
			check(relayed, items);
		}
		long total = (OpCounter.total - totalBefore) / n;
		long charged = (PluginHeap.CHARGED - chargedBefore) / n;
		System.out.printf("%dKiB (%d bytes, %d items): instructions=%d (%.1f/byte) charged=%d (%.1f/byte) peak=%d best=%dms failures=%s%n",
				size / 1024, body.length(), items, total, (double) total / body.length(), charged,
				(double) charged / body.length(), PluginHeap.PEAK, best / 1_000_000, FilterTests.counters(registry));
		StringBuilder types = new StringBuilder("  charged by type:");
		for (int t = 0; t < PluginHeap.BY_TYPE.length; t++) {
			long d = PluginHeap.BY_TYPE[t] - typeBefore[t];
			if (d > 0) {
				types.append(" #").append(t).append('=').append(d / n).append('/').append((PluginHeap.COUNT_BY_TYPE[t] - typeCountBefore[t]) / n);
			}
		}
		System.out.println(types);
		if (counter != null && detail > 0) {
			List<long[]> rows = new ArrayList<>();
			for (int f = 0; f < counter.byFunction.length; f++) {
				long d = counter.byFunction[f] - fnBefore[f];
				if (d != 0) {
					rows.add(new long[] { d, f, counter.entries[f] - entBefore[f] });
				}
			}
			rows.sort((a, b) -> Long.compare(b[0], a[0]));
			for (int i = 0; i < Math.min(detail, rows.size()); i++) {
				long[] r = rows.get(i);
				System.out.printf("  %12.0f %5.1f%% %10.0f %8.1f  #%-5d %s%n", (double) r[0] / n,
						100.0 * r[0] / n / Math.max(1, total), (double) r[2] / n, r[2] == 0 ? 0 : (double) r[0] / r[2],
						r[1], names.getOrDefault((int) r[1], "?"));
			}
			String opsOf = System.getProperty("ops");
			if (opsOf != null) {
				for (String fs : opsOf.split(",")) {
					int f = Integer.parseInt(fs);
					long[] ops = counter.fnOps[f];
					if (ops == null) {
						continue;
					}
					List<long[]> orows = new ArrayList<>();
					for (int o = 0; o < ops.length; o++) {
						long d = ops[o] - (opsBefore[f] == null ? 0 : opsBefore[f][o]);
						if (d != 0) {
							orows.add(new long[] { d, o });
						}
					}
					orows.sort((p1, p2) -> Long.compare(p2[0], p1[0]));
					StringBuilder line = new StringBuilder("  ops #" + f + ":");
					for (int r = 0; r < Math.min(30, orows.size()); r++) {
						line.append(' ')
							.append(run.endive.wasm.types.OpCode.values()[(int) orows.get(r)[1]])
							.append('=')
							.append(orows.get(r)[0] / n);
					}
					System.out.println(line);
				}
			}
			String addrdump = System.getProperty("addrdump");
			if (addrdump != null) {
				try (java.io.PrintWriter pw = new java.io.PrintWriter(addrdump)) {
					counter.byAddress.forEach((addr, c) -> pw.println(addr + "\t" + c[0] + "\t" + counter.opAt.get(addr)));
				}
				for (int f = 0; f < counter.byFunction.length; f++) {
					if (counter.fnOps[f] != null) {
						System.out.println("  first address #" + f + " = " + firstAddress(f));
					}
				}
			}
			if (args.length > 4) {
				long floor = Long.parseLong(args[4]);
				OpCounter.ROOTS.forEach((k, v) -> v.print(System.out, 0, n, floor, f -> names.getOrDefault(f, "#" + f)));
			}
		}
		System.exit(0);
	}

	private static int firstAddress(int f) {
		return -1;
	}

	private static void check(String relayed, int items) {
		if (!relayed.contains("\"sluice\":{")) {
			throw new IllegalStateException("no sluice field: " + relayed.substring(0, Math.min(300, relayed.length())));
		}
		String last = "\"name\":\"item-" + (items - 1) + "\"";
		if (!relayed.replaceAll("\r\n[0-9a-f]+\r\n", "").contains(last)) {
			throw new IllegalStateException("items lost: " + last);
		}
	}

}
