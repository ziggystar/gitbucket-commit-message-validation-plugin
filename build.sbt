name := "gitbucket-message-format-hook"
organization := "io.github.gitbucket"
version := "1.0.0"
scalaVersion := "2.13.5"
gitbucketVersion := "4.35.3"

scalacOptions := Seq("-deprecation", "-feature", "-language:postfixOps")
Compile / javacOptions ++= Seq("-target", "8", "-source", "8")

useJCenter := true
