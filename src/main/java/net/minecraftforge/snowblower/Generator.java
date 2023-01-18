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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Generator {
    private final File output;
    private final File extraMappings;
    private final boolean startOver;
    private final MinecraftVersion startVer;
    private final MinecraftVersion targetVer;
    private final String branchName;
    private final boolean releasesOnly;
    private final Consumer<String> logger;

    public Generator(File output, File extraMappings, MinecraftVersion startVer, MinecraftVersion targetVer, String branchName, boolean startOver, boolean releasesOnly, Consumer<String> logger) {
        this.output = output;
        this.extraMappings = extraMappings;
        this.startOver = startOver;
        this.startVer = startVer;
        this.targetVer = targetVer;
        this.branchName = branchName;
        this.releasesOnly = releasesOnly;
        this.logger = logger;
    }

    public void run() throws IOException, GitAPIException {
        try (Git git = Git.init().setDirectory(this.output).setInitialBranch(this.branchName).call()) {
            run(git);
        }
    }

    private void run(Git git) throws IOException, GitAPIException {
        this.output.mkdirs();

        if (git.getRepository().resolve(Constants.HEAD) != null && git.getRepository().resolve(this.branchName) == null)
            git.checkout().setOrphan(true).setName(this.branchName).call();

        Path src = this.output.toPath().resolve("src");

        if (this.startOver) {
            deleteRecursive(src.toFile());
            deleteInitalCommit(this.output.toPath());

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

        Path cache = this.output.toPath().resolve("build").resolve("cache");
        Path java = src.resolve("main").resolve("java");

        for (int x = 0; x < toGenerate.size(); x++) {
            var versionInfo = toGenerate.get(x);
            File versionCache = cache.resolve(versionInfo.id().toString()).toFile();
            versionCache.mkdirs();

            this.logger.accept("[" + x + "/" + toGenerate.size() + "] Generating " + versionInfo.id());
            if (this.generate(java, versionCache, versionInfo, Version.query(versionInfo.url()))) {
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

    private boolean checkMetadata(Git git, boolean fresh, File root, MinecraftVersion start) throws IOException, GitAPIException {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Snowblower", "I should put the git hash, or version number, but that needs to wait till I teach this thing about its own version"); //TODO
        meta.put("Start", start.toString());

        File file = new File(root, "Snowblower.txt");
        if (!file.exists()) {
            if (!fresh) {
                this.logger.accept("The starting commit on this branch does not match the provided starting version. Please choose a different branch or add the --start-over flag and try again.");
                return false;
            } else {
                // Create metadata file, eventually use it for cache busting?
                StringBuilder output = new StringBuilder();
                output.append("Source files creates by Snowblower\n");
                output.append("https://github.com/MinecraftForge/Snowblower\n\n");
                meta.forEach((k,v) -> output.append(k).append(": ").append(v).append('\n'));
                Files.write(file.toPath(), output.toString().getBytes(StandardCharsets.UTF_8));
                git.add().addFilepattern("Snowblower.txt").call();

                // Create some git metadata files to make life sane
                String attrib = Stream.of(
                    "* text eol=lf",
                    "*.java text eol=lf",
                    "*.png binary",
                    "*.gif binary"
                ).collect(Collectors.joining("\n"));
                Files.write(new File(root, ".gitattributes").toPath(), attrib.getBytes(StandardCharsets.UTF_8));
                git.add().addFilepattern(".gitattributes").call();

                // Create some git metadata files to make life sane
                String ignore = Stream.of(
                    "/build"
                ).collect(Collectors.joining("\n"));
                Files.write(new File(root, ".gitignore").toPath(), ignore.getBytes(StandardCharsets.UTF_8));
                git.add().addFilepattern(".gitignore").call();

                commit(git, "Init");

                return true;
            }
        } else {
            Map<String, String> existing = new HashMap<>();
            try (Stream<String> stream = Files.lines(file.toPath())) {
                stream.forEach(l -> {
                    int idx = l.indexOf(' ');
                    if (idx <= 1 || l.charAt(idx - 1) != ':')
                        return;

                    String key = l.substring(0, idx - 1);
                    String value = l.substring(idx + 1);
                    existing.put(key, value);
                });
            }

            if (!existing.equals(meta)) {
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

    private boolean generate(Path src, File cache, VersionManifestV2.VersionInfo versionInfo, Version version) throws IOException {
        deleteRecursive(src.toFile());

        IMappingFile clientMojToObj = downloadMappings(cache, versionInfo, version, "client");

        if (clientMojToObj == null) {
            this.logger.accept("  Client mappings not found, skipping version");
            return false;
        }

        IMappingFile serverMojToObj = downloadMappings(cache, versionInfo, version, "server");

        if (serverMojToObj == null) {
            this.logger.accept("  Server mappings not found, skipping version");
            return false;
        }

        if (!canMerge(clientMojToObj, serverMojToObj))
            throw new IllegalStateException("Client mappings for " + versionInfo.id() + " are not a strict superset of the server mappings.");

        File clientJar = new File(cache, "client.jar");
        if (!clientJar.exists()) {
            this.logger.accept("  Downloading client jar");
            if (!copy(version, clientJar, "client"))
                throw new IllegalStateException();
        }

        File serverJar = new File(cache, "server.jar");
        if (!serverJar.exists()) {
            this.logger.accept("  Downloading server jar");
            if (!copy(version, serverJar, "server"))
                throw new IllegalStateException();
        }

        File joinedJar = new File(cache, "joined.jar");

        if (!joinedJar.exists()) {
            this.logger.accept("  Merging client and server jars");
            Merger merger = new Merger(clientJar, serverJar, joinedJar);
            // Whitelist only Mojang classes to process
            clientMojToObj.getClasses().forEach(cls -> merger.whitelist(cls.getMapped()));
            merger.annotate(AnnotationVersion.API, true);
            merger.keepData();
            merger.skipMeta();
            merger.process();
        }

        File renamedJar = new File(cache, "joined-renamed.jar");
        File clientMappingsReversed = new File(cache, "client_mappings_reversed.tsrg");
        if (!clientMappingsReversed.exists())
            clientMojToObj.write(clientMappingsReversed.toPath(), IMappingFile.Format.TSRG2, true);

        if (!renamedJar.exists()) {
            this.logger.accept("  Renaming joined jar");
            Renamer.builder()
                    .input(joinedJar)
                    .output(renamedJar)
                    .map(clientMappingsReversed)
                    .add(Transformer.parameterAnnotationFixerFactory())
                    .add(Transformer.identifierFixerFactory(IdentifierFixerConfig.ALL))
                    .add(Transformer.sourceFixerFactory(SourceFixerConfig.JAVA))
                    .add(Transformer.recordFixerFactory())
                    .add(Transformer.signatureStripperFactory(SignatureStripperConfig.ALL))
                    .logger(s -> {}).build().run();
        }

        File decompiledJar = new File(cache, "joined-decompiled.jar");

        if (!decompiledJar.exists()) {
            this.logger.accept("  Decompiling joined jar");
            ConsoleDecompiler.main(new String[]{"-din=1", "-rbr=1", "-dgs=1", "-asc=1", "-rsy=1", "-iec=1", "-jvn=1", "-isl=0", "-iib=1", "-bsm=1", "-dcl=1",
                    "-log=ERROR", // IFernflowerLogger.Severity
                    /*"-cfg", "{libraries}", */
                    renamedJar.toString(), decompiledJar.toString()});
        }

        try (FileSystem zipFs = FileSystems.newFileSystem(decompiledJar.toPath())) {
            Path rootPath = zipFs.getPath("/");
            try (Stream<Path> walker = Files.walk(rootPath)) {
                walker.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).forEach(p -> {
                    try {
                        Path target = this.output.toPath().resolve("src").resolve("main").resolve("java").resolve(rootPath.relativize(p).toString());
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        return true;
    }

    private IMappingFile downloadMappings(File cache, VersionManifestV2.VersionInfo versionInfo, Version version, String type) throws IOException {
        File mappings = new File(cache, type + "_mappings.txt");

        if (!mappings.exists()) {
            this.logger.accept("  Downloading " + type + " mappings");
            boolean copiedFromExtra = false;

            if (this.extraMappings != null) {
                Path extraMap = this.extraMappings.toPath().resolve(versionInfo.type()).resolve(versionInfo.id().toString()).resolve("maps").resolve(type + ".txt");
                if (Files.exists(extraMap)) {
                    Files.copy(extraMap, mappings.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    copiedFromExtra = true;
                }
            }

            if (!copiedFromExtra && !copy(version, mappings, type + "_mappings"))
                return null;
        }

        return IMappingFile.load(mappings);
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

    private static void deleteRecursive(File dir) throws IOException {
        if (!dir.exists())
            return;

        try (Stream<Path> walker = Files.walk(dir.toPath())) {
            walker.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private static boolean copy(Version version, File output, String key) throws IOException {
        Version.Download download = version.downloads().get(key);
        if (download == null)
            return false;

        try (FileOutputStream out = new FileOutputStream(output)) {
            out.getChannel().transferFrom(Channels.newChannel(download.url().openStream()), 0, Long.MAX_VALUE);
        }

        return true;
    }
}
