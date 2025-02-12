/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util;

import com.google.common.collect.Iterables;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.YarnGithubResolver.GithubDependency;

import org.apache.commons.io.FilenameUtils;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.SelfResolvingDependency;

import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class DependencyProvider {

	private LoomDependencyManager dependencyManager;

	public abstract void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception;

	public abstract String getTargetConfig();

	public void addDependency(Object object, Project project) {
		addDependency(object, project, "compile");
	}

	public void addDependency(Object object, Project project, String target) {
		if(object instanceof File){
			object = project.files(object);
		}
		project.getDependencies().add(target, object);
	}

	public void register(LoomDependencyManager dependencyManager){
		this.dependencyManager = dependencyManager;
	}

	public LoomDependencyManager getDependencyManager() {
		return dependencyManager;
	}

	public static class DependencyInfo {
		final Project project;
		final Dependency dependency;
		final Configuration sourceConfiguration;

		public static DependencyInfo create(Project project, Dependency dependency, Configuration sourceConfiguration) {
			if (dependency instanceof SelfResolvingDependency) {
				if (dependency instanceof GithubDependency) {
					return new ConcreteDependencyInfo(project, (GithubDependency) dependency, sourceConfiguration);
				} else {
					return ConcreteDependencyInfo.create(project, (SelfResolvingDependency) dependency, sourceConfiguration);
				}
			} else {
				return new DependencyInfo(project, dependency, sourceConfiguration);
			}
		}

		private DependencyInfo(Project project, Dependency dependency, Configuration sourceConfiguration) {
			this.project = project;
			this.dependency = dependency;
			this.sourceConfiguration = sourceConfiguration;
		}

		public Dependency getDependency() {
			return dependency;
		}

		public String getResolvedVersion() {
			for (ResolvedDependency rd : sourceConfiguration.getResolvedConfiguration().getFirstLevelModuleDependencies()) {
				if (rd.getModuleGroup().equals(dependency.getGroup()) && rd.getModuleName().equals(dependency.getName())) {
					return rd.getModuleVersion();
				}
			}

			return dependency.getVersion();
		}

		public Configuration getSourceConfiguration() {
			return sourceConfiguration;
		}

		public Set<File> resolve() {
			return sourceConfiguration.files(dependency);
		}

		public Optional<File> resolveFile() {
			Set<File> files = resolve();
			if (files.isEmpty()) {
				return Optional.empty();
			} else if (files.size() > 1) {
				StringBuilder builder = new StringBuilder(this.toString());
				builder.append(" resolves to more than one file:");
				for (File f : files) {
					builder.append("\n\t-").append(f.getAbsolutePath());
				}
				throw new RuntimeException(builder.toString());
			} else {
				return files.stream().findFirst();
			}
		}

		@Override
		public String toString() {
			return getDepString();
		}

		public String getFullName() {
			return dependency.getGroup() + '.' + dependency.getName();
		}

		public String getDepString(){
			return dependency.getGroup() + ":" + dependency.getName() + ":" + dependency.getVersion();
		}

		public String getResolvedDepString(){
			return dependency.getGroup() + ":" + dependency.getName() + ":" + getResolvedVersion();
		}
	}

	public static class ConcreteDependencyInfo extends DependencyInfo {
		protected final String group, name, version;

		static ConcreteDependencyInfo create(Project project, SelfResolvingDependency dependency, Configuration configuration) {
			Set<File> files = dependency.resolve();

			File root;
			switch (files.size()) {
			case 0: //Don't think Gradle would ever let you do this
				throw new IllegalStateException("Empty dependency?");

			case 1: //Single file dependency
				root = Iterables.getOnlyElement(files);
				break;

			default: //File collection, try work out the classifiers
				List<File> sortedFiles = files.stream().sorted(Comparator.comparing(File::getName, Comparator.comparingInt(String::length))).collect(Collectors.toList());

				//First element in sortedFiles is the one with the shortest name, we presume all the others are different classifier types of this
				File shortest = sortedFiles.remove(0);
				String shortestName = FilenameUtils.removeExtension(shortest.getName()); //name.jar -> name

				if (sortedFiles.stream().map(File::getName).anyMatch(name -> !name.startsWith(shortestName))) {
					//If there is another file which doesn't start with the same name as the presumed classifier-less one we're out of our depth
					throw new IllegalArgumentException("Unable to resolve classifiers for " + dependency + " (failed to sort " + files + ')');
				}

				//We appear to be right, therefore this is the normal dependency file we want
				root = shortest;
			}

			String name, version;
			if ("jar".equals(FilenameUtils.getExtension(root.getName())) && ZipUtil.containsEntry(root, "fabric.mod.json")) {
				//It's a Fabric mod, see how much we can extract out
				JsonObject json = new Gson().fromJson(new String(ZipUtil.unpackEntry(root, "fabric.mod.json"), StandardCharsets.UTF_8), JsonObject.class);
				if (json == null || !json.has("id") || !json.has("version")) throw new IllegalArgumentException("Invalid Fabric mod jar: " + root + " (malformed json: " + json + ')');

				if (json.has("name")) {//Go for the name field if it's got one
					name = json.get("name").getAsString();
				} else {
					name = json.get("id").getAsString();
				}
				version = json.get("version").getAsString();
			} else {
				//Not a Fabric mod, just have to make something up
				name = FilenameUtils.removeExtension(root.getName());

				int split = name.indexOf('-');
				if (split > 0) {
					project.getLogger().debug("Inferring versioning from file dependency: " + root);

					name = name.substring(0, split);
					version = name.substring(split + 1);

					project.getLogger().debug("Read dependency as " + name + '-' + version + " (from split point " + split + ')');
				} else {
					project.getLogger().warn("Unable to infer versioning from file dependency: " + root);
					project.getLogger().warn("Renaming the file to the format \"name-version." + FilenameUtils.getExtension(name) + "\" would be advisable");

					version = "1.0";
				}
			}

			return new ConcreteDependencyInfo(project, dependency, configuration, "net.fabricmc.synthetic", name, version);
		}

		ConcreteDependencyInfo(Project project, GithubDependency dependency, Configuration configuration) {
			this(project, dependency, configuration, dependency.getGroup(), dependency.getName(), dependency.getVersion());
		}

		private ConcreteDependencyInfo(Project project, Dependency dependency, Configuration configuration, String group, String name, String version) {
			super(project, dependency, configuration);

			this.group = group;
			this.name = name;
			this.version = version;
		}

		@Override
		public String getResolvedVersion() {
			return version;
		}

		@Override
		public String getFullName() {
			return group + '.' + name;
		}

		@Override
		public String getDepString() {
			return group + ':' + name + ':' + version;
		}

		@Override
		public String getResolvedDepString() {
			return getDepString();
		}
	}
}
