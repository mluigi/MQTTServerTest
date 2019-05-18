import TemperatureReads.deviceId
import TemperatureReads.value
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Vertx
import io.vertx.mqtt.MqttServer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.scheduleAtFixedRate

val dbLog: Logger = LoggerFactory.getLogger("DB")
val mqttLog: Logger = LoggerFactory.getLogger("MQTT")

val db = Database.connect(
    "jdbc:mysql://localhost:3306/test", driver = "com.mysql.jdbc.Driver",
    user = "test", password = ""
)

val mqttServer: MqttServer = MqttServer.create(Vertx.vertx())

object Devices : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 50).uniqueIndex()
}

object TemperatureReads : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val value = double("value")
    val deviceId = (integer("deviceId") references Devices.id).nullable()
}


fun main() {
    transaction(db) {
        SchemaUtils.drop(Devices, TemperatureReads)
        SchemaUtils.create(Devices, TemperatureReads)
        dbLog.info("Created Devices and TemperatureRead tables.")
    }

    val tempCache = ArrayList<Pair<Int, String>>()


    Timer().scheduleAtFixedRate(2000, 1000) {
        synchronized(tempCache) {
            if (tempCache.size > 0) {
                transaction(db) {
                    TemperatureReads.batchInsert(tempCache) { (devId, message) ->
                        this[value] = message.toDouble()
                        this[deviceId] = devId
                    }

                    dbLog.info("Added ${tempCache.size} reads")
                    tempCache.clear()
                }
            }
        }
    }

    mqttServer.endpointHandler { endpoint ->
        mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] request to connect, clean session = ${endpoint.isCleanSession}")
        var devId = 0
        transaction(db) {
            devId = Devices.insertIgnore {
                it[name] = endpoint.clientIdentifier()
            } get Devices.id
        }
        if (endpoint.auth() != null) {
            mqttLog.info("[username = ${endpoint.auth().username}, password = ${endpoint.auth().password}]")
        }
//        if (endpoint.will() != null) {
//            mqttLog.info("[will topic = ${endpoint.will().willTopic} msg = ${endpoint.will().willMessage} QoS = ${endpoint.will().willQos} isRetain = ${endpoint.will().isWillRetain}]")
//        }

        mqttLog.info("[keep alive timeout = ${endpoint.keepAliveTimeSeconds()}]")

        var startTime = 0L
        var packets = 0
        val times = mutableListOf<Long>()
        var prev = 0L
        var started = false
        endpoint.publishHandler {
            if (it.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
                endpoint.publishAcknowledge(it.messageId())
            } else if (it.qosLevel() == MqttQoS.EXACTLY_ONCE) {
                endpoint.publishReceived(it.messageId())
            }

            when (it.topicName()) {
                "temperature" -> {
                    synchronized(tempCache) {
                        //mqttLog.info("temp: ${it.payload()}")
                        tempCache.add(Pair(devId, it.payload().toString()))
                    }
                }
                else -> {
                    when (it.payload().toString()) {
                        "start" -> {
                            if (!started) {
                                startTime = System.nanoTime()
                                started = true
                            }
                        }
                        "end" -> {

                            if (started) {
                                val endTime = System.nanoTime()

                                mqttLog.info("Took ${(endTime - startTime) / 1_000_000}ms to receive $packets from ${endpoint.clientIdentifier()}")
                                mqttLog.info(
                                    "Time between packages: min ${times.toLongArray().min()!!}ns " +
                                            "max ${times.toLongArray().max()!! / 1_000_000}ms " +
                                            "avg ${times.toLongArray().average() / 1_000_000}ms"
                                )
                                startTime = 0
                                prev = 0
                                packets = 0
                                times.clear()
                                started = false
                            }
                        }
                        else -> {
                            if (started) {
                                val curr = System.nanoTime()
                                times.add(curr - if (prev == 0L) startTime else prev)
                                prev = curr
                                ++packets
                            }
                        }
                    }
                }
            }
        }.publishReleaseHandler {
            endpoint.publishComplete(it)
        }

        endpoint.publishAcknowledgeHandler { }

        endpoint.disconnectHandler {
            mqttLog.info("MQTT client [${endpoint.clientIdentifier()}] disconnected.")
        }

        endpoint.exceptionHandler {
            mqttLog.info("${it.cause}: ${it.message}")
        }

        // accept connection from the remote client
        endpoint.accept(true)
    }.exceptionHandler {
        mqttLog.info("${it.cause}: ${it.message}")
    }.listen { ar ->
        if (ar.succeeded()) {
            mqttLog.info("MQTT server is listening on port ${ar.result().actualPort()}")
        } else {
            mqttLog.error("Error on starting the server")
            ar.cause().printStackTrace()
        }
    }
}
