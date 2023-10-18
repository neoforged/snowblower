/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.snowblower.tasks.init;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

import net.minecraftforge.snowblower.Generator;
import net.minecraftforge.snowblower.Main;
import net.minecraftforge.snowblower.util.Cache;
import net.minecraftforge.snowblower.util.Util;
import net.minecraftforge.srgutils.MinecraftVersion;

public class InitTask {
    private static final String COMMIT_MESSAGE = "Initial commit";
    private final Consumer<String> logger;
    private final Path root;
    private final Git git;

    public InitTask(Consumer<String> logger, Path root, Git git) {
        this.logger = logger;
        this.root = root;
        this.git = git;
    }

    public void cleanup() throws IOException {
        for (var file : new String[]{"Snowblower.txt", ".gitattributes", ".gitignore", "gradlew", "gradlew.bat", "gradle"}) {
            var target = this.root.resolve(file);
            if (Files.isDirectory(target))
                Util.deleteRecursive(target);
            else if (Files.exists(target))
                Files.delete(target);
        }
    }

    public boolean validate(MinecraftVersion start) throws IOException, GitAPIException {
        var meta = new Cache().comment(
            "Source files created by Snowblower",
            "https://github.com/MinecraftForge/Snowblower")
            .put("Snowblower", getGitCommitHash()) // Now that I moved this to its own package, we could use the package hash. But I like the git commit.
            .put("Start", start.toString());

        var metaPath = root.resolve("Snowblower.txt");
        if (Files.exists(metaPath) && !meta.isValid(metaPath)) {
            return false;
        }

        if (!Files.exists(metaPath)) {
            // Create metadata file
            meta.write(metaPath);
            Util.add(git, metaPath);

            // Create some git metadata files to make life sane
            var attrs = root.resolve(".gitattributes");
            Util.writeLines(attrs,
                "* text eol=lf",
                "*.java text eol=lf",
                "*.json text eol=lf",
                "*.xml text eol=lf",
                "*.bin binary",
                "*.png binary",
                "*.gif binary",
                "*.nbt binary",
                "*.ogg binary"
            );
            Util.add(git, attrs);

            var ignore = root.resolve(".gitignore");
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

            try {
                Path copyParentFolder = Util.isDev() ? Util.getSourcePath() : Util.getPath(Main.class.getResource("/resource_root.txt").toURI()).getParent();
                List<String> toCopy = List.of("gradlew", "gradlew.bat", "gradle");
                AddCommand addCmd = git.add();

                for (String filename : toCopy) {
                    Path copyPath = copyParentFolder.resolve(filename);
                    if (Files.isRegularFile(copyPath)) {
                        Files.copy(copyPath, root.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.walkFileTree(copyPath, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                Path destinationPath = root.resolve(copyParentFolder.relativize(file).toString());
                                Files.createDirectories(destinationPath.getParent());
                                Files.copy(file, destinationPath, StandardCopyOption.REPLACE_EXISTING);
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    }
                    addCmd.addFilepattern(filename);
                }

                addCmd.call();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }

            DirCache dirCache = git.getRepository().lockDirCache();
            dirCache.getEntry("gradlew").setFileMode(FileMode.EXECUTABLE_FILE);
            dirCache.write();
            dirCache.commit();

            PosixFileAttributeView posixFileAttributeView = Files.getFileAttributeView(root.resolve("gradlew"), PosixFileAttributeView.class);
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

    private static String getGitCommitHash() {
        String implVersion = Generator.class.getPackage().getImplementationVersion();
        if (implVersion != null)
            return implVersion.substring(implVersion.indexOf('(') + 1, implVersion.indexOf(')'));

        try {
            // This should never be a jar if the implementation version is missing, so we don't need Util#getPath
            Path folderPath = Path.of(Util.getCodeSourceUri());

            while (!Files.exists(folderPath.resolve(".git"))) {
                folderPath = folderPath.getParent();
                if (folderPath == null)
                    return "unknown";
            }

            try (Git sourceGit = Git.open(folderPath.toFile())) {
                ObjectId headId = sourceGit.getRepository().resolve(Constants.HEAD);
                return headId == null ? "unknown" : headId.getName();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isInitCommit(String message) {
        return COMMIT_MESSAGE.equals(message);
    }
}
