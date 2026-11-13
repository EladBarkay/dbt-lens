package dev.dbt.intellij.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.EnvironmentUtil
import com.intellij.util.messages.Topic
import dev.dbt.intellij.artifacts.DbtArtifacts
import dev.dbt.intellij.config.DbtSettings
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

enum class DbtCliPhase {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class DbtCliState(
    val id: Long = 0,
    val command: DbtCommand? = null,
    val phase: DbtCliPhase = DbtCliPhase.IDLE,
    val message: String = "",
    val output: String = "",
    val compiled: String? = null,
) {
    val running
        get() = phase == DbtCliPhase.RUNNING
}

fun interface DbtCliListener {
    fun changed(state: DbtCliState)

    companion object {
        val TOPIC = Topic.create("DBT Lens command", DbtCliListener::class.java)
    }
}

/** One cancellable local command per IDE project. No command runs on opening a file or indexing. */
@Service(Service.Level.PROJECT)
class DbtCli(private val project: Project) : Disposable {
    private class Run(val command: DbtCommand, val id: Long) {
        val cancelled = AtomicBoolean()
        @Volatile var process: KillableProcessHandler? = null
    }

    private val lock = Any()
    private val dispatchQueued = AtomicBoolean()
    private var current: Run? = null
    @Volatile private var disposed = false
    @Volatile
    var state = DbtCliState()
        private set

    fun start(command: DbtCommand): Boolean {
        val settings = project.getService(DbtSettings::class.java).state.copy()
        val run =
            synchronized(lock) {
                if (disposed || project.isDisposed || current != null) return false
                Run(command, state.id + 1).also {
                    current = it
                    state =
                        DbtCliState(
                            it.id,
                            command,
                            DbtCliPhase.RUNNING,
                            "${command.kind.title} · ${command.context.root.fileName}",
                        )
                }
            }
        publish()
        object : Task.Backgroundable(project, "dbt: ${command.kind.title}", true) {
                override fun run(indicator: ProgressIndicator) {
                    execute(run, settings, indicator)
                }
            }
            .queue()
        return true
    }

    fun cancel() {
        val run = synchronized(lock) { current } ?: return
        run.cancelled.set(true)
        run.process?.destroyProcess()
        update(run) { it.copy(message = "Stopping dbt…") }
    }

