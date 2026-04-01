/*
 * Copyright (c) NeoForged
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.tasks;

import net.neoforged.installertools.ProcessMinecraftJar;
import net.neoforged.mergetool.AnnotationVersion;
import net.neoforged.mergetool.Merger;
import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.Tools;
import net.neoforged.snowblower.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MergeRemapTask {
    public static final String JOINED_JAR_FILENAME = "joined.jar";
    public static final String JOINED_JAR_CACHE_FILENAME = JOINED_JAR_FILENAME + ".cache";
    private static final Logger LOGGER = LoggerFactory.getLogger(MergeRemapTask.class);

    private static Cache getKey(Version version, Path mappings, DependencyHashCache depCache) throws IOException {
        var key = new Cache()
                .put(Tools.INSTALLERTOOLS, depCache)
                .put("map", mappings)
                .put("client", getSha("client", version))
                .put("server-full", getSha("server", version));

        if (!version.isUnobfuscated())
            key.put(Tools.MERGETOOL, depCache);

        return key;
    }

    public static boolean inPartialCache(Path cache, Version version, DependencyHashCache depCache) throws IOException {
        var joined = cache.resolve(JOINED_JAR_FILENAME);
        if (!Files.exists(joined))
            return false;

        var key = getKey(version, cache.resolve(MappingTask.MAPPINGS_FILENAME), depCache);
        var keyF = cache.resolve(JOINED_JAR_CACHE_FILENAME);

        return Files.exists(keyF) && key.isValid(keyF, k -> !k.equals("server"));
    }

    public static Path getJoinedRemappedJar(Path cache, Version version, Path mappings, DependencyHashCache depCache, boolean partialCache) throws IOException {
        var joinedJar = cache.resolve(JOINED_JAR_FILENAME);

        if (partialCache && inPartialCache(cache, version, depCache)) {
            LOGGER.debug("Hit partial cache for joined jar");

            return joinedJar;
        }

        var key = getKey(version, mappings, depCache);
        var keyF = cache.resolve(JOINED_JAR_CACHE_FILENAME);

        var clientJar = downloadMinecraftJar("client", cache, version);
        var serverFullJar = downloadMinecraftJar("server", cache, version);
        var serverJar = BundlerExtractTask.getExtractedServerJar(cache, version, serverFullJar, depCache, mappings);

        key.put("client", clientJar)
                .put("server-full", serverFullJar)
                .put("server", serverJar);

        if (!Files.exists(joinedJar) || !key.isValid(keyF)) {
            LOGGER.debug("Merging client and server jars and remapping");

            Path joinedObfJar = null;
            try {
                List<String> args = new ArrayList<>();
                if (version.isUnobfuscated()) {
                    args.addAll(List.of(
                            "--input", clientJar.toString(),
                            "--input", serverJar.toString()
                    ));
                } else {
                    // If obfuscated, run MergeTool instead of using ProcessMinecraftJar to create the obfuscated joined jar
                    // so that dist annotations are respected on class members (methods & fields).
                    // Dist annotations on class members are used in older versions, e.g., certain constructors of Vector3f
                    // in at least the 1.14-1.16 era.
                    joinedObfJar = cache.resolve("joined-obf.jar");
                    Merger merger = new Merger(clientJar.toFile(), serverJar.toFile(), joinedObfJar.toFile());
                    merger.annotate(AnnotationVersion.API, true);
                    merger.keepData();
                    merger.skipMeta();
                    merger.process();

                    args.addAll(List.of("--input", joinedObfJar.toString()));
                    // Dist annotations are injected by MergeTool
                    args.add("--no-dist-annotations");
                }

                args.addAll(List.of(
                        "--output", joinedJar.toString(),
                        "--no-mod-manifest"
                ));

                if (mappings != null) {
                    args.add("--input-mappings");
                    args.add(mappings.toString());
                }

                var stdout = System.out;
                try (var ps = new PrintStream(OutputStream.nullOutputStream())) {
                    System.setOut(ps); // Turn off installertools log output

                    new ProcessMinecraftJar().process(args.toArray(String[]::new));
                } finally {
                    System.setOut(stdout);
                }
            } finally {
                if (joinedObfJar != null)
                    Files.deleteIfExists(joinedObfJar);
            }

            key.write(keyF);
        }

        if (partialCache) {
            Files.delete(clientJar);
            Files.delete(serverFullJar);
            Files.delete(serverJar);
        }

        return joinedJar;
    }

    private static String getSha(String type, Version version) {
        return version.downloads().get(type).sha1();
    }

    public static Path downloadMinecraftJar(String type, Path cache, Version version) throws IOException {
        var jar = cache.resolve(type + ".jar");
        var keyF = cache.resolve(type + ".jar.cache");
        var dl = version.downloads().get(type);
        if (dl == null || dl.sha1() == null)
            throw new IllegalStateException("Could not download \"" + type + "\" jar as version json for version \"" + version.id() + "\" doesn't have download entry");

        // We include the hash from the json, because Mojang HAS done silent updates before
        // Should we detect/notify about this somehow?
        // Plus it's faster to use the existing string instead of hashing a file.
        var key = new Cache()
                .put(type, dl.sha1());

        if (!Files.exists(jar) || !key.isValid(keyF)) {
            try {
                Util.downloadFile(jar, dl.url(), dl.sha1());
            } catch (IOException e) {
                throw new IOException("Failed to download \"" + type + "\" jar for version \"" + version.id() + "\"", e);
            }

            key.put(type, jar);
            key.write(keyF);
        }

        return jar;
    }
}
