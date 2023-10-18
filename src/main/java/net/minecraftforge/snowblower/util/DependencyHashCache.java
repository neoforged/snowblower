/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.snowblower.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class DependencyHashCache {
    private final Map<String, String> hashes;

    private DependencyHashCache(Map<String, String> hashes) {
        this.hashes = hashes;
    }

    public static DependencyHashCache load(Path path) throws IOException {
        Map<String, String> hashes = new HashMap<>();

        Files.readAllLines(path).forEach(line -> {
            int commentIdx = line.indexOf('#');
            int equalIdx = line.indexOf('=');
            if (commentIdx == 0 || equalIdx == -1)
                return;

            if (commentIdx != -1)
                line = line.substring(0, commentIdx).trim();

            String key = line.substring(0, equalIdx);
            String value = line.substring(equalIdx + 1);
            hashes.put(key, value);
        });

        return new DependencyHashCache(hashes);
    }

    public String getHash(String key) {
        return hashes.get(key);
    }
}
