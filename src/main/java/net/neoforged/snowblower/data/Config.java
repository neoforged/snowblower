/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.data;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.neoforged.snowblower.util.Util;
import net.neoforged.srgutils.MinecraftVersion;
import org.jetbrains.annotations.Nullable;

public record Config(Map<String, BranchSpec> branches) {
    public static Config load(Path file) throws IOException {
        try (var in = new InputStreamReader(Files.newInputStream(file))) {
            return Util.GSON.fromJson(in, Config.class);
        }
    }

    public record BranchSpec(
        String type,
        @Nullable MinecraftVersion start,
        @Nullable MinecraftVersion end,
        @Nullable List<MinecraftVersion> versions,
        @Nullable List<MinecraftVersion> includeVersions,
        @Nullable List<MinecraftVersion> excludeVersions
    ) {
        public BranchSpec(String type, MinecraftVersion start, MinecraftVersion end) {
            this(type, start, end, null, null, null);
        }
    }
}
