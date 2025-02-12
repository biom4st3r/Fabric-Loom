package net.fabricmc.loom;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.dependencies.SelfResolvingDependencyInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.TaskDependency;

import net.fabricmc.loom.util.DownloadUtil;

public class YarnGithubResolver {
	private static final String DEFAULT_REPO = "FabricMC/yarn";
	private static final String DOWNLOAD_URL = "https://api.github.com/repos/%s/zipball/%s";
	private static final Action<DownloadSpec> NOOP = spec -> {
	};
	final Function<Path, FileCollection> fileFactory;
	private final Path cache;
	final Logger logger;

	private static void createDirectory(Path path) {
		try {
			if (Files.notExists(path)) Files.createDirectories(path);
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to create directory", e);
		}
	}

	public YarnGithubResolver(Project project) {
		cache = project.getExtensions().getByType(LoomGradleExtension.class).getUserCache().toPath().resolve("yarn-resolutions");
		createDirectory(cache);
		logger = project.getLogger();
		fileFactory = project::files;
	}

	public static class DownloadSpec {
		final String originalName;
		private String group, name, version, reason;
		boolean forceFresh;

		protected DownloadSpec(String name) {
			int group = name.indexOf('/');
			this.group = name.substring(0, group++);
			this.name = name.substring(group, name.indexOf('/', group));

			originalName = name;
		}

		public String getGroup() {
			return group;
		}

		public void setGroup(String group) {
			this.group = group;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getVersion() {
			//Might be able to infer the default relative to the original commit, might also be more effort than it's worth
			return version == null ? "1.0" : version;
		}

		public void setVersion(String version) {
			this.version = version;
		}

		public String getReason() {
			return reason;
		}

		public void because(String reason) {
			this.reason = reason;
		}

		public void setForceFresh(boolean force) {
			forceFresh = force;
		}
	}

	public Dependency yarnBranch(String branch) {
		return yarnBranch(branch, NOOP);
	}

	public Dependency yarnBranch(String branch, Action<DownloadSpec> action) {
		return fromBranch(DEFAULT_REPO, branch, action);
	}

	public Dependency fromBranch(String repo, String branch) {
		return fromBranch(repo, branch, NOOP);
	}

	public Dependency fromBranch(String repo, String branch, Action<DownloadSpec> action) {
		DownloadSpec spec = new DownloadSpec(repo + '/' + branch);
		action.execute(spec);
		return createFrom(spec, String.format(DOWNLOAD_URL, repo, branch));
	}

	public Dependency yarnCommit(String commit) {
		return yarnCommit(commit, NOOP);
	}

	public Dependency yarnCommit(String commit, Action<DownloadSpec> action) {
		return fromCommit(DEFAULT_REPO, commit, action);
	}

	public Dependency fromCommit(String repo, String commit) {
		return fromCommit(repo, commit, NOOP);
	}

	public Dependency fromCommit(String repo, String commit, Action<DownloadSpec> action) {
		DownloadSpec spec = new DownloadSpec(repo + '/' + commit);
		action.execute(spec);
		return createFrom(spec, String.format(DOWNLOAD_URL, repo, commit));
	}

	//Gradle needs the internal interface to avoid class cast exceptions
	public class GithubDependency implements SelfResolvingDependencyInternal, FileCollectionDependency {
		protected final DownloadSpec spec;
		protected final String origin;
		protected final Path destination;
		private String reason;

		public GithubDependency(DownloadSpec spec, String origin, Path destination) {
			this.spec = spec;
			this.origin = origin;
			this.destination = destination;
			reason = spec.getReason();
		}

		@Override
		public String getGroup() {
			return spec.getGroup();
		}

		@Override
		public String getName() {
			return spec.getName();
		}

		@Override
		public String getVersion() {
			return spec.getVersion();
		}

		@Override
		public ComponentIdentifier getTargetComponentId() {
			return null;
		}

		@Override
		public boolean contentEquals(Dependency dependency) {
			if (dependency == this) return true;
			if (!(dependency instanceof GithubDependency)) return false;
			return Objects.equals(((GithubDependency) dependency).origin, origin);
		}

		@Override
		public Dependency copy() {
			return new GithubDependency(spec, origin, destination);
		}

		@Override
		public void because(String reason) {
			this.reason = reason;
		}

		@Override
		public String getReason() {
			return reason;
		}

		@Override
		public TaskDependency getBuildDependencies() {
			logger.info("Computing Github dependency's task dependency for " + spec.originalName + " from " + origin + " to " + destination);
			return task -> Collections.emptySet();
		}

		@Override
		public FileCollection getFiles() {
			logger.info("Detecting Github dependency's file(s) for " + spec.originalName + " from " + origin + " to " + destination);

			resolve(); //Ensure the destination actually exists, as Gradle doesn't really care either way
			return fileFactory.apply(destination);
		}

		@Override
		public Set<File> resolve(boolean transitive) {
			return resolve();
		}

		@Override
		public Set<File> resolve() {
			logger.info("Resolving Github dependency for " + spec.originalName + " from " + origin + " to " + destination);
			if (Files.notExists(destination.getParent())) throw new IllegalStateException("Dependency on " + origin + " lacks a destination");

			try {
				DownloadUtil.downloadIfChanged(new URL(origin), destination.toFile(), logger, true);
				return Collections.singleton(destination.toFile());
			} catch (MalformedURLException e) {
				throw new IllegalArgumentException("Invalid origin URL: " + origin, e);
			} catch (IOException e) {
				throw new RuntimeException("Unable to download " + spec.originalName + " from " + origin, e);
			}
		}
	}

	private Dependency createFrom(DownloadSpec spec, String origin) {
		Path destination = cache.resolve(spec.originalName + ".zip");
		createDirectory(destination.getParent());

		if (spec.forceFresh) {
			try {
				Files.deleteIfExists(destination);
			} catch (IOException e) {
				throw new UncheckedIOException("Unable to delete " + spec.originalName + " at " + destination, e);
			}
		}

		logger.debug("Creating new Github dependency for " + spec.originalName + " from " + origin + " to " + destination);
		return new GithubDependency(spec, origin, destination);
	}
}