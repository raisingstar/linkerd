package io.buoyant.k8s

import com.twitter.logging.Logger

private[k8s] trait EventLogging {
  def ns: String
  def srv: String
  
  @inline 
  private[this] def logAction[A](
    noun: String,
    format: A => String = (a: A) => a.toString
  )(
    verb: String
  )(
    value: A
  ): Unit =
    log.trace(
      s"k8s ns %s service %s %s %s %s",
      ns, srv, 
      verb, noun,
      format(value)
    )

  @inline
  private[this] def formatMapping(kv: (Any, Any)): String =
    kv match {
      case (name: String, port: Int) => s"'$name' to port $port"
      case (name: String, (oldTo, newTo)) => s"'$name' from $oldTo to $newTo"
      case (name: String, to) => s"'$name' to $to"
      case (fromPort, (oldTo, newTo)) => s"$fromPort from $oldTo to $newTo"
      case (fromPort, to) => s"from $fromPort to $to"
    }

  protected def newState[A](
    noun: String,
    oldState: Set[A],
    newState: Set[A]
  ): Unit =
    if (oldState != newState) {
      log.debug(
        "k8s ns %s service %s modified %ss",
        ns, srv, noun
      )
      if (log.isLoggable(Logger.TRACE)) {
        val was: String => A => Unit = logAction(noun)
        (newState -- oldState).foreach { was("added") }
        (oldState -- newState).foreach { was("deleted") }
      }
    }

  def deletion(noun: String = ""): Unit =
    log.debug("k8s ns %s service %s deleted %s", ns, srv, noun)

  def newState[A, B](oldState: Map[A, B], newState: Map[A, B]): Unit =
    if (oldState != newState) {
      log.debug(
        "k8s ns %s service %s modified port mappings",
        ns, srv
      )
      if (log.isLoggable(Logger.TRACE)) {
        val was: String => ((Any, Any)) => Unit =
          logAction("port mapping", formatMapping)
        val wasRemapped =
          logAction("port", formatMapping)("remapped")(_)

        val (remapped, removed) = (for {
          key <- oldState.keySet
          oldValue <- oldState.get(key)
        } yield newState.get(key) match {
          case Some(`oldValue`) =>
            // the key exists in the new state, but has the same value in both
            // the old and new states. skip it (yield None on both sides).
            (None, None)
          case Some(newValue) =>
            // the key exists in both states, but has a different value in the
            // new state. yield Some on the right-hand (remapped) side.
            (Some(key -> (oldValue, newValue)), None)
          case None =>
            // the mapping no longer exists in the new state, so yield None on
            // the remapped (left) side and Some on the deleted (right) side.
            (None, Some(key -> oldValue))
        }).unzip
        val added = newState -- oldState.keys

        added.foreach { was("added") }
        removed.foreach { _.foreach(was("deleted")) }
        remapped.foreach { _.foreach { wasRemapped } }
      }
    }
}