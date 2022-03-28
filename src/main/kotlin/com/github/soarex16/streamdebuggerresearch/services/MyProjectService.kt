package com.github.soarex16.streamdebuggerresearch.services

import com.intellij.openapi.project.Project
import com.github.soarex16.streamdebuggerresearch.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
