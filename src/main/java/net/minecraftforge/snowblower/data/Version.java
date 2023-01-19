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

package net.minecraftforge.snowblower.data;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public record Version(Map<String, Download> downloads, List<Library> libraries, JavaVersion javaVersion) {
    public static Version query(URL url) throws IOException {
        HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
        urlConnection.connect();
        return VersionManifestV2.GSON.fromJson(new InputStreamReader(urlConnection.getInputStream()), Version.class);
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
