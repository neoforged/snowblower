/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.neoforged.mergetool.AnnotationVersion;
import net.neoforged.mergetool.Merger;
import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.Tools;
import net.neoforged.snowblower.util.Util;
import net.neoforged.srgutils.IMappingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MergeTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(MergeTask.class);

    public static Path getJoinedJar(Path cache, Version version, Path mappings, DependencyHashCache depCache, boolean partialCache) throws IOException {
        var keyF = cache.resolve("joined.jar.cache");
        var joinedJar = cache.resolve("joined.jar");
        if (partialCache && Files.exists(joinedJar) && Files.exists(keyF)) {
            var key = new Cache()
                    .put(Tools.MERGETOOL, depCache)
                    .put("client", getSha("client", version))
                    .put("server-full", getSha("server", version))
                    .put("map", mappings)
                    .put(Tools.MERGETOOL, depCache);
            if (key.isValid(keyF, k -> !k.equals("server"))) {
                LOGGER.debug("  Hitting cache for joined jar");
                return joinedJar;
            }
        }

        var clientJar = getJar("client", cache, version);
        var serverFullJar = getJar("server", cache, version);
        var serverJar = BundlerExtractTask.getExtractedServerJar(cache, serverFullJar, depCache);

        var key = new Cache()
            .put(Tools.MERGETOOL, depCache)
            .put("client", clientJar)
            .put("server", serverJar)
            .put("server-full", serverFullJar)
            .put("map", mappings);

        if (!Files.exists(joinedJar) || !key.isValid(keyF)) {
            LOGGER.debug("  Merging client and server jars");
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

    private static Path getJar(String type, Path cache, Version version) throws IOException {
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
            try {
                Util.downloadFile(jar, dl.url(), dl.sha1());
            } catch (IOException e) {
                throw new IOException("Failed to download " + type + " jar", e);
            }

            key.put(type, jar);
            key.write(keyF);
        }

        return jar;
    }
}
