/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.plugin.tasks

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.util.ConfigureUtil
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.KonanInteropSpec.IncludeDirectoriesSpec
import org.jetbrains.kotlin.gradle.plugin.model.KonanModelArtifact
import org.jetbrains.kotlin.gradle.plugin.model.KonanModelArtifactImpl
import org.jetbrains.kotlin.konan.library.defaultResolver
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.Distribution
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

/**
 * A task executing cinterop tool with the given args and compiling the stubs produced by this tool.
 */
open class KonanInteropTask: KonanBuildingTask(), KonanInteropSpec {

    @Internal override val toolRunner: KonanToolRunner = KonanInteropRunner(project)

    override fun init(config: KonanBuildingConfig<*>, destinationDir: File, artifactName: String, target: KonanTarget) {
        super.init(config, destinationDir, artifactName, target)
        this.defFile = project.konanDefaultDefFile(artifactName)
    }

    // Output directories -----------------------------------------------------

    override val artifactSuffix: String
        @Internal get() = ".klib"

    override val artifactPrefix: String
        @Internal get() = ""

    // Interop stub generator parameters -------------------------------------

    @InputFile lateinit var defFile: File

    @Optional @Input var packageName: String? = null

    @Input val compilerOpts   = mutableListOf<String>()
    @Input val linkerOpts     = mutableListOf<String>()

    @Nested val includeDirs = IncludeDirectoriesSpecImpl()

    @InputFiles val headers   = mutableSetOf<FileCollection>()
    @InputFiles val linkFiles = mutableSetOf<FileCollection>()

    override fun buildArgs() = mutableListOf<String>().apply {
        addArg("-o", artifact.canonicalPath)

        addArgIfNotNull("-target", konanTarget.visibleName)
        addArgIfNotNull("-def", defFile.canonicalPath)
        addArgIfNotNull("-pkg", packageName)

        addFileArgs("-h", headers)

        compilerOpts.forEach {
            addArg("-copt", it)
        }

        val linkerOpts = mutableListOf<String>().apply { addAll(linkerOpts) }
        linkFiles.forEach {
            linkerOpts.addAll(it.files.map { it.canonicalPath })
        }
        linkerOpts.forEach {
            addArg("-lopt", it)
        }

        addArgs("-copt", includeDirs.allHeadersDirs.map { "-I${it.absolutePath}" })
        addArgs("-headerFilterAdditionalSearchPrefix", includeDirs.headerFilterDirs.map { it.absolutePath })

        addArgs("-repo", libraries.repos.map { it.canonicalPath })

        addFileArgs("-library", libraries.files)
        addArgs("-library", libraries.namedKlibs)
        addArgs("-library", libraries.artifacts.map { it.artifact.canonicalPath })

        addKey("-nodefaultlibs", noDefaultLibs)

        addAll(extraOpts)
    }

    // region DSL.

    inner class IncludeDirectoriesSpecImpl: IncludeDirectoriesSpec {
        @Input val allHeadersDirs = mutableSetOf<File>()
        @Input val headerFilterDirs = mutableSetOf<File>()

        override fun allHeaders(vararg includeDirs: Any) = allHeaders(includeDirs.toList())
        override fun allHeaders(includeDirs: Collection<Any>) {
            allHeadersDirs.addAll(includeDirs.map { project.file(it) })
        }

        override fun headerFilterOnly(vararg includeDirs: Any) = headerFilterOnly(includeDirs.toList())
        override fun headerFilterOnly(includeDirs: Collection<Any>) {
            headerFilterDirs.addAll(includeDirs.map { project.file(it) })
        }
    }

    override fun defFile(file: Any) {
        defFile = project.file(file)
    }

    override fun packageName(value: String) {
        packageName = value
    }

    override fun compilerOpts(vararg values: String) {
        compilerOpts.addAll(values)
    }

    override fun header(file: Any) = headers(file)
    override fun headers(vararg files: Any) {
        headers.add(project.files(files))
    }
    override fun headers(files: FileCollection) {
        headers.add(files)
    }

    override fun includeDirs(vararg values: Any) = includeDirs.allHeaders(values.toList())

    override fun includeDirs(closure: Closure<Unit>) = includeDirs(ConfigureUtil.configureUsing(closure))
    override fun includeDirs(action: Action<IncludeDirectoriesSpec>) = includeDirs { action.execute(this) }
    override fun includeDirs(configure: IncludeDirectoriesSpec.() -> Unit) = includeDirs.configure()

    override fun linkerOpts(vararg values: String) = linkerOpts(values.toList())
    override fun linkerOpts(values: List<String>) {
        linkerOpts.addAll(values)
    }

    override fun link(vararg files: Any) {
        linkFiles.add(project.files(files))
    }
    override fun link(files: FileCollection) {
        linkFiles.add(files)
    }

    // endregion

    // region IDE model
    override fun toModelArtifact(): KonanModelArtifact {
        val repos = libraries.repos
        val resolver = defaultResolver(
                repos.map { it.absolutePath },
                konanTarget,
                Distribution(konanHomeOverride = project.konanHome)
        )

        return KonanModelArtifactImpl(
                artifactName,
                artifact,
                CompilerOutputKind.LIBRARY,
                konanTarget.name,
                name,
                listOfNotNull(defFile.parentFile),
                listOf(defFile),
                libraries.asFiles(resolver),
                repos.toList()
        )
    }
    // endregion
}

