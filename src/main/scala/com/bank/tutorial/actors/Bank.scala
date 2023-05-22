package com.bank.tutorial.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
 import akka.actor.typed.{ActorRef, Behavior}
 import akka.persistence.typed.PersistenceId
 import akka.persistence.typed.scaladsl.Effect
 import akka.persistence.typed.scaladsl.EventSourcedBehavior
 import BankAccount.Response._
 import BankAccount.Command
 import BankAccount.Command._

 import java.util.UUID

object Bank {


     // messages, ege commands
     // events
     sealed trait  Event
      case class BankAccountInitialized(id: String) extends Event
     // state = internal state of this bank
     case class AccountLedger(accounts: Map[String, ActorRef[Command]])

  // command handler
  // given the actor's current state and the command produce an Effect that includes the persisted event and a state
  def commandHandler(context: ActorContext[Command]):(AccountLedger, Command) => Effect[Event, AccountLedger] = (state, command) =>
    command match {
      case createCommand @ CreateBankAccount(_,_,_,_) =>
        val id = s"${UUID.randomUUID().toString}"
        val bankAccountActor = context.spawn(BankAccount(id), id)
        Effect
          .persist(BankAccountInitialized(id))
          .thenReply(bankAccountActor)(_=> createCommand)
      case updateCommand @ UpdateBalance(id, _, _, replyTo) =>
        state.accounts.get(id) match {
          case Some(bankAccount) => Effect.reply(bankAccount)(updateCommand)
          case None =>
            Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(throw new RuntimeException("Bank account cannot not be Found")))
        }
      case getCommand @ GetBankAccount(id, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(getCommand)
          case None =>
            Effect.reply(replyTo)(GetBankAccountResponse(None))
        }
    }

  // event handler
  def eventHandler(context: ActorContext[Command]): (AccountLedger, Event) => AccountLedger = (state, event) =>
    event match {
      case BankAccountInitialized(id) =>
        val account  = context.child(id)
          .getOrElse(context.spawn(BankAccount(id), id))
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.accounts + (id -> account))

    }

  def apply(id: String): Behavior[Command] = {
    //the command the actor will receive,
    //the event it will persist to Casandra
    // the state it will manage internally
    Behaviors.setup{ context =>
      EventSourcedBehavior[Command, Event, AccountLedger](
        persistenceId = PersistenceId.ofUniqueId(id),
        emptyState = AccountLedger(Map()),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler(context)
      )
    }



  }


}
