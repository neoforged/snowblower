/*
 * Copyright (c) NeoForged
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.util;

import net.neoforged.snowblower.data.MinecraftVersion;
import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.data.VersionManifestV2;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.stream.Collectors;

public class UnobfuscatedVersions {
    private static final MinecraftVersion VER1_21_11_UNOBFUSCATED = MinecraftVersion.from("1.21.11_unobfuscated");
    private static final String UNOBF_ROOT = "/unobfuscated";
    // Base version (e.g., 1.21.11) -> unobfuscated version info generated from the version info JSON
    private static Map<MinecraftVersion, VersionManifestV2.VersionInfo> unobfuscatedVersions;

    public static List<MinecraftVersion> getVersionsToExclude() {
        List<MinecraftVersion> result = unobfuscatedVersions.values().stream().map(VersionManifestV2.VersionInfo::id).collect(Collectors.toList());

        // Keep 1.21.11_unobfuscated in the version list so that we get a cleaner diff with versions that come later,
        // since versions past 1.21.11 are unobfuscated and preserve the LVT names.
        // Using the LVT, the decompiled output will have the original names for all parameters and local variables.
        result.remove(VER1_21_11_UNOBFUSCATED);

        return result;
    }

    public static void injectUnobfuscatedVersions(List<VersionManifestV2.VersionInfo> versions) throws IOException {
        loadUnobfuscatedVersions();

        ListIterator<VersionManifestV2.VersionInfo> iterator = versions.listIterator();

        while (iterator.hasNext()) {
            VersionManifestV2.VersionInfo versionInfo = iterator.next();

            VersionManifestV2.VersionInfo unobfVersionInfo = unobfuscatedVersions.get(versionInfo.id());
            if (unobfVersionInfo == null)
                continue;

            // Insert the unobfuscated variant just after the main version so that it is counted as newer
            iterator.add(unobfVersionInfo);
        }
    }

    private static void loadUnobfuscatedVersions() throws IOException {
        if (unobfuscatedVersions != null)
            return;

        URL folderUrl = UnobfuscatedVersions.class.getResource(UNOBF_ROOT);
        if (folderUrl == null)
            throw new RuntimeException("Failed to find " + UNOBF_ROOT + " root in resources? This should never happen!");
        URI folderUri;
        try {
            folderUri = folderUrl.toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        unobfuscatedVersions = new HashMap<>();
        try (var zipFs = "jar".equals(folderUrl.getProtocol()) ? FileSystems.newFileSystem(folderUri, Map.of()) : null) {
            Path dirPath = zipFs == null ? Path.of(folderUri) : zipFs.getPath(UNOBF_ROOT);

            try (var walker = Files.walk(dirPath)) {
                Iterable<Path> iterable = () -> walker.filter(Files::isRegularFile).iterator();
                for (Path p : iterable) {
                    String filename = p.getFileName().toString();
                    if (!filename.endsWith(".json"))
                        continue;

                    URL url = UnobfuscatedVersions.class.getResource(UNOBF_ROOT + '/' + dirPath.relativize(p).toString().replace('\\', '/'));
                    Version version;
                    try (var in = new InputStreamReader(Files.newInputStream(p))) {
                        version = Util.GSON.fromJson(in, Version.class);
                    }

                    MinecraftVersion baseMcVer = MinecraftVersion.from(version.id().toString().replace("_unobfuscated", ""));
                    unobfuscatedVersions.put(baseMcVer, new VersionManifestV2.VersionInfo(version.id(), version.type(), url,
                            version.time(), version.releaseTime(), HashFunction.SHA1.hash(p), 1));
                }
            }
        }
    }
}
