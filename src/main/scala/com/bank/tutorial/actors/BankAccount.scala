package com.bank.tutorial.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.util.{Failure, Success, Try}

// single bank account
// receives messages and will store events in cassandra
// uses event sourcing technique (play back capability: fault tolerance, auditing purpose)
object BankAccount {


  /* data Modeling start ... */
  // messages or, commands, if using Akka Persistence nomenclature
  sealed trait Command
  object Command {
    case class CreateBankAccount(user: String, currency: String, balance: Double, replyTo: ActorRef[Response]) extends Command
    case class UpdateBalance(id: String, currency: String, amount: Double /* +- */ , replyTo: ActorRef[Response]) extends Command
    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }

  // events = to persist to Cassandra
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BankAccountBalanceUpdated(balance: Double, currency: String) extends Event

  // state = internal state of this bank account
  case class BankAccount(id: String, user: String, currency: String, balance: Double)

  // responses = send back replies to whoever is querying or modifying this bank account
  sealed trait Response
  object Response{
    case class BankAccountCreatedResponse(id: String) extends Response
    case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Try[BankAccount]) extends Response
    case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
  }
  /* ... data Modeling end */

  import Command._
  import Response._
  // given the actor's current state and the command message, produce an Effect that includes the persisted event and a state
  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      /*
          - receives CreateBankAccount command from bank
          - persists a BankAccountCreated event
          - updates state, eg BankAccount
          - replies to bank with BankAccountCreated
          - (bank surfaces the response to the HTTP Server)
       */
      case CreateBankAccount(user, currency, balance, bankActor) =>
        val id = state.id
        Effect
          .persist(BankAccountCreated(BankAccount(id, user, currency, balance)))
          .thenReply(bankActor)(_ => BankAccountCreatedResponse(id))

      case UpdateBalance(id, currency, amount, bankActor) =>
        val newBalance = state.balance + amount
        if (newBalance < 0) // illegal
          Effect.reply(bankActor)(BankAccountBalanceUpdatedResponse(Failure(new RuntimeException("Overdraft is not permitted"))))
        else
          Effect.persist(BankAccountBalanceUpdated(newBalance, currency))
            .thenReply(bankActor)(newState => BankAccountBalanceUpdatedResponse(Success(newState)))

      case GetBankAccount(_, bankActor) =>
        Effect.reply(bankActor)(GetBankAccountResponse(Some(state)))
    }
  // receives an event returns current state
  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) => bankAccount
      case BankAccountBalanceUpdated(balance, currency) =>
        state.copy(balance =  balance, currency = currency)
    }

  /* actor definition start ... */
  //persistent actor is comprised of:
  // initial state (will be updated state will be used by next command)
  // command handler = message handler to persist events
  // event handler to update state
  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
  /* ... actor definition ends */

}
