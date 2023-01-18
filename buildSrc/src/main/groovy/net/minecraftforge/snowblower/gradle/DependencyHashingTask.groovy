/*
 * Snowblower
 * Copyright (C) 2023 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package net.minecraftforge.snowblower.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.security.MessageDigest

abstract class DependencyHashingTask extends DefaultTask {
    @Input
    abstract Property<Configuration> getConfiguration()

    @OutputFile
    abstract RegularFileProperty getOutput()

    DependencyHashingTask() {
        output.convention(project.layout.buildDirectory.file('dependency_hashes.txt'))
    }

    @TaskAction
    void run() {
        output.get().asFile.text = configuration.get().resolvedConfiguration.firstLevelModuleDependencies.collect { dep ->
            def hash = MessageDigest.getInstance('SHA-1').digest(dep.moduleArtifacts.iterator().next().file.bytes)
            def hashString = hash.encodeHex().toString()
            // "${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}=$hashString"
            "${dep.moduleGroup}:${dep.moduleName}=$hashString # ${dep.moduleVersion}"
        }.join('\n')
    }
}