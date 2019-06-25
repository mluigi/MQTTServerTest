import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.mqtt.MqttClient

fun main() {
    //Clients Simulation


    try {
        val mqttClient = MqttClient.create(Vertx.vertx())
            .connect(1883, "192.168.1.70") {
                if (it.failed()) {
                    mqttLog.error("Couldn't connect: ${it.cause()}")
                }
            }

        while (mqttClient.isConnected.not()) {
        }

        while (true) {
            for (i in 0..500) {
                mqttClient.publish(
                    "test",
                    Buffer.buffer("a"),
                    MqttQoS.AT_LEAST_ONCE,
                    false, false
                )
                Thread.sleep(10)

            }
            Thread.sleep(500)
            for (i in 0..500) {
                mqttClient.publish(
                    "test",
                    Buffer.buffer("a"),
                    MqttQoS.AT_MOST_ONCE,
                    false, false
                )
                Thread.sleep(10)
            }
            Thread.sleep(500)
        }

        mqttClient.disconnect()
    } catch (e: Exception) {
        mqttLog.error(e.message)
    }

}