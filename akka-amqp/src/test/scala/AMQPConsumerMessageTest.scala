package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.util.Logging
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import se.scalablesolutions.akka.amqp._
import se.scalablesolutions.akka.amqp.AMQP.{ConsumerParameters, ChannelParameters, ProducerParameters}
import org.multiverse.api.latches.StandardLatch
import se.scalablesolutions.akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.{CountDownLatch, TimeUnit}

class AMQPConsumerMessageTest extends JUnitSuite with MustMatchers with Logging {

//  @Test
  def consumerMessage = {
    val connection = AMQP.newConnection()
    try {

      val countDown = new CountDownLatch(2)
      val channelCallback = actor {
        case Started => countDown.countDown
        case Restarting => ()
        case Stopped => ()
      }

      val channelParameters = ChannelParameters("text_exchange",ExchangeType.Direct, channelCallback = Some(channelCallback))

      val payloadLatch = new StandardLatch
      val consumer = AMQP.newConsumer(connection, ConsumerParameters(channelParameters, "non.interesting.routing.key", actor {
        case Delivery(payload, _, _, _, _) => payloadLatch.open
      }))

      val producer = AMQP.newProducer(connection, ProducerParameters(channelParameters))
      countDown.await(2, TimeUnit.SECONDS) must be (true)
      producer ! Message("some_payload".getBytes, "non.interesting.routing.key")
      payloadLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
    } finally {
      connection.stop
    }
  }
}