

package robo.fault_tolerance

import com.typesafe.config.ConfigFactory
import akka.actor.{Props, ActorSystem}

/**
 * Runs the sample
 */
object FaultHandlingDocSample extends App {

  import robo.fault_tolerance.Worker._

  val config = ConfigFactory.parseString(
    """
      |akka.logLevel = "DEBUG"
      |akka.actor.debug {
      | receive = on
      | lifecycle = on
      |}
    """.stripMargin)

  val system = ActorSystem("FaultTolerantSample", config)
  val worker = system.actorOf(Props[Worker], name = "worker")
  val listener = system.actorOf(Props[Listener], name = "listener")

  // start work and listen to progress
  worker.tell(Start, listener)
}
