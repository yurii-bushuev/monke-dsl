package kek.ui

import scala.scalajs.js

/** Best-effort localStorage persistence for the workbench source. Failures are ignored. */
object Storage:
  private val Key = "kek.ui.source"

  def load(): Option[String] =
    try
      val raw = js.Dynamic.global.localStorage.getItem(Key)
      if raw == null || js.isUndefined(raw) then None else Some(raw.toString)
    catch case _: Throwable => None

  def save(source: String): Unit =
    try js.Dynamic.global.localStorage.setItem(Key, source)
    catch case _: Throwable => ()
