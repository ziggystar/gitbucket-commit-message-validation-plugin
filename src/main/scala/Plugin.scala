import cats.instances.map
import cats.syntax.flatMap
import gitbucket.core.model.Profile
import gitbucket.core.plugin.{IssueHook, ReceiveHook}
import gitbucket.core.service.{
  AccountService,
  CommitStatusService,
  RepositoryService,
  SystemSettingsService
}
import gitbucket.core.util.{Directory, JGitUtil}
import io.circe._
import io.circe.generic.semiauto._
import io.github.gitbucket.solidbase.model.Version
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.DepthWalk.RevWalk
import org.eclipse.jgit.transport.{ReceiveCommand, ReceivePack}
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory

import scala.util.Using

trait Validator {
  def apply(ref: String, message: String): Seq[String]
  def validateAll(commits: Seq[(String, String)]): Seq[String] =
    commits.flatMap(cm => this.apply(cm._1, cm._2))
}
object Validator {
  def fromList(vs: Seq[Validator]): Validator = ListValidator(vs.toList)
  def parseYamlSpec(spec: String): Either[Error, Validator] =
    yaml.parser
      .parse(spec)
      .flatMap(js => implicitly[Decoder[List[PatternValidator]]].decodeJson(js))
      .map(fromList)
}

case class ListValidator(vs: List[Validator]) extends Validator {
  override def apply(ref: String, message: String): Seq[String] =
    vs.flatMap(_.apply(ref, message))
}

case class PatternValidator(
    refPattern: Option[String],
    messagePattern: String,
    note: Option[String]
) extends Validator {
  private val logger = LoggerFactory.getLogger(classOf[PatternValidator])
  def apply(ref: String, message: String): List[String] = {
    if (refPattern.forall(ref.matches) && !message.matches(messagePattern)) {
      List(note.getOrElse("commit message rejected") + s"; '$message' failed on pattern '$messagePattern' in ref matching '${refPattern.getOrElse('*')}'")
    } else Nil
  }
}

object PatternValidator {
  implicit val fooDecoder: Decoder[PatternValidator] =
    deriveDecoder[PatternValidator]
  implicit val fooEncoder: Encoder[PatternValidator] =
    deriveEncoder[PatternValidator]
}

class CommitMessageHook
    extends ReceiveHook
    with RepositoryService
    with AccountService
    with CommitStatusService
    with SystemSettingsService {

  private val logger = LoggerFactory.getLogger(classOf[CommitMessageHook])

  def parseValidationSpec(spec: String): String => Option[String] = msg => {
    val pattern = spec.linesIterator.toSeq.headOption.getOrElse("")
    if (!msg.matches(pattern))
      Some(s"commit message '$msg' does not match pattern '$pattern'")
    else None
  }

  override def preReceive(
      owner: String,
      repository: String,
      receivePack: ReceivePack,
      command: ReceiveCommand,
      pusher: String,
      mergePullRequest: Boolean
  )(implicit session: Profile.profile.api.Session): Option[String] =
    Using.resource(Git.open(Directory.getRepositoryDir(owner, repository))) {
      git =>

        val result: Option[Either[Error, Option[String]]] = for {
          cmd <- Option(command).filter(c =>
            List(
              ReceiveCommand.Type.UPDATE,
              ReceiveCommand.Type.UPDATE_NONFASTFORWARD
            ).contains(c.getType)
          )
          tree = Using.resource(new RevWalk(git.getRepository, 0)) { revWalk =>
            revWalk.parseCommit(cmd.getOldId).getTree
          }
          treeWalk <- Option(
            TreeWalk.forPath(git.getRepository, ".gitMessageFormat", tree)
          )
          fileContent = Using.resource(treeWalk) { f =>
            new String(git.getRepository.open(f.getObjectId(0)).getBytes)
          }
          error = Validator.parseYamlSpec(fileContent).map { validator =>
            Option(
              validator.validateAll(
                JGitUtil
                  .getCommitLog(
                    git,
                    cmd.getOldId.getName,
                    cmd.getNewId.getName
                  )
                  //.tail //we don't check the old commit
                  .map(cm => (cmd.getRef.getName, cm.shortMessage))
              )
            )
              .filterNot(_.isEmpty)
              .map(_.mkString("\n"))
          }
        } yield error

        result.flatMap(
          _.left
            .map { err =>
              logger.warn("error while processing message format hook: " + err)
              None
            }
            .merge
            .map { error =>
              logger.info(
                s"reject push to $owner/$repository on ref ${command.getRef.getName} by $pusher: $error"
              )
              error
            }
        )
    }
}

class Plugin extends gitbucket.core.plugin.Plugin {
  override val pluginId: String = "messageFormat"
  override val pluginName: String = "Git Message Format Plugin"

  override val description: String =
    "A plugin that performs a commit message check based on file .gitMessageFormat in repository."
  override val versions: List[Version] = List(
    new Version("1.0.0")
  )

  override val receiveHooks: Seq[ReceiveHook] = Seq(
    new CommitMessageHook()
  )
}
