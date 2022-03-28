package com.soarex16.debugger.streams

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil
import com.sun.jdi.IntegerValue
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine

class ModifyMethodReturnValueAction : AnAction() {
    private val LOG = Logger.getInstance(ModifyMethodReturnValueAction::class.java)

    private fun getCurrentFile(session: XDebugSession?): PsiFile? {
        val position = session?.currentPosition ?: return null
        val currentFile = position.file

        val psiManager = PsiManager.getInstance(session.project)
        return psiManager.findFile(currentFile)
    }

    override fun update(e: AnActionEvent) {
        val session = DebuggerUIUtil.getSession(e)
        val currentFile = getCurrentFile(session)

        val presentation = e.presentation
        presentation.isEnabled = !(currentFile == null || !currentFile.language.`is`(JavaLanguage.INSTANCE))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val session = DebuggerUIUtil.getSession(e)!!
        val psiFile = getCurrentFile(session)

        if (psiFile == null) {
            LOG.info("file not found")
            return
        }

        val methodName = "someFun"
        val psiMethod = PsiTreeUtil.collectElements(psiFile) { it is PsiMethod }.find { (it as PsiMethod).name == methodName } as? PsiMethod

        if (psiMethod == null) {
            LOG.info("method $methodName not found")
            return
        }

        MethodValueSniffer.modifyMethodValue(session, psiMethod) { vm: VirtualMachine, value: Value? ->
            if (value == null || value !is IntegerValue) return@modifyMethodValue value

            vm.mirrorOf(value.value() + 1)
        }
    }
}