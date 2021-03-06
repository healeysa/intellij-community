// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.sdk

import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import com.google.common.io.Files
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.io.HttpRequests
import com.intellij.webcore.packaging.PackageManagementService
import com.intellij.webcore.packaging.PackagesNotificationPanel
import org.jetbrains.annotations.CalledInAny
import org.jetbrains.annotations.CalledInAwt
import java.io.File
import java.io.IOException
import kotlin.math.absoluteValue

private val LOGGER = Logger.getInstance(PySdkToInstall::class.java)

@CalledInAny
internal fun getSdksToInstall(): List<PySdkToInstall> {
  return if (SystemInfo.isWindows) listOf(getPy37ToInstallOnWindows(), getPy38ToInstallOnWindows())
  else emptyList()
}

private fun getPy37ToInstallOnWindows(): PySdkToInstallOnWindows {
  val version = "3.7"
  val name = "Python $version"
  val hashFunction = Hashing.md5()

  return if (SystemInfo.is32Bit) {
    PySdkToInstallOnWindows(
      name,
      version,
      "https://www.python.org/ftp/python/3.7.6/python-3.7.6.exe",
      25792544,
      "9e73a1b27bb894f87fdce430ef88b3d5",
      hashFunction,
      "python-3.7.6.exe"
    )
  }
  else {
    PySdkToInstallOnWindows(
      name,
      version,
      "https://www.python.org/ftp/python/3.7.6/python-3.7.6-amd64.exe",
      26802312,
      "cc31a9a497a4ec8a5190edecc5cdd303",
      hashFunction,
      "python-3.7.6-amd64.exe"
    )
  }
}

private fun getPy38ToInstallOnWindows(): PySdkToInstallOnWindows {
  val version = "3.8"
  val name = "Python $version"
  val hashFunction = Hashing.md5()

  return if (SystemInfo.is32Bit) {
    PySdkToInstallOnWindows(
      name,
      version,
      "https://www.python.org/ftp/python/3.8.1/python-3.8.1.exe",
      26446128,
      "2d4c7de97d6fcd8231fc3decbf8abf79",
      hashFunction,
      "python-3.8.1.exe"
    )
  }
  else {
    PySdkToInstallOnWindows(
      name,
      version,
      "https://www.python.org/ftp/python/3.8.1/python-3.8.1-amd64.exe",
      27543360,
      "3e4c42f5ff8fcdbe6a828c912b7afdb1",
      hashFunction,
      "python-3.8.1-amd64.exe"
    )
  }
}

internal abstract class PySdkToInstall internal constructor(name: String, version: String)
  : ProjectJdkImpl(name, PythonSdkType.getInstance(), null, version) {

  @CalledInAny
  abstract fun renderInList(renderer: PySdkListCellRenderer)

  @CalledInAny
  abstract fun getInstallationWarning(defaultButtonName: String): String

  @CalledInAwt
  abstract fun install(module: Module?, systemWideSdksDetector: () -> List<PyDetectedSdk>): PyDetectedSdk?
}

