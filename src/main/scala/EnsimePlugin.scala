// Copyright (C) 2014 - 2016 ENSIME Contributors
// Licence: Apache-2.0
package org.ensime

import java.io.FileNotFoundException
import java.lang.management.ManagementFactory

import scala.collection.JavaConverters._
import scala.util._
import scala.util.control.NoStackTrace

import SExpFormatter._
import sbt._
import sbt.IO._
import sbt.Keys._
import sbt.complete.Parsers._

/**
 * Conventional way to define importable keys for an AutoPlugin.
 */
object EnsimeKeys {
  // for ensimeConfig
  val ensimeName = settingKey[String](
    "Name of the ENSIME project"
  )
  val ensimeScalacOptions = taskKey[Seq[String]](
    "Arguments for the scala presentation compiler, extracted from the compiler flags."
  )
  val ensimeJavacOptions = taskKey[Seq[String]](
    "Arguments for the java presentation compiler, extracted from the compiler flags."
  )

  val ensimeIgnoreSourcesInBase = settingKey[Boolean](
    "ENSIME doesn't support sources in base, this tolerates their existence."
  )
  val ensimeIgnoreMissingDirectories = settingKey[Boolean](
    "ENSIME requires declared source directories to exist. If you find this noisy, ignore them."
  )
  val ensimeIgnoreScalaMismatch = settingKey[Boolean](
    "ENSIME can only use one version of scala per project. If you have mixed scala versions (e.g. lib and sbt plugin), set this to acknowledge."
  )

  val ensimeJavaFlags = taskKey[Seq[String]](
    "Flags to be passed to ENSIME JVM process."
  )
  val ensimeJavaHome = settingKey[File](
    "The java home directory to be used by the ENSIME JVM process."
  )
  val ensimeScalaVersion = taskKey[String](
    "Version of scala for the ENSIME JVM process."
  )

  val ensimeScalaJars = taskKey[Seq[File]](
    "The scala compiler's jars."
  )
  val ensimeScalaProjectJars = taskKey[Seq[File]](
    "The build's scala compiler's jars."
  )

  val ensimeServerJars = taskKey[Seq[File]](
    "The ensime-server's jars."
  )
  val ensimeServerProjectJars = taskKey[Seq[File]](
    "The build's ensime-server's jars."
  )

  val ensimeUseTarget = taskKey[Option[File]](
    "Use a calculated jar instead of the class directory. " +
      "Note that `proj/compile` does not produce the jar, change your workflow to use `proj/packageBin`."
  )

  // used to start the REPL and assembly jar bundles of ensime-server.
  // intransitive because we don't need parser combinators, scala.xml or jline
  val ensimeScalaCompilerJarModuleIDs = settingKey[Seq[ModuleID]](
    "The artefacts to resolve for :scala-compiler-jars in ensimeConfig."
  )

  // for ensimeConfigProject
  val ensimeProjectScalacOptions = taskKey[Seq[String]](
    "Arguments for the project definition presentation compiler (not possible to extract)."
  )

  val ensimeUnmanagedSourceArchives = settingKey[Seq[File]](
    "Source jars (and zips) to complement unmanagedClasspath. May be set for the project and its submodules."
  )
  val ensimeUnmanagedJavadocArchives = settingKey[Seq[File]](
    "Documentation jars (and zips) to complement unmanagedClasspath. May only be set for submodules."
  )

  val ensimeMegaUpdate = taskKey[Map[ProjectRef, (UpdateReport, UpdateReport)]](
    "Runs the aggregated UpdateReport for `update' and `updateClassifiers' respectively."
  )
  val ensimeConfigTransformer = settingKey[EnsimeConfig => EnsimeConfig](
    "A function that is applied to a generated ENSIME configuration. This transformer function " +
      "can be used to add or filter any resulting config and can serve as a hook for other plugins."
  )
  val ensimeConfigTransformerProject = settingKey[EnsimeConfig => EnsimeConfig](
    "A function that is applied to a generated ENSIME project config. Equivalent of 'configTransformer' task, " +
      "on the build level."
  )
}

object EnsimePlugin extends AutoPlugin {
  import CommandSupport._

