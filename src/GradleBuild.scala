package android

import java.io.File
import java.io._
import java.util.concurrent.TimeUnit

import com.android.builder.model.{MavenCoordinates, AndroidProject}
import Keys._
import com.hanhuy.gradle.discovery.{AndroidDiscoveryModel, RepositoryListModel}
import org.gradle.tooling.{ProjectConnection, GradleConnector}
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.gradle.tooling.model.GradleProject
import sbt.Keys._
import sbt._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.Try

/**
 * @author pfnguyen
 */
trait GradleBuild extends Build {
  private[this] var projectsMap = Map.empty[String,Project]

  def gradle = projectsMap.apply _

  override def projects = {
    val originalProjects = super.projects.map (p => (p.id, p)).toMap
    val initgradle = IO.readLinesURL(Tasks.resourceUrl("plugin-init.gradle"))
    val f = File.createTempFile("plugin-init", ".gradle")
    IO.writeLines(f, initgradle)
    f.deleteOnExit()

    println("Searching for android gradle projects...")
    val gconnection = GradleConnector.newConnector.asInstanceOf[DefaultGradleConnector]
    gconnection.daemonMaxIdleTime(5, TimeUnit.SECONDS)
    gconnection.setVerboseLogging(false)

    try {
      val discovered = processDirectoryAt(file("."), f, gconnection)._2
      f.delete()
      val projects = discovered map { case (p, deps) =>
        val orig = originalProjects.get(p.id)
        val p2 = deps.foldLeft(p) { (prj, dep) =>
          val d = discovered.find(_._1.id == dep).get._1
          prj.settings(
            collectResources in Android <<=
              collectResources in Android dependsOn (compile in Compile in d),
            compile in Compile <<= compile in Compile dependsOn(
              android.Keys.Internal.packageT in Compile in d),
            localProjects in Android += LibraryProject(d.base)
          )
        }
        orig.fold(p2)(o => p2.copy(settings = o.asInstanceOf[ProjectDefinition[_]].settings ++ p.settings))
      }
      projectsMap = projects map { p => (p.id, p) } toMap

      println("Discovered gradle projects:")
      println("  " + projects.map(p => p.id).mkString("\n  "))

      projects
    } catch {
      case ex: Exception =>
        @tailrec
        def collectMessages(e: Throwable, msgs: List[String] = Nil, seen: Set[Throwable] = Set.empty): String = {
          if (e == null || seen(e))
            msgs.mkString("\n")
          else
            collectMessages(e.getCause, e.getMessage :: msgs, seen + e)
        }
        throw new MessageOnlyException(collectMessages(ex))
    }
  }

  val nullsink = new OutputStream {
    override def write(b: Int) = ()
  }

  def modelBuilder[A](c: ProjectConnection, model: Class[A]) = {
    c.model(model)
      .setStandardOutput(nullsink)
      .setStandardError(nullsink)
  }
  def initScriptModelBuilder[A](c: ProjectConnection, model: Class[A], initscript: File) =
    modelBuilder(c, model).withArguments(
      "--init-script", initscript.getAbsolutePath)

  def repositoryListModel(c: ProjectConnection, initscript: File) =
    initScriptModelBuilder(c, classOf[RepositoryListModel], initscript).get()

  def androidDiscoveryModel(c: ProjectConnection, initscript: File) =
    initScriptModelBuilder(c, classOf[AndroidDiscoveryModel], initscript).get()

  def gradleProject(c: ProjectConnection) =
    modelBuilder(c, classOf[GradleProject]).get()

  def androidProject(c: ProjectConnection) =
    modelBuilder(c, classOf[AndroidProject]).get()

