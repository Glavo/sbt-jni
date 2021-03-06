import sbt._
import sbt.Keys._

object ScriptedHelper extends AutoPlugin {

  override def requires = empty
  override def trigger = allRequirements

  override def projectSettings = Seq(
    scalacOptions ++= Seq("-feature", "-deprecation"),
    crossScalaVersions := Seq("2.13.1", "2.12.10", "2.11.12"),
    scalaVersion := crossScalaVersions.value.head
  )

}
