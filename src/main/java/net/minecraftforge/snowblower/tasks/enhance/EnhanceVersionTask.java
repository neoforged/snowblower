/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.snowblower.tasks.enhance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraftforge.snowblower.data.Version;
import net.minecraftforge.snowblower.util.HashFunction;

public class EnhanceVersionTask {
    // TODO: I moved this out to its own package even tho its only one function, because I'm curious if we can cache it.
    // And doing that via a package level would be useful.

    private static final String GRADLE_CONTENT = """
    plugins {
        id 'java'
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(%java_version%)
        }
    }

    repositories {
        mavenCentral()
        maven {
            name = 'Mojang'
            url = 'https://libraries.minecraft.net/'
        }
    }

    dependencies {
    %deps%
    }
    """;

    public static List<Path> enhance(Path output, Version version) throws IOException {
        var data = GRADLE_CONTENT
            .replace("%java_version%", Integer.toString(version.javaVersion().majorVersion())) // This assumes the minimum to be 8 (which it is)
            .replace("%deps%", version.libraries().stream()
                    .filter(Version.Library::isAllowed)
                    .sorted()
                    .map(lib -> "    implementation '" + lib.name() + '\'')
                    .collect(Collectors.joining("\n")))
            .getBytes(StandardCharsets.UTF_8);

        var build = output.resolve("build.gradle");
        var existing = HashFunction.MD5.hash(build);
        var created = HashFunction.MD5.hash(data);
        if (!existing.equals(created)) {
            Files.write(build, data);
            return List.of(build);
        }
        return Collections.emptyList();
    }

}
