/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.snowblower.util;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public class Cache {
    private final Map<String, String> data = new LinkedHashMap<>();
    private String comment = null;

    public Cache comment(String... lines) {
        if (lines == null || lines.length == 0)
            comment = null;
        else
            comment = String.join("\n", lines);
        return this;
    }

    public Cache put(String key, String value) {
        data.put(key, value);
        return this;
    }

    public Cache put(String key, Path path) throws IOException {
        data.put(key, HashFunction.SHA1.hash(path));
        return this;
    }

    public Cache put(String key, DependencyHashCache depCache) {
        data.put(key, depCache.getHash(key));
        return this;
    }

    public Cache put(Class<?> codeClass) {
        try {
            Path folderPath = Util.getPath(codeClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            String packageName = codeClass.getPackageName();
            String[] packageParts = packageName.split("\\.");
            String keyPrefix = packageName.replace('.', '/') + '/';

            for (String packagePart : packageParts) {
                folderPath = folderPath.resolve(packagePart);
            }

            String className = codeClass.getName().substring(codeClass.getName().lastIndexOf('.') + 1);

            try (Stream<Path> walker = Files.list(folderPath)) {
                // Includes inner classes
                walker.filter(p -> p.getFileName().toString().startsWith(className) && Files.isRegularFile(p)).forEach(p -> {
                    try {
                        put(keyPrefix + p.getFileName().toString(), p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }

        return this;
    }

    public void write(Path target) throws IOException {
        StringBuilder buf = new StringBuilder();
        if (comment != null)
            buf.append(comment).append("\n\n");
        data.forEach((k,v) -> buf.append(k).append(": ").append(v).append('\n'));
        Files.writeString(target, buf.toString());
    }

    public boolean isValid(Path target) throws IOException {
        if (!Files.exists(target))
            return false;

        Map<String, String> existing = new HashMap<>();
        try (Stream<String> stream = Files.lines(target)) {
            stream.forEach(l -> {
                int idx = l.indexOf(' ');
                if (idx <= 1 || l.charAt(idx - 1) != ':') // We don't care about comments.
                    return;

                String key = l.substring(0, idx - 1);
                String value = l.substring(idx + 1);
                existing.put(key, value);
            });
        }
        return existing.equals(data);
    }

}