  // ensures compiler settings are loaded before us
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements
  val autoImport = EnsimeKeys
  import autoImport._

  override lazy val buildSettings = Seq(
    commands += Command.args("ensimeConfig", ("", ""), "Generate a .ensime for the project.", "proj1 proj2")(ensimeConfig),
    commands += Command.command("ensimeConfigProject", "", "Generate a project/.ensime for the project definition.")(ensimeConfigProject),

    ensimeScalaVersion <<= state.map { implicit s =>
      // infer the scalaVersion by majority vote, because many badly
      // written builds will forget to set the scalaVersion for the
      // root project. And we can't ask for (scalaVersion in
      // ThisBuild) because very few projects do it The Right Way.
      implicit val struct = Project.structure(s)

      val scalaVersions = struct.allProjectRefs.map { implicit p =>
        scalaVersion.gimme
      }.groupBy(identity).map { case (sv, svs) => sv -> svs.size }.toList

      scalaVersions.sortWith { case ((_, c1), (_, c2)) => c1 < c2 }.head._1
    },

    ensimeIgnoreSourcesInBase := false,
    ensimeIgnoreMissingDirectories := false,
    ensimeIgnoreScalaMismatch := false,

    ensimeJavaFlags := JavaFlags,
    ensimeJavaHome := javaHome.value.getOrElse(JdkDir),
    // unable to infer the user's scalac options: https://github.com/ensime/ensime-sbt/issues/98
    ensimeProjectScalacOptions := ensimeSuggestedScalacOptions(Properties.versionNumberString),
    ensimeMegaUpdate <<= Keys.state.flatMap { implicit s =>

      def checkCoursier(): Unit = {
        val structure = Project.extract(s).structure
        val plugins = structure.allProjects.flatMap(_.autoPlugins).map(_.getClass.getName)
        val usesCoursier = plugins.exists(_.contains("CoursierPlugin"))
        if (!usesCoursier) {
          log.warn(
            "SBT is using ivy to resolve dependencies which is known to be slow. " +
              "Coursier is recommended: http://get-coursier.io"
          )
        }
      }

      val projs = Project.structure(s).allProjectRefs
      log.info("ENSIME update.")
      for {
        updateReport <- update.forAllProjects(s, projs)
        _ = checkCoursier()
        updateClassifiersReport <- updateClassifiers.forAllProjects(s, projs)
      } yield {
        projs.map { p =>
          (p, (updateReport(p), updateClassifiersReport(p)))
        }.toMap
      }
    }
  )

  override lazy val projectSettings = Seq(
    ensimeUnmanagedSourceArchives := Nil,
    ensimeUnmanagedJavadocArchives := Nil,
    ensimeConfigTransformer := identity,
    ensimeConfigTransformerProject := identity,
    ensimeUseTarget := None,

    ensimeScalacOptions := (
      (scalacOptions in Compile).value ++
      ensimeSuggestedScalacOptions((ensimeScalaVersion in ThisBuild).value)
    ).distinct,
    ensimeJavacOptions := (javacOptions in Compile).value
  )

  // exposed for users to use
  def ensimeSuggestedScalacOptions(scalaVersion: String): Seq[String] = Seq(
    "-feature",
    "-deprecation",
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Xfuture"
  ) ++ {
      CrossVersion.partialVersion(scalaVersion) match {
        case Some((2, 10))           => Seq("-Ymacro-no-expand")
        case Some((2, v)) if v >= 11 => Seq("-Ywarn-unused-import", "-Ymacro-expand:discard")
        case _                       => Nil
      }
    }

