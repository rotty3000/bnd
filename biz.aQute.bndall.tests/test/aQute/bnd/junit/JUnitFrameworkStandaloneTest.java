package aQute.bnd.junit;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import aQute.lib.io.IO;

public class JUnitFrameworkStandaloneTest {

	@Test
	public void standaloneWorkspaceMode() throws Exception {
		File tmpDir = File.createTempFile("junit", "");
		tmpDir.delete();
		tmpDir.mkdir();

		ClassLoader classLoader = getClass().getClassLoader();

		List<File> classPathBundles = new ArrayList<>();
		File classesDir = null;

		String java_home = System.getProperty("java.home");

		try (URLClassLoader urlLoader = (URLClassLoader) classLoader) {
			for (URL url : urlLoader.getURLs()) {
				if ("file".equals(url.getProtocol()) && !url.getPath()
					.contains(java_home)) {

					File file = Paths.get(url.toURI())
						.toFile();

					if (file.isDirectory()) {
						if (classesDir == null) {
							classesDir = file;
						}
					} else {
						classPathBundles.add(file);
					}
				}
			}
		}

		try {
			try (JUnitFramework jf = new JUnitFramework(classPathBundles, IO.getFile("bnd.bnd"), classesDir, tmpDir)) {

				Assert.assertNotNull(jf.getProject());
			} finally {
				Files.walk(tmpDir.toPath())
					.sorted(Comparator.reverseOrder())
					.map(Path::toFile)
					.forEach(File::delete);
			}
		}
		catch (Throwable t) {
			t.printStackTrace();
		}
	}

}
