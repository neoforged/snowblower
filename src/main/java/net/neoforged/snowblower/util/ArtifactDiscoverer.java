/*
 * Copyright (c) NeoForged
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.util;

import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.data.VersionManifestV2;
import net.neoforged.snowblower.github.GitHubActions;
import net.neoforged.snowblower.tasks.MergeRemapTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ArtifactDiscoverer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactDiscoverer.class);

    public static void downloadArtifacts(Path rootCache, Path libCache, Path extraMappings, List<VersionManifestV2.VersionInfo> versions, boolean partialCache) throws IOException {
        LOGGER.info("Discovering and downloading artifacts for {} versions", versions.size());
        GitHubActions.logStartGroup("Discovering and downloading artifacts");

        try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            for (var versionInfo : versions) {
                Path versionCache = rootCache.resolve(versionInfo.id().toString());
                Version version = Version.load(versionCache.resolve("version.json"));

                executor.submit(() -> {
                    downloadVersion(libCache, extraMappings, partialCache, versionCache, version);

                    return null;
                });
            }

            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.MINUTES))
                throw new RuntimeException("Failed to download all artifacts within 10 minutes? Are we connected to the Internet?");
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted during artifact discovery", e);
        }

        GitHubActions.logEndGroup();
    }

    private static void downloadVersion(Path libCache, Path extraMappings, boolean partialCache, Path versionCache, Version version) throws IOException {
        // Client and server mappings
        downloadMappings(versionCache, extraMappings, version, "client");
        downloadMappings(versionCache, extraMappings, version, "server");

        // Client and server jar
        if (!partialCache) {
            // Only download client and server jar ahead of time if the partial cache is disabled;
            // otherwise, we may be able to skip if the joined jar is downloaded and up-to-date
            MergeRemapTask.downloadMinecraftJar("client", versionCache, version);
            MergeRemapTask.downloadMinecraftJar("server", versionCache, version);
        }

        // Libraries
        downloadLibraries(libCache, version);
    }

    private static Void downloadMappings(Path versionCache, Path extraMappings, Version version, String type) throws IOException {
        var mappings = versionCache.resolve(type + "_mappings.txt");

        if (!Files.exists(mappings)) {
            if (extraMappings != null) {
                Path extraMap = extraMappings.resolve(version.type())
                        .resolve(version.id().toString())
                        .resolve("maps")
                        .resolve(type + ".txt");

                if (Files.exists(extraMap)) {
                    Files.copy(extraMap, mappings, StandardCopyOption.REPLACE_EXISTING);
                    return null;
                }
            }

            if (version.isUnobfuscated())
                return null;

            Util.downloadFile(mappings, version, type + "_mappings");
        }

        return null;
    }

    private static Set<String> librariesInProgress = new HashSet<>();

    private static void downloadLibraries(Path libCache, Version version) throws IOException {
        if (version.libraries() == null)
            return;

        for (var lib : version.libraries()) {
            if (lib.downloads() == null || !lib.downloads().containsKey("artifact"))
                continue;

            var dl = lib.downloads().get("artifact");
            if (dl.path().contains("../")) {
                // Just in case...
                throw new IllegalStateException("Detected path traversal attack in library path! Is this a legit version? Version: "
                        + version.id() + ", library path: " + dl.path());
            }

            var target = libCache.resolve(dl.path());

            if (!Files.exists(target)) {
                synchronized (ArtifactDiscoverer.class) {
                    if (!librariesInProgress.add(dl.path()))
                        continue;
                }

                Files.createDirectories(target.getParent());
                Util.downloadFile(target, dl.url(), dl.sha1());

                synchronized (ArtifactDiscoverer.class) {
                    librariesInProgress.remove(dl.path());
                }
            }
        }
    }
}
