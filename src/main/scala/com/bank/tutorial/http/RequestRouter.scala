package com.bank.tutorial.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Location, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives._
import com.bank.tutorial.actors.BankAccount.Command
import com.bank.tutorial.actors.BankAccount.Response
import com.bank.tutorial.actors.BankAccount.Response._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import RequestValidation._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import cats.data.Validated.{Invalid, Valid}

class RequestRouter(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {

  case class FailureResponse(reason: String)

  implicit val timeout: Timeout = Timeout(5.seconds)

  //if valid, continue the route, otherwise complete with 400 status
  def validateRequest[A](request: A)(successRoute: Route)(implicit validator: RequestValidator[A]): Route = {
    validator.validateRequestImpl(request) match {
      case Valid(_) => successRoute
      case Invalid(failures) =>
        complete(StatusCodes.BadRequest, FailureResponse(failures.toList.map(_.errorMessage).mkString(", ")))
    }
  }

  trait Ask[A <: RequestEntity, B] extends ((A, B) => Future[Response])
  //functional polymorphism. values are supplied to def toAkkaRequest()() depending on the runtime type of request argument
  implicit val createCommand: Ask[BankAccountCreateRequest, String] =
    (requestEntity: BankAccountCreateRequest, id: String) =>
      bank.ask(replyTo => requestEntity.toCommand(replyTo))
  implicit val updateCommand: Ask[BankAccountUpdateRequest, String] =
    (requestEntity: BankAccountUpdateRequest, id: String) =>
      bank.ask(replyTo => requestEntity.toCommand(id, replyTo))
  implicit val retrieveCommand: Ask[BankAccountRetrieveRequest, String] =
    (requestEntity: BankAccountRetrieveRequest, id: String) =>
      bank.ask(replyTo => requestEntity.toCommand(id, replyTo))

  //use request entity to generate akka ask pattern
  def ask[A <: RequestEntity, B](request: A, id: B = "")(implicit ask: Ask[A, B]): Future[Response] =
    ask.apply(request, id)

  val routes =
    pathPrefix(("bank")) {
      pathEndOrSingleSlash {
        post {
         //parse json payload
          entity(as[BankAccountCreateRequest]) { createRequest =>
            validateRequest(createRequest) {
              onSuccess(ask(createRequest)) {
                case BankAccountCreatedResponse(id) =>
                  respondWithHeader(Location(s"/bank/$id")) {
                    complete(StatusCodes.Created)
                  }
              }
            }
          }
        }
      } ~
        path(Segment) { id =>
          get {
            val retrieveRequest = BankAccountRetrieveRequest(id)
            validateRequest(retrieveRequest) {
              onSuccess(ask(retrieveRequest, id)) {
                case GetBankAccountResponse(Some(account)) =>
                  complete(account)
                case GetBankAccountResponse(None) =>
                  complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id could not be found"))
              }
            }
          } ~ put {
            //parse json payload
            entity(as[BankAccountUpdateRequest]) { updateRequest =>
              validateRequest(updateRequest) {
                onSuccess(ask(updateRequest, id)) {
                  case BankAccountBalanceUpdatedResponse(Success(account)) =>
                    complete(account)
                  case BankAccountBalanceUpdatedResponse(Failure(reason)) =>
                    complete(StatusCodes.NotFound, FailureResponse(reason.getMessage))
                }
              }
            }
          }
        }
    }

}