  def ensimeConfig: (State, Seq[String]) => State = { (state, args) =>
    val extracted = Project.extract(state)
    implicit val st = state
    implicit val pr = extracted.currentRef
    implicit val bs = extracted.structure

    var transitiveCache = Map.empty[ProjectRef, Set[ProjectRef]]
    def transitiveProjects(ref: ProjectRef): Set[ProjectRef] = {
      if (transitiveCache.contains(ref))
        transitiveCache(ref)
      else {
        val proj = Project.getProjectForReference(ref, bs).get
        val deps = Set(ref) ++ proj.dependencies.flatMap { dep =>
          transitiveProjects(dep.project)
        }
        transitiveCache += ref -> deps
        deps
      }
    }

    val active =
      if (args.isEmpty) bs.allProjectRefs
      else args.flatMap { name =>
        val ref = bs.allProjectRefs.find(_.project == name).getOrElse {
          throw new IllegalArgumentException(s"$name is not a valid project id")
        }
        transitiveProjects(ref)
      }

    val projects = active.flatMap { ref =>
      Project.getProjectForReference(ref, bs).map((ref, _))
    }.toMap

    val updateReports = ensimeMegaUpdate.run

    val scalaCompilerJars = ensimeScalaJars.run.toSet
    val serverJars = ensimeServerJars.run.toSet -- scalaCompilerJars

    // for some reason this gives the wrong number in projectData
    val ensimeScalaV = (ensimeScalaVersion in ThisBuild).run

    implicit val rawProjects = projects.collect {
      case (ref, proj) =>
        val (updateReport, updateClassifiersReport) = updateReports(ref)
        val project = projectData(ensimeScalaV, proj, updateReport, updateClassifiersReport)(ref, bs, state)
        (project.name, project)
    }.toMap

    val subProjects: Map[String, EnsimeProject] = rawProjects.mapValues { m =>
      val deps = m.dependencies
      // restrict jars to immediate deps at each module
      m.copy(
        runtimeJars = m.runtimeJars -- deps.flatMap(_.runtimeJars),
        sourceJars = m.sourceJars -- deps.flatMap(_.sourceJars),
        docJars = m.docJars -- deps.flatMap(_.docJars)
      )
    }

    val root = file(Properties.userDir)
    val out = file(".ensime")
    val cacheDir = file(".ensime_cache")
    val name = (ensimeName).gimmeOpt.getOrElse {
      if (subProjects.size == 1) subProjects.head._2.name
      else root.getAbsoluteFile.getName
    }
    val compilerArgs = (ensimeScalacOptions).run.toList
    val javaCompilerArgs = (ensimeJavacOptions).run.toList
    val javaH = (ensimeJavaHome).gimme
    val javaSrc = {
      file(javaH.getAbsolutePath + "/src.zip") match {
        case f if f.exists => Set(f)
        case _ =>
          log.warn(s"No Java sources detected in $javaH (your ENSIME experience will not be as good as it could be.)")
          Set.empty
      }
    } ++ (ensimeUnmanagedSourceArchives in ThisBuild).gimme

    val javaFlags = ensimeJavaFlags.run.toList

    val scalaVersion = (ensimeScalaVersion in ThisBuild).run

    val modules = subProjects.mapValues(ensimeProjectToModule)

    val config = EnsimeConfig(
      root, cacheDir,
      scalaCompilerJars, serverJars,
      name, scalaVersion, compilerArgs,
      modules, javaH, javaFlags, javaCompilerArgs, javaSrc, subProjects
    )

    val transformedConfig = ensimeConfigTransformer.gimme.apply(config)

    // workaround for Windows
    write(out, toSExp(transformedConfig).replaceAll("\r\n", "\n") + "\n")

    state
  }

  private def ensureCreatedOrIgnore(ignore: Boolean, log: Logger)(sources: Set[File]): Set[File] = {
    sources.collect {
      case dir if dir.isDirectory() => dir
      case dir if !ignore =>
        log.info(s"""Creating $dir. Read about `ensimeIgnoreMissingDirectories`""")
        dir.mkdirs()
        dir
    }
  }

