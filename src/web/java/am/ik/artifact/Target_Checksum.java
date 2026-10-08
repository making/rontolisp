package am.ik.artifact;

import java.io.IOException;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link Checksum}: verification needs a JCA
 * {@code MessageDigest} provider, which the browser image does not carry (the reason
 * {@code eval/Sha2Kernels} is hand-written). Nothing is ever downloaded there
 * ({@code Target_HttpDownloader}), so verification is unreachable in practice; the
 * substitution keeps the provider lookup out of the image.
 */
@TargetClass(Checksum.class)
final class Target_Checksum {

	@Substitute
	public void verify(byte[] bytes, String source) throws IOException {
		throw new IOException("verifying " + source + " is not available in the browser playground");
	}

}
