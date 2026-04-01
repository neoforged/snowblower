/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.tasks.init;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.neoforged.snowblower.data.MinecraftVersion;
import net.neoforged.snowblower.util.Cache;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.FileMode;

import net.neoforged.snowblower.Generator;
import net.neoforged.snowblower.util.Util;

public class InitTask {
    private static final String COMMIT_MESSAGE = "Initial commit";

    public static boolean validateOrInit(Path output, Git git, MinecraftVersion start) throws IOException, GitAPIException {
        var meta = new Cache().comment(
            "Source files created by Snowblower",
            "https://github.com/neoforged/snowblower")
            .put("VersionId", Integer.toString(Generator.VERSION_ID))
            .put("Start", start.toString());

        var metaPath = output.resolve("Snowblower.txt");
        if (Files.exists(metaPath) && !meta.isValid(metaPath))
            return false;

        if (!Files.exists(metaPath)) {
            // Create metadata file
            meta.write(metaPath);
            Util.add(git, metaPath);

            // Create some git metadata files to make life sane
            var attrs = output.resolve(".gitattributes");
            Util.writeLines(attrs,
                "* text eol=lf",
                "*.java text eol=lf",
                "*.json text eol=lf",
                "*.xml text eol=lf",
                "*.bin binary",
                "*.png binary",
                "*.gif binary",
                "*.nbt binary",
                "*.ogg binary",
                "# In GitHub, hide resources by default",
                "src/main/resources/** linguist-generated"
            );
            Util.add(git, attrs);

            var ignore = output.resolve(".gitignore");
            Util.writeLines(ignore,
                ".gradle",
                "build",
                "",
                "# Eclipse",
                ".settings",
                ".metadata",
                ".classpath",
                ".project",
                "bin",
                "",
                "# IntelliJ",
                "out",
                "*.idea",
                "*.iml"
            );
            Util.add(git, ignore);

            try (var fs = Util.isDev() ? null : FileSystems.newFileSystem(getOurJar(), (ClassLoader) null)) {
                Path copyParentFolder = fs == null ? Util.getSourcePath() : fs.getRootDirectories().iterator().next();
                List<String> toCopy = List.of("gradlew", "gradlew.bat", "gradle/wrapper");
                AddCommand addCmd = git.add();

                for (String filename : toCopy) {
                    Path copyPath = copyParentFolder.resolve(filename);
                    if (Files.isRegularFile(copyPath)) {
                        Files.copy(copyPath, output.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.walkFileTree(copyPath, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                Path destinationPath = output.resolve(copyParentFolder.relativize(file).toString());
                                Files.createDirectories(destinationPath.getParent());
                                Files.copy(file, destinationPath, StandardCopyOption.REPLACE_EXISTING);
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    }
                    addCmd.addFilepattern(filename);
                }

                addCmd.call();
            }

            DirCache dirCache = git.getRepository().lockDirCache();
            dirCache.getEntry("gradlew").setFileMode(FileMode.EXECUTABLE_FILE);
            dirCache.write();
            dirCache.commit();

            PosixFileAttributeView posixFileAttributeView = Files.getFileAttributeView(output.resolve("gradlew"), PosixFileAttributeView.class);
            if (posixFileAttributeView != null) {
                Set<PosixFilePermission> perms = posixFileAttributeView.readAttributes().permissions();
                perms.addAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
                posixFileAttributeView.setPermissions(perms);
            }

            // Oldest release timestamp in the Mojang version manifest (that number itself is an approximation but whatever)
            Util.commit(git, COMMIT_MESSAGE, new Date(1242245460000L));
        }

        return true;
    }

    private static Path getOurJar() {
        try {
            return Paths.get(InitTask.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isInitCommit(String message) {
        return COMMIT_MESSAGE.equals(message);
    }
}