  def projectData(
    ensimeScalaV: String,
    project: ResolvedProject,
    updateReport: UpdateReport,
    updateClassifiersReport: UpdateReport
  )(
    implicit
    projectRef: ProjectRef,
    buildStruct: BuildStructure,
    state: State
  ): EnsimeProject = {
    log.info(s"ENSIME processing ${project.id} (${name.gimme})")

    val builtInTestPhases = Set(Test, IntegrationTest)
    val testPhases = {
      for {
        phase <- ivyConfigurations.gimme
        if !phase.name.toLowerCase.contains("internal")
        if builtInTestPhases(phase) | builtInTestPhases.intersect(phase.extendsConfigs.toSet).nonEmpty
      } yield phase
    }.toSet

    def sourcesFor(config: Configuration) = {
      // invoke source generation so we can filter on existing directories
      (managedSources in config).runOpt
      (managedSourceDirectories in config).gimmeOpt.map(_.toSet).getOrElse(Set()) ++
        (unmanagedSourceDirectories in config).gimmeOpt.getOrElse(Set())
    }

    def targetForOpt(config: Configuration): Option[File] =
      (ensimeUseTarget in config).runOpt match {
        case Some(Some(jar)) => Some(jar)
        case _               => (classDirectory in config).gimmeOpt
      }

    val myDoc = (artifactPath in (Compile, packageDoc)).gimmeOpt

    def configFilter(config: Configuration): ConfigurationFilter = {
      val c = config.name.toLowerCase
      if (sbtPlugin.gimme) configurationFilter("provided" | c)
      else configurationFilter(c)
    }

    def jarsFor(config: Configuration) = updateReport.select(
      configuration = configFilter(config),
      artifact = artifactFilter(extension = Artifact.DefaultExtension)
    ).toSet

    def unmanagedJarsFor(config: Configuration) =
      (unmanagedJars in config).runOpt.map(_.map(_.data).toSet).getOrElse(Set())

    def jarSrcsFor(config: Configuration) = updateClassifiersReport.select(
      configuration = configFilter(config),
      artifact = artifactFilter(classifier = Artifact.SourceClassifier)
    ).toSet ++ (ensimeUnmanagedSourceArchives in config in projectRef).gimme

    def jarDocsFor(config: Configuration) = updateClassifiersReport.select(
      configuration = configFilter(config),
      artifact = artifactFilter(classifier = Artifact.DocClassifier)
    ).toSet ++ (ensimeUnmanagedJavadocArchives in config in projectRef).gimme

    def configDataFor(config: Configuration): EnsimeConfiguration = {
      val sbv = scalaBinaryVersion.gimme
      val sources = ensureCreatedOrIgnore(ensimeIgnoreMissingDirectories.gimme, log) {
        config match {
          case Compile => sourcesFor(Compile) ++ sourcesFor(Provided) ++ sourcesFor(Optional)
          case _       => sourcesFor(config)
        }
      }

      val target = targetForOpt(config).get
      val scalaCompilerArgs = ((scalacOptions in config).run ++
        ensimeSuggestedScalacOptions(ensimeScalaV)).toList
      val javaCompilerArgs = (javacOptions in config).run.toList
      val jars = config match {
        case Compile => jarsFor(Compile) ++ unmanagedJarsFor(Compile) ++ jarsFor(Provided) ++ jarsFor(Optional)
        case _       => jarsFor(config) ++ unmanagedJarsFor(config)
      }

      EnsimeConfiguration(config.name, sources, Set(target), scalaCompilerArgs, javaCompilerArgs, jars)
    }

    if (scalaVersion.gimme != ensimeScalaV) {
      log.warn(
        s"""You have a different version of scala for ENSIME ($ensimeScalaV) and ${project.id} (${scalaVersion.gimme}).
           |If this is not what you intended, try fixing your build with:
           |  scalaVersion in ThisBuild := "${scalaVersion.gimme}"
           |in your build.sbt, or override the ENSIME scala version with:
           |  ensimeScalaVersion in ThisBuild := "${scalaVersion.gimme}"
           |in a ensime.sbt: http://ensime.org/build_tools/sbt/#customise""".stripMargin
      )
      if (!ensimeIgnoreScalaMismatch.gimme)
        throw new IllegalStateException(
          """To ignore this error (i.e. have have multiple scala versions), customise `ensimeIgnoreScalaMismatch`"""
        ) with NoStackTrace
    }

    if (sourcesInBase.gimme) {
      val sources = baseDirectory.gimme.list().filter(_.endsWith(".scala"))
      if (sources.nonEmpty) {
        log.warn(
          s"""You have .scala files in the base of your project. Such "script style" projects
             |are not supported by ENSIME. Move them into src/main/scala to get support.
             |Please read https://github.com/ensime/ensime-server/issues/1432""".stripMargin
        )
        if (!ensimeIgnoreSourcesInBase.gimme) {
          throw new IllegalStateException("To ignore this error, customise `ensimeIgnoreSourcesInBase`") with NoStackTrace
        }
      }
    }

    val compileConfig = configDataFor(Compile)
    val testConfigs: List[EnsimeConfiguration] =
      { for (test <- testPhases) yield configDataFor(test) }.toList

    val configs = Map(compileConfig.name -> compileConfig) ++
      { for (test <- testConfigs) yield (test.name -> test) }.toMap

    val deps = project.dependencies.map(_.project.project).toSet
    val runtimeJars = jarsFor(Runtime) ++ unmanagedJarsFor(Runtime) -- compileConfig.jars
    val jarSrcs = (testPhases + Provided + Compile).flatMap(jarSrcsFor)
    val jarDocs = (testPhases + Provided + Compile).flatMap(jarDocsFor) ++ myDoc

    EnsimeProject(project.id, deps, runtimeJars, jarSrcs, jarDocs, configs)

  }

