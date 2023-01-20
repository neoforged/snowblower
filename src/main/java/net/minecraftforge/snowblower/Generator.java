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
import net.minecraftforge.mergetool.AnnotationVersion;
import net.minecraftforge.mergetool.Merger;
import net.minecraftforge.snowblower.data.Version;
import net.minecraftforge.snowblower.data.VersionManifestV2;
import net.minecraftforge.snowblower.data.VersionManifestV2.VersionInfo;
import net.minecraftforge.snowblower.tasks.init.InitTask;
import net.minecraftforge.snowblower.util.Cache;
import net.minecraftforge.snowblower.util.DependencyHashCache;
import net.minecraftforge.snowblower.util.HashFunction;
import net.minecraftforge.snowblower.util.Tools;
import net.minecraftforge.snowblower.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.MinecraftVersion;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Generator {
    private final Path output;
    private final Path cache;
    private final Path extraMappings;
    private final boolean startOver;
    private final MinecraftVersion startVer;
    private final MinecraftVersion targetVer;
    private final String branchName;
    private final boolean releasesOnly;
    private final DependencyHashCache depCache;
    private final Consumer<String> logger;

    public Generator(Path output, Path cache, Path extraMappings, MinecraftVersion startVer, MinecraftVersion targetVer, String branchName, boolean startOver, boolean releasesOnly,
            DependencyHashCache depCache, Consumer<String> logger) {
        this.output = output;
        this.cache = cache;
        this.extraMappings = extraMappings;
        this.startOver = startOver;
        this.startVer = startVer;
        this.targetVer = targetVer;
        this.branchName = branchName;
        this.releasesOnly = releasesOnly;
        this.depCache = depCache;
        this.logger = logger;
    }

    public void run() throws IOException, GitAPIException {
        try (Git git = Git.init().setDirectory(this.output.toFile()).setInitialBranch(this.branchName == null ? "main" : this.branchName).call()) {
            run(git, this.startOver, this.branchName, this.startVer, this.targetVer, this.extraMappings);
        }
    }

    private void run(Git git, boolean fresh, String branch, MinecraftVersion startVer, MinecraftVersion targetVer, Path extraMappings) throws IOException, GitAPIException {
        Files.createDirectories(this.output);

        // Find the current branch in case the command line didn't specify one.
        var currentBranch = git.getRepository().getBranch();
        if (branch == null) {
            if (currentBranch == null)
                throw new IllegalStateException("Git repository has no HEAD reference");
            branch = currentBranch;
        }

        // If we are not in the wanted branch, attempt to switch to it. creating it if necessary
        if (!branch.equals(currentBranch) && git.getRepository().resolve(Constants.HEAD) != null) {
            boolean orphan = git.getRepository().resolve(branch) == null;
            git.checkout().setOrphan(orphan).setName(branch).call();
            if (orphan)
                fresh = true;
        }

        var init = new InitTask(this.logger, this.output, git);
        var src = this.output.resolve("src");

        if (fresh) {
            // Honestly we could delete the entire output folder minus the .git folder. We should keep the cache outside of the output
            Util.deleteRecursive(src);
            init.cleanup();

            git.checkout().setOrphan(true).setName("orphan_temp").call();    // Move to temp branch so we can delete existing one
            git.branchDelete().setBranchNames(branch).setForce(true).call(); // Delete existing branch
            git.checkout().setOrphan(true).setName(branch).call();           // Move to correctly named branch
            git.reset().call(); // Cleans up any files that are in the git repo.
        }

        // Get the last commit before we run the init's commit
        var lastVersion = getLastVersion(git);

        // Validate the current metadata, and make initial commit if needed.
        if (!init.validate(fresh, startVer))
            return;

        var manifest = VersionManifestV2.query();
        if (manifest.versions() == null)
            throw new IllegalStateException("Failed to find versions, manifest mising versions listing");

        var versions = Arrays.asList(manifest.versions());
        /* Sort the list by release time.. in case Mojang screwed it up?
            Arrays.stream(manifest.versions())
            .sorted((a,b) -> b.releaseTime().compareTo(a.releaseTime())) // b to a, so its in descending order
            .toList();
        */

        // Find the latest version from the manifest
        if (targetVer == null) {
            var lat = manifest.latest();
            if (lat == null)
                throw new IllegalStateException("Failed to determine latest version, Manifest does not contain latest entries");

            if (releasesOnly)
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
        if (releasesOnly)
            toGenerate.removeIf(v -> !v.type().equals("release"));
        // Reverse so it's in oldest first
        Collections.reverse(toGenerate);

        // Allow resuming by finding the last thing we generated
        if (lastVersion != null) {
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

        var libs = this.cache.resolve("libraries");
        var main = src.resolve("main");

        for (int x = 0; x < toGenerate.size(); x++) {
            var versionInfo = toGenerate.get(x);
            var versionCache = this.cache.resolve(versionInfo.id().toString());
            Files.createDirectories(versionCache);

            this.logger.accept("[" + (x + 1) + "/" + toGenerate.size() + "] Generating " + versionInfo.id());
            var version = Version.load(versionCache.resolve("version.json"));
            if (this.generate(main, versionCache, libs, versionInfo, version)) {
                this.logger.accept("  Committing files");
                /*
                 *  TODO: Make this faster by only commiting the detected changed files.
                 *  Which we can grab from our extract task.
                 *  Shrimp brings up a potential good point. if we get hundreds of versions, it may be prohibitive to
                 *  rebuild the entire git history  when minor things changes. I don't think this is a valid complaint
                 *  as the point of this project is to automate things and have zero manual input into the generated
                 *  repo. But for future note we can look into/reevaluate that case when it happens
                 */
                git.add().addFilepattern("src").addFilepattern("build.gradle").call(); // Add all new files, and update existing ones.
                git.add().addFilepattern("src").setUpdate(true).call(); // Need this to remove all missing files.
                Util.commit(git, versionInfo.id().toString(), versionInfo.releaseTime());
            }
        }
    }


    private static List<VersionInfo> findVersionsWithMappings(Consumer<String> logger, List<VersionInfo> versions, Path cache, Path extraMappings) throws IOException {
        logger.accept("Downloading version manifests");
        List<VersionInfo> ret = new ArrayList<>();
        for (var ver : versions) {
            // Download the version json file.
            var json = cache.resolve(ver.id().toString()).resolve("version.json");
            if (!Files.exists(json) || !HashFunction.SHA1.hash(json).equals(ver.sha1()))
                Util.downloadFile(logger, json, ver.url(), ver.sha1());

            //TODO: Convert extraMappings into a object with 'boolean hasMapping(version, side)' and 'Path getMapping(version, size)'
            var root = extraMappings.resolve(ver.type()).resolve(ver.id().toString()).resolve("maps");
            var client = root.resolve("client.txt");
            var server = root.resolve("server.txt");
            if (Files.exists(client) && Files.exists(server))
                ret.add(ver);
            else {
                var dls = Version.load(json).downloads();
                if (dls.containsKey("client_mappings") && dls.containsKey("server_mappings"))
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
            if (commit.getCommitterIdent().getName().equalsIgnoreCase("SnowBlower"))
                return commit.getShortMessage();
        }
        return null;
    }

    private boolean generate(Path src, Path cache, Path libCache, VersionManifestV2.VersionInfo versionInfo, Version version) throws IOException {
        var mappings = getMergedMappings(cache, versionInfo, version);
        if (mappings == null)
            return false;

        var joined = getJoinedJar(cache, version, mappings);
        var libs = getLibraries(libCache, version);
        var renamed = getRenamedJar(cache, joined, mappings, libCache, libs);
        var decomped = getDecompiledJar(cache, renamed, libCache, libs);

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

        try (FileSystem zipFs = FileSystems.newFileSystem(decomped)) {
            var root = zipFs.getPath("/");
            try (Stream<Path> walker = Files.walk(root)) {
                walker.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        var relative = root.relativize(p);
                        var target = (p.toString().endsWith(".java") ? java : resources).resolve(relative.toString());

                        if (existingFiles.remove(target)) {
                            var existing = HashFunction.MD5.hash(target);
                            var created = HashFunction.MD5.hash(p);
                            if (!existing.equals(created))
                                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.createDirectories(target.getParent());
                            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        }

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        existingFiles.stream().sorted().forEach(p -> {
            try {
                Files.delete(p);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Files.writeString(this.output.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(%java_version%)
                    }
                }

                repositories {
                    mavenCentral()
                    maven {
                        name = 'Mojang'
                        url = 'https://libraries.minecraft.net/'
                    }
                }

                dependencies {
                %deps%
                }
                """
                .replace("%java_version%", Integer.toString(version.javaVersion().majorVersion())) // This assumes the minimum to be 8 (which it is)
                .replace("%deps%", version.libraries().stream()
                        .filter(Version.Library::isAllowed)
                        .sorted()
                        .map(lib -> "    implementation '" + lib.name() + '\'')
                        .collect(Collectors.joining("\n"))));

        return true;
    }

    private IMappingFile downloadMappings(Path cache, VersionManifestV2.VersionInfo versionInfo, Version version, String type) throws IOException {
        var mappings = cache.resolve(type + "_mappings.txt");

        if (!Files.exists(mappings)) {
            this.logger.accept("  Downloading " + type + " mappings");
            boolean copiedFromExtra = false;

            if (this.extraMappings != null) {
                Path extraMap = this.extraMappings.resolve(versionInfo.type()).resolve(versionInfo.id().toString()).resolve("maps").resolve(type + ".txt");
                if (Files.exists(extraMap)) {
                    Files.copy(extraMap, mappings, StandardCopyOption.REPLACE_EXISTING);
                    copiedFromExtra = true;
                }
            }

            if (!copiedFromExtra && !Util.downloadFile(this.logger, mappings, version, type + "_mappings"))
                return null;
        }

        try (var in = Files.newInputStream(mappings)) {
            return IMappingFile.load(in);
        }
    }

    private Path getMergedMappings(Path cache, VersionManifestV2.VersionInfo versionInfo, Version version) throws IOException {
        var clientMojToObj = downloadMappings(cache, versionInfo, version, "client");

        if (clientMojToObj == null) {
            this.logger.accept("  Client mappings not found, skipping version");
            return null;
        }

        var serverMojToObj = downloadMappings(cache, versionInfo, version, "server");

        if (serverMojToObj == null) {
            this.logger.accept("  Server mappings not found, skipping version");
            return null;
        }

        if (!canMerge(clientMojToObj, serverMojToObj))
            throw new IllegalStateException("Client mappings for " + versionInfo.id() + " are not a strict superset of the server mappings.");

        var key = new Cache()
            .put("client", cache.resolve("client_mappings.txt"))
            .put("server", cache.resolve("server_mappings.txt"));
        var keyF = cache.resolve("obf_to_moj.tsrg.cache");
        var ret = cache.resolve("obf_to_moj.tsrg");

        if (!Files.exists(ret) || !key.isValid(keyF)) {
            clientMojToObj.write(ret, IMappingFile.Format.TSRG2, true);
            key.write(keyF);
        }

        return ret;
    }

    // https://github.com/LexManos/MappingToy/blob/master/src/main/java/net/minecraftforge/lex/mappingtoy/MappingToy.java#L271
    private static boolean canMerge(IMappingFile client, IMappingFile server) {
        // Test if the client is a strict super-set of server.
        // If so, the client mappings can be used for the joined jar.
        final Function<IMappingFile.IField, String> fldToString = fld -> fld.getOriginal() + " " + fld.getDescriptor() + " -> " + fld.getMapped() + " " + fld.getMappedDescriptor();
        final Function<IMappingFile.IMethod, String> mtdToString = mtd -> mtd.getOriginal() + " " + mtd.getDescriptor() + " -> " + mtd.getMapped() + " " + mtd.getMappedDescriptor();

        for (IMappingFile.IClass clsS : server.getClasses()) {
            IMappingFile.IClass clsC = client.getClass(clsS.getOriginal());
            if (clsC == null || !clsS.getMapped().equals(clsC.getMapped()))
                return false;

            Set<String> fldsS = clsS.getFields().stream().map(fldToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> fldsC = clsC.getFields().stream().map(fldToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> mtdsS = clsS.getMethods().stream().map(mtdToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> mtdsC = clsC.getMethods().stream().map(mtdToString).collect(Collectors.toCollection(HashSet::new));

            fldsS.removeAll(fldsC);
            mtdsS.removeAll(mtdsC);

            if (!fldsS.isEmpty() || !mtdsS.isEmpty())
                return false;
        }

        return true;
    }

    private Path getJar(String type, Path cache, Version version) throws IOException {
        var jar = cache.resolve(type + ".jar");
        var keyF = cache.resolve(type + ".jar.cache");
        var dl = version.downloads().get(type);
        if (dl == null || dl.sha1() == null)
            throw new IllegalStateException("Could not download \"" + type + "\" jar as version json doesn't have download entry");

        // We include the hash from the json, because Mojang HAS done silent updates before
        // Should we detect/notify about this somehow?
        // Plus it's faster to use the existing string instead of hashing a file.
        var key = new Cache()
            .put(type, dl.sha1());

        if (!Files.exists(jar) || !key.isValid(keyF)) {
            this.logger.accept("  Downloading " + type + " jar");
            if (!Util.downloadFile(this.logger, jar, dl.url(), dl.sha1()))
                throw new IllegalStateException("Failed to download " + type + " jar");
            key.put(type, jar);
            key.write(keyF);
        }

        return jar;
    }

    private Path getJoinedJar(Path cache, Version version, Path mappings) throws IOException {
        var clientJar = getJar("client", cache, version);
        var serverJar = getJar("server", cache, version);

        var key = new Cache()
            .put(Tools.MERGETOOL, this.depCache)
            .put("client", clientJar)
            .put("server", serverJar)
            .put("map", mappings);
        var keyF = cache.resolve("joined.jar.cache");
        var joinedJar = cache.resolve("joined.jar");

        if (!Files.exists(joinedJar) || !key.isValid(keyF)) {
            this.logger.accept("  Merging client and server jars");
            Merger merger = new Merger(clientJar.toFile(), serverJar.toFile(), joinedJar.toFile());
            // Whitelist only Mojang classes to process
            var map = IMappingFile.load(mappings.toFile());
            map.getClasses().forEach(cls -> merger.whitelist(cls.getOriginal()));
            merger.annotate(AnnotationVersion.API, true);
            merger.keepData();
            merger.skipMeta();
            merger.process();

            key.write(keyF);
        }

        return joinedJar;
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
}
