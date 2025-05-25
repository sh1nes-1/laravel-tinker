package nl.deschepers.laraveltinker.util

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.remote.BaseRemoteProcessHandler
import com.jetbrains.php.composer.ComposerUtils
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.config.commandLine.PhpCommandSettingsBuilder
import com.jetbrains.php.run.PhpEditInterpreterExecutionException
import com.jetbrains.php.run.script.PhpScriptRunConfiguration
import com.jetbrains.php.run.script.PhpScriptRuntimeConfigurationProducer
import nl.deschepers.laraveltinker.Strings
import nl.deschepers.laraveltinker.balloon.LaravelRootDoesNotExistBalloon
import nl.deschepers.laraveltinker.balloon.NoPhpInterpreterBalloon
import nl.deschepers.laraveltinker.balloon.PhpInterpreterErrorBalloon
import nl.deschepers.laraveltinker.balloon.VendorFolderNotFound
import nl.deschepers.laraveltinker.listener.PhpProcessListener
import nl.deschepers.laraveltinker.settings.ProjectSettingsState
import nl.deschepers.laraveltinker.toolwindow.TinkerOutputToolWindowFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.stream.Collectors

class PhpArtisanTinkerUtil(private val project: Project, private val phpCode: String) {
    fun run() {
        val projectSettings = ProjectSettingsState.getInstance(project)
        FileDocumentManager.getInstance().saveAllDocuments()

        val runConfiguration =
            PhpScriptRunConfiguration(
                project,
                PhpScriptRuntimeConfigurationProducer().configurationFactory,
                "Laravel Tinker"
            )

        val phpInterpreter = PhpProjectConfigurationFacade.getInstance(project).interpreter
        if (phpInterpreter == null) {
            NoPhpInterpreterBalloon(project).show()
            return
        }

        // Find the project root (directory containing composer.json)
        val composerFile = ComposerUtils.findFileInProject(project, ComposerUtils.CONFIG_DEFAULT_FILENAME)
        val projectRoot = composerFile.parent.path
        var laravelRoot = projectRoot

        // If the user has set a custom path, use that instead
        if (projectSettings.laravelRoot.isNotEmpty()) {
            val customLaravelRoot = File(projectSettings.laravelRoot + "/bootstrap/app.php")
            if (customLaravelRoot.exists() && customLaravelRoot.isFile) {
                laravelRoot = projectSettings.laravelRoot
            } else {
                LaravelRootDoesNotExistBalloon(project, projectSettings.laravelRoot).show()
                return
            }
        }

        // Check if the vendor path exists (but do not set vendorRoot to include /vendor)
        val vendorDir = File(laravelRoot, "vendor")
        if (!vendorDir.exists() || !vendorDir.isDirectory) {
            VendorFolderNotFound(project, vendorDir.path).show()
            return
        }

        // Always set laravelRoot and vendorRoot to the project root (not vendor dir)
        projectSettings.laravelRoot = laravelRoot
        projectSettings.vendorRoot = laravelRoot

        val inputStream = javaClass.classLoader.getResourceAsStream("scripts/tinker_run.php")
        // Write tinker_run.php to a temporary file so it can be executed by PHP (required for Xdebug)
        val tempTinkerScript = File.createTempFile("tinker_run", ".php")
        tempTinkerScript.deleteOnExit()
        inputStream!!.use { input ->
            tempTinkerScript.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val tinkerScriptPath = tempTinkerScript.absolutePath
        val phpCommandSettings: PhpCommandSettings
        val processHandler: ProcessHandler

        try {
            phpCommandSettings =
                PhpCommandSettingsBuilder(project, phpInterpreter).loadAndStartDebug().build()
            phpCommandSettings.setWorkingDir(laravelRoot)
            phpCommandSettings.importCommandLineSettings(
                runConfiguration.settings.commandLineSettings,
                laravelRoot
            )

            val tinkerRunSettings = projectSettings.parseJson()
            // Instead of -r, pass the script file path and arguments
            phpCommandSettings.addArguments(listOf(tinkerScriptPath, phpCode, tinkerRunSettings.toString()))

            processHandler = getAnsiUnfilteredProcessHandler(runConfiguration.createProcessHandler(project, phpCommandSettings))

            ProcessTerminatedListener.attach(processHandler, project, "")
        } catch (ex: ExecutionException) {
            PhpInterpreterErrorBalloon(
                project,
                ex.message ?: Strings.get("lt.error.php_interpreter_error")
            ).show()

            return
        } catch (ex: PhpEditInterpreterExecutionException) {
            PhpInterpreterErrorBalloon(
                project,
                ex.message ?: Strings.get("lt.error.php_interpreter_error")
            ).show()

            return
        }

        val phpProcessListener = PhpProcessListener(project)
        processHandler.addProcessListener(phpProcessListener)

        ToolWindowManager.getInstance(project).getToolWindow("Laravel Tinker")?.activate(null)
        TinkerOutputToolWindowFactory.tinkerOutputToolWindow[project]?.resetOutput()

        ProgressManager.getInstance()
            .run(
                object : Backgroundable(project, Strings.get("lt.running")) {
                    override fun run(progressIndicator: ProgressIndicator) {
                        processHandler.startNotify()
                        processHandler.processInput?.writer()?.write("\u0004")
                        while (!processHandler.isProcessTerminated) {
                            Thread.sleep(250)
                            try {
                                progressIndicator.checkCanceled()
                            } catch (ex: ProcessCanceledException) {
                                processHandler.destroyProcess()
                                throw ex
                            }
                        }
                    }
                }
            )
    }

    private fun getAnsiUnfilteredProcessHandler(processHandler: ProcessHandler): ProcessHandler {
        if (processHandler is OSProcessHandler) {
            return KillableProcessHandler(processHandler.process, processHandler.commandLine, StandardCharsets.UTF_8)
        }

        if (processHandler is BaseRemoteProcessHandler<*>) {
            return BaseRemoteProcessHandler(processHandler.process, processHandler.commandLine, StandardCharsets.UTF_8)
        }

        // Could not find suitable cast, return original (colorless, but working) handler
        return processHandler
    }
}