  private def ensimeProjectToModule(p: EnsimeProject): EnsimeModule = {

    val mainConfig = p.configs.head._2

    val mainSources = mainConfig.sources
    val mainTarget = mainConfig.targets
    val mainJars = mainConfig.jars

    val testConfigs = (p.configs - mainConfig.name).values
    val testSources = testConfigs.flatMap(_.sources).toSet
    val testTargets = testConfigs.flatMap(_.targets).toSet
    val testJars = testConfigs.flatMap(_.jars).toSet -- mainJars

    EnsimeModule(
      p.name, mainSources, testSources, mainTarget, testTargets, p.dependsOnNames,
      mainJars, p.runtimeJars, testJars, p.sourceJars, p.docJars
    )
  }

  def ensimeConfigProject: State => State = { implicit state: State =>
    val extracted = Project.extract(state)

    implicit val pr = extracted.currentRef
    implicit val bs = extracted.structure

    val jars = for {
      unit <- bs.units
      file <- unit._2.classpath
      if !file.isDirectory() & file.getName.endsWith(Artifact.DefaultExtension)
    } yield file

    val targets = for {
      unit <- bs.units
      dir <- unit._2.classpath
      if dir.isDirectory()
    } yield dir

    val classifiers = for {
      config <- updateSbtClassifiers.run.configurations
      module <- config.modules
      artefact <- module.artifacts
    } yield artefact

    val srcs = classifiers.collect {
      case (artefact, file) if artefact.classifier == Some(Artifact.SourceClassifier) => file
    }
    // they don't seem to publish docs...
    val docs = classifiers.collect {
      case (artefact, file) if artefact.classifier == Some(Artifact.DocClassifier) => file
    }

    val root = file(Properties.userDir) / "project"
    val out = root / ".ensime"
    val cacheDir = root / ".ensime_cache"
    val name = ensimeName.gimmeOpt.getOrElse {
      file(Properties.userDir).getName
    } + "-project"

    val compilerArgs = ensimeProjectScalacOptions.run.toList
    val scalaV = Properties.versionNumberString
    val javaSrc = JdkDir / "src.zip" match {
      case f if f.exists => Set(f)
      case _             => Set.empty[File]
    }
    val javaFlags = ensimeJavaFlags.run.toList

    val conf = EnsimeConfiguration(name, Set(root), targets.toSet, Nil, Nil, jars.toSet)

    val subProject = EnsimeProject(
      name, Set.empty, Set.empty, srcs.toSet, docs.toSet, Map(conf.name -> conf)
    )

    val module = ensimeProjectToModule(subProject)

    val scalaCompilerJars = ensimeScalaProjectJars.run.toSet
    val serverJars = ensimeServerProjectJars.run.toSet -- scalaCompilerJars

    val config = EnsimeConfig(
      root, cacheDir,
      scalaCompilerJars, serverJars,
      name, scalaV, compilerArgs,
      Map(module.name -> module), JdkDir, javaFlags, Nil, javaSrc,
      Map(subProject.name -> subProject)
    )

    val transformedConfig = ensimeConfigTransformerProject.gimme.apply(config)

    write(out, toSExp(transformedConfig).replaceAll("\r\n", "\n") + "\n")

    state
  }

