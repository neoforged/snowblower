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
        ObjectId headId = git.getRepository().resolve(Constants.HEAD);
        if (headId != null && git.getRepository().resolve(this.branchName) == null)
            git.checkout().setOrphan(true).setName(this.branchName).call();

        Path src = this.output.toPath().resolve("src").resolve("main").resolve("java");

        if (this.startOver) {
            deleteRecursive(src.toFile());

            git.checkout().setOrphan(true).setName("orphan_temp").call();
            git.branchDelete().setBranchNames(this.branchName).setForce(true).call();
            git.checkout().setOrphan(true).setName(this.branchName).call();
        }

        Iterator<RevCommit> iterator = headId == null ? null : git.log().add(headId).call().iterator();
        RevCommit latestCommit = null;
        RevCommit oldestCommit = null;

        if (iterator != null) {
            while (iterator.hasNext()) {
                oldestCommit = iterator.next();
                if (latestCommit == null)
                    latestCommit = oldestCommit;
            }
        }

        if (!this.startOver && oldestCommit != null && !oldestCommit.getShortMessage().equals(this.startVer.toString())) {
            this.logger.accept("The starting commit on this branch does not match the provided starting version. Please choose a different branch or add the --start-over flag and try again.");
            return;
        }

        this.output.mkdirs();

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

        File tmp = new File(this.output, "tmp");

        for (VersionManifestV2.VersionInfo versionInfo : toGenerate) {
            tmp.mkdir();

            this.generate(src, tmp, versionInfo, Version.query(versionInfo.url()));

            this.logger.accept("  Committing files");
            git.add().addFilepattern("src").call();
            git.commit()
                .setMessage(versionInfo.id().toString()).setAuthor("SnowBlower", "snow@blower.com")
                .setSign(false)
                .call();

            deleteRecursive(tmp);
        }
    }

    private void generate(Path src, File tmp, VersionManifestV2.VersionInfo versionInfo, Version version) throws IOException {
        this.logger.accept("Generating " + versionInfo.id());
        deleteRecursive(src.toFile());

        this.logger.accept("  Downloading client jar");
        File clientJar = new File(tmp, "client.jar");
        copy(version, clientJar, "client");

        this.logger.accept("  Downloading server jar");
        File serverJar = new File(tmp, "server.jar");
        copy(version, serverJar, "server");

        IMappingFile clientObjToMoj = downloadMappings(tmp, versionInfo, version, "client");
        IMappingFile serverObjToMoj = downloadMappings(tmp, versionInfo, version, "server");

        if (!canMerge(clientObjToMoj, serverObjToMoj))
            throw new IllegalStateException("Client mappings for " + versionInfo.id() + " are not a strict superset of the server mappings.");

        File joinedJar = new File(tmp, "joined.jar");

        Merger merger = new Merger(clientJar, serverJar, joinedJar);
        // Whitelist only Mojang classes to process
        clientObjToMoj.getClasses().forEach(cls -> merger.whitelist(cls.getOriginal()));
        merger.annotate(AnnotationVersion.API, true);
        merger.keepData();
        merger.skipMeta();
        merger.process();

        File renamedJar = new File(tmp, "joined-renamed.jar");
        File clientMappingsReversed = new File(tmp, "client_mappings_reversed.tsrg");
        clientObjToMoj.write(clientMappingsReversed.toPath(), IMappingFile.Format.TSRG2, false);

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

        File decompiledJar = new File(tmp, "joined-decompiled.jar");

        this.logger.accept("  Decompiling joined jar");
        ConsoleDecompiler.main(new String[]{"-din=1", "-rbr=1", "-dgs=1", "-asc=1", "-rsy=1", "-iec=1", "-jvn=1", "-isl=0", "-iib=1", "-bsm=1", "-dcl=1",
                "-log=ERROR", // IFernflowerLogger.Severity
                /*"-cfg", "{libraries}", */
                renamedJar.toString(), decompiledJar.toString()});

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
    }

    private IMappingFile downloadMappings(File tmp, VersionManifestV2.VersionInfo versionInfo, Version version, String type) throws IOException {
        this.logger.accept("  Downloading " + type + " mappings");
        File mappings = new File(tmp, type + "_mappings.txt");
        boolean copiedFromExtra = false;

        if (this.extraMappings != null) {
            Path extraMap = this.extraMappings.toPath().resolve(versionInfo.type()).resolve(versionInfo.id().toString()).resolve("maps").resolve(type + ".txt");
            if (Files.exists(extraMap)) {
                Files.copy(extraMap, mappings.toPath(), StandardCopyOption.REPLACE_EXISTING);
                copiedFromExtra = true;
            }
        }

        if (!copiedFromExtra)
            copy(version, mappings, type + "_mappings");

        return IMappingFile.load(mappings).reverse();
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

    private static void copy(Version version, File output, String key) throws IOException {
        try (FileOutputStream out = new FileOutputStream(output)) {
            out.getChannel().transferFrom(Channels.newChannel(version.downloads().get(key).url().openStream()), 0, Long.MAX_VALUE);
        }
    }
}
