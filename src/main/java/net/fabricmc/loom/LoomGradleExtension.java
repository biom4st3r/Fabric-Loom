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

package net.fabricmc.loom;

import com.google.gson.JsonObject;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.providers.MinecraftProvider;
import net.fabricmc.loom.util.LoomDependencyManager;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.mercury.Mercury;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

public class LoomGradleExtension {
	public String runDir = "run";
	public String refmapName;
	public String loaderLaunchMethod;
	public boolean remapMod = true;
	public boolean autoGenIDERuns = true;
	public boolean extractJars = false;
	public String customManifest = null;

	private File atFile;
	private File optifine;
	private List<Path> unmappedModsBuilt = new ArrayList<>();

	//Not to be set in the build.gradle
	private final Project project;
	private LoomDependencyManager dependencyManager;
	private JsonObject installerJson;
	private int installerJsonPriority = Integer.MAX_VALUE; // 0+, higher = less prioritized
	private MappingSet[] srcMappingCache = new MappingSet[2];
	private Mercury[] srcMercuryCache = new Mercury[2];

	public MappingSet getOrCreateSrcMappingCache(int id, Supplier<MappingSet> factory) {
		return srcMappingCache[id] != null ? srcMappingCache[id] : (srcMappingCache[id] = factory.get());
	}

	public Mercury getOrCreateSrcMercuryCache(int id, Supplier<Mercury> factory) {
		return srcMercuryCache[id] != null ? srcMercuryCache[id] : (srcMercuryCache[id] = factory.get());
	}

	public LoomGradleExtension(Project project) {
		this.project = project;
	}

	public void addUnmappedMod(Path file) {
		unmappedModsBuilt.add(file);
	}

	public List<Path> getUnmappedMods() {
		return Collections.unmodifiableList(unmappedModsBuilt);
	}

	public void setInstallerJson(JsonObject object, int priority) {
	    if (installerJson == null || priority <= installerJsonPriority) {
            this.installerJson = object;
            this.installerJsonPriority = priority;
        }
    }

    public JsonObject getInstallerJson() {
	    return installerJson;
    }

	public File getUserCache() {
		File userCache = new File(project.getGradle().getGradleUserHomeDir(), "caches" + File.separator + "fabric-loom");
		if (!userCache.exists()) {
			userCache.mkdirs();
		}
		return userCache;
	}

	public File getRootProjectPersistentCache() {
		File projectCache = new File(project.getRootProject().file(".gradle"), "loom-cache");
		if(!projectCache.exists()){
			projectCache.mkdirs();
		}
		return projectCache;
	}

	public File getRootProjectBuildCache() {
		File projectCache = new File(project.getRootProject().getBuildDir(), "loom-cache");
		if(!projectCache.exists()){
			projectCache.mkdirs();
		}
		return projectCache;
	}

	public File getProjectBuildCache() {
		File projectCache = new File(project.getBuildDir(), "loom-cache");
		if(!projectCache.exists()){
			projectCache.mkdirs();
		}
		return projectCache;
	}

	public File getRemappedModCache() {
		File remappedModCache = new File(getRootProjectPersistentCache(), "remapped_mods");
		if (!remappedModCache.exists()) {
			remappedModCache.mkdir();
		}
		return remappedModCache;
	}

	public File getNestedModCache() {
		File nestedModCache = new File(getRootProjectPersistentCache(), "nested_mods");
		if (!nestedModCache.exists()) {
			nestedModCache.mkdir();
		}
		return nestedModCache;
	}

	@Nullable
	private static Dependency findDependency(Project p, Collection<Configuration> configs, BiPredicate<String, String> groupNameFilter) {
		for (Configuration config : configs) {
			for (Dependency dependency : config.getDependencies()) {
				String group = dependency.getGroup();
				String name = dependency.getName();
				if (groupNameFilter.test(group, name)) {
					p.getLogger().debug("Loom findDependency found: " + group + ":" + name + ":" + dependency.getVersion());
					return dependency;
				}
			}
		}

		return null;
	}

	@Nullable
	private <T> T recurseProjects(Function<Project, T> projectTFunction) {
		Project p = this.project;
		T result;
		while (!AbstractPlugin.isRootProject(p)) {
			if ((result = projectTFunction.apply(p)) != null) {
				return result;
			}
			p = p.getRootProject();
		}
		result = projectTFunction.apply(p);
		return result;
	}

	@Nullable
	private Dependency getMixinDependency() {
		return recurseProjects((p) -> {
			List<Configuration> configs = new ArrayList<>();
			// check compile classpath first
			configs.add(p.getConfigurations().getByName("compileClasspath"));
			// failing that, buildscript
			configs.addAll(p.getBuildscript().getConfigurations());

			return findDependency(p, configs, (group, name) -> {
				if (name.equalsIgnoreCase("mixin") && group.equalsIgnoreCase("org.spongepowered")) {
					return true;
				}

				if (name.equalsIgnoreCase("sponge-mixin") && group.equalsIgnoreCase("net.fabricmc")) {
					return true;
				}

				return false;
			});
		});
	}

	@Nullable
	public String getMixinJsonVersion() {
		Dependency dependency = getMixinDependency();

		if (dependency != null) {
			if (dependency.getGroup().equalsIgnoreCase("net.fabricmc")) {
				if (Objects.requireNonNull(dependency.getVersion()).split("\\.").length >= 4) {
					return dependency.getVersion().substring(0, dependency.getVersion().lastIndexOf('.')) + "-SNAPSHOT";
				}
			}

			return dependency.getVersion();
		}

		return null;
	}

	public String getLoaderLaunchMethod() {
		return loaderLaunchMethod != null ? loaderLaunchMethod : "";
	}

	public LoomDependencyManager getDependencyManager() {
		return dependencyManager;
	}

	public MinecraftProvider getMinecraftProvider(){
		return getDependencyManager().getProvider(MinecraftProvider.class);
	}

	public MinecraftMappedProvider getMinecraftMappedProvider(){
		return getMappingsProvider().mappedProvider;
	}

	public MappingsProvider getMappingsProvider(){
		return getDependencyManager().getProvider(MappingsProvider.class);
	}

	public void setDependencyManager(LoomDependencyManager dependencyManager) {
		this.dependencyManager = dependencyManager;
	}

	public String getRefmapName() {
		if(refmapName == null || refmapName.isEmpty()){
			project.getLogger().warn("Could not find refmap definition, will be using default name: " + project.getName() + "-refmap.json");
			refmapName = project.getName() + "-refmap.json";
		}

		return refmapName;
	}

	public void setAT(Object file) {
		atFile = project.file(file);
	}

	public boolean hasAT() {
		return atFile != null;
	}

	public File getAT() {
		return atFile;
	}

	public void setOptiFine(Object file) {
		optifine = project.file(file);
	}

	public boolean hasOptiFine() {
		return optifine != null;
	}

	public File getOptiFine() {
		return optifine;
	}
}
