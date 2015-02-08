
package robo.fsm

import akka.actor.{ActorRef, FSM, Actor}
import scala.collection.immutable

// received events
case class SetTarget(ref: ActorRef)

case class Queue(obj: Any)

case object Flush

// sent events
case class Batch(obj: immutable.Seq[Any])

// states
sealed trait State

case object Idle extends State

case object Active extends State

sealed trait Data

case object Uninitialized extends Data

case class Todo(target: ActorRef, queue: immutable.Seq[Any]) extends Data

class Buncher extends Actor with FSM[State, Data] {
  import scala.concurrent.duration._

  startWith(Idle, Uninitialized)

  when(Idle) {
    case Event(SetTarget(ref), Uninitialized) =>
      stay using Todo(ref, Vector.empty)
  }

  // transition elided ...
  when(Active, stateTimeout = 1 second) {
    case Event(Flush | StateTimeout, t: Todo) =>
      goto(Idle) using t.copy(queue = Vector.empty)
  }
  // unhandled elided ...
  whenUnhandled {
    // common code for both states
    case Event(Queue(obj), t @ Todo(_, v)) =>
      goto(Active) using t.copy(queue = v :+ obj)
    case Event(e, s) =>
      log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }

  // zachowanie przy zmianie stanu z Active na Idle
  onTransition {
    case Active -> Idle =>
      stateData match {
        case Todo(ref, queue) => ref ! Batch(queue)
      }
  }

  initialize()
}
