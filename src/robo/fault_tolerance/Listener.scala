
package robo.fault_tolerance

import akka.actor.{ReceiveTimeout, ActorLogging, Actor}
import scala.concurrent.duration._
import robo.fault_tolerance.Worker.Progress

/**
 * Listens on progress from the worker and shuts down the system when enough
 * work has been done.
 */
class Listener extends Actor with ActorLogging {

  context.setReceiveTimeout(15 seconds)

  def receive = {
    case Progress(percent) =>
      log.info("Current progress: {} %", percent)
      if (percent >= 100) {
        log.info("That's all shutting down. ")
        context.system.shutdown()
      }
    case ReceiveTimeout =>
      // No progress witihn 15 seconds, Service Unavailable
      log.error("Shutting down due to unavailable service")
      context.system.shutdown()
  }
}

