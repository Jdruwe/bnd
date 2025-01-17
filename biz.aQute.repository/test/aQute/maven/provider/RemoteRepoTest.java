package aQute.maven.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aQute.bnd.http.HttpClient;
import aQute.bnd.service.url.State;
import aQute.bnd.test.jupiter.InjectTemporaryDirectory;
import aQute.http.testservers.HttpTestServer.Config;
import aQute.lib.hex.Hex;
import aQute.lib.io.IO;
import aQute.libg.cryptography.MD5;
import aQute.libg.cryptography.SHA1;
import aQute.libg.reporter.ReporterAdapter;

public class RemoteRepoTest {
	File					local;
	File					remote;
	FakeNexus				fnx;
	MavenRemoteRepository	repo;
	ReporterAdapter			reporter	= new ReporterAdapter(System.err);

	@BeforeEach
	protected void setUp(@InjectTemporaryDirectory
	File tmp) throws Exception {
		local = IO.getFile(tmp, "local");
		remote = IO.getFile(tmp, "remote");
		remote.mkdirs();
		local.mkdirs();
		Config config = new Config();
		fnx = new FakeNexus(config, remote);
		fnx.start();
		reporter.setTrace(true);
		repo = new MavenRemoteRepository(local, new HttpClient(), fnx.getBaseURI() + "/repo/", reporter);
	}

	@AfterEach
	protected void tearDown() throws Exception {
		fnx.close();
	}

	@Test
	public void testBasic() throws Exception {
		File localFoobar = IO.getFile(this.local, "some.jar");
		File localFoobarSha1 = IO.getFile(this.local, "some.jar.sha1");
		File localFoobarMD5 = IO.getFile(this.local, "some.jar.md5");
		File remoteFoobar = IO.getFile(this.remote, "foo/bar");
		File remoteFoobarSha1 = IO.getFile(this.remote, "foo/bar.sha1");
		File remoteFoobarMD5 = IO.getFile(this.remote, "foo/bar.md5");
		remoteFoobar.getParentFile()
			.mkdirs();

		// Test does not exist
		assertFalse(remoteFoobar.exists());
		assertEquals(State.NOT_FOUND, repo.fetch("foo/bar", localFoobar)
			.getState());
		assertFalse(localFoobar.exists());

		//
		// Create remote
		//

		IO.store("bla", remoteFoobar);
		assertTrue(remoteFoobar.isFile());
		assertEquals(3L, remoteFoobar.length());

		//
		// Fetch it, must exist now
		//

		assertEquals(State.UPDATED, repo.fetch("foo/bar", localFoobar)
			.getState());
		assertTrue(localFoobar.isFile());
		assertEquals(3L, localFoobar.length());

		//
		// Overwrite it
		//

		assertFalse(remoteFoobarSha1.isFile());
		assertFalse(remoteFoobarMD5.isFile());

		IO.store("overwrite", localFoobar);
		repo.store(localFoobar, "foo/bar");
		assertEquals(9L, remoteFoobar.length());
		assertTrue(remoteFoobarSha1.isFile());
		assertTrue(remoteFoobarMD5.isFile());

		byte[] sha1Expected = SHA1.digest("overwrite".getBytes())
			.digest();
		byte[] sha1Actual = Hex.toByteArray(IO.collect(remoteFoobarSha1));
		assertTrue(Arrays.equals(sha1Expected, sha1Actual));

		byte[] md5Expected = MD5.digest("overwrite".getBytes())
			.digest();
		byte[] md5Actual = Hex.toByteArray(IO.collect(remoteFoobarMD5));
		assertTrue(Arrays.equals(md5Expected, md5Actual));

		//
		// Fetch the new one with checksums
		//

		IO.delete(localFoobar.getParentFile());

		assertEquals(State.UPDATED, repo.fetch("foo/bar", localFoobar)
			.getState());
		String sha1 = SHA1.digest(localFoobar)
			.asHex();
		assertEquals(sha1, IO.collect(remoteFoobarSha1));

		//
		// Delete the remote entry
		//

		IO.delete(localFoobar);
		assertTrue(repo.delete("foo/bar"));
		assertFalse(remoteFoobar.exists());
		assertEquals(State.NOT_FOUND, repo.fetch("foo/bar", localFoobar)
			.getState());
		assertFalse(localFoobar.exists());
		Thread.sleep(1000);
		assertFalse(remoteFoobarSha1.exists());
		assertFalse(remoteFoobarMD5.exists());

	}

