/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.snowblower.util;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
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
import org.jetbrains.annotations.Nullable;

public class Util {
    public static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(MinecraftVersion.class, (JsonDeserializer<MinecraftVersion>) (json, typeOfT, context) -> MinecraftVersion.from(json.getAsString()))
        .create();
    public static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.of(5, ChronoUnit.SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    public static PersonIdent COMMITTER = new PersonIdent("snowforge[bot]", "127516132+snowforge[bot]@users.noreply.github.com");

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

        downloadFile(logger, output, download.url(), download.sha1());

        return true;
    }

    public static void downloadFile(Consumer<String> logger, Path file, URL url, @Nullable String sha1) throws IOException {
        if (Files.exists(file)) {
            Files.delete(file);
        } else {
            Files.createDirectories(file.getParent());
        }

        download(logger, url, () -> HttpResponse.BodyHandlers.ofFile(file));

        if (sha1 != null) {
            var actual = HashFunction.SHA1.hash(file);
            if (!actual.equals(sha1)) {
                Files.delete(file);
                throw new IOException("Failed to download " + url + " Invalid Hash:\n" +
                        "    Expected: " + sha1 + "\n" +
                        "    Actual: " + actual);
            }
        }
    }

    private static <T> HttpResponse<T> download(Consumer<String> logger, URL url, Supplier<HttpResponse.BodyHandler<T>> bodyHandlerFactory) throws IOException {
        logger.accept("  Downloading " + url);
        int maxAttempts = 10;
        int attempts = 1;
        long waitTime = 1_000L;
        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        HttpRequest httpRequest = getHttpRequest(uri);

        while (true) {
            IOException ioException = null;
            HttpResponse<T> httpResponse = null;

            try {
                httpResponse = HTTP_CLIENT.send(httpRequest, bodyHandlerFactory.get());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                ioException = e;
            }

            if (httpResponse == null || httpResponse.statusCode() != HttpURLConnection.HTTP_OK) {
                if (attempts == maxAttempts) {
                    String errorMessage = "    Failed to download " + url + " - exceeded max attempts of " + maxAttempts;
                    if (ioException != null) {
                        throw new IOException(errorMessage, ioException);
                    } else if (httpResponse != null) {
                        throw new IOException(errorMessage + ", response code: " + httpResponse.statusCode());
                    } else {
                        throw new IOException(errorMessage);
                    }
                }

                String error = httpResponse == null ? Objects.toString(ioException) : "HTTP Response Code " + httpResponse.statusCode();
                logger.accept("    Failed to download, attempt: " + attempts + "/" + maxAttempts + ", error: " + error + ", retrying in " + waitTime + " ms...");
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                waitTime = waitTime * ((long) Math.pow(2, attempts));

                attempts++;

                continue;
            }

            return httpResponse;
        }
    }

    private static HttpRequest getHttpRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Cache-Control", "no-store,max-age=0,no-cache")
                .header("Expires", "0")
                .header("Pragma", "no-cache")
                .GET()
                .build();
    }

    public static <T> T downloadJson(Consumer<String> logger, URL url, Class<T> type) throws IOException {
        try (var in = new InputStreamReader(download(logger, url, HttpResponse.BodyHandlers::ofInputStream).body())) {
            return GSON.fromJson(in, type);
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
        var timed = new PersonIdent(COMMITTER, time);
        git.commit()
            .setMessage(message)
            .setAuthor(timed)
            .setCommitter(timed)
            .setSign(false)
            .call();
    }

    public static void add(Git git, Path file) throws GitAPIException {
        var root = git.getRepository().getDirectory().getParentFile().toPath();
        var path = root.toAbsolutePath().relativize(file.toAbsolutePath());
        git.add().addFilepattern(path.toString()).call();
    }

    public static URL makeURL(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) { // God I hate the URL class
            throw new RuntimeException(e);
        }
    }

    public static <T> Collector<T, ?, Map<Integer, List<T>>> partitionEvery(final int chunkSize) {
        final AtomicInteger counter = new AtomicInteger();
        return Collectors.groupingBy(it -> counter.getAndIncrement() / chunkSize);
    }

}
