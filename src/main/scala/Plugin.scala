import gitbucket.core.model.Profile
import gitbucket.core.plugin.ReceiveHook
import gitbucket.core.service.{AccountService, CommitStatusService, RepositoryService, SystemSettingsService}
import gitbucket.core.util.{Directory, JGitUtil}
import io.github.gitbucket.solidbase.model.Version
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.DepthWalk.RevWalk
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.transport.{ReceiveCommand, ReceivePack}
import org.eclipse.jgit.treewalk.TreeWalk

import scala.util.Using

class CommitHook
  extends ReceiveHook
    with RepositoryService
    with AccountService
    with CommitStatusService
    with SystemSettingsService {

  def parseValidationSpec(spec: String): String => Option[String] = msg => {
    val pattern = spec.linesIterator.toSeq.headOption.getOrElse("")
    if(!msg.matches(pattern)) Some(s"commit message '$msg' does not match pattern '$pattern'") else None
  }

  override def preReceive(owner: String, repository: String, receivePack: ReceivePack, command: ReceiveCommand, pusher: String, mergePullRequest: Boolean)(implicit session: Profile.profile.api.Session): Option[String] = {
    Using.resource(Git.open(Directory.getRepositoryDir(owner, repository))) { git =>
      val configFileContent = Using.resource(new RevWalk(git.getRepository, 0)){revWalk =>
        val tree: RevTree = revWalk.parseCommit(command.getOldId).getTree
        Option(TreeWalk.forPath(git.getRepository, ".gitMessageFormat", tree))
          .map(fw => Using.resource(fw) { f =>
            new String(git.getRepository.open(f.getObjectId(0)).getBytes)
          })
      }
      configFileContent.map(parseValidationSpec).flatMap { validator =>
        Option(JGitUtil
          .getCommitLog(git, command.getOldId.getName, command.getNewId.getName)
          .map(_.shortMessage)
          .flatMap(validator) //retain only malformed messages
        ).filterNot(_.isEmpty)
          .map(_.mkString("\n"))
      }
    }
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
    new CommitHook()
  )
}
