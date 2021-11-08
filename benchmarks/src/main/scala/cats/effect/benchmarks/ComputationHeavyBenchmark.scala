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

import cats.effect.IO
import cats.effect.std.Queue
import cats.implicits._
import cats.effect.implicits._
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
class ComputationHeavyBenchmark {
  @Param(Array("1000"))
  var size: Int = _

  val outputCount = 100
  val computeIterations = 1000

  @Benchmark
  def scheduling(): Unit = {
    import cats.effect.unsafe.implicits.global
    val io = for {
      inputs <- Queue.unbounded[IO, Int]
      outputs <- Queue.unbounded[IO, Seq[Int]]
      source <- (0 until size)
        .map(i => IO.blocking(() /* simulate external source */ ) >> inputs.offer(i))
        .toList
        .sequence
        .start // data source
      _ <- outputs
        .take
        .map(_ => IO.blocking(() /* simulate output consumer */ ))
        .void
        .foreverM
        .start // output drain
      _ <- computeElementOutput(inputs, outputs)(1)
      _ <- source.join
    } yield ()
    io.unsafeRunSync()
  }

  def computeElementOutput(inputs: Queue[IO, Int], outputs: Queue[IO, Seq[Int]])(
      iteration: Int): IO[Unit] = for {
    element <- inputs.take
    result <- (0 until outputCount).map(neuralNetworkInput(element)).toList.parSequence
    _ <- outputs.offer(result)
    _ <-
      if (iteration < size) IO.cede >> computeElementOutput(inputs, outputs)(iteration + 1)
      else IO.unit
  } yield ()

  // Simulate calculating a computation-heavy neural network feature (input) based on each
  // element received from the queue
  private def neuralNetworkInput(element: Int)(seed: Int) = for {
    _ <- computeSomething(element, seed)(0)
    _ <- IO.cede
  } yield element

  private def computeSomething(a: Int, b: Int)(i: Int): IO[Int] = {
    if (i == computeIterations) IO.pure(a)
    else
      for {
        a2 <- IO(MurmurHash3.arrayHash(Array[Int](a, b), b))
        _ <- IO.cede
        result <- computeSomething(a2, b)(i + 1)
      } yield result
  }
}