	@Test
	public void testChecksumError() throws Exception {
		File localFoobar = IO.getFile(this.local, "some.jar");
		File remoteFoobar = IO.getFile(this.remote, "foo/bar");
		File remoteFoobarSha1 = IO.getFile(this.remote, "foo/bar.sha1");
		File remoteFoobarMD5 = IO.getFile(this.remote, "foo/bar.md5");

		remoteFoobar.getParentFile()
			.mkdirs();
		IO.store("bla", remoteFoobar);
		IO.store("123456", remoteFoobarSha1);
		try {
			repo.fetch("foo/bar", localFoobar);
			fail("Expected an exception because checksum is wrong");
		} catch (Exception e) {
			// ok
		}
	}

	@Test
	public void testLowercaseChecksum() throws Exception {
		File localFoobar = IO.getFile(this.local, "some.jar");
		File remoteFoobar = IO.getFile(this.remote, "foo/bar");
		File remoteFoobarSha1 = IO.getFile(this.remote, "foo/bar.sha1");
		File remoteFoobarMD5 = IO.getFile(this.remote, "foo/bar.md5");

		remoteFoobar.getParentFile()
			.mkdirs();
		IO.store("bla", remoteFoobar);
		IO.store(" FFA6706FF2127A749973072756F83C532E43ED02\r\n".toLowerCase(), remoteFoobarSha1);

		assertEquals(State.UPDATED, repo.fetch("foo/bar", localFoobar)
			.getState());
	}

	@Test
	public void testNoChecksum() throws Exception {
		File localFoobar = IO.getFile(this.local, "some.jar");
		File remoteFoobar = IO.getFile(this.remote, "foo/bar");
		File remoteFoobarSha1 = IO.getFile(this.remote, "foo/bar.sha1");
		File remoteFoobarMD5 = IO.getFile(this.remote, "foo/bar.md5");

		remoteFoobar.getParentFile()
			.mkdirs();
		assertFalse(remoteFoobarSha1.exists());
		assertFalse(remoteFoobarMD5.exists());

		IO.store("bla", remoteFoobar);
		assertEquals(State.UPDATED, repo.fetch("foo/bar", localFoobar)
			.getState());
	}

	@Test
	public void testOnlyWrongMD5Checksum() throws Exception {
		File localFoobar = IO.getFile(this.local, "some.jar");
		File remoteFoobar = IO.getFile(this.remote, "foo/bar");
		File remoteFoobarMD5 = IO.getFile(this.remote, "foo/bar.md5");

		remoteFoobar.getParentFile()
			.mkdirs();
		IO.store("1234", remoteFoobarMD5);
		assertTrue(remoteFoobarMD5.exists());

		IO.store("bla", remoteFoobar);
		try {
			assertEquals(State.UPDATED, repo.fetch("foo/bar", localFoobar)
				.getState());
			fail("Expected exception with a false MD5 checksum");
		} catch (Exception e) {
			System.out.println(e);
			//
		}
	}

	@Test
	public void testOnlyMD5Checksum() throws Exception {
		File localFoobar = IO.getFile(this.local, "some.jar");
		File remoteFoobar = IO.getFile(this.remote, "foo/bar");
		File remoteFoobarSha1 = IO.getFile(this.remote, "foo/bar.sha1");
		File remoteFoobarMD5 = IO.getFile(this.remote, "foo/bar.md5");

		remoteFoobar.getParentFile()
			.mkdirs();
		assertFalse(remoteFoobarSha1.exists());
		IO.store("128ECF542A35AC5270A87DC740918404", remoteFoobarMD5);
		assertTrue(remoteFoobarMD5.exists());

		IO.store("bla", remoteFoobar);
		assertEquals(State.UPDATED, repo.fetch("foo/bar", localFoobar)
			.getState());
	}
}