  // WORKAROUND: https://github.com/typelevel/scala/issues/75
  lazy val JdkDir: File = List(
    // manual
    sys.env.get("JDK_HOME"),
    sys.env.get("JAVA_HOME"),
    // fallback
    sys.props.get("java.home").map(new File(_).getParent),
    sys.props.get("java.home"),
    // osx
    Try("/usr/libexec/java_home".!!.trim).toOption
  ).flatten.filter { n =>
      new File(n + "/lib/tools.jar").exists
    }.headOption.map(new File(_).getCanonicalFile).getOrElse(
      throw new FileNotFoundException(
        """Could not automatically find the JDK/lib/tools.jar.
      |You must explicitly set JDK_HOME or JAVA_HOME.""".stripMargin
      )
    )

  lazy val JavaFlags = {
    // WORKAROUND https://github.com/ensime/ensime-sbt/issues/91
    val raw = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList.map {
      case "-Xss1M" => "-Xss2m"
      case flag     => flag
    }
    raw.find { flag => flag.startsWith("-Xss") } match {
      case Some(has) => raw
      case None      => "-Xss2m" :: raw
    }
  }

}

case class EnsimeConfig(
  root: File,
  cacheDir: File,
  scalaCompilerJars: Set[File],
  ensimeServerJars: Set[File],
  name: String,
  scalaVersion: String,
  compilerArgs: List[String],
  modules: Map[String, EnsimeModule],
  javaHome: File,
  javaFlags: List[String],
  javaCompilerArgs: List[String],
  javaSrc: Set[File],
  subProjects: Map[String, EnsimeProject]
)

case class EnsimeModule(
  name: String,
  mainRoots: Set[File],
  testRoots: Set[File],
  targets: Set[File],
  testTargets: Set[File],
  dependsOnNames: Set[String],
  compileJars: Set[File],
  runtimeJars: Set[File],
  testJars: Set[File],
  sourceJars: Set[File],
  docJars: Set[File]
) {

  def dependencies(implicit lookup: String => EnsimeModule): Set[EnsimeModule] =
    dependsOnNames map lookup

}

case class EnsimeProject(
  name: String,
  dependsOnNames: Set[String],
  runtimeJars: Set[File],
  sourceJars: Set[File],
  docJars: Set[File],
  configs: Map[String, EnsimeConfiguration]
) {

  def dependencies(implicit lookup: String => EnsimeProject): Set[EnsimeProject] =
    dependsOnNames map lookup

}

case class EnsimeConfiguration(
  name: String,
  sources: Set[File],
  targets: Set[File],
  scalaCompilerArgs: List[String],
  javaCompilerArgs: List[String],
  jars: Set[File]
)

object CommandSupport {
  private def fail(errorMessage: String)(implicit state: State): Nothing = {
    state.log.error(errorMessage)
    throw new IllegalArgumentException()
  }

  def log(implicit state: State) = state.log

  // our version of http://stackoverflow.com/questions/25246920
  implicit class RichSettingKey[A](key: SettingKey[A]) {
    def gimme(implicit pr: ProjectRef, bs: BuildStructure, s: State): A =
      gimmeOpt getOrElse { fail(s"Missing setting: ${key.key.label}") }
    def gimmeOpt(implicit pr: ProjectRef, bs: BuildStructure, s: State): Option[A] =
      key in pr get bs.data
  }

  implicit class RichTaskKey[A](key: TaskKey[A]) {
    def run(implicit pr: ProjectRef, bs: BuildStructure, s: State): A =
      runOpt.getOrElse { fail(s"Missing task key: ${key.key.label}") }
    def runOpt(implicit pr: ProjectRef, bs: BuildStructure, s: State): Option[A] =
      EvaluateTask(bs, key, s, pr).map(_._2) match {
        case Some(Value(v)) => Some(v)
        case _              => None
      }

    def forAllProjects(state: State, projects: Seq[ProjectRef]): Task[Map[ProjectRef, A]] = {
      val tasks = projects.flatMap(p => key.in(p).get(Project.structure(state).data).map(_.map(it => (p, it))))
      std.TaskExtra.joinTasks(tasks).join.map(_.toMap)
    }
  }

}

