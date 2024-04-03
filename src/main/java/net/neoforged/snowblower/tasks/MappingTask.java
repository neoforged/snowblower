/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.neoforged.snowblower.data.Version;
import net.neoforged.snowblower.util.Cache;
import net.neoforged.snowblower.util.Util;
import net.neoforged.srgutils.IMappingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MappingTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(MappingTask.class);
    
    public static Path getMergedMappings(Path cache, Version version, Path extraMappings) throws IOException {
        var clientMojToObf = downloadMappings(cache, extraMappings, version, "client");

        if (clientMojToObf == null) {
            LOGGER.debug("  Client mappings not found, skipping version");
            return null;
        }

        var serverMojToObf = downloadMappings(cache, extraMappings, version, "server");

        if (serverMojToObf == null) {
            LOGGER.debug("  Server mappings not found, skipping version");
            return null;
        }

        if (!canMerge(clientMojToObf, serverMojToObf))
            throw new IllegalStateException("Client mappings for " + version.id() + " are not a strict superset of the server mappings.");

        var key = new Cache()
            // Should we add code version? I don't think it matters much for this one.
            .put("client", cache.resolve("client_mappings.txt"))
            .put("server", cache.resolve("server_mappings.txt"));
        var keyF = cache.resolve("obf_to_moj.tsrg.cache");
        var ret = cache.resolve("obf_to_moj.tsrg");

        if (!Files.exists(ret) || !key.isValid(keyF)) {
            clientMojToObf.write(ret, IMappingFile.Format.TSRG2, true);
            key.write(keyF);
        }

        return ret;
    }


    private static IMappingFile downloadMappings(Path cache, Path extraMappings, Version version, String type) throws IOException {
        var mappings = cache.resolve(type + "_mappings.txt");

        if (!Files.exists(mappings)) {
            boolean copiedFromExtra = false;

            if (extraMappings != null) {
                Path extraMap = extraMappings.resolve(version.type()).resolve(version.id().toString()).resolve("maps").resolve(type + ".txt");
                if (Files.exists(extraMap)) {
                    Files.copy(extraMap, mappings, StandardCopyOption.REPLACE_EXISTING);
                    copiedFromExtra = true;
                }
            }

            if (!copiedFromExtra && !Util.downloadFile(mappings, version, type + "_mappings"))
                return null;
        }

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
