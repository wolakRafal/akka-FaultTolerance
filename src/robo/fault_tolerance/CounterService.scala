
package robo.fault_tolerance

import akka.actor._
import akka.actor.SupervisorStrategy.Restart
import scala.Some
import akka.actor.OneForOneStrategy
import akka.event.LoggingReceive

object CounterService {

  case class Increment(n: Int)

  case object GetCurrentCount

  case class CurrentCount(key: String, count: Long)

  class ServiceUnavailable(msg: String) extends RuntimeException(msg)

  case object Reconnect

}

/**
 * Adds the value received in ‘Increment‘ message to a persistent
 * counter. Replies with ‘CurrentCount‘ when it is asked for ‘CurrentCount‘.
 * ‘CounterService‘ supervise ‘Storage‘ and ‘Counter‘.
 */
class CounterService extends Actor {

  import CounterService._
  import Counter._
  import Storage._
  import scala.concurrent.duration._

  // Restart the storage child when StorageException is thrown.
  // After 3 restarts within 5 seconds it will be stopped.
  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 5 seconds, loggingEnabled = true) {
    case _: StorageException => Restart
  }

  val key = self.path.name
  var storage: Option[ActorRef] = None
  var counter: Option[ActorRef] = None
  var backlog = IndexedSeq.empty[(ActorRef, Any)]
  val MaxBacklog = 10000

  //// Use this Actors’ Dispatcher as ExecutionContext

  import context.dispatcher

  //  @throws[T](classOf[Exception])
  override def preStart(): Unit = {
    initStorage()
  }

  /**
   * The child storage is restarted in case of failure, but after 3 restarts,
   * and still failing it will be stopped. Better to back-off than continuously
   * failing. When it has been stopped we will schedule a Reconnect after a delay.
   * Watch the child so we receive Terminated message when it has been terminated.
   */
  def initStorage(): Unit = {
    storage = Some(context.watch(context.actorOf(Props[Storage], name = "storage")))
    // Tell the counter, if any, to use the new storage
    counter foreach {
      _ ! UseStorage(storage)
    }
    // We need the initial value to be able to operate
    storage.get ! Get(key)
  }

  def receive = LoggingReceive {
    case Entry(k, v) if k == key && counter == None =>
      // Reply from Storage of the initial value, now we can create the Counter
      val c = context.actorOf(Props(classOf[Counter], key, v))
      counter = Some(c)
      // Tell the counter to use current storage
      c ! UseStorage(storage)
      // and send the buffered backlog to the counter
      for ((replyTo, msg) <- backlog) c.tell(msg, replyTo)
      backlog = IndexedSeq.empty[(ActorRef, Any)] // empty
    case msg@Increment(n) => forwardOrPlaceInBacklog(msg)
    case msg@GetCurrentCount => forwardOrPlaceInBacklog(msg)
    case Terminated(actorRef) if Some(actorRef) == storage =>
      // After 3 restarts the storage child is stopped.
      // We receive Terminated because we watch the child, see initStorage.
      storage = None
      // Tell the counter that there is no storage for the moment
      counter foreach {
        _ ! UseStorage(None)
      }
      // Try to re-establish storage after while
      context.system.scheduler.scheduleOnce(10 seconds, self, Reconnect)
    case Reconnect =>
      // Re-establish storage after the scheduled delay
      initStorage()
  }

  def forwardOrPlaceInBacklog(msg: Any): Unit = {
    // We need the initial value from storage before we can start delegate to
    // the counter. Before that we place the messages in a backlog, to be sent
    // to the counter when it is initialized.
    counter match {
      case Some(c) => c forward msg
      case None =>
        if (backlog.size >= MaxBacklog) {
          throw new ServiceUnavailable(
            "CounterService not available, lack of initial value")
        }
        backlog :+= (sender -> msg)
    }
  }

}

object Counter {

  case class UseStorage(storage: Option[ActorRef])

}

/**
 * The in memory count variable that will send current
 * value to the ‘Storage‘, if there is any storage
 * available at the moment.
 */
class Counter(key: String, initialValue: Long) extends Actor {
import akka.routing.
  var router = {
    val routees = Vector.fill(5) {
      val r = context.actorOf(Props[Worker])
      context watch r
      c(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }

  import Counter._
  import CounterService._
  import Storage._

  var count = initialValue
  var storage: Option[ActorRef] = None

  def receive = LoggingReceive {
    case UseStorage(s) =>
      storage = s
      storeCount()

    case Increment(n) =>
      count += n
      storeCount()

    case GetCurrentCount => sender ! CurrentCount(key, count)
  }

  def storeCount(): Unit = {
    // Delegate dangerous work, to protect our valuable state.
    // We can continue without storage.
    storage foreach {
      _ ! Store(Entry(key, count))
    }
  }

}

object Storage {

  case class Store(entry: Entry)

  case class Get(key: String)

  case class Entry(key: String, value: Long)

  class StorageException(msg: String) extends RuntimeException(msg)

}

/**
 * Saves key/value pairs to persistent storage when receiving ‘Store‘ message.
 * Replies with current value when receiving ‘Get‘ message.
 * Will throw StorageException if the underlying data store is out of order.
 */
class Storage extends Actor {

  import Storage._

  val db = DummyDB

  def receive = ???
}

object DummyDB {

  import Storage.StorageException

  private var db = Map[String, Long]()

  @throws(classOf[StorageException])
  def save(key: String, value: Long): Unit = synchronized {
    if (11 <= value && value <= 14) {
      throw new StorageException("Simulated store failure " + value)
    }
    db += (key -> value)
  }

  @throws(classOf[StorageException])
  def load(key: String): Option[Long] = synchronized {
    db.get(key)
  }
}
