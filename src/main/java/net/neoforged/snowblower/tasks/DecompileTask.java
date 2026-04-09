/*
 * Copyright (c) NeoForged
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.tasks;

import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.snowblower.util.Tools;
import net.neoforged.snowblower.util.Util;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class DecompileTask {
    public static final String DECOMP_JAR_FILENAME = "joined-decompiled.jar";
    public static final String DECOMP_JAR_CACHE_FILENAME = DECOMP_JAR_FILENAME + ".cache";
    private static final Logger LOGGER = LoggerFactory.getLogger(DecompileTask.class);
    private static final List<String> DECOMPILE_ARGS_UNOBF = List.of(
            // For comparison, see NeoForm parameters for 26.1-snapshot-1 here:
            // https://github.com/neoforged/NeoForm/blob/64142f5933f3e68a0d73abc60f1672b1ec90d17a/settings.gradle#L36-L51
            "--decompile-inner",
            "--remove-bridge",
            "--decompile-generics",
            "--ascii-strings",
            "--remove-synthetic",
            "--include-classpath",
            "--ignore-invalid-bytecode",
            "--bytecode-source-mapping",
            "--indent-string=    ",
            "--dump-code-lines",
            // Disable the OnlyInPlugin provided by neoforged's VineflowerPlugins.
            // Dist annotations are provided in MergeRemapTask either by MergeTool for obfuscated game versions,
            // or by InstallerTool's ProcessMinecraftJar for unobfuscated game versions.
            // As of the writing of this comment (12/23/2025), Vineflower's plugin system is currently bugged,
            // leading to a lack of proper support for injecting dist annotations.
            // Specifically, plugins don't get called for classes without any concrete methods, e.g., interfaces with no
            // default methods, which means dist annotations are missed in that case when relying on the Vineflower plugin.
            // Also, the plugin itself does not respect dist annotations on nested classes (which are necessary).
            "--add-onlyin=0"
    );
    private static final List<String> DECOMPILE_ARGS_OBF;

    static {
        List<String> decompileArgsObf = new ArrayList<>(DECOMPILE_ARGS_UNOBF);

        // Use JAD-style local variable and method parameter names when dealing with obfuscated game versions.
        decompileArgsObf.addAll(List.of(
                "--use-method-parameters=0",
                "--variable-renaming=jad",
                "--rename-parameters"
        ));

        DECOMPILE_ARGS_OBF = List.copyOf(decompileArgsObf);
    }

    private static List<String> getDecompileArgs(Version version) {
        return version.isUnobfuscated() ? DECOMPILE_ARGS_UNOBF : DECOMPILE_ARGS_OBF;
    }

    private static Cache getKey(Version version, Path joined, DependencyHashCache depCache) throws IOException {
        return new Cache()
                .put(Tools.VINEFLOWER, depCache)
                .put(Tools.VINEFLOWER_PLUGINS, depCache)
                .put("joined", joined)
                .put("decompileArgs", String.join(" ", getDecompileArgs(version)));
    }

    public static boolean inPartialCache(Path cache, Version version, DependencyHashCache depCache) throws IOException {
        var decomp = cache.resolve(DECOMP_JAR_FILENAME);
        if (!Files.exists(decomp))
            return false;

        var joined = cache.resolve(MergeRemapTask.JOINED_JAR_FILENAME);
        if (!Files.exists(joined))
            return false;

        var key = getKey(version, joined, depCache);
        var keyF = cache.resolve(DECOMP_JAR_CACHE_FILENAME);
        if (!Files.exists(keyF) || !key.isValid(keyF))
            return false;

        // Ensure that no single task needs to be re-run
        return MappingTask.inPartialCache(cache, version, depCache)
                && BundlerExtractTask.inPartialCache(cache, version, depCache)
                && MergeRemapTask.inPartialCache(cache, version, depCache);
    }

    public static Path checkPartialCache(Path cache, Version version, DependencyHashCache depCache, boolean partialCache) throws IOException {
        if (!partialCache)
            return null;

        if (inPartialCache(cache, version, depCache)) {
            LOGGER.debug("Hit partial cache for decompiled jar");

            return cache.resolve(DECOMP_JAR_FILENAME);
        }

        return null;
    }

    public static Path getDecompiledJar(Path cache, Version version, Path joined, Path libCache, List<Path> libs, DependencyHashCache depCache) throws IOException {
        var key = getKey(version, joined, depCache);

        for (var lib : libs) {
            var relative = libCache.relativize(lib);
            key.put(relative.toString(), lib);
        }

        var ret = cache.resolve(DECOMP_JAR_FILENAME);
        var keyF = cache.resolve(DECOMP_JAR_CACHE_FILENAME);

        if (!Files.exists(ret) || !key.isValid(keyF)) {
            LOGGER.debug("Decompiling joined.jar");
            var cfg = cache.resolve("joined-libraries.cfg");
            Util.writeLines(cfg, libs.stream().map(l -> "-e=" + l.toString()).toArray(String[]::new));

            ConsoleDecompiler.main(Stream.concat(getDecompileArgs(version).stream(), Stream.of(
                    "-log=ERROR", // IFernflowerLogger.Severity
                    "-cfg", cfg.toString(),
                    joined.toString(),
                    ret.toString()
            )).toArray(String[]::new));

            key.write(keyF);
        }

        return ret;
    }
}