// direct formatter to deal with a small number of domain objects
// if we had to do this for general objects, it would make sense
// to create a series of implicit convertors to an SExp hierarchy
object SExpFormatter {

  def toSExp(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  def toSExp(f: File): String = toSExp(f.getAbsolutePath)

  def fsToSExp(ss: Iterable[File]): String =
    if (ss.isEmpty) "nil"
    else {
      // normalise and ensure monkeys go first
      // (bit of a hack to do it here, maybe best when creating)
      val (monkeys, humans) = ss.toList.distinct.sortBy { f =>
        f.getName + f.getPath
      }.partition(_.getName.contains("monkey"))
      monkeys ::: humans
    }.map(toSExp).mkString("(", " ", ")")

  def ssToSExp(ss: Iterable[String]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.map(toSExp).mkString("(", " ", ")")

  def msToSExp(ss: Iterable[EnsimeModule]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.sortBy(_.name).map(toSExp).mkString("(", " ", ")")

  def prToSExp(ss: Iterable[EnsimeProject]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.sortBy(_.name).map(toSExp).mkString("(", " ", ")")

  def csToSExp(ss: Iterable[EnsimeConfiguration]): String =
    if (ss.isEmpty) "nil"
    else ss.toSeq.sortBy(_.name).map(toSExp).mkString("(", " ", ")")

  def fToSExp(key: String, op: Option[File]): String =
    op.map { f => s":$key ${toSExp(f)}" }.getOrElse("")

  def sToSExp(key: String, op: Option[String]): String =
    op.map { f => s":$key ${toSExp(f)}" }.getOrElse("")

  def toSExp(b: Boolean): String = if (b) "t" else "nil"

  // a lot of legacy key names and conventions
  def toSExp(c: EnsimeConfig): String = s"""(
 :root-dir ${toSExp(c.root)}
 :cache-dir ${toSExp(c.cacheDir)}
 :scala-compiler-jars ${fsToSExp(c.scalaCompilerJars)}
 :ensime-server-jars ${fsToSExp(c.ensimeServerJars)}
 :name "${c.name}"
 :java-home ${toSExp(c.javaHome)}
 :java-flags ${ssToSExp(c.javaFlags)}
 :java-compiler-args ${ssToSExp(c.javaCompilerArgs)}
 :reference-source-roots ${fsToSExp(c.javaSrc)}
 :scala-version ${toSExp(c.scalaVersion)}
 :compiler-args ${ssToSExp(c.compilerArgs)}
 :subprojects ${msToSExp(c.modules.values)}
 :projects ${prToSExp(c.subProjects.values)}
)"""

  // a lot of legacy key names and conventions
  def toSExp(m: EnsimeModule): String = s"""(
   :name ${toSExp(m.name)}
   :source-roots ${fsToSExp((m.mainRoots ++ m.testRoots))}
   :targets ${fsToSExp(m.targets)}
   :test-targets ${fsToSExp(m.testTargets)}
   :depends-on-modules ${ssToSExp(m.dependsOnNames.toList.sorted)}
   :compile-deps ${fsToSExp(m.compileJars)}
   :runtime-deps ${fsToSExp(m.runtimeJars)}
   :test-deps ${fsToSExp(m.testJars)}
   :doc-jars ${fsToSExp(m.docJars)}
   :reference-source-roots ${fsToSExp(m.sourceJars)})"""

  def toSExp(p: EnsimeProject): String = s"""(
   :name ${toSExp(p.name)}
   :depends-on-projects ${ssToSExp(p.dependsOnNames.toList.sorted)}
   :runtime-jars ${fsToSExp(p.runtimeJars)}
   :doc-jars ${fsToSExp(p.docJars)}
   :reference-sources ${fsToSExp(p.sourceJars)}
   :configurations ${csToSExp(p.configs.values)})"""

  def toSExp(f: EnsimeConfiguration): String = s"""
    (:name ${toSExp(f.name)}
     :sources ${fsToSExp(f.sources)}
     :targets ${fsToSExp(f.targets)}
     :scalac-options ${ssToSExp(f.scalaCompilerArgs)}
     :javac-options ${ssToSExp(f.javaCompilerArgs)}
     :library-dependencies ${fsToSExp(f.jars)})"""
}
