/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.snowblower;

import net.minecraftforge.fart.api.IdentifierFixerConfig;
import net.minecraftforge.fart.api.Renamer;
import net.minecraftforge.fart.api.SignatureStripperConfig;
import net.minecraftforge.fart.api.SourceFixerConfig;
import net.minecraftforge.fart.api.Transformer;
import net.minecraftforge.snowblower.data.Config;
import net.minecraftforge.snowblower.data.Config.BranchSpec;
import net.minecraftforge.snowblower.data.Version;
import net.minecraftforge.snowblower.data.VersionManifestV2;
import net.minecraftforge.snowblower.data.VersionManifestV2.VersionInfo;
import net.minecraftforge.snowblower.tasks.MappingTask;
import net.minecraftforge.snowblower.tasks.MergeTask;
import net.minecraftforge.snowblower.tasks.enhance.EnhanceVersionTask;
import net.minecraftforge.snowblower.tasks.init.InitTask;
import net.minecraftforge.snowblower.util.Cache;
import net.minecraftforge.snowblower.util.DependencyHashCache;
import net.minecraftforge.snowblower.util.HashFunction;
import net.minecraftforge.snowblower.util.Tools;
import net.minecraftforge.snowblower.util.Util;
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
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class Generator implements AutoCloseable {
    public static final int COMMIT_BATCH_SIZE = 10;

    private final Path output;
    private final Path cache;
    private final Path extraMappings;
    private final DependencyHashCache depCache;
    private final Consumer<String> logger = System.out::println;

    private Git git;
    private String remoteName;
    private String branchName;
    private Config.BranchSpec branch;
    private boolean checkout;
    private boolean push;
    private boolean removeRemote;
    private boolean freshIfRequired;

    public Generator(Path output, Path cache, Path extraMappings, DependencyHashCache depCache) {
        this.output = output.toAbsolutePath().normalize();
        this.cache = cache.toAbsolutePath().normalize();
        this.extraMappings = extraMappings == null ? null : extraMappings.toAbsolutePath().normalize();
        this.depCache = depCache;
    }

    public Generator setup(String branchName, @Nullable URL remoteUrl, boolean checkout, boolean push, Config cfg, BranchSpec cliBranch,
            boolean fresh, boolean freshIfRequired) throws IOException, GitAPIException {
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
        this.checkout = checkout;
        this.push = push;
        this.freshIfRequired = freshIfRequired;

        branchName = setupBranch(branchName, fresh);

        var cfgBranch = cfg.branches() == null ? null : cfg.branches().get(branchName);
        if (cfgBranch == null) {
            if (cliBranch.start() == null && cliBranch.end() == null && cliBranch.versions() == null)
                throw new IllegalArgumentException("Unknown branch config: " + branchName);
            this.branch = cliBranch;
        } else {
            this.branch = new BranchSpec(
                cfgBranch.type(),
                cliBranch.start() == null ? cfgBranch.start() : cliBranch.start(),
                cliBranch.end() == null ? cfgBranch.end() : cliBranch.end(),
                cfgBranch.versions()
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
            this.logger.accept("Starting over existing branch " + branchName);
            deleteTemp = deleteBranch(branchName, currentBranch);
            exists = false;
            git.checkout().setOrphan(true).setName(branchName).call(); // Move to correctly named branch
        } else if (!fresh && this.checkout && this.remoteName != null && git.getRepository().resolve(this.remoteName + "/" + branchName) != null) {
            if (exists) {
                deleteTemp = deleteBranch(branchName, currentBranch);
            }

            var upstreamMode = this.removeRemote ? CreateBranchCommand.SetupUpstreamMode.NOTRACK : CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM;
            git.checkout().setCreateBranch(true).setName(branchName).setUpstreamMode(upstreamMode).setStartPoint(this.remoteName + "/" + branchName).call();
        } else if (!branchName.equals(currentBranch)) {
            git.checkout().setOrphan(!exists).setName(branchName).call(); // Move to correctly named branch
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

    private void setupRemote(@Nullable URL remoteUrl) throws GitAPIException {
        if (remoteUrl == null)
            return;

        URIish remoteFakeUri = new URIish(remoteUrl);
        String foundRemote = null;

        Set<String> remoteNames = new HashSet<>();

        for (RemoteConfig remoteConfig : this.git.remoteList().call()) {
            String currRemoteName = remoteConfig.getName();
            remoteNames.add(currRemoteName);

            if (foundRemote != null)
                continue;

            for (URIish fakeUri : remoteConfig.getURIs()) {
                if (fakeUri.equals(remoteFakeUri)) {
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

            this.git.remoteAdd().setName(foundRemote).setUri(remoteFakeUri).call();
            this.removeRemote = true;
        }

        this.remoteName = foundRemote;

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
        var init = new InitTask(this.logger, this.output, git);

        var manifest = VersionManifestV2.query();
        if (manifest.versions() == null)
            throw new IllegalStateException("Failed to find versions, manifest missing versions listing");

        var versions = Arrays.asList(manifest.versions());
        /* Sort the list by release time.. in case Mojang screwed it up?
            Arrays.stream(manifest.versions())
            .sorted((a,b) -> b.releaseTime().compareTo(a.releaseTime())) // b to a, so its in descending order
            .toList();
        */

        var targetVer = this.branch.end();
        // If we have explicit filters, apply them
        if (this.branch.versions() != null) {
            versions = versions.stream().filter(v -> this.branch.versions().contains(v.id())).toList();
            if (targetVer == null)
                targetVer = versions.get(0).id();
        } else {
            versions = versions.stream().filter(v -> !v.id().getType().isSpecial()).toList();
        }

        // Find the latest version from the manifest
        if (targetVer == null) {
            var lat = manifest.latest();
            if (lat == null)
                throw new IllegalStateException("Failed to determine latest version, Manifest does not contain latest entries");

            if (this.branch.type().equals("release"))
                targetVer = lat.release();
            else {
                var release = versions.stream().filter(e -> !lat.release().equals(e.id())).findFirst().orElse(null);
                var snapshot = versions.stream().filter(e -> !lat.snapshot().equals(e.id())).findFirst().orElse(null);
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

        var startVer = this.branch.start();
        if (startVer == null) {
            startVer = versions.get(versions.size() - 1).id();
        }

        // Validate the current metadata, and make initial commit if needed.
        if (!init.validate(startVer)) {
            if (this.freshIfRequired) {
                this.setupBranch(this.branchName, true);
                if (!init.validate(startVer))
                    throw new IllegalStateException("This should never happen! We deleted the branch, but it still failed verification.");
            } else {
                this.logger.accept("The starting commit on this branch does not have matching metadata.");
                this.logger.accept("This could be due to a different Snowblower version or a different starting Minecraft version.");
                this.logger.accept("Please choose a different branch with --branch or add the --start-over / --start-over-if-required flag and try again.");
                return;
            }
        }

        // Build our target list.
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < versions.size(); i++) {
            var ver = versions.get(i).id();
            if (ver.equals(targetVer))
                endIdx = i;
            if (ver.equals(startVer)) {
                startIdx = i;
                break;
            }
        }

        if (startIdx == -1 || endIdx == -1)
            throw new IllegalStateException("Could not find start and/or end version in version manifest (or they were out of order)");

        List<VersionInfo> toGenerate = new ArrayList<>(versions.subList(endIdx, startIdx + 1));
        if (this.branch.type().equals("release"))
            toGenerate.removeIf(v -> !v.type().equals("release"));
        // Reverse so it's in oldest first
        Collections.reverse(toGenerate);

        // Allow resuming by finding the last thing we generated
        var lastVersion = getLastVersion(git);
        if (lastVersion != null && !init.isInitCommit(lastVersion)) {
            boolean found = false;
            for (int i = 0; i < toGenerate.size(); i++) {
                if (toGenerate.get(i).id().toString().equals(lastVersion)) {
                    toGenerate = toGenerate.subList(i + 1, toGenerate.size());
                    found = true;
                    break;
                }
            }

            if (!found) // We should at least find the 'end' version if we're up to date.
                throw new IllegalStateException("Git is in invalid state, latest commit is " + lastVersion + " but it is not in our version list");
        }

        // Filter version list to only versions that have mappings
        toGenerate = findVersionsWithMappings(logger, toGenerate, cache, extraMappings);

        pushRemainingCommits(); // Push old commits in increments of 10 in case we didn't push them then

        var libs = this.cache.resolve("libraries");
        boolean generatedAny = !toGenerate.isEmpty();
        for (int x = 0; x < toGenerate.size(); x++) {
            var versionInfo = toGenerate.get(x);
            var versionCache = this.cache.resolve(versionInfo.id().toString());
            Files.createDirectories(versionCache);

            this.logger.accept("[" + (x + 1) + "/" + toGenerate.size() + "] Generating " + versionInfo.id());
            var version = Version.load(versionCache.resolve("version.json"));
            generate(logger, git, output, versionCache, libs, version, extraMappings, depCache);

            if (x % COMMIT_BATCH_SIZE == (COMMIT_BATCH_SIZE - 1)) { // Push every X versions
                attemptPush("Pushing " + COMMIT_BATCH_SIZE + " versions to remote.");
            }
        }

        if (!attemptPush(generatedAny ? "Pushing remaining versions to remote." : "Pushing versions to remote.")) {
            // If the push was up-to-date or skipped, check if no versions were processed and print.
            if (!generatedAny)
                this.logger.accept("No versions to process");
        }
    }

    private void pushRemainingCommits() throws GitAPIException, IOException {
        if (remoteName == null) return;
        final ObjectId remoteBranch = git.getRepository().resolve("refs/remotes/" + remoteName + "/" + branchName);
        if (remoteBranch == null) return;

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
                    .sorted(Comparator.<Map.Entry<Integer, List<RevCommit>>, Integer>comparing(Map.Entry::getKey).reversed()) // Make sure the lists are in the other way around (we want to push the oldest first as pushing a commit pushes all commits before it)
                    .map(Map.Entry::getValue)
                    .filter(Predicate.not(List::isEmpty)) // This shouldn't ever happen but just in case
                    .toList();
            // Iterate over the commits and push them
            for (final var notPushed : commits) {
                attemptPush("Pushed " + notPushed.size() + " old commits.", new RefSpec(notPushed.get(0).getId().getName() + ":refs/heads/" + this.branchName));
            }
        };

        boolean foundCommonAncestor = false;
        // Walk all commits on the remote branch (newest -> oldest)
        for (final RevCommit commit : git.log().add(remoteBranch).setMaxCount(Integer.MAX_VALUE).call()) {
            final int idx = ourCommits.indexOf(commit);
            if (idx == 0) break; // If it is the first commit, the branch is up-to-date
            // If we find the common ancestor that is NOT the first commit push
            else if (idx > 0) {
                pusher.push(idx);
                foundCommonAncestor = true;
                break; // We've found the common commit, break
            }
        }

        if (!foundCommonAncestor) { // We haven't found a common ancestor so let's force push all commits
            pusher.push(ourCommits.size());
        }
    }

    private boolean attemptPush(String message) throws GitAPIException {
        return attemptPush(message, new RefSpec(this.branchName + ":" + this.branchName));
    }

    private boolean attemptPush(String message, RefSpec spec) throws GitAPIException {
        if (!this.push || this.remoteName == null)
            return false;

        this.logger.accept(message);

        final var result = this.git.push()
                .setRemote(this.remoteName)
                .setForce(true)
                .setRefSpecs(spec)
                .call();
        RemoteRefUpdate remoteRefUpdate = StreamSupport.stream(result.spliterator(), false)
                .map(res -> res.getRemoteUpdate("refs/heads/" + this.branchName))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Attempted to push to remote, but failed. Reason unknown."));

        this.logger.accept(switch (remoteRefUpdate.getStatus()) {
            case OK -> "  Successfully pushed to remote.";
            case UP_TO_DATE -> "  Attempted to push to remote, but local branch was up-to-date.";
            default -> throw new IllegalStateException("Could not push to remote: status: " + remoteRefUpdate.getStatus() + ", message: " + remoteRefUpdate.getMessage());
        });

        return remoteRefUpdate.getStatus() == RemoteRefUpdate.Status.OK;
    }

    private static List<VersionInfo> findVersionsWithMappings(Consumer<String> logger, List<VersionInfo> versions, Path cache, Path extraMappings) throws IOException {
        logger.accept("Downloading version manifests");
        List<VersionInfo> ret = new ArrayList<>();
        for (var ver : versions) {
            // Download the version json file.
            var json = cache.resolve(ver.id().toString()).resolve("version.json");
            if (!Files.exists(json) || !HashFunction.SHA1.hash(json).equals(ver.sha1()))
                Util.downloadFile(logger, json, ver.url(), ver.sha1());

            var dls = Version.load(json).downloads();
            if (dls.containsKey("client_mappings") && dls.containsKey("server_mappings"))
                ret.add(ver);
            else if (extraMappings != null) {
                //TODO: Convert extraMappings into a object with 'boolean hasMapping(version, side)' and 'Path getMapping(version, size)'
                var root = extraMappings.resolve(ver.type()).resolve(ver.id().toString()).resolve("maps");
                var client = root.resolve("client.txt");
                var server = root.resolve("server.txt");
                if (Files.exists(client) && Files.exists(server))
                    ret.add(ver);
            }

        }
        return ret;
    }

    /**
     * Gets the last 'automated' commit for the current branch.
     * This allows us to know what version to resume from.
     * Why we allow manual commits? I have no idea. Shrimp wanted it.
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

    private void generate(Consumer<String> logger, Git git, Path output, Path cache, Path libCache, Version version, Path extraMappings, DependencyHashCache depCache) throws IOException, GitAPIException {
        var mappings = MappingTask.getMergedMappings(logger, cache, version, extraMappings);
        if (mappings == null)
            return;

        var joined = MergeTask.getJoinedJar(logger, cache, version, mappings, depCache);
        var libs = getLibraries(libCache, version);
        var renamed = getRenamedJar(cache, joined, mappings, libCache, libs);
        var decomped = getDecompiledJar(cache, renamed, libCache, libs);

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
            var root = zipFs.getPath("/");
            try (Stream<Path> walker = Files.walk(root)) {
                walker.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        var relative = root.relativize(p);
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

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        var enhanced = EnhanceVersionTask.enhance(output, version);
        existingFiles.removeAll(enhanced);
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
            this.logger.accept("  Committing files");
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

    private List<Path> getLibraries(Path cache, Version version) throws IOException {
        if (version.libraries() == null)
            return Collections.emptyList();

        var ret = new ArrayList<Path>();
        for (var lib : version.libraries()) {
            if (lib.downloads() == null || !lib.downloads().containsKey("artifact"))
                continue;
            var dl = lib.downloads().get("artifact");
            var target = cache.resolve(dl.path());

            // In theory we should check the hash matches the hash in the json,
            // but I don't think this will ever be an issue
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Util.downloadFile(this.logger, target, dl.url(), dl.sha1());
            }

            ret.add(target);
        }
        return ret;
    }

    private Path getRenamedJar(Path cache, Path joined, Path mappings, Path libCache, List<Path> libs) throws IOException {
        var key = new Cache()
            .put(Tools.FART, this.depCache)
            .put("joined", joined)
            .put("map", mappings);

        for (var lib : libs) {
            var relative = libCache.relativize(lib);
            key.put(relative.toString(), lib);
        }

        var keyF = cache.resolve("joined-renamed.jar.cache");
        var ret = cache.resolve("joined-renamed.jar");

        if (!Files.exists(ret) || !key.isValid(keyF)) {
            this.logger.accept("  Renaming joined jar");
            var builder = Renamer.builder()
                .input(joined.toFile())
                .output(ret.toFile())
                .map(mappings.toFile())
                .add(Transformer.parameterAnnotationFixerFactory())
                .add(Transformer.identifierFixerFactory(IdentifierFixerConfig.ALL))
                .add(Transformer.sourceFixerFactory(SourceFixerConfig.JAVA))
                .add(Transformer.recordFixerFactory())
                .add(Transformer.signatureStripperFactory(SignatureStripperConfig.ALL))
                .logger(s -> {});
            libs.forEach(l -> builder.lib(l.toFile()));
            builder.build().run();

            key.write(keyF);
        }

        return ret;
    }

    private Path getDecompiledJar(Path cache, Path renamed, Path libCache, List<Path> libs) throws IOException {
        var key = new Cache()
            .put(Tools.FORGEFLOWER, this.depCache)
            .put("renamed", renamed);

        String[] decompileArgs = new String[]{"-din=1", "-rbr=1", "-dgs=1", "-asc=1", "-rsy=1", "-iec=1", "-jvn=1", "-jpr=1", "-isl=0", "-iib=1", "-bsm=1", "-dcl=1"};
        key.put("decompileArgs", String.join(" ", decompileArgs));

        for (var lib : libs) {
            var relative = libCache.relativize(lib);
            key.put(relative.toString(), lib);
        }

        var keyF = cache.resolve("joined-decompiled.jar.cache");
        var ret = cache.resolve("joined-decompiled.jar");

        if (!Files.exists(ret) || !key.isValid(keyF)) {
            this.logger.accept("  Decompiling joined-renamed.jar");
            var cfg = cache.resolve("joined-libraries.cfg");
            Util.writeLines(cfg, libs.stream().map(l -> "-e=" + l.toString()).toArray(String[]::new));

            ConsoleDecompiler.main(Stream.concat(Arrays.stream(decompileArgs), Stream.of(
                "-log=ERROR", // IFernflowerLogger.Severity
                "-cfg", cfg.toString(),
                renamed.toString(),
                ret.toString()
            )).toArray(String[]::new));

            key.write(keyF);
        }
        return ret;
    }

    @Override
    public void close() throws Exception {
        if (this.git != null)
            this.git.close();
    }
}
