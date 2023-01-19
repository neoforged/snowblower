/*
 * Snowblower
 * Copyright (C) 2023 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package net.minecraftforge.snowblower;

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
