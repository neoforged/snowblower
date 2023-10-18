/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.snowblower.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

abstract class ShadowJarFixupTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getInput()

    @OutputFile
    abstract RegularFileProperty getOutput()

    @TaskAction
    void run() {
        try (def zipFs = FileSystems.newFileSystem(input.get().asFile.toPath(), new HashMap<String, Object>())) {
            Path start = zipFs.getPath('/gradle/wrapper/gradle-wrapper.zip')
            Path end = zipFs.getPath('/gradle/wrapper/gradle-wrapper.jar')

            if (Files.isRegularFile(start) && !Files.exists(end))
                Files.move(start, end)
        }
    }
}
