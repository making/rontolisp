package am.ik.gpu;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The whole binding to the CUDA driver API: {@code libcuda.so.1} through
 * {@link SymbolLookup#libraryLookup}, and a downcall handle per entry point. No JNI, no
 * bundled shim, no generated code, and -- the point of the exercise -- no CUDA toolkit at
 * run time. {@code libcuda.so.1} ships WITH the NVIDIA driver, so "this machine has a
 * working GPU" is the entire runtime requirement; {@code libnvrtc}, {@code libcudart} and
 * {@code libcublas} all belong to the toolkit and none of them is opened here.
 *
 * <h2>A binding that is absent is not a binding that failed</h2>
 *
 * Constructing one either binds every symbol or throws, and {@link #open()} answers
 * {@code null} only when the LIBRARY is not there. That split is deliberate: on a machine
 * with no NVIDIA driver the library lookup itself fails and there is nothing to report
 * but "no", whereas a library that is present and then fails to bind has a reason worth
 * printing -- a driver too old to export an entry point, or a native-image build missing
 * one of the downcall registrations {@link #signatures()} enumerates -- and the throw
 * carries it. Because the handles are then final and non-null, every call site below is
 * free of null checks -- the availability question is asked exactly once, by {@link Gpu}.
 *
 * <h2>The two copies are critical</h2>
 *
 * {@code cuMemcpyHtoD} and {@code cuMemcpyDtoH} are bound with
 * {@link Linker.Option#critical(boolean) critical(true)}, so they accept a HEAP
 * {@link MemorySegment} and a {@code double[]} goes to the driver with no staging copy at
 * all -- the same finding {@code --blas} built on ({@code .kb/linalg-blas.md}). Its cost
 * is that the thread cannot reach a safepoint while the call runs, which {@code CudaGemm}
 * handles by bounding what one call is given rather than by staging.
 *
 * @see CuResult
 * @see Gpu
 */
final class CudaDriver {

	/** The driver library. Present iff an NVIDIA driver is installed. */
	static final String LIBRARY = "libcuda.so.1";

	/** {@code CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR}. */
	static final int ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR = 75;

	/** {@code CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR}. */
	static final int ATTRIBUTE_COMPUTE_CAPABILITY_MINOR = 76;

	/** {@code CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT}. */
	static final int ATTRIBUTE_MULTIPROCESSOR_COUNT = 16;

	/**
	 * {@code CU_MEMPOOL_ATTR_RELEASE_THRESHOLD}: how many reserved bytes a pool holds on
	 * to before it gives memory back to the OS at the next synchronization point. The
	 * driver's default is 0 -- release everything unused at EVERY sync -- which is fine
	 * for a pool that holds three buffers between two calls and ruinous for one that
	 * holds a training step's resident copies (see {@code CudaGemm.probe}).
	 */
	static final int MEMPOOL_ATTR_RELEASE_THRESHOLD = 4;

	/**
	 * {@code CU_MEMPOOL_ATTR_USED_MEM_CURRENT}: bytes currently allocated out of a pool,
	 * as a {@code cuuint64_t}. Unlike {@code cuMemGetInfo}, which answers for the whole
	 * DEVICE, this is scoped to the pool HANDLE it is asked of -- a process-local count
	 * that another process's allocations, sharing the same physical memory, do not move
	 * (see {@code CudaGemm.poolBytesInUse}).
	 */
	static final int MEMPOOL_ATTR_USED_MEM_CURRENT = 7;

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;

	static final AddressLayout P = ValueLayout.ADDRESS;

	private static final Linker LINKER = Linker.nativeLinker();

	private final MethodHandle cuInit;

	private final MethodHandle cuDriverGetVersion;

	private final MethodHandle cuDeviceGetCount;

	private final MethodHandle cuDeviceGet;

	private final MethodHandle cuDeviceGetName;

	private final MethodHandle cuDeviceGetAttribute;

	private final MethodHandle cuDevicePrimaryCtxRetain;

	private final MethodHandle cuDevicePrimaryCtxRelease;

	private final MethodHandle cuCtxSetCurrent;

	private final MethodHandle cuCtxSynchronize;

	private final MethodHandle cuMemAlloc;

	private final MethodHandle cuMemFree;

	private final @Nullable MethodHandle cuMemAllocAsync;

	private final @Nullable MethodHandle cuMemFreeAsync;

	private final @Nullable MethodHandle cuDeviceGetDefaultMemPool;

	private final @Nullable MethodHandle cuMemPoolTrimTo;

	private final @Nullable MethodHandle cuMemPoolSetAttribute;

	private final @Nullable MethodHandle cuMemPoolGetAttribute;

	private final MethodHandle cuMemcpyHtoD;

	private final MethodHandle cuMemcpyDtoH;

	private final MethodHandle cuMemcpyDtoHPinned;

	private final @Nullable MethodHandle cuMemHostAlloc;

	private final @Nullable MethodHandle cuMemFreeHost;

	private final MethodHandle cuModuleLoadData;

	private final MethodHandle cuModuleUnload;

	private final MethodHandle cuModuleGetFunction;

	private final MethodHandle cuLaunchKernel;

	private final MethodHandle cuMemGetInfo;

	private final MethodHandle cuGetErrorString;

	/**
	 * Every downcall SHAPE this binding asks the linker for, and the same for the
	 * {@linkplain Linker.Option#critical(boolean) critical} ones -- the download is bound
	 * both ways, so one shape can be in both sets. Native Image builds a downcall stub
	 * only for a signature registered at BUILD time and refuses to make a handle for any
	 * other, and refusing ONE fails this whole constructor: a shape missing from
	 * {@code reachability-metadata.json} makes the binary decline as though the machine
	 * had no driver at all. So the shapes are recorded as they are bound, and the test
	 * pins the checked-in metadata against what is actually asked for rather than against
	 * a list someone remembered to update.
	 */
	private final Set<FunctionDescriptor> signatures = new LinkedHashSet<>();

	private final Set<FunctionDescriptor> criticalSignatures = new LinkedHashSet<>();

	CudaDriver(SymbolLookup lookup) {
		Linker.Option critical = Linker.Option.critical(true);
		FunctionDescriptor htod = FunctionDescriptor.of(I, L, P, L);
		FunctionDescriptor dtoh = FunctionDescriptor.of(I, P, L, L);
		this.cuInit = handle(lookup, "cuInit", FunctionDescriptor.of(I, I));
		this.cuDriverGetVersion = handle(lookup, "cuDriverGetVersion", FunctionDescriptor.of(I, P));
		this.cuDeviceGetCount = handle(lookup, "cuDeviceGetCount", FunctionDescriptor.of(I, P));
		this.cuDeviceGet = handle(lookup, "cuDeviceGet", FunctionDescriptor.of(I, P, I));
		this.cuDeviceGetName = handle(lookup, "cuDeviceGetName", FunctionDescriptor.of(I, P, I, I));
		this.cuDeviceGetAttribute = handle(lookup, "cuDeviceGetAttribute", FunctionDescriptor.of(I, P, I, I));
		this.cuDevicePrimaryCtxRetain = handle(lookup, "cuDevicePrimaryCtxRetain", FunctionDescriptor.of(I, P, I));
		this.cuDevicePrimaryCtxRelease = handle(lookup, "cuDevicePrimaryCtxRelease_v2", FunctionDescriptor.of(I, I));
		this.cuCtxSetCurrent = handle(lookup, "cuCtxSetCurrent", FunctionDescriptor.of(I, P));
		this.cuCtxSynchronize = handle(lookup, "cuCtxSynchronize", FunctionDescriptor.of(I));
		this.cuMemAlloc = handle(lookup, "cuMemAlloc_v2", FunctionDescriptor.of(I, P, L));
		this.cuMemFree = handle(lookup, "cuMemFree_v2", FunctionDescriptor.of(I, L));
		this.cuMemAllocAsync = optional(lookup, "cuMemAllocAsync", FunctionDescriptor.of(I, P, L, P));
		this.cuMemFreeAsync = optional(lookup, "cuMemFreeAsync", FunctionDescriptor.of(I, L, P));
		this.cuDeviceGetDefaultMemPool = optional(lookup, "cuDeviceGetDefaultMemPool", FunctionDescriptor.of(I, P, I));
		this.cuMemPoolTrimTo = optional(lookup, "cuMemPoolTrimTo", FunctionDescriptor.of(I, P, L));
		this.cuMemPoolSetAttribute = optional(lookup, "cuMemPoolSetAttribute", FunctionDescriptor.of(I, P, I, P));
		this.cuMemPoolGetAttribute = optional(lookup, "cuMemPoolGetAttribute", FunctionDescriptor.of(I, P, I, P));
		this.cuMemcpyHtoD = handle(lookup, "cuMemcpyHtoD_v2", htod, critical);
		this.cuMemcpyDtoH = handle(lookup, "cuMemcpyDtoH_v2", dtoh, critical);
		this.cuMemcpyDtoHPinned = handle(lookup, "cuMemcpyDtoH_v2", dtoh);
		this.cuMemHostAlloc = optional(lookup, "cuMemHostAlloc", FunctionDescriptor.of(I, P, L, I));
		this.cuMemFreeHost = optional(lookup, "cuMemFreeHost", FunctionDescriptor.of(I, P));
		this.cuModuleLoadData = handle(lookup, "cuModuleLoadData", FunctionDescriptor.of(I, P, P));
		this.cuModuleUnload = handle(lookup, "cuModuleUnload", FunctionDescriptor.of(I, P));
		this.cuModuleGetFunction = handle(lookup, "cuModuleGetFunction", FunctionDescriptor.of(I, P, P, P));
		this.cuLaunchKernel = handle(lookup, "cuLaunchKernel",
				FunctionDescriptor.of(I, P, I, I, I, I, I, I, I, P, P, P));
		this.cuMemGetInfo = handle(lookup, "cuMemGetInfo_v2", FunctionDescriptor.of(I, P, P));
		this.cuGetErrorString = handle(lookup, "cuGetErrorString", FunctionDescriptor.of(I, I, P));
	}

	private MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor,
			Linker.Option... options) {
		MemorySegment symbol = lookup.find(name).orElseThrow(() -> new IllegalStateException(name + " is missing"));
		record(descriptor, options);
		return LINKER.downcallHandle(symbol, descriptor, options);
	}

	/**
	 * The same, for an entry point a driver older than this library may not export. Only
	 * the stream-ordered allocator is optional: everything else has been in the driver
	 * API since CUDA 4, so a driver missing one of those is not a driver.
	 */
	private @Nullable MethodHandle optional(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		return lookup.find(name).map(symbol -> {
			record(descriptor);
			return LINKER.downcallHandle(symbol, descriptor);
		}).orElse(null);
	}

	/**
	 * Remembers one bound shape. {@code critical(true)} is the only option this binding
	 * ever passes, and it makes a DIFFERENT registration than the same shape without it,
	 * which is why the two are kept apart.
	 * @param descriptor the shape just bound
	 * @param options the options it was bound with
	 */
	private void record(FunctionDescriptor descriptor, Linker.Option... options) {
		(options.length == 0 ? this.signatures : this.criticalSignatures).add(descriptor);
	}

	/**
	 * The shapes bound WITHOUT {@code critical}, for the native-image registration test.
	 * @return the plain downcall shapes this binding asked the linker for
	 */
	Set<FunctionDescriptor> signatures() {
		return Set.copyOf(this.signatures);
	}

	/**
	 * The shapes bound WITH {@code critical(true)}.
	 * @return the critical downcall shapes this binding asked the linker for
	 */
	Set<FunctionDescriptor> criticalSignatures() {
		return Set.copyOf(this.criticalSignatures);
	}

	/**
	 * Binds {@code libcuda.so.1}, or answers {@code null} when there is nothing to bind:
	 * no NVIDIA driver, a platform that has none, or a JVM that forbids native access. A
	 * library that IS present and then fails to bind -- a driver too old to export one of
	 * the entry points above, a downcall shape a native image was not built for --
	 * THROWS, because "absent" and "unusable" are different answers and only the caller
	 * printing them can tell the user which one this machine gave.
	 * @return the binding, or {@code null} when this machine has no CUDA driver
	 */
	static @Nullable CudaDriver open() {
		SymbolLookup lookup;
		try {
			lookup = SymbolLookup.libraryLookup(LIBRARY, Arena.global());
		}
		catch (Throwable ex) {
			return null;
		}
		return new CudaDriver(lookup);
	}

	// --- the entry points -------------------------------------------------------------
	// Each returns a CUresult; none throws on a device error. The Throwable on the
	// signature is MethodHandle.invokeExact's, and reaching it means a defect in the
	// descriptors above rather than anything the device did.

	int init() throws Throwable {
		return (int) this.cuInit.invokeExact(0);
	}

	int driverGetVersion(MemorySegment out) throws Throwable {
		return (int) this.cuDriverGetVersion.invokeExact(out);
	}

	int deviceGetCount(MemorySegment out) throws Throwable {
		return (int) this.cuDeviceGetCount.invokeExact(out);
	}

	int deviceGet(MemorySegment out, int ordinal) throws Throwable {
		return (int) this.cuDeviceGet.invokeExact(out, ordinal);
	}

	int deviceGetName(MemorySegment out, int length, int device) throws Throwable {
		return (int) this.cuDeviceGetName.invokeExact(out, length, device);
	}

	int deviceGetAttribute(MemorySegment out, int attribute, int device) throws Throwable {
		return (int) this.cuDeviceGetAttribute.invokeExact(out, attribute, device);
	}

	int devicePrimaryCtxRetain(MemorySegment out, int device) throws Throwable {
		return (int) this.cuDevicePrimaryCtxRetain.invokeExact(out, device);
	}

	int devicePrimaryCtxRelease(int device) throws Throwable {
		return (int) this.cuDevicePrimaryCtxRelease.invokeExact(device);
	}

	int ctxSetCurrent(MemorySegment context) throws Throwable {
		return (int) this.cuCtxSetCurrent.invokeExact(context);
	}

	int ctxSynchronize() throws Throwable {
		return (int) this.cuCtxSynchronize.invokeExact();
	}

	int memAlloc(MemorySegment out, long bytes) throws Throwable {
		return (int) this.cuMemAlloc.invokeExact(out, bytes);
	}

	int memFree(long devicePointer) throws Throwable {
		return (int) this.cuMemFree.invokeExact(devicePointer);
	}

	/**
	 * The stream-ordered allocator, on the null stream. It draws from a pool the DRIVER
	 * owns, which is the difference between 0.7 us and 71 us per allocation -- the single
	 * biggest term in the cost of a per-call intercept. Present since CUDA 11.2 and not
	 * supported by every device, so {@code CudaGemm} tries it once and keeps
	 * {@link #memAlloc} as the fallback.
	 */
	int memAllocAsync(MemorySegment out, long bytes) throws Throwable {
		MethodHandle handle = this.cuMemAllocAsync;
		if (handle == null) {
			return CuResult.CUDA_ERROR_NOT_SUPPORTED.code();
		}
		return (int) handle.invokeExact(out, bytes, MemorySegment.NULL);
	}

	/** Returns a {@link #memAllocAsync} allocation to the driver's pool. */
	int memFreeAsync(long devicePointer) throws Throwable {
		MethodHandle handle = this.cuMemFreeAsync;
		if (handle == null) {
			return CuResult.CUDA_ERROR_NOT_SUPPORTED.code();
		}
		return (int) handle.invokeExact(devicePointer, MemorySegment.NULL);
	}

	/**
	 * Free and total device memory, in bytes. Only a diagnostic: the products decline on
	 * a failed allocation rather than by asking this first, because the answer would be
	 * stale by the time it was used.
	 */
	int memGetInfo(MemorySegment free, MemorySegment total) throws Throwable {
		return (int) this.cuMemGetInfo.invokeExact(free, total);
	}

	/**
	 * The device's default memory pool -- the one {@link #memAllocAsync} draws from, and
	 * the only handle by which its high-water mark can be given back.
	 */
	int deviceGetDefaultMemPool(MemorySegment out, int device) throws Throwable {
		MethodHandle handle = this.cuDeviceGetDefaultMemPool;
		if (handle == null) {
			return CuResult.CUDA_ERROR_NOT_SUPPORTED.code();
		}
		return (int) handle.invokeExact(out, device);
	}

	/**
	 * Releases everything the pool is holding above {@code keepBytes} back to the device.
	 * A pooled allocation that FAILS still grows the pool as far as it can on the way to
	 * failing, and hands back no pointer to free, so this is the only way an
	 * out-of-memory decline can give the memory back -- to this process and to every
	 * other one on the card.
	 */
	int memPoolTrimTo(MemorySegment pool, long keepBytes) throws Throwable {
		MethodHandle handle = this.cuMemPoolTrimTo;
		if (handle == null) {
			return CuResult.CUDA_ERROR_NOT_SUPPORTED.code();
		}
		return (int) handle.invokeExact(pool, keepBytes);
	}

	/**
	 * Sets one attribute of a memory pool; {@code value} points at the attribute's own
	 * type ({@code cuuint64_t} for {@link #MEMPOOL_ATTR_RELEASE_THRESHOLD}). Optional in
	 * the same sense as the pool calls: a driver without it simply keeps the default.
	 */
	int memPoolSetAttribute(MemorySegment pool, int attribute, MemorySegment value) throws Throwable {
		MethodHandle handle = this.cuMemPoolSetAttribute;
		if (handle == null) {
			return CuResult.CUDA_ERROR_NOT_SUPPORTED.code();
		}
		return (int) handle.invokeExact(pool, attribute, value);
	}

	/**
	 * Reads one attribute of a memory pool into {@code value} ({@code cuuint64_t} for
	 * {@link #MEMPOOL_ATTR_USED_MEM_CURRENT}). Optional in the same sense as the other
	 * pool calls.
	 */
	int memPoolGetAttribute(MemorySegment pool, int attribute, MemorySegment value) throws Throwable {
		MethodHandle handle = this.cuMemPoolGetAttribute;
		if (handle == null) {
			return CuResult.CUDA_ERROR_NOT_SUPPORTED.code();
		}
		return (int) handle.invokeExact(pool, attribute, value);
	}

	/**
	 * Whether this driver exports the stream-ordered allocator AND the two calls that
	 * make its failure mode survivable. All four go together: a pool that cannot be
	 * trimmed is worse than no pool at all.
	 */
	boolean hasPooledAllocation() {
		return this.cuMemAllocAsync != null && this.cuMemFreeAsync != null && this.cuDeviceGetDefaultMemPool != null
				&& this.cuMemPoolTrimTo != null;
	}

	/**
	 * Host to device, critical, so {@code source} may be a HEAP segment and the operand
	 * needs no staging copy. The thread cannot reach a safepoint for the duration, which
	 * is why {@code CudaGemm} splits a big operand into chunks rather than handing the
	 * whole of it over at once.
	 */
	int memcpyHtoD(long destination, MemorySegment source, long bytes) throws Throwable {
		return (int) this.cuMemcpyHtoD.invokeExact(destination, source, bytes);
	}

	/**
	 * Device to host, critical. On the null stream this call also WAITS for everything
	 * already queued, so for a product whose kernel is long it must be issued after an
	 * explicit {@link #ctxSynchronize()} -- otherwise the kernel's own runtime lands
	 * inside the critical window, where the bytes it moves are no longer the bound.
	 */
	int memcpyDtoH(MemorySegment destination, long source, long bytes) throws Throwable {
		return (int) this.cuMemcpyDtoH.invokeExact(destination, source, bytes);
	}

	/**
	 * Device to a PINNED host buffer, NOT critical: the destination is native memory the
	 * driver allocated ({@link #memHostAlloc}), so no heap segment is involved, the
	 * thread transitions to native as for any ordinary downcall, and the wait for a
	 * queued kernel that this call still performs is safepoint-friendly. The staged
	 * download ({@code CudaGemm.download}) uses this and then copies the bounce buffer
	 * into the Java array itself.
	 */
	int memcpyDtoHPinned(MemorySegment destination, long source, long bytes) throws Throwable {
		return (int) this.cuMemcpyDtoHPinned.invokeExact(destination, source, bytes);
	}

	/**
	 * Page-locked host memory the device can DMA into at full speed, for the download
	 * bounce buffer. Optional like the pool calls; a driver without it downloads straight
	 * into the heap as before.
	 */
	int memHostAlloc(MemorySegment out, long bytes, int flags) throws Throwable {
		MethodHandle handle = this.cuMemHostAlloc;
		if (handle == null) {
			return CuResult.CUDA_ERROR_NOT_SUPPORTED.code();
		}
		return (int) handle.invokeExact(out, bytes, flags);
	}

	/** Frees what {@link #memHostAlloc} allocated. */
	int memFreeHost(MemorySegment pointer) throws Throwable {
		MethodHandle handle = this.cuMemFreeHost;
		if (handle == null) {
			return CuResult.CUDA_ERROR_NOT_SUPPORTED.code();
		}
		return (int) handle.invokeExact(pointer);
	}

	int moduleLoadData(MemorySegment out, MemorySegment image) throws Throwable {
		return (int) this.cuModuleLoadData.invokeExact(out, image);
	}

	int moduleUnload(MemorySegment module) throws Throwable {
		return (int) this.cuModuleUnload.invokeExact(module);
	}

	int moduleGetFunction(MemorySegment out, MemorySegment module, MemorySegment name) throws Throwable {
		return (int) this.cuModuleGetFunction.invokeExact(out, module, name);
	}

	int launchKernel(MemorySegment function, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ,
			int sharedBytes, MemorySegment stream, MemorySegment parameters, MemorySegment extra) throws Throwable {
		return (int) this.cuLaunchKernel.invokeExact(function, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes,
				stream, parameters, extra);
	}

	/**
	 * The driver's own sentence for a status code, appended to {@link CuResult}'s name
	 * for it. Falls back to the name alone when the driver declines to explain itself, so
	 * the result is always usable and this never throws.
	 * @param code a value returned by one of the entry points above
	 * @return a one-line description of the status
	 */
	String errorString(int code) {
		String described = CuResult.describe(code);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(P);
			if ((int) this.cuGetErrorString.invokeExact(code, out) != CuResult.SUCCESS) {
				return described;
			}
			MemorySegment text = out.get(P, 0);
			if (text.equals(MemorySegment.NULL)) {
				return described;
			}
			return described + ": " + text.reinterpret(Long.MAX_VALUE).getString(0);
		}
		catch (Throwable ex) {
			return described;
		}
	}

}
