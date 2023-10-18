/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.snowblower.data;

import net.minecraftforge.snowblower.util.Util;
import net.minecraftforge.srgutils.MinecraftVersion;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public record Version(
    MinecraftVersion id,
    Date time,
    Date releaseTime,
    String type,
    Map<String, Download> downloads,
    List<Library> libraries,
    JavaVersion javaVersion) {

    public static Version load(Path file) throws IOException {
        try (var in = new InputStreamReader(Files.newInputStream(file))) {
            return Util.GSON.fromJson(in, Version.class);
        }
    }

    public record Download(String path, String sha1, int size, URL url) {}

    public record Library(Map<String, Download> downloads, String name, Map<String, ?>[] rules) implements Comparable<Library> {
        @Override
        public int compareTo(Library o) {
            return this.name.compareTo(o.name);
        }

        @SuppressWarnings("unchecked")
        public boolean isAllowed() {
            if (this.rules == null || this.rules.length == 0)
                return true;

            for (Map<String, ?> rule : this.rules) {
                boolean matched = true;
                if (!(rule.get("action") instanceof String action))
                    return false;

                if (rule.get("os") instanceof Map) {
                    Map<String, ?> os = (Map<String, ?>) rule.get("os");
                    if (matched && os.get("name") instanceof String osName)
                        matched = getOsName().equals(osName);
                    if (matched && os.get("version") instanceof String osVersion)
                        matched = Pattern.compile(osVersion).matcher(System.getProperty("os.version")).find();
                    if (matched && os.get("arch") instanceof String osArch)
                        matched = Pattern.compile(osArch).matcher(System.getProperty("os.arch")).find();
                }

                if (matched && "allow".equals(action))
                    return true;
            }

            return false;
        }

        private static String getOsName() {
            String name = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
            if (name.contains("windows") || name.contains("win"))
                return "windows";
            if (name.contains("linux") || name.contains("unix"))
                return "linux";
            if (name.contains("osx") || name.contains("mac"))
                return "osx";
            return "unknown";
        }
    }

    public record JavaVersion(int majorVersion) {}
}
