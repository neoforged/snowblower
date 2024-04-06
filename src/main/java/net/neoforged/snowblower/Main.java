/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.neoforged.snowblower.data.Config;
import net.neoforged.snowblower.data.Config.BranchSpec;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.Util;
import net.neoforged.srgutils.MinecraftVersion;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();
        var outputO = parser.accepts("output", "Output directory to put the git directory in").withRequiredArg().ofType(File.class).required();
        var cacheO = parser.accepts("cache", "Cache directory to hold all files related to a version. If omitted, goes to ./cache").withRequiredArg().ofType(File.class);
        var extraMappingsO = parser.accepts("extra-mappings", "When set, points to a directory with extra mappings files").withRequiredArg().ofType(File.class);
        var startVerO = parser.accepts("start-ver", "The starting Minecraft version to generate from (inclusive). If omitted, defaults to oldest while respecting --releases-only").withRequiredArg();
        var targetVerO = parser.accepts("target-ver", "The target Minecraft version to generate up to (inclusive). If omitted, defaults to latest while respecting --releases-only").withRequiredArg();
        var branchNameO =
                parser.acceptsAll(List.of("branch-name", "branch"), "The Git branch name, creating an orphan branch if it does not exist. Uses checked out branch if omitted").withRequiredArg();
        var releasesOnlyO = parser.accepts("releases-only", "When set, only release versions will be considered");
        var startOverO = parser.accepts("start-over", "Whether to start over by deleting the target branch");
        var startOverIfRequiredO = parser.accepts("start-over-if-required", "Whether to start over by deleting the target branch, only if it is necessary to do so").availableUnless("start-over");
        var configO = parser.accepts("cfg", "Config file for SnowBlower").withRequiredArg().ofType(URI.class);
        var remoteO = parser.accepts("remote", "The URL of the Git remote to use").withRequiredArg().ofType(URL.class);
        var checkoutO = parser.accepts("checkout", "Whether to checkout the remote branch (if it exists) before generating").availableIf("remote");
        var pushO = parser.accepts("push", "Whether to push the branch to the remote once finished").availableIf("remote");
        var committerO = parser.accepts("committer", "The name and email of the user to use as the committer, separated by a space. If omitted, defaults to snowforge").withRequiredArg();
        var partialCache = parser.accepts("partial-cache", "If true, the cache will be partial, meaning that the server and client jar will be deleted, leaving only the joined jar. The SHA in the version manifest will be used to determine whether the joined jar should be remade");
        
        var excludeO = parser.accepts("exclude", "A glob pattern (see FileSystem#getPathMatcher) for excluding files from the output").withRequiredArg().ofType(String.class);
        var includeO = parser.accepts("include", "A glob pattern (see FileSystem#getPathMatcher) for including only specified files from the output").withRequiredArg().ofType(String.class);

        var githubAppId = parser.accepts("github-app-id", "The ID of a GitHub app to use for git auth").withRequiredArg().ofType(String.class);
        var githubInstallationRepo = parser.accepts("github-installation-repo", "The name of the repository to use as the installation target of the GitHub app").availableIf(githubAppId).withRequiredArg();

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.err.println();
            parser.printHelpOn(System.err);
            System.exit(1);
            return;
        }

        if (options.has(githubInstallationRepo) && System.getenv("GITHUB_APP_KEY") != null) {
            final var splitRepo = options.valueOf(githubInstallationRepo).split("/");
            CredentialsProvider.setDefault(GitHubAppCredentials.jwt(options.valueOf(githubAppId),
                    GitHubAppCredentials.parsePKCS8(System.getenv("GITHUB_APP_KEY")),
                    app -> app.getInstallationByRepository(splitRepo[0], splitRepo[1]).createToken().create()));
        }

        var depHashCacheUrl = Main.class.getResource("/dependency_hashes.txt");
        if (depHashCacheUrl == null)
            throw new IllegalStateException("Could not find dependency_hashes.txt on classpath");
        var depCache = DependencyHashCache.load(Util.getPath(depHashCacheUrl.toURI()));

        File output = options.valueOf(outputO);
        File cache = options.valueOf(cacheO);
        Path cachePath = cache == null ? Paths.get("cache") : cache.toPath();
        File extraMappings = options.valueOf(extraMappingsO);
        Path extraMappingsPath = extraMappings == null ? null : extraMappings.toPath();
        boolean startOver = options.has(startOverO);
        boolean startOverIfRequired = !startOver && options.has(startOverIfRequiredO);
        boolean partialCachce = options.has(partialCache);
        URL remote = options.has(remoteO) ? options.valueOf(remoteO) : null;
        boolean checkout = options.has(checkoutO);
        boolean push = options.has(pushO);
        List<String> includes = options.valuesOf(includeO);
        List<String> excludes = options.valuesOf(excludeO);

        var startVer = options.has(startVerO) ? MinecraftVersion.from(options.valueOf(startVerO)) : null;
        var targetVer = options.has(targetVerO) ? MinecraftVersion.from(options.valueOf(targetVerO)) : null;
        if (targetVer != null && targetVer.compareTo(startVer) < 0)
            throw new IllegalArgumentException("Target version must be greater than or equal to start version");
        var cliBranch = new BranchSpec(options.has(releasesOnlyO) ? "release" : "all", startVer, targetVer);

        String branchName = options.valueOf(branchNameO);

        Config cfg;
        if (options.has(configO)) {
            URI configUri = options.valueOf(configO);
            try {
                URL url = configUri.toURL();
                if ("file".equals(configUri.getScheme())) {
                    cfg = Config.load(Util.getPath(configUri));
                } else {
                    cfg = Util.downloadJson(url, Config.class);
                }
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        } else {
            Map<String, BranchSpec> branches = new HashMap<>();
            branches.put("release", new BranchSpec("release", null, null));
            branches.put("dev", new BranchSpec("all", null, null));
            cfg = new Config(branches);
        }

        if (options.has(committerO)) {
            final String[] committer = options.valueOf(committerO).split(" ");
            if (committer.length != 2) {
                throw new IllegalArgumentException("Committer should be in the format 'name email'!");
            }
            Util.COMMITTER = new PersonIdent(committer[0], committer[1]);
        }

        try (var gen = new Generator(output.toPath(), cachePath, extraMappingsPath, depCache, includes, excludes)) {
            gen.setup(branchName, remote, checkout, push, cfg, cliBranch, startOver, startOverIfRequired, partialCachce);
            gen.run();
        }
    }
}
