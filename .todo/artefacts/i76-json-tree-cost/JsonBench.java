package am.ik.sluice.client.filter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import am.ik.sluice.client.config.FilterConfig;
import am.ik.sluice.tunnel.TunnelBudget;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

/** JSON bodies of each size through json_plugin, at the default limits. */
public class JsonBench {

	public static void main(String[] args) throws Exception {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
		for (int i = 1; i < args.length; i++) {
			int size = Integer.parseInt(args[i]) * 1024;
			SimpleMeterRegistry registry = new SimpleMeterRegistry();
			UpstreamFilter filter = Filters.builder()
				.definitions(Map.of("plugin",
						FilterConfig.builder()
							.plugin(args[0])
							.vms(1)
							.maxMemory(DataSize.ofMegabytes(Long.getLong("mem", 16)))
							.callTimeout(Duration.ofSeconds(Long.getLong("timeout", 1)))
							.maxBody(DataSize.ofMegabytes(16))
							.maxMeters(16)
							.build()))
				.meters(registry)
				.budget(TunnelBudget.unlimited())
				.build()
				.forRoute("route.test", List.of(FilterConfig.builder().name("plugin").build()));
			// an object of an array of small objects, about 40 bytes each
			StringBuilder json = new StringBuilder("{\"items\":[");
			int n = 0;
			while (json.length() < size - 64) {
				if (n > 0) {
					json.append(',');
				}
				json.append("{\"id\":").append(n).append(",\"name\":\"item-").append(n).append("\"}");
				n++;
			}
			json.append("]}");
			String body = json.toString();
			String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
					+ "\r\n\r\n" + body;
			long best = Long.MAX_VALUE;
			long first = 0;
			int added = 0;
			int unchanged = 0;
			for (int run = 0; run < 4; run++) {
				long t = System.nanoTime();
				String relayed = FilterTests.relayed(filter, "GET", "/", response, 64 * 1024);
				long took = System.nanoTime() - t;
				if (run == 0) {
					first = took;
				}
				else {
					best = Math.min(best, took);
				}
				if (relayed.contains("\"sluice\":{")) {
					added++;
				}
				else if (relayed.replaceAll("\r\n[0-9a-f]+\r\n", "").contains(body)) {
					unchanged++;
				}
			}
			System.out.printf("%6dKiB items=%d first=%dms best=%dms added=%d/4 unchanged=%d/4 failures=%s%n",
					size / 1024, n, first / 1_000_000, best / 1_000_000, added, unchanged,
					FilterTests.counters(registry));
		}
		System.exit(0);
	}

}
