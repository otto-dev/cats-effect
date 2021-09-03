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

package cats.effect
package unsafe

import cats.effect.BaseSpec
import cats.effect.testkit.TestInstances
import cats.syntax.traverse._
import org.scalacheck.Arbitrary

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class BlockingSchedulingSpec extends BaseSpec with TestInstances {

  override def executionTimeout: FiniteDuration = 1.minute

  def smallerRuntime(): IORuntime = {
    lazy val rt: IORuntime = {
      val (blocking, blockDown) =
        IORuntime.createDefaultBlockingExecutionContext(threadPrefix =
          s"io-blocking-${getClass.getName}")
      val (scheduler, schedDown) =
        IORuntime.createDefaultScheduler(threadPrefix = s"io-scheduler-${getClass.getName}")
      val (compute, compDown) =
        IORuntime.createDefaultComputeThreadPool(
          rt,
          threadPrefix = s"io-compute-${getClass.getName}",
          threads = 2)

      IORuntime(
        compute,
        blocking,
        scheduler,
        { () =>
          compDown()
          blockDown()
          schedDown()
        },
        IORuntimeConfig()
      )
    }

    rt
  }

  "The work stealing runtime" should {
    "schedule fibers correctly when there's blocking on the pool" in real {
      val iterations = 200000

      implicit val rt = cats.effect.unsafe.implicits.global

      Resource.make(IO(smallerRuntime()))(rt => IO(rt.shutdown())).use { _ =>
        def future = Future(implicitly[Arbitrary[SyncIO[Int]]].arbitrary.sample.get)(rt.compute)
          .flatMap(sio => Future(sio.attempt.unsafeRunSync())(rt.compute))(rt.compute)

        (0 until iterations)
          .toList
          .traverse { _ => IO(Await.result(future, Duration.Inf)) }
          .flatMap { _ => IO(ok) }
          .evalOn(rt.compute)
      }
    }
  }
}
