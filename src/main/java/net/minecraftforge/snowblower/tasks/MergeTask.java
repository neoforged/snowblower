/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.snowblower.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import net.minecraftforge.mergetool.AnnotationVersion;
import net.minecraftforge.mergetool.Merger;
import net.minecraftforge.snowblower.data.Version;
import net.minecraftforge.snowblower.util.Cache;
import net.minecraftforge.snowblower.util.DependencyHashCache;
import net.minecraftforge.snowblower.util.Tools;
import net.minecraftforge.snowblower.util.Util;
import net.minecraftforge.srgutils.IMappingFile;

public class MergeTask {
    public static Path getJoinedJar(Consumer<String> logger, Path cache, Version version, Path mappings, DependencyHashCache depCache) throws IOException {
        var clientJar = getJar(logger, "client", cache, version);
        var serverJar = BundlerExtractTask.getExtractedServerJar(logger, cache, getJar(logger, "server", cache, version), depCache);

        var key = new Cache()
            .put(Tools.MERGETOOL, depCache)
            .put("client", clientJar)
            .put("server", serverJar)
            .put("map", mappings);
        var keyF = cache.resolve("joined.jar.cache");
        var joinedJar = cache.resolve("joined.jar");

        if (!Files.exists(joinedJar) || !key.isValid(keyF)) {
            logger.accept("  Merging client and server jars");
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

    private static Path getJar(Consumer<String> logger, String type, Path cache, Version version) throws IOException {
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
                Util.downloadFile(logger, jar, dl.url(), dl.sha1());
            } catch (IOException e) {
                throw new IOException("Failed to download " + type + " jar", e);
            }

            key.put(type, jar);
            key.write(keyF);
        }

        return jar;
    }
}
