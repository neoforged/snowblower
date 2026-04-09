/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.tasks;

import net.neoforged.installertools.BundlerExtract;
import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.Tools;
import net.neoforged.srgutils.IMappingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

public class BundlerExtractTask {
    public static final String SERVER_EXTRACTED_JAR_FILENAME = "server-extracted.jar";
    public static final String SERVER_EXTRACTED_JAR_CACHE_FILENAME = SERVER_EXTRACTED_JAR_FILENAME + ".cache";
    private static final Logger LOGGER = LoggerFactory.getLogger(BundlerExtractTask.class);
    private static final Attributes.Name FORMAT = new Attributes.Name("Bundler-Format");

    private static Cache getKey(Version version, DependencyHashCache depCache) {
        return new Cache()
                .put(Tools.INSTALLERTOOLS, depCache)
                .put("server", version.downloads().get("server").sha1());
    }

    public static boolean inPartialCache(Path cache, Version version, DependencyHashCache depCache) throws IOException {
        var key = getKey(version, depCache);
        var keyF = cache.resolve(SERVER_EXTRACTED_JAR_CACHE_FILENAME);

        return Files.exists(keyF) && key.isValid(keyF);
    }

    public static Path getExtractedServerJar(Path cache, Version version, Path serverJar, DependencyHashCache depCache, Path mappingsPath) throws IOException {
        boolean bundled = true;
        try (FileSystem fs = FileSystems.newFileSystem(serverJar, Map.of())) {
            Path mfp = fs.getPath("META-INF", "MANIFEST.MF");
            if (!Files.exists(mfp)) {
                bundled = false; // We assume not bundled
            } else {
                Manifest mf;
                try (InputStream is = Files.newInputStream(mfp)) {
                    mf = new Manifest(is);
                }

                String format = mf.getMainAttributes().getValue(FORMAT);
                if (format == null)
                    bundled = false; // We assume not bundled
            }
        }

        var key = getKey(version, depCache);
        var keyF = cache.resolve(SERVER_EXTRACTED_JAR_CACHE_FILENAME);
        var extractedServerJar = cache.resolve(SERVER_EXTRACTED_JAR_FILENAME);

        if (!Files.exists(extractedServerJar) || !key.isValid(keyF)) {
            LOGGER.debug("Extracting server jar");

            if (bundled) {
                var stdout = System.out;
                try (var ps = new PrintStream(OutputStream.nullOutputStream())) {
                    System.setOut(ps); // Turn off installertools log output

                    new BundlerExtract().process(new String[]{"--input", serverJar.toString(), "--output", extractedServerJar.toString(), "--jar-only"});
                } finally {
                    System.setOut(stdout);
                }
            } else {
                if (mappingsPath != null)
                    deleteExtraFiles(serverJar, extractedServerJar, mappingsPath);
            }

            key.write(keyF);
        }

        return extractedServerJar;
    }

    /**
     * Bundling was introduced in 21w39a, the 3rd snapshot in the 1.18 development cycle.
     * For versions pre-21w39a, {@code server.jar} is not bundled, and libraries are shaded directly into the jar.
     * These shaded library files (both class files and resources) are not relevant to decompilation.
     * <p>
     * These versions are also obfuscated, so they have official mappings. To ensure decompilation is ONLY performed
     * on Minecraft/Mojang classes, we can filter the server jar to JUST include Minecraft/Mojang classes (and no resources)
     * by using the mappings file as a guide. This also makes the assumption that any resources present in the server jar
     * are also present in the client jar and have the same contents. This debloats the joined jar and speeds up decompilation.
     */
    private static void deleteExtraFiles(Path serverJar, Path extractedServerJar, Path mappingsPath) throws IOException {
        Files.deleteIfExists(extractedServerJar);

        IMappingFile mappings;
        try (var in = Files.newInputStream(mappingsPath)) {
            // The server isn't bundled here, so it must be obfuscated.
            // Thus, we need to look up by obfuscated name here.
            mappings = IMappingFile.load(in).reverse();
        }

        try (var inFs = FileSystems.newFileSystem(serverJar);
                var outFs = FileSystems.newFileSystem(extractedServerJar, Map.of("create", true))) {
            var inRoot = inFs.getPath("/");
            var outRoot = outFs.getPath("/");

            try (Stream<Path> walker = Files.walk(inRoot)) {
                Iterable<Path> iterable = () -> walker.filter(Files::isRegularFile).iterator();
                for (Path p : iterable) {
                    Path relative = inRoot.relativize(p);
                    if (!relative.getFileName().toString().endsWith(".class"))
                        continue;

                    String fullPathStr = relative.toString();
                    String classname = fullPathStr.substring(0, fullPathStr.length() - ".class".length());

                    // Remove any class files not present in the mappings file;
                    // this implies they are not Minecraft/Mojang classes and instead
                    // come from a shaded library.
                    if (mappings.getClass(classname) == null)
                        continue;

                    Path outPath = outRoot.resolve(fullPathStr);
                    Path outPathParent = outPath.getParent();
                    if (outPathParent != null)
                        Files.createDirectories(outPathParent);
                    Files.copy(p, outPath, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
