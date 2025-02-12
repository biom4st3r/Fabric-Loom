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

package net.fabricmc.loom.providers;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.MinecraftVersionInfo;
import org.gradle.api.Project;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.function.Consumer;

public class MinecraftLibraryProvider {

	public File MINECRAFT_LIBS;

	private Collection<File> libs = new HashSet<>();

	public void provide(MinecraftProvider minecraftProvider, Project project, Consumer<Runnable> postPopulationScheduler) {
		MinecraftVersionInfo versionInfo = minecraftProvider.versionInfo;

		initFiles(project, minecraftProvider);

		for (MinecraftVersionInfo.Library library : versionInfo.libraries) {
			if (library.allowed() && library.getFile(MINECRAFT_LIBS) != null) {
				// TODO: Add custom library locations

				// By default, they are all available on all sides
				/* boolean isClientOnly = false;
				if (library.name.contains("java3d") || library.name.contains("paulscode") || library.name.contains("lwjgl") || library.name.contains("twitch") || library.name.contains("jinput") || library.name.contains("text2speech") || library.name.contains("objc")) {
					isClientOnly = true;
				} */

				project.getDependencies().add(Constants.MINECRAFT_DEPENDENCIES, project.getDependencies().module(library.getArtifactName()));
			}
		}

		postPopulationScheduler.accept(() -> libs = project.getConfigurations().getByName(Constants.MINECRAFT_DEPENDENCIES).getFiles());
	}

	public Collection<File> getLibraries() {
		return libs;
	}

	private void initFiles(Project project, MinecraftProvider minecraftProvider) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MINECRAFT_LIBS = new File(extension.getUserCache(), "libraries");
	}

}
