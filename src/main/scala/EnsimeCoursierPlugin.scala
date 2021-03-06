// Copyright (C) 2014 - 2016 ENSIME Contributors
// Licence: Apache-2.0
package org.ensime

import scala.util.Properties.{versionNumberString => sbtScalaVersion}

import EnsimeCoursierKeys._
import EnsimeKeys._
import sbt._
import sbt.IO._
import sbt.Keys._
import scalaz._
import scalaz.concurrent.Task

object EnsimeCoursierKeys {
  val ensimeServerVersion = settingKey[String](
    "The ensime server version"
  )

  val ensimeResolvers = settingKey[Seq[coursier.Repository]](
    "The resolvers to download the scala compiler and ensime-server jars"
  )

  def addEnsimeScalaPlugin(module: ModuleID, args: String = ""): Seq[Setting[_]] = {
    ensimeScalacOptions += {
      val mod = CrossVersion(
        ensimeScalaVersion.value,
        CrossVersion.binaryScalaVersion(ensimeScalaVersion.value)
      )(module.intransitive)
      val resolvers = ensimeResolvers.value
      val jar = EnsimeCoursierPlugin.resolve(mod)(resolvers).head
      s"-Xplugin:${jar.getCanonicalFile}$args"
    }
  }

}

/**
 * Defines the tasks that resolve all the jars needed to start the
 * ensime-server.
 *
 * Intentionally separated from EnsimePlugin to allow corporate users
 * to avoid a dependency on coursier and provide hard coded jar paths.
 */
object EnsimeCoursierPlugin extends AutoPlugin {

  override def requires = EnsimePlugin
  override def trigger = allRequirements
  val autoImport = EnsimeCoursierKeys

  override lazy val buildSettings = Seq(
    ensimeServerVersion := "1.0.0",
    ensimeResolvers := Seq(
      // intentionally not using the ivy cache because it's very unreliable
      coursier.MavenRepository("https://repo1.maven.org/maven2/"),
      // including snapshots by default makes it easier to use dev ensime
      coursier.MavenRepository("https://oss.sonatype.org/content/repositories/snapshots/")
    ),

    ensimeScalaJars := resolveScalaJars(scalaOrganization.value, ensimeScalaVersion.value)(ensimeResolvers.value),
    ensimeScalaProjectJars := resolveScalaJars("org.scala-lang", sbtScalaVersion)(ensimeResolvers.value),
    ensimeServerJars := resolveEnsimeJars(scalaOrganization.value, ensimeScalaVersion.value, ensimeServerVersion.value)(ensimeResolvers.value),
    ensimeServerProjectJars := resolveEnsimeJars("org.scala-lang", sbtScalaVersion, ensimeServerVersion.value)(ensimeResolvers.value)
  )

  def resolve(modules: ModuleID*)(implicit repos: Seq[coursier.Repository]): Seq[File] = {
    val resolution = coursier.Resolution(
      modules.map { module =>
      coursier.Dependency(
        coursier.Module(
          module.organization,
          module.name
        ),
        module.revision,
        configuration = module.configurations.getOrElse(""),
        transitive = module.isTransitive
      )
    }.toSet
    )

    val fetch = coursier.Fetch.from(repos, coursier.Cache.fetch())
    val resolved = resolution.process.run(fetch).unsafePerformSync
    resolved.errors.foreach { err =>
      throw new RuntimeException(s"failed to resolve $err")
    }

    Task.gatherUnordered(
      resolved.artifacts.map(coursier.Cache.file(_).run)
    ).unsafePerformSync.map {
        case -\/(err)  => throw new RuntimeException(err.message)
        case \/-(file) => file
      }
  }

  def resolveScalaJars(org: String, version: String)(implicit repos: Seq[coursier.Repository]): Seq[File] = resolve(
    org % "scalap" % version intransitive,
    org % "scala-compiler" % version intransitive,
    org % "scala-reflect" % version intransitive,
    org % "scala-library" % version intransitive
  )

  def resolveEnsimeJars(org: String, scala: String, ensime: String)(implicit repos: Seq[coursier.Repository]): Seq[File] = {
    val Some((major, minor)) = CrossVersion.partialVersion(scala)
    resolve(
      "org.ensime" % s"server_$major.$minor" % ensime,
      org % "scalap" % scala intransitive
    )
  }

}
