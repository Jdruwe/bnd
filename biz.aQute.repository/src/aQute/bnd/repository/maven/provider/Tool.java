package aQute.bnd.repository.maven.provider;

import static aQute.bnd.exceptions.ConsumerWithException.asConsumer;
import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import aQute.bnd.header.Parameters;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Descriptors;
import aQute.bnd.osgi.Domain;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.Processor;
import aQute.bnd.osgi.Resource;
import aQute.bnd.version.Version;
import aQute.lib.io.FileTree;
import aQute.lib.io.IO;
import aQute.lib.strings.Strings;
import aQute.lib.tag.Tag;
import aQute.lib.utf8properties.UTF8Properties;
import aQute.libg.command.Command;
import aQute.maven.api.Archive;

public class Tool extends Processor {

	private static final String	OSGI_OPT_SRC		= "OSGI-OPT/src";
	private static final String	OSGI_OPT_SRC_PREFIX	= OSGI_OPT_SRC + "/";
	private final Jar			jar;
	private final File			tmp;
	private final File			sources;
	private final File			javadoc;
	private final File			javadocOptions;

	public Tool(Processor parent, Jar jar) throws Exception {
		super(parent);
		this.jar = jar;
		tmp = Files.createTempDirectory("tool")
			.toFile();
		sources = new File(tmp, "sources");
		javadoc = new File(tmp, "javadoc");
		javadocOptions = new File(tmp, "javadoc.options");
	}

	void setSources(Jar sourcesJar, String prefix) throws Exception {
		IO.delete(sources);
		IO.mkdirs(sources);
		final int prefix_length = prefix.length();
		for (Entry<String, Resource> e : sourcesJar.getResources()
			.entrySet()) {
			String path = e.getKey();
			if (!path.startsWith(prefix)) {
				continue;
			}
			File out = IO.getFile(sources, path.substring(prefix_length));
			IO.mkdirs(out.getParentFile());
			e.getValue()
				.write(out);
		}
	}

	public boolean hasSources() {
		return sources.isDirectory() || jar.hasDirectory(OSGI_OPT_SRC);
	}

	public Jar doJavadoc(Map<String, String> options, boolean exportsOnly) throws Exception {
		prepareSource(Collections.emptyMap());

		if (!sources.isDirectory()) {
			return new Jar(Archive.JAVADOC_CLASSIFIER);
		}

		IO.mkdirs(javadoc);

		try (PrintWriter writer = IO.writer(javadocOptions)) {
			writer.println("-quiet");
			writer.println("-protected");
			writer.printf("%s '%s'%n", "-d", fileName(javadoc));
			writer.println("-charset 'UTF-8'");
			writer.printf("%s '%s'%n", "-sourcepath", fileName(sources));

			Properties pp = new UTF8Properties();
			pp.putAll(options);

			Domain manifest = Domain.domain(jar.getManifest());
			String name = manifest.getBundleName();
			if (name == null)
				name = manifest.getBundleSymbolicName()
					.getKey();

			String version = manifest.getBundleVersion();
			if (version == null)
				version = Version.LOWEST.toString();

			String bundleDescription = manifest.getBundleDescription();

			if (bundleDescription != null && !Strings.trim(bundleDescription)
				.isEmpty()) {
				printOverview(manifest, name, version, bundleDescription);
			}

			set(pp, "-doctitle", name);
			set(pp, "-windowtitle", name);
			set(pp, "-header", manifest.getBundleVendor());
			set(pp, "-bottom", manifest.getBundleCopyright());
			set(pp, "-footer", manifest.getBundleDocURL());

			writer.println("-tag 'Immutable:t:\"Immutable\"'");
			writer.println("-tag 'ThreadSafe:t:\"ThreadSafe\"'");
			writer.println("-tag 'NotThreadSafe:t:\"NotThreadSafe\"'");
			writer.println("-tag 'GuardedBy:mf:\"Guarded By:\"'");
			writer.println("-tag 'security:m:\"Required Permissions\"'");
			writer.println("-tag 'noimplement:t:\"Consumers of this API must not implement this interface\"'");

			for (Enumeration<?> e = pp.propertyNames(); e.hasMoreElements();) {
				String key = (String) e.nextElement();
				String value = pp.getProperty(key);

				if (key.startsWith("-")) {
					//
					// Allow people to add the same command multiple times
					// by suffixing it with '.' something
					//
					int n = key.lastIndexOf('.');
					if (n > 0) {
						key = key.substring(0, n);
					}

					writer.printf("%s '%s'%n", key, escape(value));
				}
			}

			FileTree sourcefiles = new FileTree();
			if (exportsOnly) {
				Parameters exports = manifest.getExportPackage();
				exports.keySet()
					.stream()
					.map(packageName -> Descriptors.fqnToBinary(packageName) + "/*.java")
					.forEach(sourcefiles::addIncludes);
			}
			sourcefiles.stream(sources, "**/*.java")
				.forEachOrdered(f -> writer.printf("'%s'%n", fileName(f)));
		}

		Command command = new Command();
		command.add(getJavaExecutable("javadoc"));
		command.add("@" + fileName(javadocOptions));
		StringBuilder out = new StringBuilder();
		StringBuilder err = new StringBuilder();
		int result = command.execute(out, err);
		if (result != 0) {
			warning("Error during execution of javadoc command: %s\n******************\n%s", out, err);
		}
		return new Jar(Archive.JAVADOC_CLASSIFIER, javadoc);
	}

