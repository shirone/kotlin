/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.script

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// TODO: replace VirtualFile usage with File for performance/unnecessary dependencies reasons

class KotlinScriptExtraImportsProvider(val project: Project, private val scriptDefinitionProvider: KotlinScriptDefinitionProvider) {
    private val cacheLock = ReentrantReadWriteLock()
    private val preconfigured = hashMapOf<String, List<KotlinScriptExtraImport>>()
    private val cache = hashMapOf<String, List<KotlinScriptExtraImport>>()
    private val envVars: Map<String, List<String>> by lazy { generateKotlinScriptClasspathEnvVars(project) }
    private val notificationHandlers = ArrayList<(Iterable<String>) -> Unit>()
    private val handlersLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    init {
        val weakThis = WeakReference(this)
        scriptDefinitionProvider.subscribeOnDefinitionsChanged { weakThis.get()?.invalidateAllExtraImports() }
    }

    fun isExtraImportsConfig(file: VirtualFile): Boolean = file.name.endsWith(IMPORTS_FILE_EXTENSION)

    fun getExtraImports(vararg files: VirtualFile): List<KotlinScriptExtraImport> = getExtraImports(files.asIterable())

    fun getExtraImports(files: Iterable<VirtualFile>): List<KotlinScriptExtraImport> {
        val newCashedFiles = ArrayList<String>()
        val res = cacheLock.read {
            files.flatMap { file ->
                if (file.isValid && !file.isDirectory) {
                    preconfigured[file.path]
                    ?: cache[file.path]
                    ?: scriptDefinitionProvider.findScriptDefinition(file)?.let { def ->
                        (listOf(KotlinScriptExtraImportFromDefinition(def)) +
                         (file.parent.findFileByRelativePath(file.name + IMPORTS_FILE_EXTENSION)?.let {
                             loadScriptExtraImportConfigs(it.inputStream).map { KotlinScriptExtraImportFromConfig(it, envVars) }
                         } ?: emptyList()))
                                .apply {
                                    cacheLock.write { cache.put(file.path, this) }
                                    newCashedFiles.add(file.path)
                                }
                    }
                    ?: emptyList()
                }
                else emptyList()
            }
        }
        notifyIfAny(newCashedFiles)
        return res
    }

    fun preconfigureExtraImports(file: VirtualFile, extraImports: List<KotlinScriptExtraImport>?) {
        cacheLock.write {
            if (extraImports != null && extraImports.isNotEmpty()) {
                preconfigured[file.path] = extraImports
            }
            else {
                preconfigured.remove(file.path)
            }
        }
    }

    fun invalidateExtraImportsByImportsFiles(importsFiles: Iterable<VirtualFile>) {
        importsFiles.mapNotNull { it.parent.findFileByRelativePath(it.name.removeSuffix(IMPORTS_FILE_EXTENSION))?.path?.let { file ->
            cacheLock.write {
                 cache.remove(it.path)?.let { file }
            }
        } }.let {
            notifyIfAny(it)
        }
    }

    fun invalidateAllExtraImports() {
        cacheLock.write {
            cache.keys.toList().apply {
                cache.clear()
            }
        }.let {
            notifyIfAny(it)
        }
    }

    private fun notifyIfAny(files: Iterable<String>) {
        if (files.any()) {
            handlersLock.read {
                notificationHandlers.forEach { it(files) }
            }
        }
    }

    fun getKnownCombinedClasspath(): List<String> = cacheLock.read {
        cache.values.flatMap { it.flatMap { it.classpath } }
    }.distinct()

    fun getCombinedClasspathFor(files: Iterable<VirtualFile>): List<String> =
        getExtraImports(files)
                .flatMap { it.classpath }
                .distinct()

    fun subscribeOnExtraImportsChanged(handler: (Iterable<String>) -> Unit): Unit {
        handlersLock.write { notificationHandlers.add(handler) }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): KotlinScriptExtraImportsProvider? =
                ServiceManager.getService(project, KotlinScriptExtraImportsProvider::class.java)

        val IMPORTS_FILE_EXTENSION = ".ktsimports.xml"
    }
}