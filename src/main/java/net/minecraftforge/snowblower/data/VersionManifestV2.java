/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.snowblower.data;

import net.minecraftforge.snowblower.util.Util;
import net.minecraftforge.srgutils.MinecraftVersion;

import java.io.IOException;
import java.net.URL;
import java.util.Date;

public record VersionManifestV2(
    LatestInfo latest,
    VersionInfo[] versions) {

    public static VersionManifestV2 query() throws IOException {
        return Util.downloadJson(Util.makeURL("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"), VersionManifestV2.class);
    }

    public record LatestInfo(
        MinecraftVersion release,
        MinecraftVersion snapshot
    ) {}

    public record VersionInfo(
        MinecraftVersion id,
        String type,
        URL url,
        Date time,
        Date releaseTime,
        String sha1,
        int complianceLevel
    ) {}
}