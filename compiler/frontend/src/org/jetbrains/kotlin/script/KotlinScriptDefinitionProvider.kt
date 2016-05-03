/*
 * Copyright 2010-2015 JetBrains s.r.o.
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
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly
import java.util.*
import kotlin.concurrent.read
import kotlin.concurrent.write

class KotlinScriptDefinitionProvider {

    private val definitions: MutableList<KotlinScriptDefinition> = arrayListOf(StandardScriptDefinition)
    private val definitionsLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val notificationHandlers = ArrayList<() -> Unit>()
    private val handlersLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    fun setScriptDefinitions(newDefinitions: List<KotlinScriptDefinition>): Unit {
        var changed = false
        definitionsLock.read {
            if (newDefinitions != definitions) {
                definitionsLock.write {
                    definitions.clear()
                    definitions.addAll(newDefinitions)
                }
                changed = true
            }
        }
        if (changed) {
            handlersLock.read {
                notificationHandlers.forEach { it() }
            }
        }
    }

    fun findScriptDefinition(file: VirtualFile): KotlinScriptDefinition? = definitionsLock.read { definitions.firstOrNull { it.isScript(file) } }

    fun findScriptDefinition(psiFile: PsiFile): KotlinScriptDefinition? = definitionsLock.read { definitions.firstOrNull { it.isScript(psiFile) } }

    fun isScript(file: VirtualFile): Boolean = findScriptDefinition(file) != null

    fun isScript(psiFile: PsiFile): Boolean = findScriptDefinition(psiFile) != null

    fun subscribeOnDefinitionsChanged(handler: () -> Unit): Unit {
        handlersLock.write { notificationHandlers.add(handler) }
    }

    @TestOnly
    fun addScriptDefinition(scriptDefinition: KotlinScriptDefinition) {
        definitionsLock.write {
            definitions.add(0, scriptDefinition)
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): KotlinScriptDefinitionProvider =
                ServiceManager.getService(project, KotlinScriptDefinitionProvider::class.java)
    }
}
