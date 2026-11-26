package am.ik.gpu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The one thing the residency cache says out loud: a budget that turned out to be BELOW
 * what the program keeps coming back to ({@link DeviceResidency#pressureReport()}). That
 * run is the one shape in which {@code --gpu} is a LOSS -- an evicted matrix is a first
 * sight again on its next pass, so the loop alternates between the CPU's rate and a cold
 * upload, measured below {@code --simd} alone -- and until this report it looked like an
 * ordinary slow run ({@code .kb/gpu.md}, "A budget below the working set").
 *
 * <p>
 * Nothing here needs a device: the cache is bookkeeping over {@code long} pointers, so
 * every rule that decides WHETHER to report can be pinned on any machine. What a device
 * adds is only the pointers being real. The rules pinned are the three that separate the
 * cliff from ordinary churn -- an eviction nobody comes back to is not pressure, an
 * upload after a WRITE is not pressure, and the report waits for a whole budget's worth
 * of re-uploads -- plus the counts themselves.
 */
class DeviceResidencyPressureTest {

	/** A span the fake buffers stand for; the arrays are keys, never read. */
	private static final long SPAN = 1024;

	private static double[] array() {
		return new double[(int) (SPAN / Double.BYTES)];
	}

	@Test
	void aWorkingSetInsideTheBudgetReportsNothing() {
		DeviceResidency residency = new DeviceResidency();
		residency.setBudget(4 * SPAN);
		double[] a = array(), b = array();
		residency.put(a, 0, SPAN, 0x1000, false);
		residency.put(b, 0, SPAN, 0x2000, false);
		residency.put(a, 0, SPAN, 0x3000, false);
		assertThat(residency.evictions()).isZero();
		assertThat(residency.reuploads()).isZero();
		assertThat(residency.pressureReport()).isNull();
	}

	@Test
	void anEvictionNobodyComesBackForIsNotPressure() {
		// Every result a training step drops is evicted and never asked for again: the
		// mode working as designed, and the reason the eviction COUNT cannot be the
		// signal.
		DeviceResidency residency = new DeviceResidency();
		residency.setBudget(SPAN);
		// Held for the length of the test: the keys are WEAK, and an array the collector
		// takes mid-loop takes its entry with it, which is a different mechanism.
		double[][] dead = new double[16][];
		for (int i = 0; i < dead.length; i++) {
			dead[i] = array();
			residency.put(dead[i], 0, SPAN, 0x1000 + i, false);
		}
		assertThat(residency.evictions()).isEqualTo(15);
		assertThat(residency.evictedBytes()).isEqualTo(15 * SPAN);
		assertThat(residency.reuploads()).isZero();
		assertThat(residency.pressureReport()).isNull();
	}

	@Test
	void anArrayUploadedAgainAfterItsEvictionIsAReupload() {
		DeviceResidency residency = new DeviceResidency();
		residency.setBudget(SPAN);
		double[] a = array(), b = array();
		residency.put(a, 0, SPAN, 0x1000, false);
		residency.put(b, 0, SPAN, 0x2000, false); // evicts a
		assertThat(residency.evictions()).isEqualTo(1);
		residency.put(a, 0, SPAN, 0x3000, false); // and comes back for it
		assertThat(residency.reuploads()).isEqualTo(1);
		assertThat(residency.reuploadedBytes()).isEqualTo(SPAN);
		// One re-upload is one array unlucky at one peak; the report waits for a whole
		// budget's worth, which a run whose working set fits never reaches.
		assertThat(residency.pressureReport()).isNull();
		residency.put(b, 0, SPAN, 0x4000, false);
		assertThat(residency.reuploads()).isEqualTo(2);
		String report = residency.pressureReport();
		assertThat(report).isNotNull();
		assertThat(report).contains("1 KB").contains("2 re-uploads").contains("below this program's working set");
	}

	@Test
	void anArrayWrittenAfterItsEvictionIsNotAReupload() {
		// The KV cache: rewritten between calls, so its next upload carries bytes the
		// device never held. That is a first sight, not the program coming back for what
		// the budget threw away.
		DeviceResidency residency = new DeviceResidency();
		residency.setBudget(SPAN);
		double[] a = array(), b = array();
		residency.put(a, 0, SPAN, 0x1000, false);
		residency.put(b, 0, SPAN, 0x2000, false); // evicts a
		residency.written(a);
		residency.put(a, 0, SPAN, 0x3000, false); // which evicts b in its turn
		assertThat(residency.evictions()).isEqualTo(2);
		assertThat(residency.reuploads()).isZero();
		assertThat(residency.pressureReport()).isNull();
	}

	@Test
	void aCacheWithNoBudgetInForceReportsNothing() {
		// Before the first pre-flight derives one the budget is 0, and everything a put
		// records is evicted at once; that is a cache not yet in service, not a program
		// whose working set is too big.
		DeviceResidency residency = new DeviceResidency();
		double[] a = array(), b = array();
		residency.put(a, 0, SPAN, 0x1000, false);
		residency.put(b, 0, SPAN, 0x2000, false);
		residency.put(a, 0, SPAN, 0x3000, false);
		assertThat(residency.reuploads()).isPositive();
		assertThat(residency.pressureReport()).isNull();
	}

	@Test
	void theReportNamesTheBudgetTheRunWasUnder() {
		// The shape of the measurement this exists for: two matrices, either of which
		// fits alone and neither of which fits beside the other, in a loop.
		DeviceResidency residency = new DeviceResidency();
		residency.setBudget(512L << 20);
		double[] a = array(), b = array();
		for (int i = 0; i < 2; i++) {
			residency.put(a, 0, 512L << 20, 0x1000 + i, false);
			residency.put(b, 0, 512L << 20, 0x2000 + i, false);
		}
		String report = residency.pressureReport();
		assertThat(report).isNotNull();
		assertThat(report).contains("(512 MB)").contains("1.0 GB went up again");
	}

	@Test
	void theLibrarySurfaceAnswersOnAnyMachineAndAsksForItsLineOnce() {
		// Gpu.residencyPressure is what an interceptor's exit line asks, and
		// reportResidencyPressure is the interceptor asking for it -- both on a machine
		// with a device and on one without, where the answer is null and no probe has
		// run. Asked twice because the second ask must not register a second hook.
		assertThatCode(() -> Gpu.reportResidencyPressure("--gpu: ")).doesNotThrowAnyException();
		assertThatCode(() -> Gpu.reportResidencyPressure("--gpu: ")).doesNotThrowAnyException();
		assertThatCode(Gpu::residencyPressure).doesNotThrowAnyException();
	}

}
