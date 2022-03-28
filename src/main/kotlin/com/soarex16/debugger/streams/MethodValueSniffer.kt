// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.soarex16.debugger.streams

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.engine.requests.RequestManagerImpl
import com.intellij.debugger.impl.PrioritizedTask
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.ui.breakpoints.FilteredRequestor
import com.intellij.debugger.ui.breakpoints.FilteredRequestorImpl
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.xdebugger.XDebugSession
import com.sun.jdi.ClassNotPreparedException
import com.sun.jdi.Method
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.request.MethodExitRequest

class MethodValueSniffer {
    companion object {
        private val LOG = Logger.getInstance(MethodValueSniffer::class.java)

        @JvmStatic
        fun modifyMethodValue(
            session: XDebugSession?,
            method: PsiMethod,
            updater: (VirtualMachine, Value?) -> Value?
        ) {
            if (session == null) return

            val xDebugProcess = session.debugProcess as? JavaDebugProcess ?: return
            val javaDebuggerSession = xDebugProcess.debuggerSession
            val debugProcess = javaDebuggerSession.process
            val vm: VirtualMachine = debugProcess.virtualMachineProxy.virtualMachine

            if (!vm.canGetMethodReturnValues()) {
                LOG.info("Can't modify method return value because vm version (${vm.version()}) does not supports this feature")
            }

            runInDebuggerThread(debugProcess) {
                val className = method.containingClass?.qualifiedName
                val vmClass = vm.classesByName(className).firstOrNull()

                if (vmClass == null) {
                    LOG.info("Method $className not found by jvm")
                    return@runInDebuggerThread
                }

                try {
                    val vmMethod = vmClass.methods().find {
                        it.name() == method.name
                                && it.returnTypeName() == method.returnType?.presentableText
                                && it.argumentTypeNames() == method.parameterList.parameters.map { param -> param.type.presentableText }
                    }

                    if (vmMethod == null) {
                        LOG.info("Method does not exists in $className")
                        return@runInDebuggerThread
                    }

                    val requestor = ValueSnifferRequestor(debugProcess.project, vmMethod, updater)
                    createMethodBreakpoint(debugProcess, vmMethod, requestor)
                } catch (e: ClassNotPreparedException) {
                    LOG.warn(e)
                }
            }
        }

        private fun createMethodBreakpoint(
            debugProcess: DebugProcessImpl,
            vmMethod: Method,
            requestor: FilteredRequestor
        ) {
            val requestManager: RequestManagerImpl = debugProcess.requestsManager ?: return
            val methodExitRequest: MethodExitRequest = requestManager.createMethodExitRequest(requestor)
            methodExitRequest.enable()
            methodExitRequest.addClassFilter(vmMethod.declaringType())
        }

        private class ValueSnifferRequestor(
            project: Project,
            val method: Method,
            val updater: (VirtualMachine, Value?) -> Value?
        ) : FilteredRequestorImpl(project) {
            override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent?): Boolean {
                if (event == null) return false
                val context = action.suspendContext ?: return false
                val debugProcess = context.debugProcess
                val vm = debugProcess.virtualMachineProxy.virtualMachine

                if (event.location().method() == method) {
                    val thread = context.thread ?: return false
                    val ev = event as? MethodExitEvent ?: return false

                    try {
                        val originalReturnValue = ev.returnValue()
                        val replacedReturnValue = try {
                            updater(vm, originalReturnValue)
                        } catch (e: Throwable) {
                            LOG.info("value modification error", e)
                            return false
                        }
                        thread.forceEarlyReturn(replacedReturnValue)
                    } catch (e: UnsupportedOperationException) {
                        LOG.warn(e)
                    } finally {
                        debugProcess.requestsManager.deleteRequest(this)
                    }
                }

                return false
            }

            override fun getSuspendPolicy(): String = DebuggerSettings.SUSPEND_ALL
        }

        private fun runInDebuggerThread(debugProcess: DebugProcessImpl, action: () -> Unit) {
            val command = object : DebuggerCommandImpl(PrioritizedTask.Priority.NORMAL) {
                override fun action() {
                    action()
                }
            }

            val managerThread = debugProcess.managerThread
            if (DebuggerManagerThreadImpl.isManagerThread()) {
                managerThread.invoke(command)
            } else {
                managerThread.schedule(command)
            }
        }
    }
}