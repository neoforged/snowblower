/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.tasks;

import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.DependencyHashCache;
import net.neoforged.srgutils.IMappingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MappingTask {
    public static final String MAPPINGS_FILENAME = "moj_to_obf.tsrg";
    public static final String MAPPINGS_CACHE_FILENAME = MAPPINGS_FILENAME + ".cache";
    private static final Logger LOGGER = LoggerFactory.getLogger(MappingTask.class);

    private static Cache getKey(Path cache, Version version) throws IOException {
        Path clientMappings = cache.resolve("client_mappings.txt");
        Path serverMappings = cache.resolve("server_mappings.txt");

        var key = new Cache()
                .put("client", Files.exists(clientMappings) ? clientMappings : null)
                .put("server", Files.exists(serverMappings) ? serverMappings : null);

        Version.Download clientMappingsDownload = version.downloads().get("client_mappings");
        if (clientMappingsDownload != null)
            key.put("client_mappings", clientMappingsDownload.sha1());
        Version.Download serverMappingsDownload = version.downloads().get("server_mappings");
        if (serverMappingsDownload != null)
            key.put("server_mappings", serverMappingsDownload.sha1());

        return key;
    }

    @SuppressWarnings("unused") // depCache is kept for parity with other similar methods
    public static boolean inPartialCache(Path cache, Version version, DependencyHashCache depCache) throws IOException {
        var mappings = cache.resolve(MAPPINGS_FILENAME);
        if (!Files.exists(mappings))
            return version.isUnobfuscated(); // No mappings necessary if unobfuscated, so count it as in the partial cache

        var key = getKey(cache, version);
        var keyF = cache.resolve(MAPPINGS_CACHE_FILENAME);

        return Files.exists(keyF) && key.isValid(keyF);
    }

    public static Path getMergedMappings(Path cache, Version version) throws IOException {
        boolean unobfuscated = version.isUnobfuscated();
        var clientMojToObf = downloadMappings(cache, "client");

        if (!unobfuscated && clientMojToObf == null) {
            LOGGER.debug("Client mappings not found, skipping version");
            return null;
        }

        var serverMojToObf = downloadMappings(cache, "server");

        if (!unobfuscated && serverMojToObf == null) {
            LOGGER.debug("Server mappings not found, skipping version");
            return null;
        }

        if (clientMojToObf == null && serverMojToObf == null)
            return null;

        if (clientMojToObf != null && serverMojToObf != null && !canMerge(clientMojToObf, serverMojToObf))
            throw new IllegalStateException("Client mappings for " + version.id() + " are not a strict superset of the server mappings.");

        var key = getKey(cache, version);
        var keyF = cache.resolve(MAPPINGS_CACHE_FILENAME);
        var ret = cache.resolve(MAPPINGS_FILENAME);

        if (!Files.exists(ret) || !key.isValid(keyF)) {
            var mappingsToWrite = clientMojToObf != null ? clientMojToObf : serverMojToObf;
            mappingsToWrite.write(ret, IMappingFile.Format.TSRG2, false);
            key.write(keyF);
        }

        return ret;
    }

    private static IMappingFile downloadMappings(Path cache, String type) throws IOException {
        var mappings = cache.resolve(type + "_mappings.txt");

        if (!Files.exists(mappings))
            return null; // Downloaded ahead of time by ArtifactDiscoverer

        try (var in = Files.newInputStream(mappings)) {
            return IMappingFile.load(in);
        }
    }

    // https://github.com/LexManos/MappingToy/blob/master/src/main/java/net/minecraftforge/lex/mappingtoy/MappingToy.java#L271
    private static boolean canMerge(IMappingFile client, IMappingFile server) {
        // Test if the client is a strict super-set of server.
        // If so, the client mappings can be used for the joined jar.
        final Function<IMappingFile.IField, String> fldToString = fld -> fld.getOriginal() + " " + fld.getDescriptor() + " -> " + fld.getMapped() + " " + fld.getMappedDescriptor();
        final Function<IMappingFile.IMethod, String> mtdToString = mtd -> mtd.getOriginal() + " " + mtd.getDescriptor() + " -> " + mtd.getMapped() + " " + mtd.getMappedDescriptor();

        for (IMappingFile.IClass clsS : server.getClasses()) {
            IMappingFile.IClass clsC = client.getClass(clsS.getOriginal());
            if (clsC == null || !clsS.getMapped().equals(clsC.getMapped()))
                return false;

            Set<String> fldsS = clsS.getFields().stream().map(fldToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> fldsC = clsC.getFields().stream().map(fldToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> mtdsS = clsS.getMethods().stream().map(mtdToString).collect(Collectors.toCollection(HashSet::new));
            Set<String> mtdsC = clsC.getMethods().stream().map(mtdToString).collect(Collectors.toCollection(HashSet::new));

            fldsS.removeAll(fldsC);
            mtdsS.removeAll(mtdsC);

            if (!fldsS.isEmpty() || !mtdsS.isEmpty())
                return false;
        }

        return true;
    }
}