  def processDirectoryAt(base: File, initscript: File,
                         connector: GradleConnector,
                         repositories: List[Resolver] = Nil, seen: Set[File] = Set.empty): (Set[File],List[(Project,Set[String])]) = {
    val c = connector.forProjectDirectory(base).connect()
    val prj = gradleProject(c)
    val discovery = androidDiscoveryModel(c, initscript)
    val repos = repositories ++ (
      repositoryListModel(c, initscript).getResolvers.asScala.toList map (r =>
        r.getUrl.toString at r.getUrl.toString))

    val (visited,subprojects) = prj.getChildren.asScala.toList.foldLeft((seen + base.getCanonicalFile,List.empty[(Project,Set[String])])) { case ((saw,acc),child) =>
      // gradle 2.4 added getProjectDirectory
      val childDir = Try(child.getProjectDirectory).getOrElse(file(child.getPath.replaceAll(":", ""))).getCanonicalFile
      if (!saw(childDir)) {
        println("Processing gradle sub-project at: " + childDir.getName)
        val (visited, subs) = processDirectoryAt(childDir, initscript, connector, repos, saw + childDir)
        (visited ++ saw, subs ++ acc)
      } else
        (saw,acc)
    }

    try {
      if (discovery.isApplication || discovery.isLibrary) {
        val ap = androidProject(c)
        val sourceVersion = ap.getJavaCompileOptions.getSourceCompatibility
        val targetVersion = ap.getJavaCompileOptions.getTargetCompatibility

        val default = ap.getDefaultConfig
        val flavor = default.getProductFlavor
        val sourceProvider = default.getSourceProvider

        val optional: List[Setting[_]] = Option(flavor.getApplicationId).toList.map {
          applicationId in Android := _
        } ++ Option(flavor.getVersionCode).toList.map {
          versionCode in Android := Some(_)
        } ++ Option(flavor.getVersionName).toList.map {
          versionName in Android := Some(_)
        } ++ Option(flavor.getMinSdkVersion).toList.map {
          minSdkVersion in Android := _.getApiString
        } ++ Option(flavor.getTargetSdkVersion).toList.map {
          targetSdkVersion in Android := _.getApiString
        } ++ Option(flavor.getRenderscriptTargetApi).toList.map {
          rsTargetApi in Android := _.toString
        } ++ Option(flavor.getRenderscriptSupportModeEnabled).toList.map {
          rsSupportMode in Android := _
        } ++ Option(flavor.getMultiDexEnabled).toList.map {
          dexMulti in Android := _
        } ++ Option(flavor.getMultiDexKeepFile).toList.map {
          dexMainFileClasses in Android := IO.readLines(_, IO.utf8)
        }
        val v = ap.getVariants.asScala.head
        val art = v.getMainArtifact
        def libraryDependency(m: MavenCoordinates) =
          libraryDependencies += m.getGroupId % m.getArtifactId % m.getVersion

        val androidLibraries = art.getDependencies.getLibraries.asScala
        val (aars,projects) = androidLibraries.partition(_.getProject == null)
        val (resolved,localAars) = aars.partition(a => Option(a.getResolvedCoordinates.getGroupId).exists(_.nonEmpty))
        val localAar = localAars.toList map { l =>
          android.Keys.localAars in Android += l.getBundle
        }
        val libs = resolved.toList ++
          art.getDependencies.getJavaLibraries.asScala.toList filter (j =>
          Option(j.getResolvedCoordinates.getGroupId).exists(_.nonEmpty)) map { j =>
          libraryDependency(j.getResolvedCoordinates)
        }

        val p = Project(base = base, id = ap.getName).settings(
          (if (discovery.isApplication) Plugin.androidBuild else Plugin.androidBuildAar): _*).settings(
          resolvers ++= repos,
          platformTarget in Android := ap.getCompileTarget,
          name := ap.getName,
          javacOptions in Compile ++= "-source" :: sourceVersion :: "-target" :: targetVersion :: Nil,
          buildConfigOptions in Android ++= flavor.getBuildConfigFields.asScala.toList map { case (key, field) =>
            (field.getType, key, field.getValue)
          },
          resValues in Android ++= flavor.getResValues.asScala.toList map { case (key, field) =>
            (field.getType, key, field.getValue)
          },
          debugIncludesTests in Android := false, // default because can't express it easily otherwise
          proguardOptions in Android ++= flavor.getProguardFiles.asScala.toList.flatMap(IO.readLines(_, IO.utf8)),
          manifestPlaceholders in Android ++= flavor.getManifestPlaceholders.asScala.toMap map { case (k,o) => (k,o.toString) },
          projectLayout in Android := new ProjectLayout.Wrapped((projectLayout in Android).value) {
            override def manifest = sourceProvider.getManifestFile
            override def javaSource = sourceProvider.getJavaDirectories.asScala.head
            override def resources = sourceProvider.getResourcesDirectories.asScala.head
            override def res = sourceProvider.getResDirectories.asScala.head
            override def renderscript = sourceProvider.getRenderscriptDirectories.asScala.head
            override def aidl = sourceProvider.getAidlDirectories.asScala.head
            override def assets = sourceProvider.getAssetsDirectories.asScala.head
            override def jniLibs = sourceProvider.getJniLibsDirectories.asScala.head
          }
        ).settings(optional: _*).settings(libs: _*).settings(localAar: _*)
        (visited, (p, projects.map(_.getProject.replaceAll(":","")).toSet) :: subprojects)
      } else
        (visited, subprojects)
    } finally {
      c.close()
    }
  }
}
