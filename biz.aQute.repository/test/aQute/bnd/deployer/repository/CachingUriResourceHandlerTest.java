package aQute.bnd.deployer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import aQute.bnd.http.HttpClient;
import aQute.lib.io.IO;
import test.lib.NanoHTTPD;

public class CachingUriResourceHandlerTest {

	private static final String	EXPECTED_SHA	= "d0002141a722ef03ecd8fd2e0d3e4d3bc680ba91483cb4962f68a41a12dd01ab"
		.toUpperCase();

	static File					currentDir		= new File(System.getProperty("user.dir"));

	@Test
	public void testLoadFromCache() throws Exception {
		CachingUriResourceHandle handle = new CachingUriResourceHandle(
			new URI("http://localhost:18083/bundles/dummybundle.jar"), IO.getFile("testdata/httpcache/1"),
			new HttpClient(), null);
		File result = handle.request();

		assertEquals(IO.getFile("testdata/httpcache/1/http%3A%2F%2Flocalhost%3A18083%2Fbundles/dummybundle.jar")
			.getAbsolutePath(), result.getAbsolutePath());
	}

	@Test
	public void testFailedLoadFromRemote() throws Exception {
		String testDirName = "testdata/httpcache/2";
		File cacheDir = new File(testDirName);
		URI baseUri = new URI("http://localhost:18083/bundles");
		URI uri = new URI(baseUri + "/dummybundle.jar");
		CachingUriResourceHandle handle = new CachingUriResourceHandle(uri, cacheDir, new HttpClient(), null);

		assertThatIOException().isThrownBy(() -> handle.request());

		/* cleanup */
		List<String> cacheFiles = Arrays.asList(cacheDir.list());
		String uriCacheDir = URLEncoder.encode(baseUri.toURL()
			.toExternalForm(), "UTF-8");
		assertThat(cacheFiles).hasSizeBetween(1, 2)
			.contains(uriCacheDir);
		if (cacheFiles.size() == 2) {
			assertThat(cacheFiles).contains(".gitignore");
		}
		IO.getFile(testDirName + "/" + uriCacheDir)
			.delete();
	}

	@Test
	public void testLoadFromRemote() throws Exception {
		String testDirName = "testdata/httpcache/3";
		File cacheDir = new File(testDirName);
		URI baseUri = new URI("http://localhost:18083/bundles");
		URI uri = new URI(baseUri + "/dummybundle.jar");
		CachingUriResourceHandle handle = new CachingUriResourceHandle(uri, cacheDir, new HttpClient(), null);

		NanoHTTPD httpd = new NanoHTTPD(18083, IO.getFile("testdata/http"));
		try {
			File result = handle.request();
			assertEquals(IO.getFile("testdata/httpcache/3/http%3A%2F%2Flocalhost%3A18083%2Fbundles/dummybundle.jar")
				.getAbsolutePath(), result.getAbsolutePath());

			File shaFile = new File(result.getAbsolutePath() + AbstractIndexedRepo.REPO_INDEX_SHA_EXTENSION);
			assertEquals(EXPECTED_SHA, IO.collect(shaFile));

			result.delete();
			shaFile.delete();

			/* cleanup */
			List<String> cacheFiles = Arrays.asList(cacheDir.list());
			String uriCacheDir = URLEncoder.encode(baseUri.toURL()
				.toExternalForm(), "UTF-8");
			assertThat(cacheFiles).hasSizeBetween(1, 2)
				.contains(uriCacheDir);
			if (cacheFiles.size() == 2) {
				assertThat(cacheFiles).contains(".gitignore");
			}
			IO.getFile(testDirName + "/" + uriCacheDir)
				.delete();
		} finally {
			httpd.stop();
		}
	}

