package com.twitter.haplocheirus

import java.util.concurrent.{LinkedBlockingQueue, TimeoutException, TimeUnit}
import scala.collection.mutable
import com.twitter.gizzard.scheduler.ErrorHandlingJobQueue
import com.twitter.ostrich.Stats
import com.twitter.xrayspecs.TimeConversions._
import net.lag.configgy.ConfigMap


// FIXME stats
class RedisPool(config: ConfigMap, queue: ErrorHandlingJobQueue) {
  case class ClientPool(available: LinkedBlockingQueue[PipelinedRedisClient], var count: Int)

  val poolSize = config("pool_size").toInt
  val poolTimeout = config("pool_timeout_msec").toInt.milliseconds
  val serverMap = new mutable.HashMap[String, ClientPool]

  def makeClient(hostname: String) = {
    val pipelineSize = config("pipeline").toInt
    val timeout = config("timeout_msec").toInt.milliseconds
    val expiration = config("expiration_hours").toInt.hours
    new PipelinedRedisClient(hostname, pipelineSize, timeout, expiration, queue)
  }

  def get(hostname: String): PipelinedRedisClient = {
    val pool = synchronized {
      val pool = serverMap.getOrElseUpdate(hostname, {
        val queue = new LinkedBlockingQueue[PipelinedRedisClient]()
        ClientPool(queue, 0)
      })
      if (pool.count < poolSize) {
        pool.available.offer(makeClient(hostname))
        pool.count += 1
      }
      pool
    }
    val client = pool.available.poll(poolTimeout.inMilliseconds, TimeUnit.MILLISECONDS)
    if (client eq null) {
      throw new TimeoutException("Unable to get redis connection to " + hostname)
    }
    client
  }

  def giveBack(hostname: String, client: PipelinedRedisClient) {
    synchronized {
      serverMap(hostname).available.offer(client)
    }
  }

  def withClient[T](hostname: String)(f: PipelinedRedisClient => T): T = {
    val client = Stats.timeMicros("redis-acquire-usec") { get(hostname) }
    try {
      f(client)
    } finally {
      Stats.timeMicros("redis-release-usec") { giveBack(hostname, client) }
    }
  }

  def shutdown() {
    synchronized {
      serverMap.foreach { case (hostname, pool) =>
        while (pool.available.size > 0) {
          pool.available.take().shutdown()
        }
      }
      serverMap.clear()
    }
  }

  override def toString = synchronized {
    "<RedisPool: %s>".format(serverMap.map { case (hostname, pool) =>
      "%s=(%d available, %d total)".format(hostname, pool.available.size, pool.count)
    }.mkString(", "))
  }
}