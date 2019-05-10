import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.mqtt.MqttClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.system.measureTimeMillis

fun main() {
    //Clients Simulation

    var nExceptions = 0

    val runnable = Runnable {
        try {
            val mqttClient = MqttClient.create(Vertx.vertx())
                .connect(1883, "localhost") {
                    if (it.failed()) {
                        mqttLog.error("Couldn't connect: ${it.cause()}")
                    }
                }

            while (mqttClient.isConnected.not()) {
            }

            for (i in 0..Random.nextInt(6)) {
                mqttClient.publish(
                    "temperature",
                    Buffer.buffer("${Random.nextDouble(-40.0, 80.0)}"),
                    MqttQoS.AT_LEAST_ONCE,
                    false, false
                )
                Thread.sleep(Random.nextLong(200,600))
            }

            mqttClient.disconnect()
        } catch (e: Exception) {
            nExceptions++
        }
    }


    val pool = Executors.newFixedThreadPool(20)

    val time = measureTimeMillis {
        for (i in 1..500) {
            pool.execute(runnable)
            Thread.sleep(Random.nextLong(50))
        }

        while (pool.awaitTermination(15, TimeUnit.SECONDS)){}
    }

    dbLog.info("Took $time ms with $nExceptions exceptions.")
}