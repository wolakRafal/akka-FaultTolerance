
package robo.fault_tolerance

import akka.actor._
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.SupervisorStrategy.Stop
import akka.actor.OneForOneStrategy
import akka.event.LoggingReceive
import akka.pattern.ask

object Worker {

  case object Start

  case object Do

  case class Progress(percent: Double)

}

/**
 * Worker performs some work when it receives the ‘Start‘ message.
 * It will continuously notify the sender of the ‘Start‘ message
 * of current ‘‘Progress‘‘. The ‘Worker‘ supervise the ‘CounterService‘.
 */
class Worker extends Actor with ActorLogging {

  import Worker._
  import CounterService._
  import akka.pattern.pipe

  implicit val askTimeout = Timeout(5 seconds)

  // Stop the CounterService child if it throws ServiceUnavailable
  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: ServiceUnavailable => Stop
  }

  // The sender of the initial Start message will continuously be notified
  // about progress
  var progressListener: Option[ActorRef] = None
  val counterService = context.actorOf(Props[CounterService], name = "counter")
  val totalCount = 51

  import context.dispatcher

  // Use this Actors’ Dispatcher as ExecutionContext

  def receive = LoggingReceive {
    case Start if progressListener.isEmpty =>
      progressListener = Some(sender)
      context.system.scheduler.schedule(Duration.Zero, 1 second, self, Do)
    case Do =>
      counterService ! Increment(1)
      counterService ! Increment(1)
      counterService ! Increment(1)

      counterService ? GetCurrentCount map {
        case CurrentCount(_, count) => Progress(100.0 * count / totalCount)
      } pipeTo progressListener.get
  }
}

