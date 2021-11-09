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

package cats.effect.benchmarks

import cats.effect.{FiberIO, IO}
import cats.effect.std.Queue
import cats.implicits._
import cats.effect.implicits._
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.annotations.{
  Benchmark,
  BenchmarkMode,
  Mode,
  OutputTimeUnit,
  Param,
  Scope,
  State
}

import java.util.concurrent.TimeUnit
import scala.util.hashing.MurmurHash3

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MINUTES)
class FairnessBenchmark {
  @Param(Array("100"))
  var size: Int = _

  val parallelism = 10
  val computations = 10000

  @Benchmark
  def scheduling(blackhole: Blackhole): Unit = {
    import cats.effect.unsafe.implicits.global
    val io = for {
      request <- Queue.unbounded[IO, Int]
      response <- Queue.unbounded[IO, Seq[Int]]
      unfairness <- createUnfairness
      source <- (0 until size)
        .map(i => IO.blocking(() /* simulate external source */ ) >> request.offer(i))
        .toList
        .sequence
        .start // data source
      _ <- response
        .take
        .map(v => IO.blocking(blackhole.consume(v) /* simulate output consumer */ ))
        .void
        .foreverM
        .start // output drain
      _ <- processNextRequest(request, response)(1)
      _ <- source.join
      _ <- unfairness.traverse(_.cancel)
    } yield ()
    io.unsafeRunSync()
  }

  def createUnfairness: IO[List[FiberIO[Nothing]]] =
    (0 until (Runtime.getRuntime().availableProcessors() * 2))
      .toList
      .traverse(x => murmurMe(0)(x).foreverM.start)

  def processNextRequest(inputs: Queue[IO, Int], outputs: Queue[IO, Seq[Int]])(
      iteration: Int): IO[Unit] = for {
    element <- inputs.take
    result <- (0 until parallelism).toList.parTraverse(_ => murmurMe(0)(element))
    _ <- outputs.offer(result)
    _ <-
      if (iteration < size)
        IO.cede >> processNextRequest(inputs, outputs)(iteration + 1)
      else IO.unit
  } yield ()

  private def murmurMe(i: Int)(x: Int): IO[Int] = {
    if (i == computations) IO.pure(x)
    else IO.cede >> IO.defer(murmurMe(i + 1)(MurmurHash3.stringHash(s"$x,$i")))
  }
}
