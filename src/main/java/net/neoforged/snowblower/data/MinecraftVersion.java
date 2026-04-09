/*
 * Copyright (c) NeoForged
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.data;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record MinecraftVersion(Type type, String version) {
    // Version formats per slicedlime - https://x.com/slicedlime/status/1995886660417192442
    // <year>.<drop>[.<hotfix>][-<snapshot-type>-<build>]
    // where <snapshot-type> can be: snapshot, pre, rc
    private static final Pattern RELEASE = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");
    private static final Pattern SNAPSHOT_PRE_RC = Pattern.compile(RELEASE.pattern() + "(?: Pre-Release |-rc-?|-pre-?|-snapshot-)\\d+");
    // Needed due to potential April Fools quirk; see comment below
    private static final Pattern OLD_SNAPSHOT_FORMAT = Pattern.compile("(\\d{2})w\\d{2}[a-z]");

    public static MinecraftVersion from(final String version) {
        String versionToCheck = version;
        if (versionToCheck.endsWith("_unobfuscated"))
            versionToCheck = versionToCheck.substring(0, versionToCheck.length() - "_unobfuscated".length());

        Type type = Type.SPECIAL;
        // Mojang released an April Fools snapshot, 26w14a, matching the old version format. This breaks the assumption
        // that special versions, like April Fools snapshots, have non-conventional versions.
        // To workaround this, any snapshot versions that match the old format but with the '26' year and beyond
        // are assumed to be special versions. (Mojang changed to the new format in 2026.)
        Matcher oldSnapshotMatcher = OLD_SNAPSHOT_FORMAT.matcher(versionToCheck);
        if (oldSnapshotMatcher.matches()) {
            int year = Integer.parseInt(oldSnapshotMatcher.group(1));
            if (year < 26) {
                // Lower than the year '26'; assume it's a regular (old format) snapshot
                type = Type.SNAPSHOT;
            }
        } else if (SNAPSHOT_PRE_RC.matcher(versionToCheck).matches()) {
            type = Type.SNAPSHOT;
        } else if (RELEASE.matcher(versionToCheck).matches()) {
            type = Type.RELEASE;
        }

        return new MinecraftVersion(type, version);
    }

    @Override
    public String toString() {
        return this.version;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || this.getClass() != o.getClass())
            return false;

        MinecraftVersion that = (MinecraftVersion) o;
        return Objects.equals(this.version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.version);
    }

    public enum Type {
        RELEASE,
        SNAPSHOT,
        SPECIAL;

        public boolean isSpecial() {
            return this == SPECIAL;
        }
    }

    public static class Deserializer implements JsonDeserializer<MinecraftVersion> {
        public static final Deserializer INSTANCE = new Deserializer();

        private Deserializer() {}

        @Override
        public MinecraftVersion deserialize(JsonElement json, java.lang.reflect.Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                return MinecraftVersion.from(json.getAsString());
            } else {
                throw new JsonParseException("Expected minecraft version string");
            }
        }
    }
}
