package com.bank.tutorial.http

import akka.actor.typed.ActorRef
import cats.data.ValidatedNel
import cats.implicits.catsSyntaxValidatedId
import com.bank.tutorial.actors.BankAccount.{Command, Response}
import com.bank.tutorial.actors.BankAccount.Command.{CreateBankAccount, GetBankAccount, UpdateBalance}
import com.bank.tutorial.http.RequestValidation.{ ValidationResult, validateMinimum, validateOverdraft, validateRequired, validateUUID}

import java.util.UUID

import cats.implicits._


object RequestValidation {

  // define field level validation criteria as type classes
  // field must be present
  trait Required[A] extends (A => Boolean)

  // field must be A UUID String
  trait UUID[A] extends (A => Boolean)

  // minimum transaction amount
  trait Minimum[A] extends ((A, Double) => Boolean) // for numerical fields.

  // overdraft protection
  trait Overdraft[A] extends ((A, Double) => Boolean) // for numerical fields.

  //instantiate validation criteria (polymorphism/overloading function style)
  implicit val requiredString: Required[String] = _.nonEmpty
  implicit val uuidString: UUID[String] = { (s) =>
    try {
      (s) == UUID.fromString(s).toString
    } catch {
      case e:IllegalArgumentException => false}
  }
  implicit val minimumAmountInt: Minimum[Int] = Math.abs(_) >= _
  implicit val minimumAmountDouble: Minimum[Double] = Math.abs(_) >= _
  implicit val overdraftInt: Overdraft[Int] = _ >= _
  implicit val overdraftDouble: Overdraft[Double] = _ >= _

  // evaluate field values, applying criteria instances
  def required[A](value: A)(implicit req: Required[A]): Boolean = req(value)

  // evaluate field values, applying criteria instances
  def uuid[A](value: A)(implicit req: UUID[A]): Boolean = req(value)

  def minimum[A](value: A, threshold: Double)(implicit min: Minimum[A]): Boolean = min(value, threshold)

  def overdraft[A](value: A, threshold: Double)(implicit draft: Overdraft[A]): Boolean = draft(value, threshold)

  // main API
  def validateOverdraft[A: Overdraft](value: A, threshold: Double, fieldName: String): ValidationResult[A] = {
    if (overdraft(value, threshold)) value.validNel
    else NegativeValue(fieldName).invalidNel
  }

  def validateMinimum[A: Minimum](value: A, min: Double, fieldName: String): ValidationResult[A] = {
    if (minimum(value, min)) value.validNel
    else BelowMinimumValue(fieldName, min).invalidNel
  }

  def validateRequired[A: Required](value: A, fieldName: String): ValidationResult[A] = {
    if (required(value)) value.validNel
    else EmptyField(fieldName).invalidNel
  }

 def validateUUID[A: UUID](value: A, fieldName: String): ValidationResult[A] = {
    if (uuid(value)) value.validNel
    else UUIDValue(fieldName).invalidNel
  }

  //cats validated type! aggregates errors related to a piece of data and surfaces out errors
  trait ValidationFailure {
    def errorMessage: String
  }

  //ValidationFailure implementations to be surfaced to the client in json format
  case class EmptyField(fieldName: String) extends ValidationFailure {
    override def errorMessage = s"value of '$fieldName' is empty"
  }

  case class NegativeValue(fieldName: String) extends ValidationFailure {
    override def errorMessage = s"value of '$fieldName' is negative"
  }

  case class BelowMinimumValue(fieldName: String, minimum: Double) extends ValidationFailure {
    override def errorMessage = s"value of '$fieldName' is below the threshold of $minimum"
  }

  case class UUIDValue(fieldName: String) extends ValidationFailure {
    override def errorMessage =  s"value of '$fieldName' is not a valid UUID"
  }

  type ValidationResult[A] = ValidatedNel[ValidationFailure, A]


}


// general type class for requests
trait RequestEntity

trait RequestValidator[A] {
  def validateRequestImpl(value: A): ValidationResult[A]
}

case class BankAccountCreateRequest(user: String, currency: String, balance: Double) extends RequestEntity {
  def toCommand(replyTo: ActorRef[Response]): Command =
    CreateBankAccount(user, currency, balance, replyTo)
}
object BankAccountCreateRequest {
  implicit val validator: RequestValidator[BankAccountCreateRequest] = new RequestValidator[BankAccountCreateRequest] {
    override def validateRequestImpl(request: BankAccountCreateRequest) = {
      val userValidation = validateRequired(request.user, "user")
      val currencyValidation = validateRequired(request.currency, "currency")
      val balanceValidation = validateMinimum(request.balance, 0, "balance")
        .combineK(validateOverdraft(request.balance, 0.01, "amount"))
      (userValidation, currencyValidation, balanceValidation).mapN(BankAccountCreateRequest.apply)
    }
  }
}

case class BankAccountUpdateRequest(currency: String, amount: Double) extends RequestEntity {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command =
    UpdateBalance(id, currency, amount, replyTo)
}
object BankAccountUpdateRequest {
  implicit val validator: RequestValidator[BankAccountUpdateRequest] = new RequestValidator[BankAccountUpdateRequest] {
    override def validateRequestImpl(request: BankAccountUpdateRequest): ValidationResult[BankAccountUpdateRequest] = {
      val currencyValidation = validateRequired(request.currency, "currency")
      val amountValidation = validateMinimum(request.amount, 0.01, "amount")
      (currencyValidation, amountValidation).mapN(BankAccountUpdateRequest.apply)
    }

  }
}

case class BankAccountRetrieveRequest(id: String) extends RequestEntity {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command =
    GetBankAccount(id, replyTo)
}
object BankAccountRetrieveRequest {
  implicit val validator: RequestValidator[BankAccountRetrieveRequest] = new RequestValidator[BankAccountRetrieveRequest] {
    override def validateRequestImpl(request: BankAccountRetrieveRequest): ValidationResult[BankAccountRetrieveRequest] = {
      val uuidValidation = validateUUID(request.id, "id")
      uuidValidation.map(BankAccountRetrieveRequest.apply)
    }

  }
}