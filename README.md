# gitbucket-commit-message-format-plugin

A [GitBucket](https://github.com/gitbucket/gitbucket) plugin that performs a format check on commit messages.

## Usage

To begin using the plugin:

1. Download the latest release
2. Install in the `$GITBUCKET_HOME/plugins` directory
3. Restart GitBucket
4. Add a `.gitMessageFormat` file, that contains a regular expression at the root folder of your repository

## Building from Source

Installation from source is also an option when installing this plugin.
To build from source, ensure the following are true first:

- Java is installed [jdk.java.net/14](https://jdk.java.net/14/)
- SBT is installed [scala-sbt.org](https://www.scala-sbt.org/)

Then, continue:

Execute `sbt package` to package the source into a `.jar` file

## Versions Table

| Plugin version | GitBucket version |
|:---------------| :---------------- |
| 1.0.0          | 4.35.x            |
