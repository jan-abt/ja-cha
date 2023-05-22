package com.bank.tutorial.app

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.bank.tutorial.actors.BankAccount.Command
import com.bank.tutorial.actors.Bank
import com.bank.tutorial.http.RequestRouter

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Main {

  trait RootCommand
  case class RetrieveActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

  def startHttpServer(bank: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {
    implicit val ex: ExecutionContext = system.executionContext
    val router = new RequestRouter(bank)
    val routes = router.routes

    val httpBindingFuture = Http().newServerAt("localhost", 8080).bind(routes)

    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(s"server is online at http://${address.getHostString}:${address.getPort}")
      case Failure(exception) =>
        system.log.error(s"failed to bind HTTP server, reason: $exception")
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {

    val rootBehavior: Behavior[RootCommand] =
      Behaviors.setup { context =>
        val id = s"Bank-One"
        val bankActor = context.spawn(Bank(id), id)

        Behaviors.receiveMessage {
          case RetrieveActor(replyTo) =>
            replyTo.tell(bankActor)
            Behaviors.same
        }
      }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "BanksSystem")
    implicit val ec: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout(5.seconds)
    val bankActorFuture: Future[ActorRef[Command]] = system.ask(replyTo => RetrieveActor(replyTo))

    bankActorFuture.foreach(startHttpServer)

  }

}