	@Test
	public void testUseCached() throws Exception {
		File cached = IO.getFile("testdata/httpcache/4/http%3A%2F%2Flocalhost%3A18083%2Fbundles/dummybundle.jar");
		long cacheTimestamp = cached.lastModified();

		CachingUriResourceHandle handle = new CachingUriResourceHandle(
			new URI("http://localhost:18083/bundles/dummybundle.jar"), IO.getFile("testdata/httpcache/4"),
			new HttpClient(), EXPECTED_SHA);

		NanoHTTPD httpd = new NanoHTTPD(18083, IO.getFile("testdata/http"));
		try {
			File result = handle.request();
			assertEquals(cached, result);
			assertEquals(cacheTimestamp, result.lastModified(), "File timestamp should NOT change");
		} finally {
			httpd.stop();
		}
	}

	@Test
	public void testReplaceCache() throws Exception {
		File cached = IO.getFile("testdata/httpcache/5/http%3A%2F%2Flocalhost%3A18083%2Fbundles/dummybundle.jar");
		long cacheTimestamp = cached.lastModified();

		// Clear the SHA so the file appears modified
		File shaFile = new File(cached.getAbsolutePath() + AbstractIndexedRepo.REPO_INDEX_SHA_EXTENSION);
		IO.store("00000000", shaFile);

		CachingUriResourceHandle handle = new CachingUriResourceHandle(
			new URI("http://localhost:18083/bundles/dummybundle.jar"), IO.getFile("testdata/httpcache/5"),
			new HttpClient(), EXPECTED_SHA);

		NanoHTTPD httpd = new NanoHTTPD(18083, IO.getFile("testdata/http"));
		try {
			File result = handle.request();
			assertEquals(cached, result);
			assertNotSame(cacheTimestamp, result.lastModified(), "File timestamp SHOULD change");

			assertEquals(EXPECTED_SHA, IO.collect(shaFile));
		} finally {
			httpd.stop();
		}
	}

	@Test
	public void testEmptyCache() throws Exception {
		String testDirName = "testdata/httpcache/6";
		File cacheDir = new File(testDirName);
		URI baseUri = new URI("http://localhost:18083/bundles");
		String jarName = "dummybundle.jar";
		URI uri = new URI(baseUri + "/" + jarName);
		String uriCacheDir = URLEncoder.encode(baseUri.toURL()
			.toExternalForm(), "UTF-8");

		File cached = IO.getFile(testDirName + "/" + uriCacheDir + "/" + jarName);
		cached.delete();

		File shaFile = new File(cached.getAbsolutePath() + AbstractIndexedRepo.REPO_INDEX_SHA_EXTENSION);
		shaFile.delete();

		CachingUriResourceHandle handle = new CachingUriResourceHandle(uri, cacheDir, new HttpClient(), EXPECTED_SHA);
		NanoHTTPD httpd = new NanoHTTPD(18083, IO.getFile("testdata/http"));
		try {
			File result = handle.request();
			assertEquals(cached, result.getAbsoluteFile());
			assertEquals(EXPECTED_SHA, IO.collect(shaFile));

			/* cleanup */
			List<String> cacheFiles = Arrays.asList(cacheDir.list());
			assertThat(cacheFiles).hasSizeBetween(1, 2)
				.contains(uriCacheDir);
			if (cacheFiles.size() == 2) {
				assertThat(cacheFiles).contains(".gitignore");
			}
			IO.delete(IO.getFile(testDirName + "/" + uriCacheDir));
		} finally {
			httpd.stop();
		}
	}

	@Test
	public void testUseCacheWhenRemoteUnavailable() throws Exception {
		File cached = IO.getFile("testdata/httpcache/7/http%3A%2F%2Flocalhost%3A18083%2Fbundles/dummybundle.jar");
		CachingUriResourceHandle handle = new CachingUriResourceHandle(
			new URI("http://localhost:18083/bundles/dummybundle.jar"), IO.getFile("testdata/httpcache/7"),
			new HttpClient(), null);

		// whoops where's the server...

		File result = handle.request();
		assertEquals(cached, result);
	}

}
