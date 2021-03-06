package sample.camel

import collection.mutable.Set

import java.util.concurrent.CountDownLatch

import org.junit._
import org.scalatest.junit.JUnitSuite

import akka.actor.Actor._
import akka.actor.{ActorRegistry, ActorRef, Actor}
import akka.camel._
import akka.camel.CamelServiceManager._
import akka.routing.CyclicIterator
import akka.routing.Routing._

/**
 * @author Martin Krasser
 */
class HttpConcurrencyTestStress extends JUnitSuite {
  import HttpConcurrencyTestStress._

  @Test def shouldProcessMessagesConcurrently = {
    val num = 50
    val latch1 = new CountDownLatch(num)
    val latch2 = new CountDownLatch(num)
    val latch3 = new CountDownLatch(num)
    val client1 = actorOf(new HttpClientActor("client1", latch1)).start
    val client2 = actorOf(new HttpClientActor("client2", latch2)).start
    val client3 = actorOf(new HttpClientActor("client3", latch3)).start
    for (i <- 1 to num) {
      client1 ! Message("client1", Map(Message.MessageExchangeId -> i))
      client2 ! Message("client2", Map(Message.MessageExchangeId -> i))
      client3 ! Message("client3", Map(Message.MessageExchangeId -> i))
    }
    latch1.await
    latch2.await
    latch3.await
    assert(num == (client1 !! "getCorrelationIdCount").as[Int].get)
    assert(num == (client2 !! "getCorrelationIdCount").as[Int].get)
    assert(num == (client3 !! "getCorrelationIdCount").as[Int].get)
  }
}

object HttpConcurrencyTestStress {
  @BeforeClass
  def beforeClass = {
    startCamelService

    val workers = for (i <- 1 to 8) yield actorOf[HttpServerWorker].start
    val balancer = loadBalancerActor(new CyclicIterator(workers.toList))

    val completion = service.get.expectEndpointActivationCount(1)
    val server = actorOf(new HttpServerActor(balancer)).start
    completion.await
  }

  @AfterClass
  def afterClass = {
    stopCamelService
    ActorRegistry.shutdownAll
  }

  class HttpClientActor(label: String, latch: CountDownLatch) extends Actor with Producer {
    def endpointUri = "jetty:http://0.0.0.0:8855/echo"
    var correlationIds = Set[Any]()

    override protected def receive = {
      case "getCorrelationIdCount" => self.reply(correlationIds.size)
      case msg => super.receive(msg)
    }

    override protected def receiveAfterProduce = {
      case msg: Message => {
        val corr = msg.headers(Message.MessageExchangeId)
        val body = msg.bodyAs[String]
        correlationIds += corr
        assert(label == body)
        latch.countDown
        print(".")
      }
    }
  }

  class HttpServerActor(balancer: ActorRef) extends Actor with Consumer {
    def endpointUri = "jetty:http://0.0.0.0:8855/echo"
    var counter = 0

    def receive = {
      case msg => balancer forward msg
    }
  }

  class HttpServerWorker extends Actor {
    protected def receive = {
      case msg => self.reply(msg)
    }
  }
}
