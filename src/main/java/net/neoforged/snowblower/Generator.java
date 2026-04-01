/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower;

import net.neoforged.snowblower.data.Config;
import net.neoforged.snowblower.data.Config.BranchSpec;
import net.neoforged.snowblower.data.MinecraftVersion;
import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.data.VersionManifestV2;
import net.neoforged.snowblower.data.VersionManifestV2.VersionInfo;
import net.neoforged.snowblower.github.GitHubActions;
import net.neoforged.snowblower.tasks.DecompileTask;
import net.neoforged.snowblower.tasks.MappingTask;
import net.neoforged.snowblower.tasks.MergeRemapTask;
import net.neoforged.snowblower.tasks.enhance.EnhanceVersionTask;
import net.neoforged.snowblower.tasks.init.InitTask;
import net.neoforged.snowblower.util.ArtifactDiscoverer;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.HashFunction;
import net.neoforged.snowblower.util.UnobfuscatedVersions;
import net.neoforged.snowblower.util.Util;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class Generator implements AutoCloseable {
    /**
     * Tracks the current version id of the generator; incremented anytime a change is made to output generation
     * so that {@code --start-over-if-required} can detect it and start over.
     */
    // If making changes to generation that affect the output (e.g., updating the decompiler or adding/removing decompiler args), increment this number.
    public static final int VERSION_ID = 2;
    public static final int COMMIT_BATCH_SIZE = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(Generator.class);

    private final Path output;
    private final Path cache;
    private final Path extraMappings;
    private final DependencyHashCache depCache;
    private final List<String> includes;
    private final List<String> excludes;

    private Git git;
    private String remoteName;
    private String branchName;
    private Config.BranchSpec branch;
    private boolean checkout;
    private boolean push;
    private boolean removeRemote;
    private boolean startOver;
    private boolean startOverIfRequired;
    private boolean partialCache;
    private boolean createdNewBranch;
    private MinecraftVersion startVer;
    private MinecraftVersion targetVer;

    public Generator(Path output, Path cache, Path extraMappings, DependencyHashCache depCache, List<String> includes, List<String> excludes) {
        this.output = output.toAbsolutePath().normalize();
        this.cache = cache.toAbsolutePath().normalize();
        this.extraMappings = extraMappings == null ? null : extraMappings.toAbsolutePath().normalize();
        this.depCache = depCache;
        this.includes = includes;
        this.excludes = new ArrayList<>(excludes);
        // Always exclude the manifest (it's included when using ProcessMinecraftJar from InstallerTools)
        this.excludes.add("META-INF/MANIFEST.MF");
    }

    public Generator setup(String branchName, @Nullable URIish remoteUrl, boolean checkout, boolean push, Config cfg, BranchSpec cliBranch,
            boolean startOver, boolean startOverIfRequired, boolean partialCache) throws IOException, GitAPIException {
        try {
            this.git = Git.open(this.output.toFile());
        } catch (RepositoryNotFoundException e) { // I wish there was a better way to detect if it exists/is init
            if (branchName == null)
                branchName = "release";
            Util.deleteRecursive(this.output);
            this.git = Git.init().setDirectory(this.output.toFile()).setInitialBranch(branchName).call();
        }

        setupRemote(remoteUrl);
        this.branchName = branchName;
        LOGGER.info("Branch: {}", branchName);
        this.checkout = checkout;
        this.push = push;
        this.startOver = startOver;
        this.startOverIfRequired = startOverIfRequired;
        this.partialCache = partialCache;
        this.createdNewBranch = false;

        branchName = setupBranch(branchName, startOver);

        var cfgBranch = cfg.branches() == null ? null : cfg.branches().get(branchName);
        if (cfgBranch == null) {
            if (cliBranch.start() == null && cliBranch.end() == null)
                throw new IllegalArgumentException("Unknown branch config: " + branchName);
            this.branch = cliBranch;
        } else {
            this.branch = new BranchSpec(
                cfgBranch.type(),
                cliBranch.start() == null ? cfgBranch.start() : cliBranch.start(),
                cliBranch.end() == null ? cfgBranch.end() : cliBranch.end(),
                cfgBranch.versions(),
                cfgBranch.includeVersions(),
                cfgBranch.excludeVersions()
            );
        }

        return this;
    }

    private String setupBranch(@Nullable String branchName, boolean fresh) throws IOException, GitAPIException {
        // Find the current branch in case the command line didn't specify one.
        var currentBranch = git.getRepository().getBranch();
        if (branchName == null) {
            if (currentBranch == null)
                throw new IllegalStateException("Git repository has no HEAD reference");
            branchName = currentBranch;
        }

        boolean exists = git.getRepository().resolve(branchName) != null;
        boolean deleteTemp = false;
        if (fresh && exists) {
            this.createdNewBranch = true;
            if (!this.startOver && this.startOverIfRequired) {
                LOGGER.info("Detected incompatible changes, starting over existing branch \"{}\"", branchName);
            } else {
                LOGGER.info("Starting over existing branch \"{}\"", branchName);
            }
            deleteTemp = deleteBranch(branchName, currentBranch);
            exists = false;
            git.checkout().setOrphan(true).setName(branchName).call(); // Move to correctly named branch
        } else if (!fresh && this.checkout && this.remoteName != null && git.getRepository().resolve(this.remoteName + "/" + branchName) != null) {
            if (exists) {
                deleteTemp = deleteBranch(branchName, currentBranch);
            }

            LOGGER.info("Checking out remote branch \"{}/{}\"", this.remoteName, branchName);
            var upstreamMode = this.removeRemote ? CreateBranchCommand.SetupUpstreamMode.NOTRACK : CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM;
            git.checkout().setCreateBranch(true).setName(branchName).setUpstreamMode(upstreamMode).setStartPoint(this.remoteName + "/" + branchName).call();
        } else if (!branchName.equals(currentBranch)) {
            this.createdNewBranch = !exists;
            LOGGER.info("Checking out {} local branch \"{}\"", exists ? "existing" : "new", branchName);
            git.checkout().setOrphan(!exists).setName(branchName).call(); // Move to correctly named branch
        } else {
            LOGGER.info("Current branch already set to local branch \"{}\"", branchName);
        }

        git.reset().setMode(ResetType.HARD).call();
        git.clean().setCleanDirectories(true).call();

        if (deleteTemp)
            git.branchDelete().setBranchNames("orphan_temp").setForce(true).call(); // Cleanup temp branch

        return branchName;
    }

    private boolean deleteBranch(String branchName, String currentBranch) throws GitAPIException {
        boolean deleteTemp = false;

        if (branchName.equals(currentBranch)) {
            git.checkout().setOrphan(true).setName("orphan_temp").call(); // Move to temp branch so we can delete existing one
            deleteTemp = true;
        }

        git.branchDelete().setBranchNames(branchName).setForce(true).call(); // Delete existing branch

        return deleteTemp;
    }

    private void setupRemote(@Nullable URIish remoteUrl) throws GitAPIException {
        if (remoteUrl == null)
            return;

        String foundRemote = null;

        Set<String> remoteNames = new HashSet<>();

        for (RemoteConfig remoteConfig : this.git.remoteList().call()) {
            String currRemoteName = remoteConfig.getName();
            remoteNames.add(currRemoteName);

            if (foundRemote != null)
                continue;

            for (URIish fakeUri : remoteConfig.getURIs()) {
                if (fakeUri.equals(remoteUrl)) {
                    foundRemote = currRemoteName;
                    break;
                }
            }
        }

        if (foundRemote == null) {
            int i = 0;
            foundRemote = "origin";
            while (remoteNames.contains(foundRemote)) {
                i++;
                foundRemote = "origin" + i;
            }

            this.git.remoteAdd().setName(foundRemote).setUri(remoteUrl).call();
            this.removeRemote = true;
        }

        this.remoteName = foundRemote;

        // TODO: The text progress monitor on stdout tends to mess with the logs; should we represent this data another way or turn it off?
        this.git.fetch().setRemote(remoteName).setProgressMonitor(new TextProgressMonitor(new OutputStreamWriter(System.out))).call();
    }

    public void run() throws IOException, GitAPIException {
        try {
            runInternal();
        } finally {
            if (this.removeRemote && this.remoteName != null) {
                this.git.remoteRemove().setRemoteName(this.remoteName).call();
            }
        }
    }

    private void runInternal() throws IOException, GitAPIException {
        var manifest = VersionManifestV2.query();
        if (manifest.versions() == null)
            throw new IllegalStateException("Failed to find versions, manifest missing versions listing");

        var versions = new ArrayList<>(Arrays.asList(manifest.versions()));
        // The version manifest defaults to sorting versions in descending order by release time, so reverse it to ascending order
        Collections.reverse(versions);
        UnobfuscatedVersions.injectUnobfuscatedVersions(versions);

        // Holds version infos filtered by the branch configuration (branch type, included/excluded versions, etc.)
        // This method also sets up the start and end versions.
        List<VersionInfo> filteredVersions = this.filterAndSetVersions(versions, manifest);

        // Validate the current metadata, and make initial commit if needed.
        if (!InitTask.validateOrInit(this.output, git, startVer) && this.startOverIfRequired("The starting commit on this branch does not have matching metadata."
                + " This could be due to a different Snowblower version or a different starting Minecraft version."))
            return;

        int[] range = this.getStartEndIndices(versions, filteredVersions);
        if (range == null)
            return;
        List<VersionInfo> toGenerate = new ArrayList<>(filteredVersions.subList(range[0], range[1] + 1));

        // Allow resuming by finding the last thing we generated
        int skipCount = this.getSkipCount(versions, filteredVersions, toGenerate, range[0]);
        if (skipCount == -1) {
            return;
        } else if (skipCount != 0) {
            toGenerate = toGenerate.subList(skipCount, toGenerate.size());
        }

        // Filter version list to only versions that have mappings
        toGenerate = findVersionsWithMappings(toGenerate, cache, extraMappings);

        pushRemainingCommits(); // Push old commits in increments of 10 in case we didn't push them then

        var libs = this.cache.resolve("libraries");

        ArtifactDiscoverer.downloadArtifacts(cache, libs, extraMappings, toGenerate, partialCache);

        LOGGER.info("Generating {} versions: {}", toGenerate.size(), toGenerate.stream().map(VersionInfo::id).toList());

        boolean generatedAny = !toGenerate.isEmpty();
        for (int x = 0; x < toGenerate.size(); x++) {
            var versionInfo = toGenerate.get(x);
            var versionCache = this.cache.resolve(versionInfo.id().toString());
            Files.createDirectories(versionCache);

            try {
                GitHubActions.logStartGroup(versionInfo.id());
                LOGGER.info("[{}, {}] Generating {}", x + 1, toGenerate.size(), versionInfo.id());
                MDC.put("mcver", " [" + versionInfo.id() + "]");

                var version = Version.load(versionCache.resolve("version.json"));
                generate(versionCache, libs, version);
            } finally {
                GitHubActions.logEndGroup();
                MDC.remove("mcver");
            }

            if (x % COMMIT_BATCH_SIZE == (COMMIT_BATCH_SIZE - 1)) { // Push every X versions
                attemptPush("Pushing " + COMMIT_BATCH_SIZE + " versions to remote.");
            }
        }

        if (!attemptPush(generatedAny ? "Pushing remaining versions to remote." : "Pushing versions to remote.")) {
            // If the push was up-to-date or skipped, check if no versions were processed and print.
            if (!generatedAny)
                LOGGER.info("No versions to process");
        }
    }

    private List<VersionInfo> filterAndSetVersions(ArrayList<VersionInfo> versions, VersionManifestV2 manifest) {
        List<VersionInfo> filteredVersions = new ArrayList<>(versions);

        this.targetVer = this.branch.end();
        // If we have explicit filters, apply them
        if (this.branch.versions() != null) {
            filteredVersions.removeIf(v -> !this.branch.versions().contains(v.id()));
            if (targetVer == null)
                targetVer = filteredVersions.getLast().id();
        } else {
            var exclude = filteredVersions.stream().map(VersionInfo::id).filter(id -> id.type().isSpecial()).collect(Collectors.toSet());
            exclude.addAll(UnobfuscatedVersions.getVersionsToExclude());
            if (this.branch.includeVersions() != null)
                this.branch.includeVersions().forEach(exclude::remove);
            if (this.branch.excludeVersions() != null)
                exclude.addAll(this.branch.excludeVersions());

            filteredVersions.removeIf(v -> exclude.contains(v.id()));
        }
        if (this.branch.type().equals("release"))
            filteredVersions.removeIf(v -> !v.type().equals("release"));

        this.startVer = this.branch.start();
        if (this.startVer == null)
            this.startVer = filteredVersions.getFirst().id();

        // Find the latest version from the manifest
        if (targetVer == null) {
            var lat = manifest.latest();
            if (lat == null)
                throw new IllegalStateException("Failed to determine latest version, Manifest does not contain latest entries");

            if (this.branch.type().equals("release"))
                targetVer = lat.release();
            else {
                var release = filteredVersions.stream().filter(e -> lat.release().equals(e.id())).findFirst().orElse(null);
                var snapshot = filteredVersions.stream().filter(e -> lat.snapshot().equals(e.id())).findFirst().orElse(null);
                if (release == null && snapshot == null)
                    throw new IllegalStateException("Failed to find latest, manifest specified " + lat.release() + " and " + lat.snapshot() + " and both are missing");
                if (release == null)
                    targetVer = snapshot.id();
                else if (snapshot == null)
                    targetVer = release.id();
                else
                    targetVer = snapshot.releaseTime().compareTo(release.releaseTime()) > 0 ? snapshot.id() : release.id();
            }
        }

        LOGGER.info("Start version: {}", startVer);
        LOGGER.info("End version: {}", targetVer);

        return filteredVersions;
    }

    private int[] getStartEndIndices(List<VersionInfo> versions, List<VersionInfo> filteredVersions) {
        // Build our target list.
        int startIdx = -1;
        int endIdx = -1;
        for (int i = filteredVersions.size() - 1; i >= 0; i--) {
            var ver = filteredVersions.get(i).id();
            if (ver.equals(startVer)) {
                startIdx = i;
                if (endIdx != -1)
                    break;
            }
            if (ver.equals(targetVer)) {
                endIdx = i;
                if (startIdx != -1)
                    break;
            }
        }

        if (startIdx == -1) {
            boolean startVerExists = false;
            for (var ver : versions) {
                if (startVer.equals(ver.id())) {
                    startVerExists = true;
                    break;
                }
            }

            if (startVerExists) {
                LOGGER.error("Start version \"{}\" is not included by the current branch configuration. Please change the included versions list or branch and try again.", startVer);
            } else {
                LOGGER.error("Start version \"{}\" not found in version manifest.", startVer);
            }
            return null;
        }

        if (endIdx == -1) {
            boolean endVerExists = false;
            for (var ver : versions) {
                if (targetVer.equals(ver.id())) {
                    endVerExists = true;
                    break;
                }
            }

            if (endVerExists) {
                LOGGER.error("End version \"{}\" is not included by the current branch configuration. Please change the included versions list or branch and try again.", startVer);
            } else {
                LOGGER.error("End version \"{}\" not found in version manifest.", startVer);
            }
            return null;
        }

        if (startIdx > endIdx) {
            LOGGER.error("Start version of \"{}\" is newer than end version of \"{}\" according to the version manifest.", startVer, targetVer);
            return null;
        }

        return new int[]{startIdx, endIdx};
    }

    private int getSkipCount(List<VersionInfo> versions, List<VersionInfo> filteredVersions, List<VersionInfo> toGenerate, int startIdx) throws GitAPIException, IOException {
        if (this.createdNewBranch)
            return 0;

        var lastVersion = getLastVersion(this.git);
        if (lastVersion == null || InitTask.isInitCommit(lastVersion))
            return 0;

        LOGGER.info("Found version of latest commit: {}", lastVersion);

        for (int i = 0; i < toGenerate.size(); i++) {
            if (toGenerate.get(i).id().toString().equals(lastVersion)) {
                return i + 1;
            }
        }

        // Fallback: If we didn't find the last committed version in the version list to generate, check if:
        // - it's missing entirely (error/start over accordingly),
        // - it got filtered out of the version list to generate (error/start over accordingly), or
        // - it's newer than the target version (then skip generation completely)
        String errorMsg = "Cannot resume generation. Version of latest commit is \"{}\", {}";

        boolean lastVersionExists = false;
        for (var ver : versions) {
            if (lastVersion.equals(ver.id().toString())) {
                lastVersionExists = true;
                break;
            }
        }

        if (!lastVersionExists && this.startOverIfRequired(errorMsg, lastVersion, "but it is not in the version manifest?"))
            return -1;

        int lastIdx = -1;
        for (int i = 0; i < filteredVersions.size(); i++) {
            VersionInfo ver = filteredVersions.get(i);
            if (lastVersion.equals(ver.id().toString())) {
                lastIdx = i;
                break;
            }
        }

        if (lastIdx == -1) {
            if (this.startOverIfRequired(errorMsg, lastVersion, "but it is not included by the current branch configuration."))
                return -1;

            return 0;
        } else if (lastIdx < startIdx) {
            if (this.startOverIfRequired(errorMsg, lastVersion, "which is older than the start version."))
                return -1;

            return 0;
        }

        // Since startIdx <= endIdx, if we got here, we know that lastIdx > endIdx (it's newer than the target version)
        // since it wasn't found in toGenerate, and that it's in the filtered version list, so skip generation.
        return toGenerate.size();
    }

    private void pushRemainingCommits() throws GitAPIException, IOException {
        if (!this.push || this.remoteName == null || this.createdNewBranch)
            return;
        final ObjectId remoteBranch = git.getRepository().resolve("refs/remotes/" + remoteName + "/" + branchName);
        if (remoteBranch == null)
            return;

        // The commits go newer -> older (e.g. 0 -> first, 100 -> last)
        final List<RevCommit> ourCommits = new ArrayList<>();
        git.log().setMaxCount(Integer.MAX_VALUE).call().forEach(ourCommits::add);

        @FunctionalInterface
        interface Pusher {
            void push(int endIndex) throws GitAPIException;
        }

        final Pusher pusher = idx -> {
            // We want to push the newest X commits so create a sublist of those commits starting from the newest one
            final var commits = ourCommits.subList(0, idx).stream()
                    .collect(Util.partitionEvery(COMMIT_BATCH_SIZE)) // Partition those into lists of maximum 10 commits so we properly batch
                    .entrySet().stream() // Stream the entry set
                    // Make sure the lists are in the other way around (we want to push the oldest first as pushing a commit pushes all commits before it)
                    .sorted(Map.Entry.<Integer, List<RevCommit>>comparingByKey().reversed())
                    .map(Map.Entry::getValue)
                    .filter(Predicate.not(List::isEmpty)) // This shouldn't ever happen but just in case
                    .toList();
            // Iterate over the commits and push them
            for (final var notPushed : commits) {
                attemptPush("Pushing " + notPushed.size() + " old commits", new RefSpec(notPushed.get(0).getId().getName() + ":refs/heads/" + this.branchName));
            }
        };

        boolean foundCommonAncestor = false;
        // Walk all commits on the remote branch (newest -> oldest)
        for (final RevCommit commit : git.log().add(remoteBranch).setMaxCount(Integer.MAX_VALUE).call()) {
            final int idx = ourCommits.indexOf(commit);
            if (idx == 0)
                return; // If it is the first commit, the branch is up-to-date

            // If we find the common ancestor that is NOT the first commit, push
            if (idx > 0) {
                pusher.push(idx);
                foundCommonAncestor = true;
                break; // We've found the common commit, break
            }
        }

        if (!foundCommonAncestor) {
            // We haven't found a common ancestor so let's force push all commits
            LOGGER.info("Could not find common ancestor commit; pushing all {} old commits", ourCommits.size());
            pusher.push(ourCommits.size());
        }
    }

    private boolean attemptPush(String message) throws GitAPIException {
        return attemptPush(message, new RefSpec(this.branchName + ":" + this.branchName));
    }

    private boolean attemptPush(String message, RefSpec spec) throws GitAPIException {
        if (!this.push || this.remoteName == null)
            return false;

        // TODO: refactor the logging statements here to be... better (called outside of this method)
        LOGGER.info(message);

        final var result = this.git.push()
                .setRemote(this.remoteName)
                .setForce(true)
                .setRefSpecs(spec)
                .call();
        RemoteRefUpdate remoteRefUpdate = StreamSupport.stream(result.spliterator(), false)
                .map(res -> res.getRemoteUpdate("refs/heads/" + this.branchName))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Attempted to force push to remote, but failed. Reason unknown."));

        LOGGER.info(switch (remoteRefUpdate.getStatus()) {
            case OK -> "  Successfully force pushed to remote.";
            case UP_TO_DATE -> "  Attempted to force push to remote, but local branch was up-to-date.";
            default -> throw new IllegalStateException("Could not force push to remote: status: " + remoteRefUpdate.getStatus() + ", message: " + remoteRefUpdate.getMessage());
        });

        return remoteRefUpdate.getStatus() == RemoteRefUpdate.Status.OK;
    }

    private static List<VersionInfo> findVersionsWithMappings(List<VersionInfo> versions, Path cache, Path extraMappings) throws IOException {
        LOGGER.info("Downloading version manifests");
        GitHubActions.logStartGroup("Downloading version manifests");

        List<VersionInfo> ret = new ArrayList<>();
        for (var ver : versions) {
            // Download the version json file.
            var json = cache.resolve(ver.id().toString()).resolve("version.json");
            if (!Files.exists(json) || !HashFunction.SHA1.hash(json).equals(ver.sha1())) {
                Util.downloadFile(json, ver.url(), ver.sha1());
            }

            Version fullVersion = Version.load(json);
            var dls = fullVersion.downloads();
            if (dls.containsKey("client_mappings") && dls.containsKey("server_mappings")) {
                ret.add(ver);
            } else if (extraMappings != null) {
                //TODO: Convert extraMappings into a object with 'boolean hasMapping(version, side)' and 'Path getMapping(version, size)'
                var root = extraMappings.resolve(ver.type()).resolve(ver.id().toString()).resolve("maps");
                var client = root.resolve("client.txt");
                var server = root.resolve("server.txt");
                if (Files.exists(client) && Files.exists(server))
                    ret.add(ver);
            } else if (fullVersion.isUnobfuscated()) {
                ret.add(ver);
            }
        }

        GitHubActions.logEndGroup();

        return ret;
    }

    /**
     * Gets the last automated commit for the current branch (i.e., committed by the provided/default committer account).
     * This allows us to know what version to resume from.
     */
    private static String getLastVersion(Git git) throws IOException, GitAPIException {
        var headId = git.getRepository().resolve(Constants.HEAD);
        if (headId == null)
            return null;

        for (var commit : git.log().add(headId).call()) {
            if (commit.getCommitterIdent().getName().equals(Util.COMMITTER.getName()))
                return commit.getShortMessage();
        }
        return null;
    }

    private void generate(Path cache, Path libCache, Version version) throws IOException, GitAPIException {
        Path decomped = DecompileTask.checkPartialCache(cache, version, depCache, partialCache);

        if (decomped == null) {
            var mappings = MappingTask.getMergedMappings(cache, version);
            if (!version.isUnobfuscated() && mappings == null)
                return;

            var joined = MergeRemapTask.getJoinedRemappedJar(cache, version, mappings, depCache, partialCache);
            var libs = getLibraries(libCache, version);
            decomped = DecompileTask.getDecompiledJar(cache, version, joined, libCache, libs, depCache);
        }

        Path src = output.resolve("src").resolve("main");
        Set<Path> existingFiles;
        if (Files.exists(src)) {
            try (Stream<Path> walker = Files.walk(src)) {
                existingFiles = walker.filter(Files::isRegularFile).collect(Collectors.toSet());
            }
        } else {
            existingFiles = new HashSet<>();
        }
        var java = src.resolve("java");
        var resources = src.resolve("resources");
        List<Path> added = new ArrayList<>();
        List<Path> removed = new ArrayList<>();

        try (FileSystem zipFs = FileSystems.newFileSystem(decomped)) {
            var matcher = createMatcher(zipFs, includes, excludes);
            var root = zipFs.getPath("/");
            try (Stream<Path> walker = Files.walk(root)) {
                Iterable<Path> iterable = () -> walker.filter(Files::isRegularFile).iterator();
                for (Path p : iterable) {
                    var relative = root.relativize(p);
                    if (!matcher.matches(relative))
                        continue;

                    var target = (p.toString().endsWith(".java") ? java : resources).resolve(relative.toString());

                    if (existingFiles.remove(target)) {
                        boolean copy;
                        Path realPath = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
                        if (!realPath.toString().equals(target.toString())) {
                            Files.delete(realPath);
                            removed.add(realPath);
                            added.add(target);
                            copy = true;
                        } else {
                            var existing = HashFunction.MD5.hash(target);
                            var created = HashFunction.MD5.hash(p);
                            copy = !existing.equals(created);
                        }

                        if (copy) {
                            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                            added.add(target);
                        }
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        added.add(target);
                    }
                }
            }
        }

        var enhanced = EnhanceVersionTask.enhance(output, version);
        enhanced.forEach(existingFiles::remove);
        added.addAll(enhanced);

        existingFiles.stream().sorted().forEach(p -> {
            try {
                Files.delete(p);
                removed.add(p);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        if (!added.isEmpty() || !removed.isEmpty()) {
            LOGGER.debug("Committing files");
            Function<Path, String> convert = p -> output.relativize(p).toString().replace('\\', '/'); // JGit requires / even on windows

            if (!added.isEmpty()) {
                var add = git.add();
                added.stream().map(convert).forEach(add::addFilepattern);
                add.call();
            }
            if (!removed.isEmpty()) {
                var rm = git.rm();
                removed.stream().map(convert).forEach(rm::addFilepattern);
                rm.call();
            }
            Util.commit(git, version.id().toString(), version.releaseTime());
        }
    }

    /**
     * Returns {@code true} if an error occurred, either the user did not setup {@code --start-over-if-required}
     * or the initial commit task failed to validate/commit after recreating the branch.
     */
    private boolean startOverIfRequired(String errorMsg, Object... errorMsgArgs) throws IOException, GitAPIException {
        if (this.startOverIfRequired) {
            this.setupBranch(this.branchName, true);

            if (!InitTask.validateOrInit(this.output, this.git, this.startVer)) {
                LOGGER.error("Initial commit failed verification after restarting branch. This should never happen!");
                return true;
            }

            return false;
        }

        LOGGER.error(errorMsg, errorMsgArgs);
        LOGGER.error("Please choose a different branch with --branch or add the --start-over / --start-over-if-required flag and try again.");
        return true;
    }

    private List<Path> getLibraries(Path cache, Version version) {
        if (version.libraries() == null)
            return Collections.emptyList();

        var ret = new ArrayList<Path>();
        for (var lib : version.libraries()) {
            if (lib.downloads() == null || !lib.downloads().containsKey("artifact"))
                continue;
            var dl = lib.downloads().get("artifact");
            var target = cache.resolve(dl.path());

            if (!Files.exists(target))
                continue; // Downloaded ahead of time by ArtifactDiscoverer

            ret.add(target);
        }

        return ret;
    }

    private static PathMatcher createMatcher(FileSystem fs, List<String> includes, List<String> excludes) {
        final PathMatcher matcher;
        if (!includes.isEmpty()) {
            // Only include those matching the inclusive patterns
            matcher = createMatcher(fs, includes);
        } else {
            // Include everything
            matcher = path -> true;
        }

        if (!excludes.isEmpty()) {
            // Exclude those matching the exclusive patterns
            var excludesMatcher = createMatcher(fs, excludes);
            return path -> matcher.matches(path) && !excludesMatcher.matches(path);
        } else {
            // Exclude nothing
            return matcher;
        }
    }

    private static PathMatcher createMatcher(FileSystem fs, List<String> globPatterns) {
        if (globPatterns.isEmpty()) {
            return path -> false;
        }
        if (globPatterns.size() == 1) {
            return fs.getPathMatcher("glob:" + globPatterns.get(0));
        }
        final List<PathMatcher> matchers = new ArrayList<>(globPatterns.size());
        for (String globPattern : globPatterns) {
            matchers.add(fs.getPathMatcher("glob:" + globPattern));
        }
        return path -> {
            for (PathMatcher matcher : matchers) {
                if (matcher.matches(path))
                    return true;
            }
            return false;
        };
    }

    @Override
    public void close() throws Exception {
        if (this.git != null)
            this.git.close();
    }
}
