/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.snowblower.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import net.minecraftforge.srgutils.MinecraftVersion;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

public record VersionManifestV2(VersionInfo[] versions) {
    private static final URL VERSION_MANIFEST_V2_URL;
    static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(MinecraftVersion.class, (JsonDeserializer<MinecraftVersion>) (json, typeOfT, context) -> MinecraftVersion.from(json.getAsString()))
            .create();

    static {
        try {
            VERSION_MANIFEST_V2_URL = new URL("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static VersionManifestV2 query() throws IOException {
        HttpsURLConnection urlConnection = (HttpsURLConnection) VERSION_MANIFEST_V2_URL.openConnection();
        urlConnection.connect();
        return GSON.fromJson(new InputStreamReader(urlConnection.getInputStream()), VersionManifestV2.class);
    }

    public record VersionInfo(MinecraftVersion id, String type, URL url) {}
}