	private String fileName(File f) {
		String result = IO.absolutePath(f);
		return result;
	}

	private String escape(String input) {
		return input.replace("\\", "\\\\")
			.replace(System.getProperty("line.separator"), "\\" + System.getProperty("line.separator"));
	}

	private void printOverview(Domain manifest, String name, String version, String bundleDescription)
		throws FileNotFoundException {
		Tag body = new Tag("body");
		new Tag(body, "h1", name);
		new Tag(body, "p", "Version " + version);
		new Tag(body, "p", bundleDescription);

		Tag table = new Tag(body, "table");
		for (String key : manifest) {
			if (key.equalsIgnoreCase(Constants.BUNDLE_DESCRIPTION) || key.equalsIgnoreCase(Constants.BUNDLE_VERSION))
				continue;

			Tag tr = new Tag(table, "tr");
			new Tag(tr, "td", key);
			new Tag(tr, "td", manifest.get(key));
		}

		File overview = new File(sources, "overview.html");
		try (PrintWriter pw = new PrintWriter(overview)) {
			body.print(2, pw);
		}
	}

	private void set(Properties pp, String key, String value) {
		if (value == null)
			return;

		if (pp.containsKey(key))
			return;

		pp.put(key, value);
	}

	private static final String[]	PACKAGE_FILES	= {
		"packageinfo", "package.html"
	};

	private static final String		PATH_SEPARATORS	= File.pathSeparator.concat(",");

	private void prepareSource(Map<String, String> options) throws Exception {
		if (sources.isDirectory()) { // extract source if not already present
			return;
		}
		if (jar.hasDirectory(OSGI_OPT_SRC)) {
			setSources(jar, OSGI_OPT_SRC_PREFIX);
			return;
		}

		IO.delete(sources);
		IO.mkdirs(sources);

		String sourcepath = options.get(Constants.SOURCEPATH);
		if (sourcepath == null) {
			sourcepath = getProperty(Constants.SOURCEPATH);
		}
		// We split the dirs on path separators or comma
		List<File> sourceDirs = Strings.splitQuotedAsStream(sourcepath, PATH_SEPARATORS)
			.map(this::getFile)
			.filter(File::isDirectory)
			.collect(toList());
		if (sourceDirs.isEmpty()) {
			return;
		}
		Set<String> packagePaths = new HashSet<>();
		jar.getResourceNames(name -> name.endsWith(".class"))
			.forEach(asConsumer(name -> {
				int n = name.lastIndexOf('/');
				String packagePath = (n < 0) ? "" : name.substring(0, n + 1);
				String sourcePath = name.substring(0, name.length() - 5)
					.concat("java");
				for (File dir : sourceDirs) {
					File sourceFile = IO.getFile(dir, sourcePath);
					if (sourceFile.isFile()) {
						packagePaths.add(packagePath);
						File targetFile = IO.getFile(sources, sourcePath);
						IO.mkdirs(targetFile.getParentFile());
						IO.copy(sourceFile, targetFile);
					}
				}
			}));
		packagePaths.stream()
			.flatMap(packagePath -> Arrays.stream(PACKAGE_FILES)
				.map(packagePath::concat))
			.forEach(asConsumer(sourcePath -> {
				for (File dir : sourceDirs) {
					File sourceFile = IO.getFile(dir, sourcePath);
					if (sourceFile.isFile()) {
						File targetFile = IO.getFile(sources, sourcePath);
						IO.mkdirs(targetFile.getParentFile());
						IO.copy(sourceFile, targetFile);
					}
				}
			}));
	}

	public Jar doSource(Map<String, String> options) throws Exception {
		prepareSource(options);

		if (!sources.isDirectory()) {
			return new Jar(Archive.SOURCES_CLASSIFIER);
		}

		return new Jar(Archive.SOURCES_CLASSIFIER, sources);
	}

	public Jar doSource() throws Exception {
		return doSource(Collections.emptyMap());
	}

	@Override
	public void close() throws IOException {
		try {
			super.close();
		} finally {
			IO.delete(tmp);
		}
	}

}
