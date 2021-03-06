package zio.intellij.testsupport.runner

import java.net.{URL, URLClassLoader}

import com.intellij.openapi.components.{PersistentStateComponent, ServiceManager, State, Storage}
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressIndicator, Task}
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.annotations.{Nls, NonNls}
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.extensions.invokeLater
import org.jetbrains.plugins.scala.util.ScalaCollectionsUtil
import zio.intellij.testsupport.ZTestRunConfiguration.ZTestRunnerName
import zio.intellij.testsupport.runner.TestRunnerDownloader.DownloadResult.{DownloadFailure, DownloadSuccess}
import zio.intellij.testsupport.runner.TestRunnerDownloader.{DownloadProgressListener, NoopProgressListener}
import zio.intellij.testsupport.runner.TestRunnerResolveService.ResolveError.DownloadError
import zio.intellij.testsupport.runner.TestRunnerResolveService._
import zio.intellij.utils.Version

import scala.beans.BeanProperty
import scala.collection.mutable
import scala.util._

// Borrowed from ScalafmtDynamicServiceImpl and friends

@State(name = "TestRunnerResolveService", storages = Array(new Storage("zio_testrunner_resolve_cache.xml")))
private[runner] final class TestRunnerResolveService
    extends PersistentStateComponent[TestRunnerResolveService.ServiceState] {

  private val testRunnerVersions: mutable.Map[Version, ResolveStatus] = ScalaCollectionsUtil.newConcurrentMap

  private val state: TestRunnerResolveService.ServiceState = new TestRunnerResolveService.ServiceState

  override def getState: TestRunnerResolveService.ServiceState = state
  override def loadState(state: TestRunnerResolveService.ServiceState): Unit =
    XmlSerializerUtil.copyBean(state, this.state)

  def resolve(
    version: Version,
    scalaVersion: ScalaVersion,
    downloadIfMissing: Boolean,
    resolveFast: Boolean = false,
    progressListener: DownloadProgressListener = NoopProgressListener
  ): ResolveResult = testRunnerVersions.get(version) match {
    case Some(ResolveStatus.Resolved(jarPaths)) => Right(jarPaths)
    case _ if resolveFast                       => Left(ResolveError.NotFound(version, scalaVersion))
    case Some(ResolveStatus.DownloadInProgress) => Left(ResolveError.DownloadInProgress(version, scalaVersion))
    case _ =>
      if (state.resolvedVersions.containsKey(version.toString)) {
        val jarUrls = state.resolvedVersions.get(version.toString).map(new URL(_))
        resolveClassPath(version, scalaVersion, jarUrls) match {
          case r @ Right(_)                 => r
          case Left(_) if downloadIfMissing => downloadAndResolve(version, scalaVersion, progressListener)
          case _                            => Left(ResolveError.NotFound(version, scalaVersion))
        }
      } else if (downloadIfMissing) downloadAndResolve(version, scalaVersion, progressListener)
      else Left(ResolveError.NotFound(version, scalaVersion))
  }

  def resolveAsync(
    version: Version,
    scalaVersion: ScalaVersion,
    project: Project,
    onResolved: ResolveResult => Unit = _ => ()
  ): Unit =
    testRunnerVersions.get(version) match {
      case Some(ResolveStatus.Resolved(fmt)) =>
        invokeLater(onResolved(Right(fmt)))
      case Some(ResolveStatus.DownloadInProgress) =>
        invokeLater(onResolved(Left(ResolveError.DownloadInProgress(version, scalaVersion))))
      case _ =>
        @NonNls val title = s"Downloading the ZIO Test runner for ZIO $version"
        val backgroundTask = new Task.Backgroundable(project, title, true) {
          override def run(indicator: ProgressIndicator): Unit = {
            indicator.setIndeterminate(true)
            val progressListener = new ProgressIndicatorDownloadListener(indicator, title)
            val result =
              try {
                resolve(version, scalaVersion, downloadIfMissing = true, progressListener = progressListener)
              } catch {
                case pce: ProcessCanceledException =>
                  Left(DownloadError(version, scalaVersion, pce))
              }
            onResolved(result)
          }
        }

        backgroundTask.setCancelText("Cancel downloading ZIO Test runner...")
        backgroundTask.queue()
    }

  private def downloadAndResolve(
    version: Version,
    scalaVersion: ScalaVersion,
    listener: DownloadProgressListener = NoopProgressListener
  ): ResolveResult = {
    val downloader = new TestRunnerDownloader(listener)
    downloader.download(version)(scalaVersion).left.map(ResolveError.DownloadError.apply).flatMap {
      case DownloadSuccess(v, scalaVersion, jarUrls) => resolveClassPath(v, scalaVersion, jarUrls)
    }
  }

  private def resolveClassPath(version: Version, scalaVersion: ScalaVersion, jarUrls: Seq[URL]): ResolveResult = {
    val urls: Array[URL] = jarUrls.toArray
    Try(new URLClassLoader(urls, null).loadClass(ZTestRunnerName + "$")) match {
      case Success(_) =>
        state.resolvedVersions.put(version.toString, jarUrls.toArray.map(_.toString))
        testRunnerVersions(version) = ResolveStatus.Resolved(jarUrls)
        Right(jarUrls)
      case Failure(e) =>
        Left(ResolveError.UnknownError(version, scalaVersion, e))
    }
  }

  private[runner] def clearCaches() = testRunnerVersions.clear()
}
object TestRunnerResolveService {
  def instance: TestRunnerResolveService = ServiceManager.getService(classOf[TestRunnerResolveService])

  type ResolveResult = Either[ResolveError, Seq[URL]]

  sealed trait ResolveStatus
  object ResolveStatus {
    object DownloadInProgress                     extends ResolveStatus
    final case class Resolved(jarPaths: Seq[URL]) extends ResolveStatus
  }

  sealed trait ResolveError
  object ResolveError {
    final case class NotFound(version: Version, scalaVersion: ScalaVersion)                        extends ResolveError
    final case class DownloadInProgress(version: Version, scalaVersion: ScalaVersion)              extends ResolveError
    final case class DownloadError(version: Version, scalaVersion: ScalaVersion, cause: Throwable) extends ResolveError
    final case class UnknownError(version: Version, scalaVersion: ScalaVersion, cause: Throwable)  extends ResolveError

    object DownloadError {
      def apply(f: DownloadFailure): DownloadError = new DownloadError(f.version, f.scalaVersion, f.cause)
    }
  }

  final class ServiceState() {
    // ZIO version -> list of classpath jar URLs
    @BeanProperty
    var resolvedVersions: java.util.Map[String, Array[String]] = new java.util.TreeMap()
  }

  private class ProgressIndicatorDownloadListener(indicator: ProgressIndicator, @Nls prefix: String = "")
      extends DownloadProgressListener {
    override def progressUpdate(message: String): Unit = {
      if (message.nonEmpty) {
        //noinspection ReferencePassedToNls,ScalaExtractStringToBundle
        indicator.setText(prefix + ": " + message)
      }
      indicator.checkCanceled()
    }
    override def doProgress(): Unit =
      indicator.checkCanceled()
  }
}
