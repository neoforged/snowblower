/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.snowblower.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;

import net.minecraftforge.snowblower.Main;
import net.minecraftforge.snowblower.data.Version;
import net.minecraftforge.srgutils.MinecraftVersion;

public class Util {
    public static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(MinecraftVersion.class, (JsonDeserializer<MinecraftVersion>) (json, typeOfT, context) -> MinecraftVersion.from(json.getAsString()))
        .create();

    public static Path getPath(URI uri) {
        try {
            return Path.of(uri);
        } catch (FileSystemNotFoundException e) {
            if (uri.getScheme().equals("jar")) {
                try {
                    FileSystems.newFileSystem(uri, Map.of());
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
                return Path.of(uri);
            }

            throw e;
        }
    }

    public static boolean isDev() {
        return Main.class.getPackage().getImplementationVersion() == null;
    }

    public static Path getSourcePath() {
        // This should never be a jar if in dev
        Path folderPath = Path.of(getCodeSourceUri());

        while (!Files.exists(folderPath.resolve(".git"))) {
            folderPath = folderPath.getParent();
            if (folderPath == null)
                return null;
        }

        return folderPath;
    }

    public static URI getCodeSourceUri() {
        try {
            return Util.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeLines(Path target, String... lines) throws IOException {
        String attrib = String.join("\n", lines);
        Files.writeString(target, attrib);
    }

    public static boolean downloadFile(Consumer<String> logger, Path output, Version version, String key) throws IOException {
        Version.Download download = version.downloads().get(key);
        if (download == null)
            return false;

        return downloadFile(logger, output, download.url(), download.sha1());
    }

    public static boolean downloadFile(Consumer<String> logger, Path file, URL url, String sha1) throws IOException {
        logger.accept("  Downloading " + url.toString());
        var connection = (HttpURLConnection) url.openConnection();
        connection.setUseCaches(false);
        connection.setDefaultUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-store,max-age=0,no-cache");
        connection.setRequestProperty("Expires", "0");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.connect();

        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(file)) {
            copy(in, out);
        }

        if (sha1 != null) {
            var actual = HashFunction.SHA1.hash(file);
            if (!actual.equals(sha1)) {
                Files.delete(file);
                throw new IOException("Failed to download " + url + " Invalid Hash:\n" +
                        "    Expected: " + sha1 + "\n" +
                        "    Actual: " + actual);
            }
        }

        return true;
    }

    public static <T> T downloadJson(URL url, Class<T> type) throws IOException {
        // TODO Make a util function that controls the cache, and allows redirects?
        var connection = (HttpURLConnection)url.openConnection();
        connection.setUseCaches(false);
        connection.setDefaultUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-store,max-age=0,no-cache");
        connection.setRequestProperty("Expires", "0");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.connect();

        try (var in = new InputStreamReader(connection.getInputStream())) {
            return GSON.fromJson(in, type);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buf = new byte[0x100];
        int cnt;
        while ((cnt = input.read(buf, 0, buf.length)) != -1) {
            output.write(buf, 0, cnt);
        }
    }

    public static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;

        try (Stream<Path> walker = Files.walk(dir)) {
            walker.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static void commit(Git git, String message, Date time) throws GitAPIException {
        var snowblower = new PersonIdent("SnowBlower", "snow@blower.com");
        var timed = new PersonIdent(snowblower, time);
        git.commit()
            .setMessage(message)
            .setAuthor(timed)
            .setCommitter(timed)
            .setSign(false)
            .call();
    }

    public static void add(Git git, Path file) throws GitAPIException {
        var root = git.getRepository().getDirectory().getParentFile().toPath();
        var path = root.relativize(file);
        git.add().addFilepattern(path.toString()).call();
    }

    public static URL makeURL(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) { // God I hate the URL class
            throw new RuntimeException(e);
        }
    }

}
