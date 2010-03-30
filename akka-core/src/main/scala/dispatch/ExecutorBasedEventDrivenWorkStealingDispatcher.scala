/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.concurrent.CopyOnWriteArrayList
import se.scalablesolutions.akka.actor.Actor

/**
 * An executor based event driven dispatcher which will try to redistribute work from busy actors to idle actors. It is assumed
 * that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors. I.e. the
 * actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.
 * <p/>
 * Although the technique used in this implementation is commonly known as "work stealing", the actual implementation is probably
 * best described as "work donating" because the actor of which work is being stolen takes the initiative.
 * <p/>
 * This dispatcher attempts to redistribute work between actors each time a message is dispatched on a busy actor. Work
 * will not be redistributed when actors are busy, but no new messages are dispatched.
 * TODO: it would be nice to be able to redistribute work even when no new messages are being dispatched, without impacting dispatching performance ?!
 * <p/>
 * The preferred way of creating dispatchers is to use
 * the {@link se.scalablesolutions.akka.dispatch.Dispatchers} factory object.
 *
 * @see se.scalablesolutions.akka.dispatch.ExecutorBasedEventDrivenWorkStealingDispatcher
 * @see se.scalablesolutions.akka.dispatch.Dispatchers
 *
 * @author Jan Van Besien
 */
class ExecutorBasedEventDrivenWorkStealingDispatcher(_name: String) extends MessageDispatcher with ThreadPoolBuilder {
  @volatile private var active: Boolean = false

  /** Type of the actors registered in this dispatcher. */
  private var actorType:Option[Class[_]] = None

  private val pooledActors = new CopyOnWriteArrayList[Actor]

  /** The index in the pooled actors list which was last used to steal work */
  @volatile private var lastIndex = 0

  // TODO: is there a naming convention for this name?
  val name: String = "event-driven-work-stealing:executor:dispatcher:" + _name
  init

  def dispatch(invocation: MessageInvocation) = if (active) {
    executor.execute(new Runnable() {
      def run = {
        if (!tryProcessMailbox(invocation.receiver)) {
          // we are not able to process our mailbox (another thread is busy with it), so lets donate some of our mailbox
          // to another actor and then process his mailbox in stead.
          findThief(invocation.receiver) match {
            case Some(thief) => {
              tryDonateAndProcessMessages(invocation.receiver, thief)
            }
            case None => { /* no other actor in the pool */ }
          }
        }
      }
    })
  } else throw new IllegalStateException("Can't submit invocations to dispatcher since it's not started")

  /**
   * Try processing the mailbox of the given actor. Fails if the dispatching lock on the actor is already held by
   * another thread (because then that thread is already processing the mailbox).
   *
   * @return true if the mailbox was processed, false otherwise
   */
  private def tryProcessMailbox(receiver: Actor): Boolean = {
    var lockAcquiredOnce = false
    // this do-wile loop is required to prevent missing new messages between the end of processing
    // the mailbox and releasing the lock
    do {
      if (receiver._dispatcherLock.tryLock) {
        lockAcquiredOnce = true
        try {
          processMailbox(receiver)
        } finally {
          receiver._dispatcherLock.unlock
        }
      }
    } while ((lockAcquiredOnce && !receiver._mailbox.isEmpty))

    return lockAcquiredOnce
  }

  /**
   * Process the messages in the mailbox of the given actor.
   */
  private def processMailbox(receiver: Actor) = {
    var messageInvocation = receiver._mailbox.poll
    while (messageInvocation != null) {
      messageInvocation.invoke
      messageInvocation = receiver._mailbox.poll
    }
  }

  private def findThief(receiver: Actor): Option[Actor] = {
    // copy to prevent concurrent modifications having any impact
    val pooledActorsCopy = pooledActors.toArray(new Array[Actor](pooledActors.size))
    var lastIndexCopy = lastIndex
    if (lastIndexCopy > pooledActorsCopy.size)
      lastIndexCopy = 0

    // we risk to pick a thief which is unregistered from the dispatcher in the meantime, but that typically means
    // the dispatcher is being shut down...
    doFindThief(receiver, pooledActorsCopy, lastIndexCopy) match {
      case (thief: Option[Actor], index: Int) => {
        lastIndex = (index + 1) % pooledActorsCopy.size
        return thief
      }
    }
  }

  /**
   * Find a thief to process the receivers messages from the given list of actors.
   *
   * @param receiver original receiver of the message
   * @param actors list of actors to find a thief in
   * @param startIndex first index to start looking in the list (i.e. for round robin)
   * @return the thief (or None) and the new index to start searching next time
   */
  private def doFindThief(receiver: Actor, actors: Array[Actor], startIndex: Int): (Option[Actor], Int) = {
    for (i <- 0 to actors.length) {
      val index = (i + startIndex) % actors.length
      val actor = actors(index)
      if (actor != receiver) { // skip ourselves
        if (actor._mailbox.isEmpty) { // only pick actors that will most likely be able to process the messages
          return (Some(actor), index)
        }
      }
    }
    return (None, startIndex) // nothing found, reuse same start index next time
  }

  /**
   * Try donating messages to the thief and processing the thiefs mailbox. Doesn't do anything if we can not acquire
   * the thiefs dispatching lock, because in that case another thread is already processing the thiefs mailbox.
   */
  private def tryDonateAndProcessMessages(receiver: Actor, thief: Actor) = {
    if (thief._dispatcherLock.tryLock) {
      try {
        donateAndProcessMessages(receiver, thief)
      } finally {
        thief._dispatcherLock.unlock
      }
    }
  }

  /**
   * Donate messages to the thief and process them on the thief as long as the receiver has more messages.
   */
  private def donateAndProcessMessages(receiver: Actor, thief: Actor): Unit = {
    donateMessage(receiver, thief) match {
      case None => {
        // no more messages to donate
        return
      }
      case Some(donatedInvocation) => {
        processMailbox(thief)
        return donateAndProcessMessages(receiver, thief)
      }
    }
  }

  /**
   * Steal a message from the receiver and give it to the thief.
   */
  private def donateMessage(receiver: Actor, thief: Actor): Option[MessageInvocation] = {
    val donated = receiver._mailbox.pollLast
    if (donated != null) {
      thief.forward(donated.message)(Some(donated.receiver))
      return Some(donated)
    } else return None
  }

  def start = if (!active) {
    active = true
  }

  def shutdown = if (active) {
    log.debug("Shutting down ThreadBasedDispatcher [%s]", name)
    executor.shutdownNow
    active = false
    references.clear
  }

  def ensureNotActive: Unit = if (active) throw new IllegalStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")

  private[akka] def init = withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity.buildThreadPool

  override def register(actor: Actor) = {
    verifyActorsAreOfSameType(actor)
    pooledActors.add(actor)
    super.register(actor)
  }

  override def unregister(actor: Actor) = {
    pooledActors.remove(actor)
    super.unregister(actor)
  }

  private def verifyActorsAreOfSameType(newActor: Actor) = {
    actorType match {
      case None => {
        actorType = Some(newActor.getClass)
      }
      case Some(aType) => {
        if (aType != newActor.getClass)
          throw new IllegalStateException(
            String.format("Can't register actor %s in a work stealing dispatcher which already knows actors of type %s",
              newActor, aType))
      }
    }
  }
}