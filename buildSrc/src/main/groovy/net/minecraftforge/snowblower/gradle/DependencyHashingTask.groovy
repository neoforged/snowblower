/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.snowblower.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.security.MessageDigest

abstract class DependencyHashingTask extends DefaultTask {
    @InputFiles
    protected abstract ConfigurableFileCollection getInternalInputFiles()

    @Internal
    abstract Property<Configuration> getConfiguration()

    @OutputFile
    abstract RegularFileProperty getOutput()

    DependencyHashingTask() {
        internalInputFiles.from(configuration)
        output.convention(project.layout.buildDirectory.file('dependency_hashes.txt'))
    }

    @TaskAction
    void run() {
        output.get().asFile.text = configuration.get().resolvedConfiguration.firstLevelModuleDependencies.collect { dep ->
            byte[] hash = MessageDigest.getInstance('SHA-1').digest(dep.moduleArtifacts.iterator().next().file.bytes)
            String hashString = hash.encodeHex().toString()
            // "${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}=$hashString"
            "${dep.moduleGroup}:${dep.moduleName}=$hashString # ${dep.moduleVersion}"
        }.join('\n')
    }
}