private class PySdkToInstallOnWindows(name: String,
                                      version: String,
                                      private val url: String,
                                      private val size: Long,
                                      private val hash: String,
                                      private val hashFunction: HashFunction,
                                      private val targetFileName: String) : PySdkToInstall(name, version) {

  override fun renderInList(renderer: PySdkListCellRenderer) {
    renderer.append(name)
    renderer.append(" $url", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    renderer.icon = AllIcons.Actions.Download
  }

  override fun getInstallationWarning(defaultButtonName: String): String {
    val header = "Python executable is not found. Choose one of the following options:"

    val browseButtonName = "..." // ComponentWithBrowseButton
    val firstOption = "Click <strong>$browseButtonName</strong> to specify a path to python.exe in your file system"

    val size = StringUtil.formatFileSize(size)
    val secondOption = "Click <strong>$defaultButtonName</strong> to download and install Python from python.org ($size)"

    return "$header<ul><li>$firstOption</li><li>$secondOption</li></ul>"
  }

  override fun install(module: Module?, systemWideSdksDetector: () -> List<PyDetectedSdk>): PyDetectedSdk? {
    try {
      return ProgressManager.getInstance().run(
        object : Task.WithResult<PyDetectedSdk?, Exception>(module?.project, "Installing $name", true) {
          override fun compute(indicator: ProgressIndicator): PyDetectedSdk? = install(systemWideSdksDetector, indicator)
        }
      )
    }
    catch (e: IOException) {
      handleIOException(e)
    }
    catch (e: PyInstallationExecutionException) {
      handleExecutionException(e)
    }
    catch (e: PyInstallationException) {
      handleInstallationException(e)
    }

    return null
  }

  private fun install(systemWideSdksDetector: () -> List<PyDetectedSdk>, indicator: ProgressIndicator): PyDetectedSdk? {
    val targetFile = File(PathManager.getTempPath(), targetFileName)

    try {
      indicator.text = "Downloading $targetFileName"
      if (indicator.isCanceled) return null
      downloadInstaller(targetFile, indicator)
      if (indicator.isCanceled) return null
      checkInstallerConsistency(targetFile)

      indicator.text = "Running $targetFileName"
      indicator.text2 = "Windows may require your approval to install Python. Please check the taskbar."
      indicator.isIndeterminate = true
      if (indicator.isCanceled) return null
      runInstaller(targetFile, indicator)

      return findInstalledSdk(systemWideSdksDetector)
    }
    finally {
      FileUtil.delete(targetFile)
    }
  }

  private fun downloadInstaller(targetFile: File, indicator: ProgressIndicator) {
    LOGGER.info("Downloading $url to $targetFile")

    return try {
      HttpRequests.request(url).saveToFile(targetFile, indicator)
    }
    catch (e: IOException) {
      throw IOException("Failed to download $url to $targetFile.", e)
    }
  }

  private fun checkInstallerConsistency(installer: File) {
    LOGGER.debug("Checking installer size")
    val sizeDiff = installer.length() - size
    if (sizeDiff != 0L) {
      throw IOException("Downloaded $installer has incorrect size, difference is ${sizeDiff.absoluteValue} bytes.")
    }

    LOGGER.debug("Checking installer checksum")
    val actualHashCode = Files.asByteSource(installer).hash(hashFunction).toString()
    if (!actualHashCode.equals(hash, ignoreCase = true)) {
      throw IOException("Checksums for $installer does not match. Actual value is $actualHashCode, expected $hash.")
    }
  }

  private fun handleIOException(e: IOException) {
    LOGGER.info(e)

    e.message?.let {
      PackagesNotificationPanel.showError(
        "Failed to install $name",
        PackageManagementService.ErrorDescription(
          it,
          null,
          e.cause?.message,
          "Try to install Python from https://www.python.org manually."
        )
      )
    }
  }

  private fun runInstaller(installer: File, indicator: ProgressIndicator) {
    val commandLine = GeneralCommandLine(installer.absolutePath, "/quiet")
    LOGGER.info("Running ${commandLine.commandLineString}")

    val output = runInstaller(commandLine, indicator)
    if (output.exitCode != 0 || output.isTimeout) throw PyInstallationException(commandLine, output)
  }

  private fun handleInstallationException(e: PyInstallationException) {
    val processOutput = e.output
    processOutput.checkSuccess(LOGGER)

    if (processOutput.isCancelled) {
      PackagesNotificationPanel.showError(
        "$name installation has been cancelled",
        PackageManagementService.ErrorDescription(
          "Some Python components that have been installed might get inconsistent after cancellation.",
          e.commandLine.commandLineString,
          listOf(processOutput.stderr, processOutput.stdout).firstOrNull { it.isNotBlank() },
          "Consider installing Python from https://www.python.org manually."
        )
      )
    }
    else {
      PackagesNotificationPanel.showError(
        "Failed to install $name",
        PackageManagementService.ErrorDescription(
          if (processOutput.isTimeout) "Timed out" else "Exit code ${processOutput.exitCode}",
          e.commandLine.commandLineString,
          listOf(processOutput.stderr, processOutput.stdout).firstOrNull { it.isNotBlank() },
          "Try to install Python from https://www.python.org manually."
        )
      )
    }
  }

  private fun runInstaller(commandLine: GeneralCommandLine, indicator: ProgressIndicator): ProcessOutput {
    try {
      return CapturingProcessHandler(commandLine).runProcessWithProgressIndicator(indicator)
    }
    catch (e: ExecutionException) {
      throw PyInstallationExecutionException(commandLine, e)
    }
  }

  private fun handleExecutionException(e: PyInstallationExecutionException) {
    LOGGER.info(e)

    e.cause.message?.let {
      PackagesNotificationPanel.showError(
        "Failed to install $name",
        PackageManagementService.ErrorDescription(
          it,
          e.commandLine.commandLineString,
          null,
          "Try to install Python from https://www.python.org manually."
        )
      )
    }
  }

  private fun findInstalledSdk(systemWideSdksDetector: () -> List<PyDetectedSdk>): PyDetectedSdk? {
    LOGGER.debug("Resetting system-wide sdks detectors")
    resetSystemWideSdksDetectors()

    return systemWideSdksDetector()
      .also { sdks ->
        LOGGER.debug { sdks.joinToString(prefix = "Detected system-wide sdks: ") { it.homePath ?: it.name } }
      }
      .singleOrNull()
  }

  private class PyInstallationException(val commandLine: GeneralCommandLine, val output: ProcessOutput) : Exception()
  private class PyInstallationExecutionException(val commandLine: GeneralCommandLine, override val cause: ExecutionException) : Exception()
}