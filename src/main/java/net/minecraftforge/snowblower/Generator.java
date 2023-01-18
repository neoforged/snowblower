/*
 * Snowblower
 * Copyright (C) 2023 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
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
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.MinecraftVersion;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Generator {
    private final Path output;
    private final Path extraMappings;
    private final boolean startOver;
    private final MinecraftVersion startVer;
    private final MinecraftVersion targetVer;
    private final String branchName;
    private final boolean releasesOnly;
    private final Consumer<String> logger;

    public Generator(File output, File extraMappings, MinecraftVersion startVer, MinecraftVersion targetVer, String branchName, boolean startOver, boolean releasesOnly, Consumer<String> logger) {
        this.output = output.toPath();
        this.extraMappings = extraMappings == null ? null : extraMappings.toPath();
        this.startOver = startOver;
        this.startVer = startVer;
        this.targetVer = targetVer;
        this.branchName = branchName;
        this.releasesOnly = releasesOnly;
        this.logger = logger;
    }

    public void run() throws IOException, GitAPIException {
        try (Git git = Git.init().setDirectory(this.output.toFile()).setInitialBranch(this.branchName).call()) {
            run(git);
        }
    }

    private void run(Git git) throws IOException, GitAPIException {
        Files.createDirectories(this.output);

        if (git.getRepository().resolve(Constants.HEAD) != null && git.getRepository().resolve(this.branchName) == null)
            git.checkout().setOrphan(true).setName(this.branchName).call();

        Path src = this.output.resolve("src");

        if (this.startOver) {
            deleteRecursive(src.toFile());
            deleteInitalCommit(this.output);

            git.checkout().setOrphan(true).setName("orphan_temp").call();
            git.branchDelete().setBranchNames(this.branchName).setForce(true).call();
            git.checkout().setOrphan(true).setName(this.branchName).call();
            git.reset().call();
        }

        ObjectId headId = git.getRepository().resolve(Constants.HEAD);
        Iterator<RevCommit> iterator = headId == null ? null : git.log().add(headId).call().iterator();
        RevCommit latestCommit = null;

        if (iterator != null && iterator.hasNext()) {
            latestCommit = iterator.next();
        }

        if (!checkMetadata(git, this.startOver, this.output, this.startVer))
            return;

        VersionManifestV2.VersionInfo[] versions = VersionManifestV2.query().versions();
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < versions.length; i++) {
            MinecraftVersion ver = versions[i].id();
            if (ver.equals(this.targetVer)) {
                endIdx = i;
            }
            if (ver.equals(this.startVer)) {
                startIdx = i;
                break;
            }
        }

        if (startIdx == -1 || endIdx == -1)
            throw new IllegalStateException("Could not find start and/or end version in version manifest (or they were out of order)");

        List<VersionManifestV2.VersionInfo> toGenerate = new ArrayList<>(Arrays.asList(versions).subList(endIdx, startIdx + 1));
        if (this.releasesOnly)
            toGenerate.removeIf(v -> !v.type().equals("release"));
        Collections.reverse(toGenerate);

        if (latestCommit != null) {
            String search = latestCommit.getShortMessage();
            boolean found = false;
            for (int i = 0; i < toGenerate.size(); i++) {
                if (toGenerate.get(i).id().toString().equals(search)) {
                    toGenerate = toGenerate.subList(i + 1, toGenerate.size());
                    found = true;
                    break;
                }
            }

            if (!found)
                return; // We have nothing to do as the latest commit is outside our range of versions to generate
        }

        var cache = this.output.resolve("build").resolve("cache");
        var libs = cache.resolve("libraries");
        var main = src.resolve("main");

        for (int x = 0; x < toGenerate.size(); x++) {
            var versionInfo = toGenerate.get(x);
            var versionCache = cache.resolve(versionInfo.id().toString());
            Files.createDirectories(versionCache);

            this.logger.accept("[" + (x + 1) + "/" + toGenerate.size() + "] Generating " + versionInfo.id());
            var version = Version.query(versionInfo.url()); //TODO: Cache version json, that requires etags
            if (this.generate(main, versionCache, libs, versionInfo, version)) {
                this.logger.accept("  Committing files");
                git.add().addFilepattern("src").call(); // Add all new files, and update existing ones.
                git.add().addFilepattern("src").setUpdate(true).call(); // Need this to remove all missing files.
                commit(git, versionInfo.id().toString());
            }
        }
    }

    private void deleteInitalCommit(Path root) throws IOException {
        for (String file : new String[] {"Snowblower.txt", ".gitattributes", ".gitignore"}) {
            Path target = root.resolve(file);
            if (Files.exists(target))
                Files.delete(target);
        }
    }

    private boolean checkMetadata(Git git, boolean fresh, Path root, MinecraftVersion start) throws IOException, GitAPIException {
        var meta = new Cache().comment(
            "Source files created by Snowblower",
            "https://github.com/MinecraftForge/Snowblower")
            .put("Snowblower", "{git hash}") // TODO Teach it about its own version/git hash
            .put("Start", start.toString());

        Path file = root.resolve("Snowblower.txt");
        if (!Files.exists(file)) {
            if (!fresh) {
                this.logger.accept("The starting commit on this branch does not match the provided starting version. Please choose a different branch or add the --start-over flag and try again.");
                return false;
            } else {
                // Create metadata file, eventually use it for cache busting?
                meta.write(file);
                add(git, file);

                // Create some git metadata files to make life sane
                var attrs = root.resolve(".gitattributes");
                writeLines(attrs,
                    "* text eol=lf",
                    "*.java text eol=lf",
                    "*.json text eol=lf",
                    "*.xml text eol=lf",
                    "*.bin binary",
                    "*.png binary",
                    "*.gif binary",
                    "*.nbt binary");
                add(git, attrs);

                // Create some git metadata files to make life sane
                var ignore = root.resolve(".gitignore");
                writeLines(ignore, "/build");
                add(git, ignore);

                commit(git, "Init");

                return true;
            }
        } else {
            if (!meta.isValid(file)) {
                this.logger.accept("The starting commit on this branch does not match the provided starting version. Please choose a different branch or add the --start-over flag and try again.");
                return false;
            }
            return true;
        }

    }

    private void commit(Git git, String message) throws GitAPIException {
        git.commit()
            .setMessage(message)
            .setAuthor("SnowBlower", "snow@blower.com")
            .setCommitter("SnowBlower", "snow@blower.com")
            .setSign(false)
            .call();
    }

    private void add(Git git, Path file) throws GitAPIException {
        var root = git.getRepository().getDirectory().getParentFile().toPath();
        var path = root.relativize(file);
        git.add().addFilepattern(path.toString()).call();
    }

    private boolean generate(Path src, Path cache, Path libCache, VersionManifestV2.VersionInfo versionInfo, Version version) throws IOException {
        var mappings = getMergedMappings(cache, versionInfo, version);
        if (mappings == null)
            return false;

        var joined = getJoinedJar(cache, version, mappings);
        var libs = getLibraries(libCache, version);
        var renamed = getRenamedJar(cache, joined, mappings, libCache, libs);
        var decomped = getDecompiledJar(cache, renamed, libCache, libs);

        Set<Path> existingFiles = Files.exists(src) ? Files.walk(src).filter(Files::isRegularFile).collect(Collectors.toSet()) : new HashSet<>();
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

            if (!copiedFromExtra && !downloadFile(mappings, version, type + "_mappings"))
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

    private Path getJoinedJar(Path cache, Version version, Path mappings) throws IOException {
        var clientJar = cache.resolve("client.jar");
        if (!Files.exists(clientJar)) { // TODO: Check hashes
            this.logger.accept("  Downloading client jar");
            if (!downloadFile(clientJar, version, "client"))
                throw new IllegalStateException("Failed to download client jar");
        }

        var serverJar = cache.resolve("server.jar");
        if (!Files.exists(serverJar)) { // TODO: Check hashes
            this.logger.accept("  Downloading server jar");
            if (!downloadFile(serverJar, version, "server"))
                throw new IllegalStateException("Failed to download dedicated server jar");
        }

        var key = new Cache()
            // TODO: Merger Library Hash
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

            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                downloadFile(target, dl.url(), dl.sha1());
            }

            ret.add(target);
        }
        return ret;
    }

    private Path getRenamedJar(Path cache, Path joined, Path mappings, Path libCache, List<Path> libs) throws IOException {
        var key = new Cache()
            // TODO: Renamer tool hash
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
            // TODO: Decompiler Tool Hash
            .put("renamed", renamed);
        for (var lib : libs) {
            var relative = libCache.relativize(lib);
            key.put(relative.toString(), lib);
        }

        var keyF = cache.resolve("joined-decompiled.jar.cache");
        var ret = cache.resolve("joined-decompiled.jar");

        if (!Files.exists(ret) || !key.isValid(keyF)) {
            this.logger.accept("  Decompiling joined-renamed.jar");
            var cfg = cache.resolve("joined-libraries.cfg");
            writeLines(cfg, libs.stream().map(l -> "-e=" + l.toString()).toArray(String[]::new));

            ConsoleDecompiler.main(new String[]{"-din=1", "-rbr=1", "-dgs=1", "-asc=1", "-rsy=1", "-iec=1", "-jvn=1", "-isl=0", "-iib=1", "-bsm=1", "-dcl=1",
                "-log=ERROR", // IFernflowerLogger.Severity
                "-cfg", cfg.toString(),
                renamed.toString(),
                ret.toString()
            });

            key.write(keyF);
        }
        return ret;
    }

    private static void deleteRecursive(File dir) throws IOException {
        if (!dir.exists())
            return;

        try (Stream<Path> walker = Files.walk(dir.toPath())) {
            walker.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private static void writeLines(Path target, String... lines) throws IOException {
        String attrib = Stream.of(lines).collect(Collectors.joining("\n"));
        Files.write(target, attrib.getBytes(StandardCharsets.UTF_8));
    }

    private boolean downloadFile(Path output, Version version, String key) throws IOException {
        Version.Download download = version.downloads().get(key);
        if (download == null)
            return false;

        return downloadFile(output, download.url(), download.sha1());
    }

    private boolean downloadFile(Path file, URL url, String sha1) throws IOException {
        try {
            this.logger.accept("  Downloading " + url.toString());
            var connection = (HttpURLConnection)url.openConnection();
            connection.setUseCaches(false);
            connection.setDefaultUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-store,max-age=0,no-cache");
            connection.setRequestProperty("Expires", "0");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();

            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(file);) {
                copy(in, out);
            }

            if (sha1 != null) {
                var actual = HashFunction.SHA1.hash(file);
                if (!actual.equals(sha1)) {
                    Files.delete(file);
                    throw new IOException("Failed to download " + url.toString() + " Invalid Hash:\n" +
                        "    Expected: " + sha1 + "\n" +
                        "    Actual: " + actual);
                }
            }

            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buf = new byte[0x100];
        int cnt = 0;
        while ((cnt = input.read(buf, 0, buf.length)) != -1) {
            output.write(buf, 0, cnt);
        }
    }
}