    // Command boundary: any failure becomes the command's status message.
    @Suppress("TooGenericExceptionCaught")
    private fun execute(run: Run, settings: DbtSettings.State, indicator: ProgressIndicator) {
        var phase = DbtCliPhase.FAILED
        var message = "dbt command failed"
        val output = DbtCommandOutput()
        try {
            if (run.cancelled.get() || indicator.isCanceled) {
                phase = DbtCliPhase.CANCELLED
                message = "dbt command cancelled"
                return
            }
            indicator.isIndeterminate = true
            indicator.text = "Resolving dbt environment"
            val plan =
                DbtCommandPlanner.plan(run.command, settings, EnvironmentUtil.getEnvironmentMap())
            val manifest = run.command.context.target!!.resolve("manifest.json")
            val before = stamp(manifest)
            if (run.cancelled.get() || indicator.isCanceled) {
                phase = DbtCliPhase.CANCELLED
                message = "dbt command cancelled"
                return
            }
            val line =
                GeneralCommandLine(plan.executable.toString())
                    .withParameters(plan.arguments)
                    .withWorkDirectory(plan.directory.toFile())
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                    .withEnvironment(plan.environment)
                    .withCharset(Charsets.UTF_8)
                    .withRedirectErrorStream(true)
            val handler =
                try {
                    KillableProcessHandler(line)
                } catch (_: com.intellij.execution.ExecutionException) {
                    throw IllegalArgumentException(
                        "Unable to start dbt. Check the executable and environment in DBT Lens settings.",
                    )
                }
            handler.setShouldKillProcessSoftly(false)
            run.process = handler
            handler.addProcessListener(
                object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        if (outputType == ProcessOutputTypes.SYSTEM)
                            return // Never echo argument/environment values.
                        synchronized(output) {
                            output.accept(event.text)
                            update(run) { it.copy(output = output.text) }
                        }
                    }
                },
            )
            indicator.text = "${run.command.kind.title} · ${plan.directory.fileName}"
            handler.startNotify()
            if (run.cancelled.get() || indicator.isCanceled) handler.destroyProcess()
            handler.process.outputStream
                .close() // Interactive prompts belong in the user's terminal.
            while (!handler.waitFor(CommandLimits.PROCESS_POLL_MILLIS)) {
                if (run.cancelled.get() || indicator.isCanceled) {
                    run.cancelled.set(true)
                    handler.destroyProcess()
                }
            }
            synchronized(output) { output.flush() }
            if (run.cancelled.get() || indicator.isCanceled) {
                phase = DbtCliPhase.CANCELLED
                message = "dbt command cancelled"
            } else if (handler.exitCode != 0) {
                message = "dbt exited with code ${handler.exitCode}. See Command output."
            } else if (output.selectionMissing) {
                message = "dbt found no matching resources. See Command output."
            } else if (stamp(manifest) == null || stamp(manifest) == before) {
                message =
                    "dbt completed without writing a new manifest. Check the installed dbt version and Command output."
            } else {
                val snapshot =
                    project.getService(DbtArtifacts::class.java).load(run.command.context, true)
                if (!snapshot.valid) message = snapshot.messages.joinToString("; ")
                else {
                    LocalFileSystem.getInstance()
                        .refreshNioFiles(listOf(run.command.context.target), false, true, null)
                    phase = DbtCliPhase.SUCCEEDED
                    message =
                        when (run.command.kind) {
                            DbtCommandKind.PARSE -> "Manifest generated"
                            DbtCommandKind.INLINE ->
                                if (output.compiled != null) "Macro call compiled"
                                else "Macro call compiled; see Command output for the result"
                            else -> "Compilation complete"
                        }
                }
            }
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            run.cancelled.set(true)
            phase = DbtCliPhase.CANCELLED
            message = "dbt command cancelled"
        } catch (e: Exception) {
            if (run.cancelled.get() || indicator.isCanceled) {
                phase = DbtCliPhase.CANCELLED
                message = "dbt command cancelled"
            } else message = e.message ?: "dbt command failed. See DBT Lens settings."
        } finally {
            com.intellij.openapi.progress.ProgressManager.getInstance()
                .executeNonCancelableSection {
                    run.process
                        ?.takeIf { !it.isProcessTerminated }
                        ?.let {
                            it.destroyProcess()
                            if (!it.waitFor(CommandLimits.TERMINATE_TIMEOUT_MILLIS)) {
                                it.killProcess()
                                it.waitFor()
                            }
                        }
                }
            synchronized(output) { output.flush() }
            synchronized(lock) {
                if (current === run) {
                    current = null
                    state =
                        DbtCliState(
                            run.id,
                            run.command,
                            phase,
                            message,
                            output.text,
                            output.compiled.takeIf { phase == DbtCliPhase.SUCCEEDED },
                        )
                }
            }
            publish()
        }
    }

    private fun stamp(file: java.nio.file.Path): List<Any?>? =
        if (!Files.isRegularFile(file)) null
        else
            Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
                .let { listOf(it.lastModifiedTime(), it.size(), it.fileKey()) }

    private fun update(run: Run, transform: (DbtCliState) -> DbtCliState) {
        synchronized(lock) { if (current === run) state = transform(state) }
        publish()
    }

    private fun publish() {
        if (!dispatchQueued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            dispatchQueued.set(false)
            if (!disposed && !project.isDisposed)
                project.messageBus.syncPublisher(DbtCliListener.TOPIC).changed(state)
        }
    }

    override fun dispose() {
        synchronized(lock) {
            disposed = true
            current?.cancelled?.set(true)
            current?.process?.destroyProcess()
        }
    }
}
