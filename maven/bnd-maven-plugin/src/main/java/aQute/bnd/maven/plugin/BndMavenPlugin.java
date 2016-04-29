package aQute.bnd.maven.plugin;

/*
 * Copyright (c) Paremus and others (2015, 2016). All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static aQute.lib.io.IO.getFile;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Manifest;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.sonatype.plexus.build.incremental.BuildContext;

import aQute.bnd.build.Project;
import aQute.bnd.header.Parameters;
import aQute.bnd.osgi.Builder;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.FileResource;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.Processor;
import aQute.bnd.osgi.Resource;
import aQute.bnd.version.MavenVersion;
import aQute.bnd.version.Version;
import aQute.lib.io.IO;
import aQute.lib.strings.Strings;
import aQute.lib.utf8properties.UTF8Properties;
import aQute.service.reporter.Report.Location;

@Mojo(name = "bnd-process", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE)
public class BndMavenPlugin extends AbstractMojo {

	private static final String	PACKAGING_POM	= "pom";
	private static final String	TSTAMP			= "${tstamp}";

	@Parameter(defaultValue = "${project.build.directory}", readonly = true)
	private File				targetDir;

	@Parameter(defaultValue = "${project.build.sourceDirectory}", readonly = true)
	private File				sourceDir;

	@Parameter(defaultValue = "${project.build.resources}", readonly = true)
	private List<org.apache.maven.model.Resource>	resources;

	@Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
	private File				classesDir;

	@Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/MANIFEST.MF", readonly = true)
	private File				manifestPath;

	@Parameter(defaultValue = "${project}", required = true, readonly = true)
	private MavenProject		project;

	@Parameter(defaultValue = "${settings}", readonly = true)
	private Settings			settings;

	@Component
	private BuildContext		buildContext;

	private Log					log;

	public void execute() throws MojoExecutionException {
		log = getLog();

		// Exit without generating anything if this is a pom-packaging project.
		// Probably it's just a parent project.
		if (PACKAGING_POM.equals(project.getPackaging())) {
			log.info("skip project with packaging=pom");
			return;
		}

		Properties beanProperties = new BeanProperties();
		beanProperties.put("project", project);
		beanProperties.put("settings", settings);
		Properties mavenProperties = new Properties(beanProperties);
		mavenProperties.putAll(project.getProperties());

		try (Builder builder = new Builder(new Processor(mavenProperties, false))) {
			builder.setTrace(log.isDebugEnabled());

			builder.setBase(project.getBasedir());
			loadProjectProperties(builder, project);
			builder.setProperty("project.output", targetDir.getCanonicalPath());

			// If no bundle to be built, we have nothing to do
			if (Builder.isTrue(builder.getProperty(Constants.NOBUNDLES))) {
				log.debug(Constants.NOBUNDLES + ": true");
				return;
			}

			// Reject sub-bundle projects
			List<Builder> subs = builder.getSubBuilders();
			if ((subs.size() != 1) || !builder.equals(subs.get(0))) {
				throw new MojoExecutionException("Sub-bundles not permitted in a maven build");
			}

			// Include local project packages automatically
			if (classesDir.isDirectory()) {
				Jar classesDirJar = new Jar(project.getName(), classesDir);
				classesDirJar.setManifest(new Manifest());
				builder.setJar(classesDirJar);
			}

			// Compute bnd classpath
			Set<Artifact> artifacts = project.getArtifacts();
			List<Object> buildpath = new ArrayList<Object>(artifacts.size());
			for (Artifact artifact : artifacts) {
				if (!artifact.getType().equals("jar")) {
					continue;
				}
				File cpe = artifact.getFile().getCanonicalFile();
				if (cpe.isDirectory()) {
					Jar cpeJar = new Jar(cpe);
					builder.addClose(cpeJar);
					builder.updateModified(cpeJar.lastModified(), cpe.getPath());
					buildpath.add(cpeJar);
				} else {
					builder.updateModified(cpe.lastModified(), cpe.getPath());
					buildpath.add(cpe);
				}
			}
			builder.setProperty("project.buildpath", Strings.join(File.pathSeparator, buildpath));
			if (log.isDebugEnabled()) {
				log.debug("builder classpath: " + builder.getProperty("project.buildpath"));
			}

			// If the project dependencies and versions are already available
			// here, why would we want developers to have to re-specify their
			// dependencies and their versions in the -includeresource and
			// Bundle-Classpath sections of the bnd configuration? Especially if
			// this is a WAB, and we are using this plugin in conjunction with
			// the maven-war-plugin instead of the maven-jar-plugin, let's
			// automatically set the -includeresource and Bundle-Classpath based
			// on the project model (pom.xml). This is a maven plugin after all,
			// this plugin has all that information without consulting the bnd
			// configuration.
			//
			// Secondly, we can use this opportunity to skip over files that
			// bndlib adds (because bndlib thinks that -wab means to convert an
			// OSGi jar to a WAB?? not sure). Whereas, if this plugin is used in
			// conjunction with the maven-war-plugin, we can use bndlib for what
			// it is mainly for, I think, which is the calculation of the
			// MANIFEST.MF and the OSGI-INF declarative services descriptors,
			// and leave the packaging to the traditional maven-war-plugin.

			List<Plugin> buildPlugins = project.getBuildPlugins();
			for (Plugin plugin : buildPlugins) {
				if ("maven-war-plugin".equals(plugin.getArtifactId())) {

					log.info("execute: detected the use of the maven-war-plugin for building ...");

					// Can we assume that the maven-war-plugin is being used for
					// building now? Maybe we need to see if they have
					// configured the warSourceDirectory instead of using the
					// standard directory layout:
					// https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html

					String warSourceDirectory = "src/main/webapp";
					Object configuration = plugin.getConfiguration();
					if (configuration instanceof Xpp3Dom) {
						Xpp3Dom xml = (Xpp3Dom) configuration;
						Xpp3Dom child = xml.getChild("warSourceDirectory");
						if (child != null) {
							warSourceDirectory = child.getValue();
						}

						// Are "provided" artifacts the only ones we do not
						// need to include?
						List<String> includeResources = new ArrayList<String>();
						List<String> bundleClasspaths = new ArrayList<String>();

						// include the BundleClasspath
						bundleClasspaths.add("WEB-INF/classes");
						bundleClasspaths.add(".");

						for (Artifact artifact : artifacts) {
							if (!artifact.getType().equals("jar")) {
								continue;
							}
							if ("provided".equals(artifact.getScope())) {
								continue;
							}
							if ("test".equals(artifact.getScope())) {
								continue;
							}
							File file = artifact.getFile().getCanonicalFile();
							if (file.isDirectory()) {
								log.info("execute: " + file.getCanonicalPath() + " is a directory");
							} else {
								log.info("execute: found artifact file.getName() = " + file.getName());
								String includeResource = "WEB-INF/lib/" + file.getName() + "=" + file.getName();
								includeResources.add(includeResource);

								String bundleClasspath = "WEB-INF/lib/" + file.getName();
								// + ";resolution:=optional";
								bundleClasspaths.add(bundleClasspath);
							}
						}

						Parameters includeResource = builder.getIncludeResource();
						log.info("execute: includeResource.toString() = " + includeResource.toString());

						log.info("execute: re-setting IncludeResource ...");
						builder.setIncludeResource(Strings.join(",", includeResources));

						includeResource = builder.getIncludeResource();
						log.info("execute: includeResource.toString() = " + includeResource.toString());


						Parameters bundleClasspath = builder.getBundleClassPath();
						log.info("execute: bundleClasspath.toString() = " + bundleClasspath.toString());

						log.info("execute: re-setting BundleClassPath ...");
						builder.setBundleClasspath(Strings.join(",", bundleClasspaths));

						bundleClasspath = builder.getBundleClassPath();
						log.info("execute: bundleClasspath.toString() = " + bundleClasspath.toString());

					}

					// Not sure why folks would invoke the maven-war-plugin
					// twice ... probably because they want me to feel their
					// pain, but i refuse. Only checking the first one in the
					// build section.
					break;
				}
			}

			// Compute bnd sourcepath
			boolean delta = !buildContext.isIncremental() || !manifestPath.exists();
			List<File> sourcepath = new ArrayList<File>();
			if (sourceDir.exists()) {
				sourcepath.add(sourceDir.getCanonicalFile());
				delta |= buildContext.hasDelta(sourceDir);
			}
			for (org.apache.maven.model.Resource resource : resources) {
				File resourceDir = new File(resource.getDirectory());
				if (resourceDir.exists()) {
					sourcepath.add(resourceDir.getCanonicalFile());
					delta |= buildContext.hasDelta(resourceDir);
				}
			}
			builder.setProperty("project.sourcepath", Strings.join(File.pathSeparator, sourcepath));
			if (log.isDebugEnabled()) {
				log.debug("builder sourcepath: " + builder.getProperty("project.sourcepath"));
			}

			// Set Bundle-SymbolicName
			if (builder.getProperty(Constants.BUNDLE_SYMBOLICNAME) == null) {
				builder.setProperty(Constants.BUNDLE_SYMBOLICNAME, project.getArtifactId());
			}
			// Set Bundle-Name
			if (builder.getProperty(Constants.BUNDLE_NAME) == null) {
				builder.setProperty(Constants.BUNDLE_NAME, project.getName());
			}
			// Set Bundle-Version
			Version version = MavenVersion.parseString(project.getVersion()).getOSGiVersion();
			builder.setProperty(Constants.BUNDLE_VERSION, version.toString());
			if (builder.getProperty(Constants.SNAPSHOT) == null) {
				builder.setProperty(Constants.SNAPSHOT, TSTAMP);
			}

			if (log.isDebugEnabled()) {
				log.debug("builder properties: " + builder.getProperties());
				log.debug("builder delta: " + delta);
			}

			if (delta || (builder.getJar() == null) || (builder.lastModified() > builder.getJar().lastModified())) {
				// Set builder paths
				builder.setClasspath(buildpath);
				builder.setSourcepath(sourcepath.toArray(new File[0]));

				// Build bnd Jar (in memory)
				Jar bndJar = builder.build();

				// Expand Jar into target/classes
				expandJar(bndJar, classesDir);
			} else {
				log.debug("No build");
			}

			// Finally, report
			reportErrorsAndWarnings(builder);
		} catch (MojoExecutionException e) {
			throw e;
		} catch (Exception e) {
			throw new MojoExecutionException("bnd error: " + e.getMessage(), e);
		}
	}

	private void loadProjectProperties(Builder builder, MavenProject project) throws Exception {
		// Load parent project properties first
		MavenProject parentProject = project.getParent();
		if (parentProject != null) {
			loadProjectProperties(builder, parentProject);
		}

		// Merge in current project properties
		Xpp3Dom configuration = project.getGoalConfiguration("biz.aQute.bnd", "bnd-maven-plugin", null, null);
		File baseDir = project.getBasedir();
		if (baseDir != null) { // file system based pom
			File pomFile = project.getFile();
			builder.updateModified(pomFile.lastModified(), "POM: " + pomFile);
			// check for bnd file
			String bndFileName = Project.BNDFILE;
			if (configuration != null) {
				Xpp3Dom bndfileElement = configuration.getChild("bndfile");
				if (bndfileElement != null) {
					bndFileName = bndfileElement.getValue();
				}
			}
			File bndFile = IO.getFile(baseDir, bndFileName);
			if (bndFile.isFile()) {
				if (log.isDebugEnabled()) {
					log.debug("loading bnd properties from file: " + bndFile);
				}
				// we use setProperties to handle -include
				builder.setProperties(bndFile.getParentFile(), builder.loadProperties(bndFile));
				return;
			}
			// no bnd file found, so we fall through
		}
		// check for bnd-in-pom configuration
		if (configuration != null) {
			Xpp3Dom bndElement = configuration.getChild("bnd");
			if (bndElement != null) {
				if (log.isDebugEnabled()) {
					log.debug("loading bnd properties from bnd element in pom: " + bndElement.getValue());
				}
				UTF8Properties properties = new UTF8Properties();
				properties.load(bndElement.getValue(), project.getFile(), builder);
				// we use setProperties to handle -include
				builder.setProperties(baseDir, properties);
				return;
			}
		}
	}

	private void reportErrorsAndWarnings(Builder builder) throws MojoExecutionException {
		Log log = getLog();

		File defaultFile = new File(project.getBasedir(), Project.BNDFILE);
		if (!defaultFile.exists()) {
			defaultFile = project.getFile();
		}
		List<String> warnings = builder.getWarnings();
		for (String warning : warnings) {
			Location location = builder.getLocation(warning);
			if (location == null) {
				location = new Location();
				location.message = warning;
			}
			buildContext.addMessage(location.file == null ? defaultFile : new File(location.file), location.line,
					location.length, location.message, BuildContext.SEVERITY_WARNING, null);
		}
		List<String> errors = builder.getErrors();
		for (String error : errors) {
			Location location = builder.getLocation(error);
			if (location == null) {
				location = new Location();
				location.message = error;
			}
			buildContext.addMessage(location.file == null ? defaultFile : new File(location.file), location.line,
					location.length, location.message, BuildContext.SEVERITY_ERROR, null);
		}
		if (!builder.isOk()) {
			if (errors.size() == 1)
				throw new MojoExecutionException(errors.get(0));
			else
				throw new MojoExecutionException("Errors in bnd processing, see log for details.");
		}
	}

	private void expandJar(Jar jar, File dir) throws Exception {
		final long lastModified = jar.lastModified();
		if (log.isDebugEnabled()) {
			log.debug(String.format("Bundle lastModified: %tF %<tT.%<tL", lastModified));
		}
		dir = dir.getAbsoluteFile();
		Files.createDirectories(dir.toPath());

		for (Map.Entry<String,Resource> entry : jar.getResources().entrySet()) {
			File outFile = getFile(dir, entry.getKey());
			Resource resource = entry.getValue();
			// Skip the copy if the source and target are the same file
			if (resource instanceof FileResource) {
				@SuppressWarnings("resource")
				FileResource fr = (FileResource) resource;
				if (outFile.equals(fr.getFile())) {
					continue;
				}
			}
			if (!outFile.exists() || outFile.lastModified() < lastModified) {
				if (log.isDebugEnabled()) {
					if (outFile.exists())
						log.debug(String.format("Updating lastModified: %tF %<tT.%<tL '%s'", outFile.lastModified(),
								outFile));
					else
						log.debug(String.format("Creating '%s'", outFile));
				}
				if (outFile.toPath().toString().contains("classes/WEB-INF")) {
					log.warn("expandJar: bndlib want's " + outFile.toPath().toString() + " skipping ...");
				} else {
					Files.createDirectories(outFile.toPath().getParent());
					try (OutputStream out = buildContext.newFileOutputStream(outFile)) {
						IO.copy(resource.openInputStream(), out);
					}
				}
			}
		}

		if (!manifestPath.exists() || manifestPath.lastModified() < lastModified) {
			if (log.isDebugEnabled()) {
				if (manifestPath.exists())
					log.debug(String.format("Updating lastModified: %tF %<tT.%<tL '%s'", manifestPath.lastModified(),
							manifestPath));
				else
					log.debug(String.format("Creating '%s'", manifestPath));
			}
			Files.createDirectories(manifestPath.toPath().getParent());
			try (OutputStream manifestOut = buildContext.newFileOutputStream(manifestPath)) {
				jar.writeManifest(manifestOut);
			}
		}
	}

	private class BeanProperties extends Properties {
		private static final long serialVersionUID = 1L;

		BeanProperties() {
			super();
		}

		@Override
		public String getProperty(String key) {
			final int i = key.indexOf('.');
			final String name = (i > 0) ? key.substring(0, i) : key;
			Object value = get(name);
			if ((value != null) && (i > 0)) {
				value = getField(value, key.substring(i + 1));
			}
			if (value == null) {
				return null;
			}
			return value.toString();
		}

		private Object getField(Object target, String key) {
			final int i = key.indexOf('.');
			final String fieldName = (i > 0) ? key.substring(0, i) : key;
			final String getterSuffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
			Object value = null;
			try {
				Class< ? > targetClass = target.getClass();
				while (!Modifier.isPublic(targetClass.getModifiers())) {
					targetClass = targetClass.getSuperclass();
				}
				Method getter;
				try {
					getter = targetClass.getMethod("get" + getterSuffix);
				} catch (NoSuchMethodException nsme) {
					getter = targetClass.getMethod("is" + getterSuffix);
				}
				value = getter.invoke(target);
			} catch (Exception e) {
				log.debug("Could not find getter method for field: " + fieldName, e);
			}
			if ((value != null) && (i > 0)) {
				value = getField(value, key.substring(i + 1));
			}
			return value;
		}
	}
}
