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
class ThroughputBenchmark {
  // computation heaviness
  @Param(Array("100"))
  var size: Int = _

  val elementCount = 100
  val parallelism = 10

  @Benchmark
  def scheduling(blackhole: Blackhole): Unit = {
    import cats.effect.unsafe.implicits.global
    val io = for {
      request <- Queue.unbounded[IO, Int]
      response <- Queue.unbounded[IO, Seq[Int]]
      source <- (0 until elementCount)
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
    } yield ()
    io.unsafeRunSync()
  }

  def processNextRequest(inputs: Queue[IO, Int], outputs: Queue[IO, Seq[Int]])(
      iteration: Int): IO[Unit] = for {
    element <- inputs.take
    result <- (0 until parallelism).toList.parTraverse(_ => murmurMe(0)(element))
    _ <- outputs.offer(result)
    _ <-
        if (iteration < elementCount)
          IO.cede >> processNextRequest(inputs, outputs)(iteration + 1)
        else IO.unit
  } yield ()

  private def murmurMe(i: Int)(x: Int): IO[Int] = {
    if (i == size) IO.pure(x)
    else IO.cede >> IO.defer(murmurMe(i + 1)(MurmurHash3.stringHash(s"$x,$i")))
  }
}
