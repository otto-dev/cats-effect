/*
 * Copyright 2020-2021 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.unsafe

import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.FiniteDuration
import scala.math.Ordering

private final case class SleepCallback private (triggerTime: Long, callback: Runnable)

private object SleepCallback {

  private def apply(triggerTime: Long, callback: Runnable): SleepCallback =
    new SleepCallback(triggerTime, callback)

  def apply(
      delay: FiniteDuration,
      callback: Runnable,
      sleepers: PriorityQueue[SleepCallback]): SleepCallback = {

    def calculateTriggerTime(delay: FiniteDuration): Long = {
      val now = System.nanoTime()
      val delayNanos = delay.toNanos

      if (delayNanos < (Long.MaxValue >> 1L))
        now + delayNanos
      else
        now + overflowFree(delayNanos, now)
    }

    def overflowFree(delay: Long, now: Long): Long =
      if (sleepers.isEmpty) delay
      else {
        val head = sleepers.head
        val headDelay = head.triggerTime - now
        if (headDelay < 0 && (delay - headDelay < 0))
          Long.MaxValue + headDelay
        else
          delay
      }

    SleepCallback(calculateTriggerTime(delay), callback)
  }

  implicit val sleepCallbackOrdering: Ordering[SleepCallback] =
    Ordering.fromLessThan(_.triggerTime > _.triggerTime)
}
