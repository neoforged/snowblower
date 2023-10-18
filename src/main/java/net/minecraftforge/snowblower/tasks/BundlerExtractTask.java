/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.snowblower.tasks;

import net.minecraftforge.installertools.BundlerExtract;
import net.minecraftforge.snowblower.util.Cache;
import net.minecraftforge.snowblower.util.DependencyHashCache;
import net.minecraftforge.snowblower.util.Tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class BundlerExtractTask {
    private static final Attributes.Name FORMAT = new Attributes.Name("Bundler-Format");

    public static Path getExtractedServerJar(Consumer<String> logger, Path cache, Path serverJar, DependencyHashCache depCache) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(serverJar, Map.of())) {
            Path mfp = fs.getPath("META-INF", "MANIFEST.MF");
            if (!Files.exists(mfp))
                return serverJar; // We assume already extracted

            Manifest mf;
            try (InputStream is = Files.newInputStream(mfp)) {
                mf = new Manifest(is);
            }

            String format = mf.getMainAttributes().getValue(FORMAT);
            if (format == null)
                return serverJar; // We assume already extracted
        }

        var key = new Cache()
                .put(Tools.INSTALLERTOOLS, depCache)
                .put("server", serverJar);
        var keyF = cache.resolve("server-extracted.jar.cache");
        var extractedServerJar = cache.resolve("server-extracted.jar");

        if (!Files.exists(extractedServerJar) || !key.isValid(keyF)) {
            logger.accept("  Extracting server jar");
            var stdout = System.out;
            System.setOut(new PrintStream(OutputStream.nullOutputStream())); // We turn off stdout to remove installer tools printing garbage
            new BundlerExtract().process(new String[]{"--input", serverJar.toString(), "--output", extractedServerJar.toString(), "--jar-only"});
            System.setOut(stdout);

            key.write(keyF);
        }

        return extractedServerJar;
    }
}
